package com.bridgewalkerapp.androidclient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.bridgewalkerapp.androidclient.apidata.Login;
import com.bridgewalkerapp.androidclient.apidata.Ping;
import com.bridgewalkerapp.androidclient.apidata.RequestStatus;
import com.bridgewalkerapp.androidclient.apidata.RequestVersion;
import com.bridgewalkerapp.androidclient.apidata.WSServerVersion;
import com.bridgewalkerapp.androidclient.apidata.WSStatus;
import com.bridgewalkerapp.androidclient.apidata.WebsocketReply;
import com.bridgewalkerapp.androidclient.apidata.WebsocketRequest;
import com.bridgewalkerapp.androidclient.data.ReplyAndRunnable;
import com.bridgewalkerapp.androidclient.data.RequestAndRunnable;

import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocketHandler;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Handler.Callback;
import android.os.RemoteException;
import android.util.Log;

public class BackendService extends Service implements Callback {
	private static final String TAG = "com.bridgewalkerapp";
	
	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_UNREGISTER_CLIENT = 2;
	public static final int MSG_SEND_COMMAND = 3;
	public static final int MSG_RECEIVED_COMMAND = 4;
	public static final int MSG_EXECUTE_RUNNABLE = 5;
	public static final int MSG_REQUEST_CONNECTION_STATUS = 6;
	public static final int MSG_CONNECTION_STATUS = 7;
	public static final int MSG_SEND_PING = 8;
	public static final int MSG_REQUEST_ACCOUNT_STATUS = 9;
	public static final int MSG_ACCOUNT_STATUS = 10;
	public static final int MSG_SHUTDOWN = 11;

	public static final int CONNECTION_STATE_PERMANENT_ERROR = -1;
	public static final int CONNECTION_STATE_CONNECTING = 0;
	public static final int CONNECTION_STATE_COMPATIBILITY_CHECKED = 1;
	public static final int CONNECTION_STATE_AUTHENTICATED = 2;
	
	public static final String BRIDGEWALKER_PREFERENCES_FILE = "bridgewalker_preferences";
	public static final String SETTING_GUEST_ACCOUNT = "SETTING_GUEST_ACCOUNT";
	public static final String SETTING_GUEST_PASSWORD = "SETTING_GUEST_PASSWORD";
	
	private static final String BRIDGEWALKER_URI = "ws://192.168.1.6:8000/backend";
	private static final int MAX_ERROR_WAIT_TIME = 15 * 1000;
	private static final int INITIAL_ERROR_WAIT_TIME = 1 * 1000;
	
	// server will time us out after 30 seconds, so send ping every 25 seconds
	private static final int KEEP_ALIVE_INTERVAL = 25 * 1000;
	private static final int SHUTDOWN_INTERVAL = 5 * 60 * 1000;
	
	private WebSocketConnection connection;
	private boolean isRunning = true;
	private int currentErrorWaitTime = INITIAL_ERROR_WAIT_TIME;
	private int connectionState = 0;
	private long lastClientActivity = 0;
	private int lastStartId = -1;
	
	private boolean useAuthentication = false;
	private String guestAccount = null;
	private String guestPassword = null;

	private ObjectMapper mapper;
	
	private Handler myHandler = null;
	private Map<Integer, Messenger> clientMessengers;
	
	private Messenger myMessenger = null;
	
	private Map<Integer, RequestAndRunnable> outstandingReplies;
	private Queue<WebsocketRequest> cmdQueue;
	
	private WSStatus currentAccountStatus = null;
	
	@SuppressLint("UseSparseArrays")
	@Override
	public void onCreate() {
		super.onCreate();
		this.myHandler = new Handler(this);
		this.myMessenger = new Messenger(this.myHandler);
		this.clientMessengers = new TreeMap<Integer, Messenger>();
		this.outstandingReplies = new HashMap<Integer, RequestAndRunnable>();
		this.cmdQueue = new LinkedList<WebsocketRequest>();
		
		this.mapper = new ObjectMapper();
		
		this.connection = new WebSocketConnection();
		connect();
		enqueuePing();
		
		Log.d(TAG, "BackendService created");
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		this.lastStartId = startId;
		if (intent != null && intent.getExtras() != null) {
			Bundle extras = intent.getExtras();
			String guestAccountExtra = extras.getString(SETTING_GUEST_ACCOUNT);
			String guestPasswordExtra = extras.getString(SETTING_GUEST_PASSWORD);
			
			if (guestAccountExtra != null && guestPasswordExtra != null) {
				this.useAuthentication = true;
				this.guestAccount = guestAccountExtra;
				this.guestPassword = guestPasswordExtra;
			}
		}
		
		return START_STICKY;
	}
	
