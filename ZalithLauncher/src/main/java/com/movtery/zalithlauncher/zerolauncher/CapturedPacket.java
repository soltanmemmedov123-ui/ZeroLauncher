package com.movtery.zalithlauncher.zerolauncher;

/**
 * CapturedPacket – immutable data snapshot for a single Minecraft protocol packet
 * as observed by the local TCP proxy.
 *
 * Instances are produced by {@link PacketDecoder} and stored in the ring-buffer
 * inside {@link MinecraftProxy}.  The UI adapter reads them exclusively on the
 * main thread (after being posted there by a Handler), so no additional
 * synchronisation is needed at read-time.
 */
public final class CapturedPacket {

    // ── Direction constants ───────────────────────────────────────────────────
    public static final int DIR_CLIENT_TO_SERVER = 0;   // C → S  (green in UI)
    public static final int DIR_SERVER_TO_CLIENT = 1;   // S → C  (blue  in UI)

    // ── Protocol state constants ──────────────────────────────────────────────
    public static final int STATE_HANDSHAKE = 0;
    public static final int STATE_STATUS    = 1;
    public static final int STATE_LOGIN     = 2;
    public static final int STATE_PLAY      = 3;

    // ── Fields ───────────────────────────────────────────────────────────────
    /** Wall-clock time when the packet was intercepted (System.currentTimeMillis). */
    public final long   timestamp;

    /** {@link #DIR_CLIENT_TO_SERVER} or {@link #DIR_SERVER_TO_CLIENT}. */
    public final int    direction;

    /** Current Minecraft protocol state at the time of capture. */
    public final int    state;

    /** Numeric packet ID (VarInt decoded value). */
    public final int    packetId;

    /**
     * Human-readable packet name from the hardcoded mapping in {@link PacketDecoder},
     * or {@code "Unknown (0xNN)"} when the ID has no entry for the current state.
     */
    public final String packetName;

    /** Total length of the raw packet bytes (length prefix + id varint + payload). */
    public final int    length;

    /**
     * Hex-dump string for the first 64 raw bytes, formatted as pairs separated
     * by spaces for easy reading, e.g. {@code "00 07 6C 6F 63 61 6C 68 6F 73 74 …"}.
     */
    public final String hexDump;

    // ── Unique serial number ──────────────────────────────────────────────────
    /** Auto-incrementing sequence number, useful for stable DiffUtil keys. */
    public final long   serialId;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CapturedPacket(long serialId,
                          long timestamp,
                          int  direction,
                          int  state,
                          int  packetId,
                          String packetName,
                          int  length,
                          String hexDump) {
        this.serialId   = serialId;
        this.timestamp  = timestamp;
        this.direction  = direction;
        this.state      = state;
        this.packetId   = packetId;
        this.packetName = packetName;
        this.length     = length;
        this.hexDump    = hexDump;
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    /** Arrow string shown in the compact list row. */
    public String directionArrow() {
        return direction == DIR_CLIENT_TO_SERVER ? "→" : "←";
    }

    /** Protocol state as a short label for the detail panel. */
    public String stateName() {
        switch (state) {
            case STATE_HANDSHAKE: return "HANDSHAKE";
            case STATE_STATUS:    return "STATUS";
            case STATE_LOGIN:     return "LOGIN";
            case STATE_PLAY:      return "PLAY";
            default:              return "UNKNOWN";
        }
    }

    /** Formatted packet-ID string shown in the detail panel. */
    public String packetIdFormatted() {
        return String.format("%d  (0x%02X)", packetId, packetId);
    }
}
