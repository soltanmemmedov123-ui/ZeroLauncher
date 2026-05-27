package com.movtery.zalithlauncher.zerolauncher;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * IpcClient – launcher-side handle for the launcher ↔ game IPC channel.
 *
 * Frame format: [4-byte command ID (int)][4-byte payload length (int)][payload bytes]
 * Command IDs: 1 = PING, 2 = GET_CLASS_LIST, 3 = GET_CLASS_DETAIL
 *
 * All I/O is serialized through synchronized(this).
 * A background heartbeat pings every HEARTBEAT_INTERVAL_MS and fires
 * OnDisconnectListener on failure.
 */
public class IpcClient {

    private static final String TAG                      = "IpcClient";
    static final  String        SOCKET_NAME              = "zerolauncher_ipc";
    static final  int           CMD_PING                 = 1;
    static final  int           CMD_GET_CLASS_LIST       = 2;
    static final  int           CMD_GET_CLASS_DETAIL     = 3;
    static final  int           CMD_INJECT_CODE          = 4;   // BeanShell script injection
    static final  int           CMD_HEAP_SUMMARY         = 5;
    static final  int           CMD_TOP_ALLOCATIONS      = 6;
    static final  int           CMD_TRACK_CLASS          = 7;
    static final  int           CMD_RUN_GC               = 8;
    static final  int           CMD_PUSH_EVENT           = 0xFF;
    // User-facing requests get 15 s; heartbeat uses a shorter timeout so dead
    // games are detected within one interval rather than interval + 15 s.
    private static final int    REQUEST_READ_TIMEOUT_MS  = 15_000;
    private static final int    HEARTBEAT_READ_TIMEOUT_MS = 3_000;
    private static final long   HEARTBEAT_INTERVAL_MS    = 5_000;

    // ── Callback / listener interfaces ────────────────────────────────────────

    public interface ResponseCallback {
        void onResponse(byte[] data);
        void onError(Exception e);
    }

    public interface OnDisconnectListener {
        void onDisconnected();
    }

    public interface PushListener {
        void onPush(int cmd, byte[] payload);
    }

    private static volatile OnDisconnectListener sDisconnectListener = null;
    private static volatile PushListener sPushListener = null;

    public static void setOnDisconnectListener(OnDisconnectListener l) {
        sDisconnectListener = l;
    }

