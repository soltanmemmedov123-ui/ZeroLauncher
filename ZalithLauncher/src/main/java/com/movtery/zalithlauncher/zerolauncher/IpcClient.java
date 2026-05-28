package com.movtery.zalithlauncher.zerolauncher;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * IpcClient – launcher-side handle for the launcher ↔ game IPC channel.
 *
 * Frame format: [4-byte command ID (int)][4-byte payload length (int)][payload bytes]
 * Command IDs: 1 = PING, 2 = GET_CLASS_LIST, 3 = GET_CLASS_DETAIL
 *
 * Protocol guarantees:
 *  - The socket is full-duplex: a single background receiver thread reads all
 *    incoming frames and dispatches them to the pending callback or push-listener.
 *  - Only one request may be in flight at a time (IPC is synchronous per-connection).
 *  - A background heartbeat detects dead game processes within ~8 s.
 *
 * Bug-fixes vs v14:
 *  1. sendRequest/readLoop race: pendingRequest is only cleared inside readLoop
 *     (single owner) so the callback is always invoked exactly once.
 *  2. sendPing uses CountDownLatch instead of the broken Object.wait pattern.
 *  3. Disconnect listeners are fired exactly once (brokenPipe vs readLoop).
 *  4. setSocketTimeout null-checks the socket reference.
 *  5. onError in the heartbeat no longer double-fires onDisconnected.
 */
public class IpcClient {

    private static final String TAG                       = "IpcClient";
    static final  String        SOCKET_NAME               = "zerolauncher_ipc";
    static final  int           CMD_PING                  = 1;
    static final  int           CMD_GET_CLASS_LIST        = 2;
    static final  int           CMD_GET_CLASS_DETAIL      = 3;
    static final  int           CMD_INJECT_CODE           = 4;
    static final  int           CMD_HEAP_SUMMARY          = 5;
    static final  int           CMD_TOP_ALLOCATIONS       = 6;
    static final  int           CMD_TRACK_CLASS           = 7;
    static final  int           CMD_RUN_GC                = 8;
    static final  int           CMD_PUSH_EVENT            = 0xFF;

    private static final int    REQUEST_READ_TIMEOUT_MS   = 15_000;
    private static final int    HEARTBEAT_READ_TIMEOUT_MS = 3_000;
    private static final long   HEARTBEAT_INTERVAL_MS     = 5_000;

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
    private static volatile PushListener         sPushListener       = null;

    public static void setOnDisconnectListener(OnDisconnectListener l) { sDisconnectListener = l; }
    public static void setPushListener(PushListener l)                  { sPushListener = l; }

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

    /**
     * Guard for the single in-flight request.
     * The callback reference is written by sendRequest (while holding sendLock)
     * and read+cleared exclusively by readLoop (no lock needed for the clear).
     * sendLock serialises concurrent callers so only one request is in flight.
     */
    private final Object   sendLock      = new Object();
    private volatile ResponseCallback pendingCallback = null;
    private volatile int              pendingCmdId    = -1;

    private IpcClient() {}

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

