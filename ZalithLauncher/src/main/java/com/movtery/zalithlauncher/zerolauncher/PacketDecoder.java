package com.movtery.zalithlauncher.zerolauncher;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PacketDecoder – stateful Minecraft protocol parser for the local proxy.
 *
 * Responsibilities:
 *  • Reads one full packet at a time from an InputStream (blocking I/O,
 *    meant to run on a dedicated relay thread).
 *  • Tracks the protocol state machine: HANDSHAKE → STATUS | LOGIN → PLAY.
 *  • Decodes packet-ID and length via VarInt.
 *  • Maps packet IDs to human-readable names for MC 1.21.x (Java Edition).
 *  • Builds a hex-dump string from the first MAX_HEX_BYTES raw bytes.
 *  • Returns a fully populated {@link CapturedPacket}.
 *
 * ── Updating packet name tables for future MC versions ───────────────────────
 * All mappings live in static initializer blocks at the bottom of this file,
 * one Map per (state × direction) combination.  When Mojang releases new
 * packets, add or change entries there.  The wiki.vg/Protocol page is the
 * canonical reference: https://wiki.vg/Protocol
 *
 * Protocol version targeted: 1.21.x  (Java Edition protocol 767)
 */
public class PacketDecoder {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int MAX_HEX_BYTES  = 64;
    private static final int MAX_PACKET_LEN = 8 * 1024 * 1024; // 8 MB safety cap

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile int  currentState    = CapturedPacket.STATE_HANDSHAKE;
    private final    int  direction;          // DIR_CLIENT_TO_SERVER or DIR_SERVER_TO_CLIENT