    public static void setPushListener(PushListener l) {
        sPushListener = l;
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile IpcClient sInstance = null;

    public static IpcClient getInstance() { return sInstance; }

    /** Must NOT be called on the main thread — does socket I/O. */
    public static synchronized void connect() {
        if (sInstance != null) return;
        IpcClient client = new IpcClient();
        if (client.openSocket(REQUEST_READ_TIMEOUT_MS)) {
            sInstance = client;
            client.startHeartbeat();
            Log.i(TAG, "IPC channel connected");
        } else {
            Log.d(TAG, "IPC connect attempt failed – game socket not ready yet");
        }
    }

    public static synchronized void disconnect() {
        if (sInstance == null) return;
        IpcClient old = sInstance;
        sInstance = null;
        old.stopHeartbeat();
        old.closeSocket();
        Log.i(TAG, "IPC channel disconnected");
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private LocalSocket      socket;
    private DataOutputStream out;
    private DataInputStream  in;
    private Thread           receiverThread;
    private volatile boolean heartbeatRunning = false;
    private final Object     pendingLock = new Object();
    private PendingRequest   pendingRequest = null;

    private IpcClient() {}

    private static final class PendingRequest {
        final int           cmdId;
        final ResponseCallback callback;

        PendingRequest(int cmdId, ResponseCallback callback) {
            this.cmdId = cmdId;
            this.callback = callback;
        }
    }

    private boolean openSocket(int timeoutMs) {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(timeoutMs);
            out    = new DataOutputStream(s.getOutputStream());
            in     = new DataInputStream(s.getInputStream());
            socket = s;
            startReceiver();
            return true;
        } catch (IOException e) {
            Log.d(TAG, "openSocket failed: " + e.getMessage());
            return false;
        }
    }

    private void closeSocket() {
        stopReceiver();
        try { if (out    != null) out.close();    } catch (IOException ignored) {}
        try { if (in     != null) in.close();     } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        out = null; in = null; socket = null;
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private void startHeartbeat() {
        heartbeatRunning = true;
        Thread t = new Thread(() -> {
            while (heartbeatRunning) {
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException e) { break; }
                if (!heartbeatRunning) break;
                // Temporarily tighten the socket timeout for the heartbeat ping
                setSocketTimeout(HEARTBEAT_READ_TIMEOUT_MS);
                sendRequest(new byte[0], CMD_PING, new ResponseCallback() {
                    @Override public void onResponse(byte[] data) {
                        setSocketTimeout(REQUEST_READ_TIMEOUT_MS); // restore
                    }
                    @Override public void onError(Exception e) {
                        Log.i(TAG, "Heartbeat lost: " + e.getMessage());
                        // brokenPipe() inside sendRequest already cleared sInstance
                        OnDisconnectListener l = sDisconnectListener;
                        if (l != null) l.onDisconnected();
                        heartbeatRunning = false;
                    }
                });
            }
        }, "ipc-heartbeat");
        t.setDaemon(true);
        t.start();
        // Don't store the thread reference — stopHeartbeat() uses the flag +
        // socket close to unblock any in-progress I/O, which is sufficient.
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
        // closeSocket() (called by disconnect() after this) will unblock any
        // in-progress read on the heartbeat thread.
    }

    private void setSocketTimeout(int ms) {
        try { if (socket != null) socket.setSoTimeout(ms); } catch (IOException ignored) {}
    }

    // ── Core send/receive ─────────────────────────────────────────────────────

    /**
     * Sends a request and delivers the response to the callback synchronously
     * on the calling thread. Always call from a background thread.
     */
    public void sendRequest(byte[] payload, int cmdId, ResponseCallback callback) {
        synchronized (pendingLock) {
            if (out == null || in == null) {
                callback.onError(new IOException("socket closed"));
                return;
            }
            if (pendingRequest != null) {
                callback.onError(new IOException("another request is already in flight"));
                return;
            }
            pendingRequest = new PendingRequest(cmdId, callback);
            try {
                out.writeInt(cmdId);
                out.writeInt(payload.length);
                if (payload.length > 0) out.write(payload);
                out.flush();
            } catch (IOException e) {
                pendingRequest = null;
                brokenPipe(e);
                callback.onError(e);
            }
        }
    }

    /** Synchronous ping — returns "PONG" or "ERROR: reason". */
    public String sendPing() {
        final Object lock = new Object();
        final String[] result = {null};
        final boolean[] done = {false};

        sendRequest(new byte[0], CMD_PING, new ResponseCallback() {
            @Override
            public void onResponse(byte[] data) {
                synchronized (lock) {
                    result[0] = "PONG";
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(Exception e) {
                synchronized (lock) {
                    result[0] = "ERROR: " + e.getMessage();
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });

        synchronized (lock) {
            if (!done[0]) {
                try { lock.wait(REQUEST_READ_TIMEOUT_MS); } catch (InterruptedException ignored) {}
            }
        }
        return result[0] != null ? result[0] : "ERROR: no response";
    }

    private void brokenPipe(IOException e) {
        Log.w(TAG, "brokenPipe: " + e.getMessage());
        PendingRequest pending;
        synchronized (pendingLock) {
            pending = pendingRequest;
            pendingRequest = null;
        }
        if (pending != null) {
            pending.callback.onError(e);
        }
        closeSocket();
        if (sInstance == this) sInstance = null;
    }

    private void startReceiver() {
        if (receiverThread != null && receiverThread.isAlive()) return;
        receiverThread = new Thread(this::readLoop, "ipc-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void stopReceiver() {
        if (receiverThread == null) return;
        receiverThread.interrupt();
        receiverThread = null;
    }

    private void readLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int cmd = in.readInt();
                int payloadLen = in.readInt();
                if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024) {
                    throw new IOException("invalid payload length: " + payloadLen);
                }
                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) in.readFully(payload);
                if (cmd == CMD_PUSH_EVENT) {
                    PushListener listener = sPushListener;
                    if (listener != null) {
                        listener.onPush(cmd, payload);
                    }
                    continue;
                }

                PendingRequest pending;
                synchronized (pendingLock) {
                    pending = pendingRequest;
                    if (pending != null && pending.cmdId == cmd) {
                        pendingRequest = null;
                    } else {
                        pending = null;
                    }
                }
                if (pending != null) {
                    pending.callback.onResponse(payload);
                } else {
                    Log.w(TAG, "Unexpected response for cmd=" + cmd);
                }
            }
        } catch (IOException e) {
            if (sInstance == this) {
                brokenPipe(e);
                OnDisconnectListener l = sDisconnectListener;
                if (l != null) l.onDisconnected();
            }
        }
    }
}