	@Override
	public boolean handleMessage(Message msg) {
		switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				this.clientMessengers.put(msg.replyTo.hashCode(), msg.replyTo);
				this.lastClientActivity = System.currentTimeMillis();
				Log.d(TAG, "Client registered");
				break;
			case MSG_UNREGISTER_CLIENT:
				this.clientMessengers.remove(msg.replyTo.hashCode());
				Log.d(TAG, "Client unregistered. Clients remaining: " + this.clientMessengers.size());
				if (this.clientMessengers.size() == 0) {
					Log.d(TAG, "Last client unregistered. Starting shutdown timer.");
					Message shutdownMsg = Message.obtain(null, MSG_SHUTDOWN);
					this.myHandler.sendMessageDelayed(shutdownMsg, SHUTDOWN_INTERVAL);
				}
				break;
			case MSG_REQUEST_CONNECTION_STATUS:
				Message replyMsg = Message.obtain(null, MSG_CONNECTION_STATUS);
				replyMsg.obj = Integer.valueOf(this.connectionState);
				try {
					msg.replyTo.send(replyMsg);
				} catch (RemoteException e) { /* ignore */ }
				break;
			case MSG_REQUEST_ACCOUNT_STATUS:
				if (this.currentAccountStatus != null) {
					Message replyMsg2 = Message.obtain(null, MSG_ACCOUNT_STATUS);
					replyMsg2.obj = this.currentAccountStatus;
					try {
						msg.replyTo.send(replyMsg2);
					} catch (RemoteException e) { /* ignore */ }
				}
				// Note: If we do not have the account status yet,
				// we ignore this message, as we will be broadcasting
				// the status later anyway.
				break;
			case MSG_SEND_COMMAND:
				RequestAndRunnable cmd = (RequestAndRunnable)msg.obj;
				this.outstandingReplies.put(msg.replyTo.hashCode(), cmd);
				this.cmdQueue.offer(cmd.getRequest());
				sendCommands();
				break;
			case MSG_SEND_PING:
				if (this.connection.isConnected())
					sendCommand(new Ping());
				enqueuePing();
				break;
			case MSG_SHUTDOWN:
				// double check, that there have not been any new
				// clients registering in the mean time
				long idleTime = System.currentTimeMillis() - this.lastClientActivity;
				if (idleTime > SHUTDOWN_INTERVAL * 0.8) {
					stopSelfResult(this.lastStartId);
				}
		}
		return true;
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		Bundle extras = intent.getExtras();
		
		if (extras != null && extras.containsKey(SETTING_GUEST_ACCOUNT)) {
			this.guestAccount = extras.getString(SETTING_GUEST_ACCOUNT);
			this.guestPassword = extras.getString(SETTING_GUEST_PASSWORD);
			this.useAuthentication = true;
		} else {
			this.useAuthentication = false;
		}
		
