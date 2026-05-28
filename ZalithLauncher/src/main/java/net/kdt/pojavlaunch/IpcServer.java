package net.kdt.pojavlaunch;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * IpcServer – game-process (:game) side of the launcher ↔ game IPC channel.
 *
 * Frame format: [4-byte command ID (int)][4-byte payload length (int)][payload bytes]
 * Response format: same header + payload bytes.
 *
 * Commands:
 *   1 = PING            → PONG (empty payload)
 *   2 = GET_CLASS_LIST  → newline-separated class names (UTF-8)
 *   3 = GET_CLASS_DETAIL→ binary ClassDetail (see BinaryProtocol)
 *   5 = CMD_HEAP_SUMMARY
 *   6 = CMD_TOP_ALLOCATIONS
 *   7 = CMD_TRACK_CLASS
 *   8 = CMD_RUN_GC
 *   0xFF = CMD_PUSH_EVENT (server→client unsolicited)
 *
 * Start: IpcServer.get().start() – called from MainActivity.onCreate()
 * Stop:  IpcServer.get().stop()  – called from GameService.shutdown() and MainActivity.onDestroy()
 *
 * Bug-fixes vs v14:
 *  1. clearPushOutput is now called in the finally block of serveClient for ALL
 *     exit paths (previously only called for IOException, not for normal exits).
 *  2. serveClient no longer holds outputLock across the entire response write;
 *     the write is still synchronised but the lock is released between frames so
 *     sendUnsolicited() can interleave without starvation.
 *  3. pushOutput is validated inside sendUnsolicited before use to prevent
 *     writing to a stale/closed stream after a reconnect.
 */
public class IpcServer {

    private static final String TAG         = "IpcServer";
    private static final String SOCKET_NAME = "zerolauncher_ipc";

    public static final int CMD_PING             = 1;
    public static final int CMD_GET_CLASS_LIST   = 2;
    public static final int CMD_GET_CLASS_DETAIL = 3;
    public static final int CMD_HEAP_SUMMARY     = 5;
    public static final int CMD_TOP_ALLOCATIONS  = 6;
    public static final int CMD_TRACK_CLASS      = 7;
    public static final int CMD_RUN_GC           = 8;
    public static final int CMD_PUSH_EVENT       = 0xFF;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static final IpcServer INSTANCE = new IpcServer();
    public static IpcServer get() { return INSTANCE; }

    // ── State ─────────────────────────────────────────────────────────────────

    private volatile boolean          running      = false;
    private          Thread           acceptThread = null;
    private          LocalServerSocket serverSocket = null;
    private final    Object           outputLock   = new Object();
    private          DataOutputStream pushOutput   = null;

    /** Pluggable handler — set by the agent to serve class-list/detail requests. */
    public interface CommandHandler {
        /** Return the response payload bytes for the given command + request payload. */
        byte[] handle(int cmd, byte[] payload) throws Exception;
    }

    private volatile CommandHandler commandHandler = null;

    public void setCommandHandler(CommandHandler h) { commandHandler = h; }

    private IpcServer() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) return;
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind server socket: " + e.getMessage());
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ipc-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "IPC server started on @" + SOCKET_NAME);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        serverSocket = null;
        if (acceptThread != null) {
            try { acceptThread.join(500); } catch (InterruptedException ignored) {}
            acceptThread = null;
        }
        Log.i(TAG, "IPC server stopped");
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            LocalSocket client = null;
            try {
                LocalServerSocket ss;
                synchronized (this) { ss = serverSocket; }
                if (ss == null) break;
                client = ss.accept();
                Log.d(TAG, "Client connected");
                serveClient(client);
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept() error: " + e.getMessage());
            } finally {
                closeQuietly(client);
            }
        }
    }

    // ── Client handler ────────────────────────────────────────────────────────

    private void serveClient(LocalSocket client) {
        DataInputStream  in  = null;
        DataOutputStream out = null;
        try {
            in  = new DataInputStream(client.getInputStream());
            out = new DataOutputStream(client.getOutputStream());
            bindPushOutput(out);

            while (running) {
                int cmd        = in.readInt();
                int payloadLen = in.readInt();
                if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024)
                    throw new IOException("invalid payload length: " + payloadLen);

                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) in.readFully(payload);

                byte[] response = handleCommand(cmd, payload);

                // FIX: write the response under outputLock so it doesn't
                // interleave with sendUnsolicited(), but release the lock
                // between requests so push events are not starved.
                synchronized (outputLock) {
                    if (pushOutput != out) break; // client was replaced; bail
                    out.writeInt(cmd);
                    out.writeInt(response.length);
                    if (response.length > 0) out.write(response);
                    out.flush();
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            // FIX: always clear pushOutput on exit, not just on IOException
            clearPushOutput(out);
        }
    }

    private byte[] handleCommand(int cmd, byte[] payload) {
        if (cmd == CMD_PING) {
            Log.d(TAG, "PING → PONG");
            return new byte[0];
        }
        CommandHandler h = commandHandler;
        if (h != null) {
            try {
                return h.handle(cmd, payload);
            } catch (Exception e) {
                Log.w(TAG, "CommandHandler error for cmd=" + cmd + ": " + e.getMessage());
            }
        } else {
            Log.w(TAG, "No CommandHandler registered for cmd=" + cmd);
        }
        return new byte[0];
    }

    public void sendUnsolicited(int cmd, byte[] payload) {
        synchronized (outputLock) {
            DataOutputStream out = pushOutput;
            if (out == null) return;
            try {
                out.writeInt(cmd);
                out.writeInt(payload.length);
                if (payload.length > 0) out.write(payload);
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "Failed to send unsolicited event: " + e.getMessage());
                // FIX: clear stale pushOutput reference; pass the local copy
                // so clearPushOutput's identity check matches correctly.
                clearPushOutput(out);
            }
        }
    }

    private void bindPushOutput(DataOutputStream out) {
        synchronized (outputLock) {
            pushOutput = out;
        }
    }

    private void clearPushOutput(DataOutputStream out) {
        synchronized (outputLock) {
            if (pushOutput == out) {
                pushOutput = null;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void closeQuietly(LocalSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }
}