    // Serial counter shared across both relay directions via AtomicLong
    private final AtomicLong  serialCounter;
    private final AtomicInteger stateRef;    // shared reference so both decoders see state change

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param direction    {@link CapturedPacket#DIR_CLIENT_TO_SERVER} or
     *                     {@link CapturedPacket#DIR_SERVER_TO_CLIENT}.
     * @param serialCounter  shared counter so serial IDs are globally ordered.
     * @param sharedState  AtomicInteger holding the current protocol state,
     *                     shared between C→S and S→C decoders so that the
     *                     C→S Handshake packet update is immediately visible
     *                     to the S→C decoder.
     */
    public PacketDecoder(int direction, AtomicLong serialCounter, AtomicInteger sharedState) {
        this.direction     = direction;
        this.serialCounter = serialCounter;
        this.stateRef      = sharedState;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads exactly one Minecraft packet from {@code in} and returns a
     * {@link CapturedPacket}.  Blocks until data is available or the stream
     * is closed (throws IOException).
     *
     * @param in the raw TCP stream (already inside the relay thread).
     */
    public CapturedPacket readPacket(InputStream in) throws IOException {
        long captureTime  = System.currentTimeMillis();
        int  state        = stateRef.get();

        // ── Step 1: read length varint ───────────────────────────────────────
        int[] lengthResult = readVarInt(in);
        int   packetLen    = lengthResult[0];
        int   lenBytes     = lengthResult[1]; // bytes consumed by the length varint itself

        if (packetLen < 0 || packetLen > MAX_PACKET_LEN)
            throw new IOException("Implausible packet length: " + packetLen);

        // ── Step 2: read the rest of the packet (id + payload) ───────────────
        byte[] packetData = new byte[packetLen];
        readFully(in, packetData);

        // ── Step 3: decode packet ID from the packet body ────────────────────
        int[] idResult = readVarIntFromArray(packetData, 0);
        int   packetId = idResult[0];

        // ── Step 4: hex dump (first MAX_HEX_BYTES of the raw packet body) ────
        String hexDump = buildHexDump(packetData, MAX_HEX_BYTES);

        // ── Step 5: look up packet name ──────────────────────────────────────
        String name = lookupName(state, direction, packetId);

        // ── Step 6: update state machine if this is a C→S Handshake packet ──
        if (state == CapturedPacket.STATE_HANDSHAKE
                && direction == CapturedPacket.DIR_CLIENT_TO_SERVER
                && packetId == 0x00) {
            updateStateFromHandshake(packetData, idResult[1]);
        }

        // ── Step 7: assemble result ──────────────────────────────────────────
        int totalLength = lenBytes + packetLen;
        return new CapturedPacket(
            serialCounter.getAndIncrement(),
            captureTime,
            direction,
            state,
            packetId,
            name,
            totalLength,
            hexDump
        );
    }

    // ── State machine ─────────────────────────────────────────────────────────

    /**
     * Parses the Handshake packet body to extract the "next state" field.
     * Handshake format (after the packet ID varint):
     *   VarInt protocol_version  |  String server_address  |  UShort server_port  |  VarInt next_state
     *
     * next_state: 1 = STATUS, 2 = LOGIN
     */
    private void updateStateFromHandshake(byte[] data, int offset) {
        try {
            // skip protocol version
            int[] r = readVarIntFromArray(data, offset);
            offset = r[1];

            // skip server address (prefixed string)
            int[] strLen = readVarIntFromArray(data, offset);
            offset = strLen[1] + strLen[0];    // skip the string bytes

            // skip server port (2 bytes unsigned short)
            offset += 2;

            // read next state
            int[] nextState = readVarIntFromArray(data, offset);
            int ns = nextState[0];
            if (ns == 1) {
                stateRef.set(CapturedPacket.STATE_STATUS);
            } else if (ns == 2) {
                stateRef.set(CapturedPacket.STATE_LOGIN);
            }
        } catch (Exception ignored) {
            // malformed handshake – leave state as-is
        }
    }

    /**
     * Called by the relay thread when the Login Success packet (0x02 S→C in LOGIN state)
     * is detected, to advance to PLAY state.  Also called for Login Acknowledged (0x03 C→S).
     * This is invoked from {@link #lookupName} as a side-effect.
     */
    private void maybeAdvanceToPlay(int state, int direction, int packetId) {
        if (state == CapturedPacket.STATE_LOGIN) {
            // Login Success: 0x02 S→C  –  triggers C→S Login Acknowledged 0x03 afterward
            if (direction == CapturedPacket.DIR_CLIENT_TO_SERVER && packetId == 0x03) {
                stateRef.set(CapturedPacket.STATE_PLAY);
            }
        }
    }

    // ── Name lookup ───────────────────────────────────────────────────────────

    private String lookupName(int state, int direction, int packetId) {
        Map<Integer, String> table = getTable(state, direction);
        maybeAdvanceToPlay(state, direction, packetId);
        if (table != null && table.containsKey(packetId)) {
            return table.get(packetId);
        }
        return String.format("Unknown (0x%02X)", packetId);
    }

    private static Map<Integer, String> getTable(int state, int direction) {
        boolean cs = (direction == CapturedPacket.DIR_CLIENT_TO_SERVER);
        switch (state) {
            case CapturedPacket.STATE_HANDSHAKE: return cs ? HS_C2S : HS_S2C;
            case CapturedPacket.STATE_STATUS:    return cs ? ST_C2S : ST_S2C;
            case CapturedPacket.STATE_LOGIN:     return cs ? LG_C2S : LG_S2C;
            case CapturedPacket.STATE_PLAY:      return cs ? PL_C2S : PL_S2C;
        }
        return null;
    }

    // ── VarInt helpers ────────────────────────────────────────────────────────

    /**
     * Reads a VarInt from the InputStream.
     * @return int[2]: {value, bytesRead}
     */
    public static int[] readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0, bytesRead = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("Stream ended mid-VarInt");
            bytesRead++;
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too wide");
        }
        return new int[]{value, bytesRead};
    }

    /**
     * Reads a VarInt from a byte array at the given offset.
     * @return int[2]: {value, newOffset}
     */
    public static int[] readVarIntFromArray(byte[] data, int offset) throws IOException {
        int value = 0, position = 0;
        while (offset < data.length) {
            int b = data[offset++] & 0xFF;
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return new int[]{value, offset};
            position += 7;
            if (position >= 32) throw new IOException("VarInt too wide");
        }
        throw new IOException("VarInt truncated in array");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r == -1) throw new IOException("Stream ended reading packet body");
            read += r;
        }
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

    // =========================================================================
    // ── Packet name tables ────────────────────────────────────────────────────
    //
    //  Source: https://wiki.vg/Protocol  (Minecraft Java Edition 1.21.x / protocol 767)
    //  To update for a new version, modify the maps below and update this comment.
    //
    //  Map key   = packet ID (integer, decimal)
    //  Map value = canonical packet name from wiki.vg
    //
    //  Abbreviations used for width: C2S = Client-to-Server, S2C = Server-to-Client
    // =========================================================================

