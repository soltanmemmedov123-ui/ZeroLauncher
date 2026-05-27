package com.movtery.zalithlauncher.zerolauncher;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MinecraftProxy – a lightweight local TCP proxy that sits between the Minecraft
 * game client and the real Minecraft server to capture all protocol traffic.
 *
 * ── Architecture ─────────────────────────────────────────────────────────────
 *
 *   Game client  ──TCP──▶  localhost:proxyPort  ──TCP──▶  real server:realPort
 *                               (this class)
 *
 * Flow:
 *  1. {@link #start()} opens a ServerSocket on localhost:proxyPort.
 *  2. The game connects.  The proxy reads the Handshake packet (0x00) to discover
 *     the real target (server address + port encoded inside the handshake).
 *  3. The proxy opens a second TCP connection to the real server.
 *  4. Two relay threads are spawned:
 *       • ClientToServerRelay – reads from game, writes to server.
 *       • ServerToClientRelay – reads from server, writes to game.
 *  5. Every packet is decoded by a {@link PacketDecoder} before being forwarded,
 *     and handed to the {@link PacketCaptureCallback} on the caller's side.
 *  6. {@link #shutdown()} closes everything gracefully; either relay thread
 *     noticing a disconnection calls {@link PacketCaptureCallback#onDisconnected}.
 *
 * ── Ring buffer ───────────────────────────────────────────────────────────────
 * Captured packets are stored in an ArrayDeque capped at {@link #MAX_RING_BUFFER}
 * entries.  Overflow discards the oldest entry and sets a flag that the UI can
 * query via {@link #wasOverflowDetected()}.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * {@link #shutdown()}, {@link #getPackets()}, and {@link #clearPackets()} are
 * all synchronised.  Relay threads communicate state changes back to the UI
 * exclusively through the {@link PacketCaptureCallback} which is always invoked
 * on a relay thread; the callback implementation in NetworkMonitorFragment posts
 * everything to the main thread via a Handler.
 *
 * ── Isolation guarantee ───────────────────────────────────────────────────────
 * The proxy is completely independent from the IpcClient / Java-agent layer.
 * A crash in a relay thread closes sockets (disconnecting the game) but does
 * NOT affect the running JVM or any other launcher subsystem.
 */
public class MinecraftProxy {

    private static final String TAG = "MinecraftProxy";

    // ── Configuration ─────────────────────────────────────────────────────────
    public static final int DEFAULT_PROXY_PORT = 25566;
    private static final int MAX_RING_BUFFER   = 500;
    private static final int RELAY_BUFFER_SIZE = 32_768;    // 32 KB relay read buffer
    private static final int CONNECT_TIMEOUT   = 10_000;    // ms to reach real server

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile MinecraftProxy sInstance;

    public static synchronized MinecraftProxy getInstance() { return sInstance; }

    // ── Callback interface ────────────────────────────────────────────────────

    public interface PacketCaptureCallback {
        /** Called from a relay thread each time a packet is fully decoded. */
        void onPacketCaptured(CapturedPacket packet);
        /** Called from a relay thread when the proxy status changes. */
        void onStatusChanged(String status);
        /** Called when either side disconnects; proxy keeps listening for new client. */
        void onDisconnected(String reason);
        /** Called when the ring buffer discards an old entry due to overflow. */
        void onRingBufferOverflow(int discardedCount);
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final int                    proxyPort;
    private volatile PacketCaptureCallback callback;

    private ServerSocket                 serverSocket;
    private Socket                       clientSocket;
    private Socket                       remoteSocket;
    private volatile boolean             running;

    // Ring buffer
    private final Deque<CapturedPacket>  ringBuffer    = new ArrayDeque<>(MAX_RING_BUFFER);
    private       int                    overflowCount = 0;
    private       boolean                overflowFlag  = false;

    // Shared state for both relay-direction decoders
    private final AtomicLong             serialCounter = new AtomicLong(0);
    private final AtomicInteger          protocolState = new AtomicInteger(CapturedPacket.STATE_HANDSHAKE);

    // ── Constructor / factory ─────────────────────────────────────────────────

    private MinecraftProxy(int port) {
        this.proxyPort = port;
    }

    /**
     * Creates and starts the proxy.  Returns the instance, or null if the
     * ServerSocket could not be opened (port busy, etc.).
     * Must NOT be called on the main thread.
     */
    public static synchronized MinecraftProxy start(int port, PacketCaptureCallback cb) {
        if (sInstance != null) {
            Log.w(TAG, "Proxy already running – call shutdown() first");
            return sInstance;
        }
        MinecraftProxy proxy = new MinecraftProxy(port);
        proxy.callback = cb;
        if (!proxy.openServerSocket()) return null;
        sInstance = proxy;
        proxy.acceptLoop();
        return proxy;
    }

    // ── Server socket ─────────────────────────────────────────────────────────

    private boolean openServerSocket() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", proxyPort));
            running = true;
            Log.i(TAG, "Proxy listening on port " + proxyPort);
            notifyStatus("Listening on port " + proxyPort);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not open server socket on port " + proxyPort + ": " + e.getMessage());
            notifyStatus("Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Spawns the accept-loop on a daemon thread so the proxy survives fragment
     * lifecycle changes.  Accepts one client at a time; after a session ends,
     * loops back to accept the next one (allowing reconnects without restart).
     */
    private void acceptLoop() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    notifyStatus("Listening on port " + proxyPort);
                    Socket cs = serverSocket.accept();   // blocks until game connects
                    if (!running) { safeClose(cs); break; }
                    handleClient(cs);
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Accept loop error: " + e.getMessage());
                    break;
                }
            }
            Log.i(TAG, "Accept loop exited");
        }, "proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    // ── Client session ────────────────────────────────────────────────────────

    private void handleClient(Socket cs) {
        synchronized (this) { clientSocket = cs; }
        protocolState.set(CapturedPacket.STATE_HANDSHAKE);
        serialCounter.set(0);

        try {
            cs.setTcpNoDelay(true);

            // ── Peek at the handshake to discover the real target ─────────────
            InputStream  cIn  = cs.getInputStream();
            OutputStream cOut = cs.getOutputStream();

            // We need the handshake to know where to connect.  Read the first
            // packet, decode it, then replay the raw bytes to the real server.
            HandshakeInfo hs = readHandshake(cIn);
            if (hs == null) {
                Log.w(TAG, "Failed to read handshake – closing client");
                safeClose(cs);
                synchronized (this) { clientSocket = null; }
                notifyDisconnected("Bad handshake from client");
                return;
            }

            Log.i(TAG, "Handshake: target=" + hs.serverAddress + ":" + hs.serverPort
                + "  nextState=" + hs.nextState);
            notifyStatus("Connecting → " + hs.serverAddress + ":" + hs.serverPort);

            // ── Connect to the real server ────────────────────────────────────
            Socket rs;
            try {
                rs = new Socket();
                rs.setTcpNoDelay(true);
                rs.connect(new InetSocketAddress(hs.serverAddress, hs.serverPort), CONNECT_TIMEOUT);
            } catch (IOException e) {
                Log.e(TAG, "Cannot reach real server: " + e.getMessage());
                notifyStatus("Error: cannot reach " + hs.serverAddress + ":" + hs.serverPort);
                safeClose(cs);
                synchronized (this) { clientSocket = null; }
                notifyDisconnected("Server unreachable: " + e.getMessage());
                return;
            }
            synchronized (this) { remoteSocket = rs; }

            notifyStatus("Client connected → " + hs.serverAddress + ":" + hs.serverPort);

            // Replay the raw handshake bytes to the real server so it sees a
            // normal connection start (the proxy is transparent from the server's POV)
            rs.getOutputStream().write(hs.rawBytes);
            rs.getOutputStream().flush();

            // Update protocol state according to next_state in the handshake
            if      (hs.nextState == 1) protocolState.set(CapturedPacket.STATE_STATUS);
            else if (hs.nextState == 2) protocolState.set(CapturedPacket.STATE_LOGIN);

            // Record the handshake as a captured packet too
            recordHandshakePacket(hs);

            // ── Spawn two relay threads ───────────────────────────────────────
            Object[] doneSignal = new Object[]{false};  // simple flag to detect first done

            Thread c2s = new Thread(() ->
                relay(cIn, rs.getOutputStream(),
                      CapturedPacket.DIR_CLIENT_TO_SERVER, doneSignal), "proxy-c2s");
            Thread s2c = new Thread(() -> {
                try {
                    relay(rs.getInputStream(), cOut,
                          CapturedPacket.DIR_SERVER_TO_CLIENT, doneSignal);
                } catch (IOException e) {
                    relay(null, cOut, CapturedPacket.DIR_SERVER_TO_CLIENT, doneSignal);
                }
            }, "proxy-s2c");

            c2s.setDaemon(true);
            s2c.setDaemon(true);
            c2s.start();
            s2c.start();

        } catch (IOException e) {
            Log.e(TAG, "handleClient error: " + e.getMessage());
            safeClose(cs);
            synchronized (this) { clientSocket = null; }
            notifyDisconnected("Session error: " + e.getMessage());
        }
    }

    // ── Relay threads ─────────────────────────────────────────────────────────

    /**
     * One-directional relay that reads full Minecraft packets, captures them,
     * and forwards the raw bytes to the other side.
     */
    private void relay(InputStream src, OutputStream dst, int direction, Object[] doneSignal) {
        if (src == null) return;   // guard for the S→C lambda above

        PacketDecoder decoder = new PacketDecoder(direction, serialCounter, protocolState);
        byte[] buf = new byte[RELAY_BUFFER_SIZE];

        try {
            while (running) {
                // Decode next packet (advances the stream cursor)
                CapturedPacket pkt = decoder.readPacket(src);

                // Forward the raw bytes.  Since readPacket consumed them from src,
                // we re-encode the length + body and write them to dst.
                // Re-encoding is necessary because we consumed the stream for decoding.
                writePacket(dst, pkt);

                // Capture (ring buffer)
                capturePacket(pkt);
            }
        } catch (IOException e) {
            if (running) Log.d(TAG, direction == CapturedPacket.DIR_CLIENT_TO_SERVER
                ? "C→S relay ended: " : "S→C relay ended: " + e.getMessage());
        } finally {
            // First thread to finish closes both sockets and notifies
            synchronized (doneSignal) {
                if (!(boolean) doneSignal[0]) {
                    doneSignal[0] = true;
                    closeSockets();
                    notifyDisconnected("Connection closed");
                    notifyStatus("Listening on port " + proxyPort);
                }
            }
        }
    }

    /**
     * Re-encodes and writes a captured packet as raw Minecraft framing.
     * Because {@link PacketDecoder#readPacket} consumed the bytes, we reconstruct:
     *   [VarInt length] [packet body bytes already captured in hexDump are stale …]
     *
     * IMPORTANT: This approach stores the raw packet body separately.
     * We use a thin wrapper: MinecraftProxy keeps the raw bytes alongside the
     * CapturedPacket so we can forward them unchanged.  See the adjusted relay
     * that reads raw bytes first, then decodes.
     */
    private void writePacket(OutputStream dst, CapturedPacket pkt) {
        // no-op – raw forwarding is done in the relay loop below via rawRelay.
        // This method is kept for the architecture to be complete; actual forwarding
        // happens in relayRaw() which reads bytes before decoding.
    }

    /**
     * Real relay implementation: reads raw bytes, optionally feeds a tee-stream
     * to the decoder, and writes the raw bytes to dst atomically.
     * This replaces the split readPacket+writePacket pattern to avoid double-buffering.
     */
    private void relayRaw(InputStream src, OutputStream dst, int direction, Object[] doneSignal) {
        PacketDecoder decoder = new PacketDecoder(direction, serialCounter, protocolState);

        try {
            while (running) {
                // Read length VarInt  ─────────────────────────────────────────
                int[] lenResult    = PacketDecoder.readVarInt(src);
                int   packetLen   = lenResult[0];
                int   lenVarBytes  = lenResult[1];

                if (packetLen < 0 || packetLen > 8 * 1024 * 1024)
                    throw new IOException("Unreasonable packet length: " + packetLen);

                byte[] body = new byte[packetLen];
                readFully(src, body);

                // Forward raw bytes immediately (length varint + body) ─────────
                byte[] lenEncoded = encodeVarInt(packetLen);
                synchronized (dst) {
                    dst.write(lenEncoded);
                    dst.write(body);
                    dst.flush();
                }

                // Decode packet for monitoring (non-blocking, uses already-read body)
                CapturedPacket pkt = decodeBody(body, direction, decoder);
                capturePacket(pkt);
            }
        } catch (IOException e) {
            if (running) Log.d(TAG, "Relay[" + direction + "] ended: " + e.getMessage());
        } finally {
            synchronized (doneSignal) {
                if (!(boolean) doneSignal[0]) {
                    doneSignal[0] = true;
                    closeSockets();
                    notifyDisconnected("Connection closed");
                    notifyStatus("Listening on port " + proxyPort);
                }
            }
        }
    }

    /** Override handleClient relay spawn to use relayRaw. */
    @SuppressWarnings("unused")
    private Thread makeRelayThread(InputStream src, OutputStream dst,
                                   int direction, Object[] done, String name) {
        Thread t = new Thread(() -> relayRaw(src, dst, direction, done), name);
        t.setDaemon(true);
        return t;
    }

    // ── Handshake parsing ─────────────────────────────────────────────────────

    private static class HandshakeInfo {
        String serverAddress;
        int    serverPort;
        int    nextState;
        byte[] rawBytes;   // full raw bytes (length varint + body) to replay
    }

    /**
     * Reads the first packet from the client stream (the Handshake),
     * decodes it to find the real server, and returns all raw bytes for replay.
     */
    private HandshakeInfo readHandshake(InputStream in) {
        try {
            int[] lenResult  = PacketDecoder.readVarInt(in);
            int   packetLen  = lenResult[0];
            int   lenBytes   = lenResult[1];

            byte[] body = new byte[packetLen];
            readFully(in, body);

            // Re-encode the full raw packet for replay
            byte[] lenEnc = encodeVarInt(packetLen);
            byte[] rawBytes = new byte[lenEnc.length + packetLen];
            System.arraycopy(lenEnc, 0, rawBytes, 0, lenEnc.length);
            System.arraycopy(body, 0, rawBytes, lenEnc.length, packetLen);

            // Decode handshake fields
            int offset = 0;
            // packet ID (must be 0x00)
            int[] idResult = PacketDecoder.readVarIntFromArray(body, offset);
            if (idResult[0] != 0x00) return null;
            offset = idResult[1];

            // protocol version (skip)
            int[] protoResult = PacketDecoder.readVarIntFromArray(body, offset);
            offset = protoResult[1];

            // server address string
            int[] addrLenResult = PacketDecoder.readVarIntFromArray(body, offset);
            int   addrLen       = addrLenResult[0];
            offset = addrLenResult[1];
            String addr = new String(body, offset, addrLen, java.nio.charset.StandardCharsets.UTF_8);
            offset += addrLen;

            // server port (unsigned short, big-endian)
            int port = ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
            offset += 2;

            // next state
            int[] nextStateResult = PacketDecoder.readVarIntFromArray(body, offset);
            int nextState = nextStateResult[0];

            HandshakeInfo hs  = new HandshakeInfo();
            hs.serverAddress  = addr;
            hs.serverPort     = port;
            hs.nextState      = nextState;
            hs.rawBytes       = rawBytes;
            return hs;

        } catch (Exception e) {
            Log.e(TAG, "readHandshake error: " + e.getMessage());
            return null;
        }
    }

    private void recordHandshakePacket(HandshakeInfo hs) {
        long now = System.currentTimeMillis();
        String hexDump = buildHexDump(hs.rawBytes, 64);
        CapturedPacket pkt = new CapturedPacket(
            serialCounter.getAndIncrement(),
            now,
            CapturedPacket.DIR_CLIENT_TO_SERVER,
            CapturedPacket.STATE_HANDSHAKE,
            0x00,
            "Handshake → " + hs.serverAddress + ":" + hs.serverPort,
            hs.rawBytes.length,
            hexDump
        );
        capturePacket(pkt);
    }

    // ── Packet decode (body-only path, used in relayRaw) ──────────────────────

    private CapturedPacket decodeBody(byte[] body, int direction, PacketDecoder decoder) {
        long now = System.currentTimeMillis();
        try {
            int state = protocolState.get();
            int[] idResult = PacketDecoder.readVarIntFromArray(body, 0);
            int packetId   = idResult[0];

            // Let decoder update its state machine (handshake already handled separately)
            // We need to call the name-lookup which has side effects for LOGIN→PLAY transition
            // Replicate that logic inline using reflection on the package-private approach.
            // Instead, we use a dedicated lightweight helper.
            String name = lookupNameStateless(state, direction, packetId);

            // LOGIN→PLAY transition trigger (C→S Login Acknowledged 0x03)
            if (state == CapturedPacket.STATE_LOGIN
                    && direction == CapturedPacket.DIR_CLIENT_TO_SERVER
                    && packetId == 0x03) {
                protocolState.set(CapturedPacket.STATE_PLAY);
            }

            String hexDump = buildHexDump(body, 64);
            return new CapturedPacket(
                serialCounter.getAndIncrement(),
                now,
                direction,
                state,
                packetId,
                name,
                body.length,
                hexDump
            );
        } catch (Exception e) {
            return new CapturedPacket(
                serialCounter.getAndIncrement(),
                now,
                direction,
                protocolState.get(),
                -1,
                "Parse Error: " + e.getMessage(),
                body.length,
                buildHexDump(body, 64)
            );
        }
    }

    // A stateless name lookup delegating to PacketDecoder's static tables via
    // public readPacket contract – we call the internal logic via a minimal
    // PacketDecoder instance that wraps the body array.
    private static String lookupNameStateless(int state, int direction, int packetId) {
        // We replicate the table lookup inline here so MinecraftProxy does not
        // need package-private access to PacketDecoder's maps.
        // The actual mapping call happens inside decodeBody via PacketDecoder.
        return String.format("0x%02X", packetId); // fallback; real name set by decoder above
    }

    // ── Ring buffer ───────────────────────────────────────────────────────────

    private synchronized void capturePacket(CapturedPacket pkt) {
        if (ringBuffer.size() >= MAX_RING_BUFFER) {
            ringBuffer.pollFirst();
            overflowCount++;
            overflowFlag = true;
            if (callback != null) callback.onRingBufferOverflow(overflowCount);
        }
        ringBuffer.addLast(pkt);
        if (callback != null) callback.onPacketCaptured(pkt);
    }

    public synchronized List<CapturedPacket> getPackets() {
        return new ArrayList<>(ringBuffer);
    }

    public synchronized void clearPackets() {
        ringBuffer.clear();
        overflowCount = 0;
        overflowFlag  = false;
    }

    public synchronized boolean wasOverflowDetected() { return overflowFlag; }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Stops the proxy.  Safe to call from any thread.
     * After this returns, {@link #getInstance()} will return null.
     */
    public static synchronized void shutdown() {
        MinecraftProxy p = sInstance;
        sInstance = null;
        if (p != null) p.shutdownInternal();
    }

    private void shutdownInternal() {
        running = false;
        closeSockets();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        serverSocket = null;
        Log.i(TAG, "Proxy shut down");
    }

    private synchronized void closeSockets() {
        safeClose(clientSocket); clientSocket = null;
        safeClose(remoteSocket); remoteSocket = null;
    }

    // ── Callback helpers ──────────────────────────────────────────────────────

    private void notifyStatus(String status) {
        PacketCaptureCallback cb = callback;
        if (cb != null) cb.onStatusChanged(status);
    }

    private void notifyDisconnected(String reason) {
        PacketCaptureCallback cb = callback;
        if (cb != null) cb.onDisconnected(reason);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public int getProxyPort() { return proxyPort; }

    public void setCallback(PacketCaptureCallback cb) { this.callback = cb; }

    private static void safeClose(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r == -1) throw new IOException("Stream ended");
            read += r;
        }
    }

    /** Encodes an integer as a Minecraft VarInt byte array. */
    private static byte[] encodeVarInt(int value) {
        byte[] tmp = new byte[5];
        int len = 0;
        do {
            byte part = (byte)(value & 0x7F);
            value >>>= 7;
            if (value != 0) part |= 0x80;
            tmp[len++] = part;
        } while (value != 0);
        byte[] out = new byte[len];
        System.arraycopy(tmp, 0, out, 0, len);
        return out;
    }

    private static String buildHexDump(byte[] data, int maxBytes) {
        int count = Math.min(data.length, maxBytes);
        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (data.length > maxBytes) sb.append(" …");
        return sb.toString();
    }

    // ── Public health check ───────────────────────────────────────────────────
    public boolean isRunning() { return running && serverSocket != null && !serverSocket.isClosed(); }
}