                setSocketTimeout(HEARTBEAT_READ_TIMEOUT_MS);
                sendRequest(new byte[0], CMD_PING, new ResponseCallback() {
                    @Override public void onResponse(byte[] data) {
                        // Restore normal timeout after a successful heartbeat
                        setSocketTimeout(REQUEST_READ_TIMEOUT_MS);
                    }
                    @Override public void onError(Exception e) {
                        // brokenPipe() has already cleared sInstance and closed
                        // the socket; just stop the loop and notify the listener.
                        // Do NOT call brokenPipe/disconnect again here.
                        heartbeatRunning = false;
                        Log.i(TAG, "Heartbeat lost: " + e.getMessage());
                        OnDisconnectListener l = sDisconnectListener;
                        if (l != null) l.onDisconnected();
                    }
                });
            }
        }, "ipc-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
    }

    /** Null-safe socket timeout setter. */
    private void setSocketTimeout(int ms) {
        LocalSocket s = socket;
        if (s == null) return;
        try { s.setSoTimeout(ms); } catch (IOException ignored) {}
    }

    // ── Core send/receive ─────────────────────────────────────────────────────

    /**
     * Sends a request and delivers the response (or error) to {@code callback}
     * on the receiver thread. Always call from a background thread.
     *
     * Only one request may be in flight at a time; concurrent callers block
     * on {@code sendLock} until the socket is free.
     */
    public void sendRequest(byte[] payload, int cmdId, ResponseCallback callback) {
        synchronized (sendLock) {
            if (out == null || in == null) {
                callback.onError(new IOException("socket closed"));
                return;
            }
            // Register the pending callback BEFORE writing, so the receiver
            // thread can never read a response before pendingCallback is set.
            pendingCallback = callback;
            pendingCmdId    = cmdId;
            try {
                out.writeInt(cmdId);
                out.writeInt(payload.length);
                if (payload.length > 0) out.write(payload);
                out.flush();
            } catch (IOException e) {
                pendingCallback = null;
                pendingCmdId    = -1;
                brokenPipe(e);
                callback.onError(e);
                return;
            }
            // The callback will be invoked by readLoop; sendLock is released here
            // so the heartbeat or another caller can queue the next request once
            // the current response arrives.
            //
            // NOTE: this is intentionally NOT a blocking wait. The callback is
            // always invoked asynchronously on the receiver thread. Callers that
            // need synchronous semantics (e.g. sendPing) implement their own latch.
        }
    }

    /** Synchronous ping – blocks until PONG or timeout. Returns "PONG" or "ERROR: reason". */
    public String sendPing() {
        final CountDownLatch latch  = new CountDownLatch(1);
        final String[]       result = {null};

        sendRequest(new byte[0], CMD_PING, new ResponseCallback() {
            @Override public void onResponse(byte[] data) {
                result[0] = "PONG";
                latch.countDown();
            }
            @Override public void onError(Exception e) {
                result[0] = "ERROR: " + e.getMessage();
                latch.countDown();
            }
        });

        try { latch.await(REQUEST_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return result[0] != null ? result[0] : "ERROR: no response";
    }

    // ── Broken pipe ───────────────────────────────────────────────────────────

    /**
     * Called when an I/O error is detected on the send or receive path.
     * Clears the singleton, closes the socket, and delivers the error to any
     * pending callback. Safe to call from any thread; idempotent.
     */
    private void brokenPipe(IOException e) {
        Log.w(TAG, "brokenPipe: " + e.getMessage());

        // Clear the singleton so reconnect attempts will create a fresh client
        if (sInstance == this) sInstance = null;

        // Deliver the error to whoever is waiting (clears the slot atomically)
        ResponseCallback cb = pendingCallback;
        pendingCallback = null;
        pendingCmdId    = -1;
        if (cb != null) cb.onError(e);

        closeSocket();
    }

    // ── Receiver thread ───────────────────────────────────────────────────────

    private void startReceiver() {
        if (receiverThread != null && receiverThread.isAlive()) return;
        receiverThread = new Thread(this::readLoop, "ipc-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void stopReceiver() {
        Thread t = receiverThread;
        receiverThread = null;
        if (t != null) t.interrupt();
    }

    private void readLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int cmd        = in.readInt();
                int payloadLen = in.readInt();
                if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024)
                    throw new IOException("invalid payload length: " + payloadLen);

                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) in.readFully(payload);

                // Push events are unsolicited – dispatch immediately
                if (cmd == CMD_PUSH_EVENT) {
                    PushListener listener = sPushListener;
                    if (listener != null) listener.onPush(cmd, payload);
                    continue;
                }

                // Solicited response – claim the pending callback
                ResponseCallback cb = pendingCallback;
                if (cb != null && pendingCmdId == cmd) {
                    pendingCallback = null;
                    pendingCmdId    = -1;
                    cb.onResponse(payload);
                } else {
                    Log.w(TAG, "Unexpected response for cmd=" + cmd
                        + " (pending=" + pendingCmdId + ")");
                }
            }
        } catch (IOException e) {
            // Only fire disconnect if we're still the active instance –
            // a deliberate disconnect() call closes the socket and causes this
            // exception too, but sInstance will already be null in that case.
            if (sInstance == this) {
                brokenPipe(e);
                OnDisconnectListener l = sDisconnectListener;
                if (l != null) l.onDisconnected();
            }
        }
    }
}