    // ── HANDSHAKE ─────────────────────────────────────────────────────────────
    private static final Map<Integer, String> HS_C2S = new HashMap<>();
    private static final Map<Integer, String> HS_S2C = new HashMap<>();
    static {
        HS_C2S.put(0x00, "Handshake");
        HS_C2S.put(0xFE, "Legacy Server List Ping");
        // S→C has no packets in HANDSHAKE state
    }

    // ── STATUS ────────────────────────────────────────────────────────────────
    private static final Map<Integer, String> ST_C2S = new HashMap<>();
    private static final Map<Integer, String> ST_S2C = new HashMap<>();
    static {
        ST_C2S.put(0x00, "Status Request");
        ST_C2S.put(0x01, "Ping Request");

        ST_S2C.put(0x00, "Status Response");
        ST_S2C.put(0x01, "Pong Response");
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    private static final Map<Integer, String> LG_C2S = new HashMap<>();
    private static final Map<Integer, String> LG_S2C = new HashMap<>();
    static {
        LG_C2S.put(0x00, "Login Start");
        LG_C2S.put(0x01, "Encryption Response");
        LG_C2S.put(0x02, "Login Plugin Response");
        LG_C2S.put(0x03, "Login Acknowledged");
        LG_C2S.put(0x04, "Cookie Response (login)");

        LG_S2C.put(0x00, "Disconnect (login)");
        LG_S2C.put(0x01, "Encryption Request");
        LG_S2C.put(0x02, "Login Success");
        LG_S2C.put(0x03, "Set Compression");
        LG_S2C.put(0x04, "Login Plugin Request");
        LG_S2C.put(0x05, "Cookie Request (login)");
    }

    // ── PLAY – Client-to-Server ───────────────────────────────────────────────
    // Reference: https://wiki.vg/Protocol#Serverbound_Play (1.21.x, protocol 767)
    private static final Map<Integer, String> PL_C2S = new HashMap<>();
    static {
        PL_C2S.put(0x00, "Confirm Teleportation");
        PL_C2S.put(0x01, "Query Block Entity Tag");
        PL_C2S.put(0x02, "Change Difficulty");
        PL_C2S.put(0x03, "Acknowledge Message");
        PL_C2S.put(0x04, "Chat Command");
        PL_C2S.put(0x05, "Signed Chat Command");
        PL_C2S.put(0x06, "Chat Message");
        PL_C2S.put(0x07, "Player Session");
        PL_C2S.put(0x08, "Chunk Batch Received");
        PL_C2S.put(0x09, "Client Status");
        PL_C2S.put(0x0A, "Client Information (play)");
        PL_C2S.put(0x0B, "Command Suggestions Request");
        PL_C2S.put(0x0C, "Acknowledge Configuration");
        PL_C2S.put(0x0D, "Click Container Button");
        PL_C2S.put(0x0E, "Click Container");
        PL_C2S.put(0x0F, "Close Container");
        PL_C2S.put(0x10, "Change Container Slot State");
        PL_C2S.put(0x11, "Cookie Response (play)");
        PL_C2S.put(0x12, "Plugin Message (play)");
        PL_C2S.put(0x13, "Debug Sample Subscription");
        PL_C2S.put(0x14, "Edit Book");
        PL_C2S.put(0x15, "Query Entity Tag");
        PL_C2S.put(0x16, "Interact");
        PL_C2S.put(0x17, "Jigsaw Generate");
        PL_C2S.put(0x18, "Keep Alive (serverbound)");
        PL_C2S.put(0x19, "Lock Difficulty");
        PL_C2S.put(0x1A, "Set Player Position");
        PL_C2S.put(0x1B, "Set Player Position and Rotation");
        PL_C2S.put(0x1C, "Set Player Rotation");
        PL_C2S.put(0x1D, "Set Player On Ground");
        PL_C2S.put(0x1E, "Move Vehicle");
        PL_C2S.put(0x1F, "Paddle Boat");
        PL_C2S.put(0x20, "Pick Item");
        PL_C2S.put(0x21, "Ping Request (play)");
        PL_C2S.put(0x22, "Place Recipe");
        PL_C2S.put(0x23, "Player Abilities (serverbound)");
        PL_C2S.put(0x24, "Player Action");
        PL_C2S.put(0x25, "Player Command");
        PL_C2S.put(0x26, "Player Input");
        PL_C2S.put(0x27, "Pong (play)");
        PL_C2S.put(0x28, "Change Recipe Book Settings");
        PL_C2S.put(0x29, "Set Seen Recipe");
        PL_C2S.put(0x2A, "Rename Item");
        PL_C2S.put(0x2B, "Resource Pack Response (play)");
        PL_C2S.put(0x2C, "Seen Advancements");
        PL_C2S.put(0x2D, "Select Trade");
        PL_C2S.put(0x2E, "Set Beacon Effect");
        PL_C2S.put(0x2F, "Set Held Item (serverbound)");
        PL_C2S.put(0x30, "Program Command Block");
        PL_C2S.put(0x31, "Program Command Block Minecart");
        PL_C2S.put(0x32, "Set Creative Mode Slot");
        PL_C2S.put(0x33, "Program Jigsaw Block");
        PL_C2S.put(0x34, "Program Structure Block");
        PL_C2S.put(0x35, "Update Sign");
        PL_C2S.put(0x36, "Swing Arm");
        PL_C2S.put(0x37, "Teleport To Entity");
        PL_C2S.put(0x38, "Use Item On");
        PL_C2S.put(0x39, "Use Item");
    }

    // ── PLAY – Server-to-Client ───────────────────────────────────────────────
    // Reference: https://wiki.vg/Protocol#Clientbound_Play (1.21.x, protocol 767)
    private static final Map<Integer, String> PL_S2C = new HashMap<>();
    static {
        PL_S2C.put(0x00, "Bundle Delimiter");
        PL_S2C.put(0x01, "Spawn Entity");
        PL_S2C.put(0x02, "Spawn Experience Orb");
        PL_S2C.put(0x03, "Entity Animation");
        PL_S2C.put(0x04, "Award Statistics");
        PL_S2C.put(0x05, "Acknowledge Block Change");
        PL_S2C.put(0x06, "Set Block Destroy Stage");
        PL_S2C.put(0x07, "Block Entity Data");
        PL_S2C.put(0x08, "Block Action");
        PL_S2C.put(0x09, "Block Update");
        PL_S2C.put(0x0A, "Boss Bar");
        PL_S2C.put(0x0B, "Change Difficulty");
        PL_S2C.put(0x0C, "Chunk Batch Finished");
        PL_S2C.put(0x0D, "Chunk Batch Start");
        PL_S2C.put(0x0E, "Chunk Biomes");
        PL_S2C.put(0x0F, "Clear Titles");
        PL_S2C.put(0x10, "Command Suggestions Response");
        PL_S2C.put(0x11, "Commands");
        PL_S2C.put(0x12, "Close Container");
        PL_S2C.put(0x13, "Set Container Content");
        PL_S2C.put(0x14, "Set Container Property");
        PL_S2C.put(0x15, "Set Container Slot");
        PL_S2C.put(0x16, "Cookie Request (play)");
        PL_S2C.put(0x17, "Set Cooldown");
        PL_S2C.put(0x18, "Chat Suggestions");
        PL_S2C.put(0x19, "Plugin Message (play)");
        PL_S2C.put(0x1A, "Damage Event");
        PL_S2C.put(0x1B, "Debug Sample");
        PL_S2C.put(0x1C, "Delete Message");
        PL_S2C.put(0x1D, "Disconnect (play)");
        PL_S2C.put(0x1E, "Disguised Chat Message");
        PL_S2C.put(0x1F, "Entity Event");
        PL_S2C.put(0x20, "Explosion");
        PL_S2C.put(0x21, "Unload Chunk");
        PL_S2C.put(0x22, "Game Event");
        PL_S2C.put(0x23, "Open Horse Screen");
        PL_S2C.put(0x24, "Hurt Animation");
        PL_S2C.put(0x25, "Initialize World Border");
        PL_S2C.put(0x26, "Clientbound Keep Alive");
        PL_S2C.put(0x27, "Chunk Data and Update Light");
        PL_S2C.put(0x28, "World Event");
        PL_S2C.put(0x29, "Particle");
        PL_S2C.put(0x2A, "Update Light");
        PL_S2C.put(0x2B, "Login (play)");
        PL_S2C.put(0x2C, "Map Data");
        PL_S2C.put(0x2D, "Merchant Offers");
        PL_S2C.put(0x2E, "Update Entity Position");
        PL_S2C.put(0x2F, "Update Entity Position and Rotation");
        PL_S2C.put(0x30, "Update Entity Rotation");
        PL_S2C.put(0x31, "Move Vehicle");
        PL_S2C.put(0x32, "Open Book");
        PL_S2C.put(0x33, "Open Screen");
        PL_S2C.put(0x34, "Open Sign Editor");
        PL_S2C.put(0x35, "Ping (play)");
        PL_S2C.put(0x36, "Ping Response (play)");
        PL_S2C.put(0x37, "Place Ghost Recipe");
        PL_S2C.put(0x38, "Player Abilities (clientbound)");
        PL_S2C.put(0x39, "Player Chat Message");
        PL_S2C.put(0x3A, "End Combat");
        PL_S2C.put(0x3B, "Enter Combat");
        PL_S2C.put(0x3C, "Combat Death");
        PL_S2C.put(0x3D, "Player Info Remove");
        PL_S2C.put(0x3E, "Player Info Update");
        PL_S2C.put(0x3F, "Look At");
        PL_S2C.put(0x40, "Synchronize Player Position");
        PL_S2C.put(0x41, "Update Recipe Book");
        PL_S2C.put(0x42, "Remove Entities");
        PL_S2C.put(0x43, "Remove Entity Effect");
        PL_S2C.put(0x44, "Reset Score");
        PL_S2C.put(0x45, "Remove Resource Pack (play)");
        PL_S2C.put(0x46, "Add Resource Pack (play)");
        PL_S2C.put(0x47, "Respawn");
        PL_S2C.put(0x48, "Set Head Rotation");
        PL_S2C.put(0x49, "Update Section Blocks");
        PL_S2C.put(0x4A, "Select Advancements Tab");
        PL_S2C.put(0x4B, "Server Data");
        PL_S2C.put(0x4C, "Set Action Bar Text");
        PL_S2C.put(0x4D, "Set Border Center");
        PL_S2C.put(0x4E, "Set Border Lerp Size");
        PL_S2C.put(0x4F, "Set Border Size");
        PL_S2C.put(0x50, "Set Border Warning Delay");
        PL_S2C.put(0x51, "Set Border Warning Reach");
        PL_S2C.put(0x52, "Set Camera");
        PL_S2C.put(0x53, "Set Held Item (clientbound)");
        PL_S2C.put(0x54, "Set Center Chunk");
        PL_S2C.put(0x55, "Set Render Distance");
        PL_S2C.put(0x56, "Set Default Spawn Position");
        PL_S2C.put(0x57, "Display Objective");
        PL_S2C.put(0x58, "Set Entity Metadata");
        PL_S2C.put(0x59, "Link Entities");
        PL_S2C.put(0x5A, "Set Entity Velocity");
        PL_S2C.put(0x5B, "Set Equipment");
        PL_S2C.put(0x5C, "Set Experience");
        PL_S2C.put(0x5D, "Set Health");
        PL_S2C.put(0x5E, "Update Objectives");
        PL_S2C.put(0x5F, "Set Passengers");
        PL_S2C.put(0x60, "Update Teams");
        PL_S2C.put(0x61, "Update Score");
        PL_S2C.put(0x62, "Set Simulation Distance");
        PL_S2C.put(0x63, "Set Subtitle Text");
        PL_S2C.put(0x64, "Update Time");
        PL_S2C.put(0x65, "Set Title Text");
        PL_S2C.put(0x66, "Set Title Animation Times");
        PL_S2C.put(0x67, "Entity Sound Effect");
        PL_S2C.put(0x68, "Sound Effect");
        PL_S2C.put(0x69, "Start Configuration");
        PL_S2C.put(0x6A, "Stop Sound");
        PL_S2C.put(0x6B, "Store Cookie (play)");
        PL_S2C.put(0x6C, "System Chat Message");
        PL_S2C.put(0x6D, "Set Tab-List Header And Footer");
        PL_S2C.put(0x6E, "Tag Query Response");
        PL_S2C.put(0x6F, "Pickup Item");
        PL_S2C.put(0x70, "Teleport Entity");
        PL_S2C.put(0x71, "Set Ticking State");
        PL_S2C.put(0x72, "Step Tick");
        PL_S2C.put(0x73, "Transfer (play)");
        PL_S2C.put(0x74, "Update Advancements");
        PL_S2C.put(0x75, "Update Attributes");
        PL_S2C.put(0x76, "Entity Effect");
        PL_S2C.put(0x77, "Update Recipes");
        PL_S2C.put(0x78, "Update Tags (play)");
        PL_S2C.put(0x79, "Projectile Power");
        PL_S2C.put(0x7A, "Custom Report Details");
        PL_S2C.put(0x7B, "Server Links");
    }
}