		return myMessenger.getBinder();
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		this.isRunning = false;
		this.connection.disconnect();
		Log.d(TAG, "BackendService destroyed");
	}
	
	private void connect() {
		try {
			this.connectionState = CONNECTION_STATE_CONNECTING;
			this.connection.connect(BRIDGEWALKER_URI, webSocketHandler);
		} catch (WebSocketException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void reconnect() {
		if (this.isRunning) {
			Log.d(TAG, "Lost connection; retrying in " + currentErrorWaitTime + " ms.");
			connectionState = CONNECTION_STATE_CONNECTING;
			sendToAllClients(MSG_CONNECTION_STATUS, Integer.valueOf(connectionState));
			
			Runnable initReconnect = new Runnable() {
				@Override
				public void run() { connect();	}
			};
			
			this.myHandler.postDelayed(initReconnect, currentErrorWaitTime);
			currentErrorWaitTime *= 2;
			if (currentErrorWaitTime > MAX_ERROR_WAIT_TIME)
				currentErrorWaitTime = MAX_ERROR_WAIT_TIME;
		}
	}
	
	private void enqueuePing() {
		Message msg = Message.obtain(null, MSG_SEND_PING);
		this.myHandler.sendMessageDelayed(msg, KEEP_ALIVE_INTERVAL);
	}
	
	private void sendCommands() {
		if (this.connection.isConnected()) {
			WebsocketRequest cmd;
			while ((cmd = this.cmdQueue.poll()) != null) {
				sendCommand(cmd);
			}
		}
	}
	
	private void sendCommand(Object cmd) {
		connection.sendTextMessage(asJson(cmd));
	}
	
	private void processReply(WebsocketReply reply) {
		// check for replies that are relevant for us
		if (this.connectionState < CONNECTION_STATE_COMPATIBILITY_CHECKED &&
						reply.getReplyType() == WebsocketReply.TYPE_WS_SERVER_VERSION) {
			WSServerVersion serverVersion = (WSServerVersion)reply;
			if (isServerVersionCompatible(serverVersion.getServerVersion())) {
				this.connectionState = CONNECTION_STATE_COMPATIBILITY_CHECKED;				
			} else {
				setPermanentError();
			}
			sendToAllClients(MSG_CONNECTION_STATUS, Integer.valueOf(this.connectionState));
			
			authenticate();
		}
		
		if (this.connectionState == CONNECTION_STATE_COMPATIBILITY_CHECKED &&
				reply.getReplyType() == WebsocketReply.TYPE_WS_LOGIN_FAILED) {
			setPermanentError();
			sendToAllClients(MSG_CONNECTION_STATUS, Integer.valueOf(this.connectionState));
		}
		
		if (this.connectionState == CONNECTION_STATE_COMPATIBILITY_CHECKED &&
				reply.getReplyType() == WebsocketReply.TYPE_WS_LOGIN_SUCCESSFUL) {
			this.connectionState = CONNECTION_STATE_AUTHENTICATED;
			sendToAllClients(MSG_CONNECTION_STATUS, Integer.valueOf(this.connectionState));
			
			requestAccountStatus();
		}
		
		if (reply.getReplyType() == WebsocketReply.TYPE_WS_STATUS) {
			this.currentAccountStatus = (WSStatus)reply;
			sendToAllClients(MSG_ACCOUNT_STATUS, this.currentAccountStatus);
		}
		
		// see if this is a reply to a specific request
		Iterator<Map.Entry<Integer, RequestAndRunnable>> it = this.outstandingReplies.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, RequestAndRunnable> entry = it.next();
			if (reply.isReplyTo(entry.getValue().getRequest())) {
				// found the client that send the request
				Messenger client = this.clientMessengers.get(entry.getKey());
				if (client != null) {
					Message msg = Message.obtain(null, MSG_EXECUTE_RUNNABLE);
					ReplyAndRunnable randr =
							new ReplyAndRunnable(reply, entry.getValue().getRunnable());
					msg.obj = randr;
					try {
						client.send(msg);
					} catch (RemoteException e) { /* ignore */ }
				}
				
				// remove from outstanding replies and return
				it.remove();
				return;
			}
		}

		// apparently not a specific reply, so just broadcast to all clients
		sendToAllClients(MSG_RECEIVED_COMMAND, reply);
	}
	
	private void sendToAllClients(int msgCode, Object msgObj) {
		for (Messenger client : this.clientMessengers.values()) {
			Message msg = Message.obtain(null, msgCode);
			msg.obj = msgObj;
			try {
				client.send(msg);
			} catch (RemoteException e) { /* ignore */ }
		}
	}
	
	private void authenticate() {
		if (this.useAuthentication &&
				this.connectionState == CONNECTION_STATE_COMPATIBILITY_CHECKED) {
			sendCommand(new Login(this.guestAccount, this.guestPassword));
		}
	}
	
	private void requestAccountStatus() {
		sendCommand(new RequestStatus());
	}
	
	private void setPermanentError() {
		this.connectionState = CONNECTION_STATE_PERMANENT_ERROR;
		this.isRunning = false;
		this.connection.disconnect();
	}
	
    private String asJson(Object o) {
    	String json = "";
    	
    	/* We expect the caller to know what
    	 * they are doing, so won't really deal with
    	 * any potential errors.
    	 */
    	try {
			json = mapper.writeValueAsString(o);
		} catch (JsonGenerationException e) {
			throw new RuntimeException(e);
		} catch (JsonMappingException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    	return json;
    }
    
	private boolean isServerVersionCompatible(String serverVersion) {
		int clientMajor = extractMajorVersion(RequestVersion.BRIDGEWALKER_CLIENT_VERSION);
		int serverMajor = extractMajorVersion(serverVersion);
		
		return clientMajor == serverMajor;
	}
	
	private Integer extractMajorVersion(String version) {
		String[] parts = version.split("\\.");
		return Integer.valueOf(parts[0]);
	}
	
	private WebSocketHandler webSocketHandler = new WebSocketHandler() {
		@Override
		public void onOpen() {
			Log.d(TAG, "WS: Connected to " + BRIDGEWALKER_URI);
			currentErrorWaitTime = INITIAL_ERROR_WAIT_TIME;
			
			sendCommand(new RequestVersion());
			
			sendCommands();		// send out any commands that are queued
		}
		
		@Override
		public void onTextMessage(String payload) {
			Log.d(TAG, "WS: Text message received (" + payload + ")");
			
			try {
				JsonNode json = mapper.readValue(payload, JsonNode.class);
				if (json == null)
					return;
				
				WebsocketReply wsReply = WebsocketReply.parseJSON(json);
				if (wsReply == null)
					return;
				
				processReply(wsReply);
			} catch (JsonParseException e) {
				/* ignore malformed reply */
			} catch (JsonMappingException e) {
				/* ignore malformed reply */
			} catch (IOException e) {
				/* ignore malformed reply */
			}
		}
		
		@Override
		public void onClose(int code, String reason) {
			Log.d(TAG, "WS: Connection lost.");
			reconnect();
		}
	};
}
