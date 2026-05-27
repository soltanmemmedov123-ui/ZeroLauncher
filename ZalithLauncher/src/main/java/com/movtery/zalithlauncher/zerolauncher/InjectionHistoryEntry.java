package com.movtery.zalithlauncher.zerolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * InjectionHistoryEntry – one record in the enhanced injection history.
 *
 * The original InjectionConsoleFragment stored raw script strings in
 * SharedPreferences with no timestamps.  This companion class stores
 * entries in a JSON array under a separate key so that:
 *
 *  1. The original history key is untouched → no migration needed.
 *  2. CrashAnalyzer can compare injection timestamps to crash times.
 *  3. A "reverted" flag lets the recovery flow mark entries as suppressed.
 */
public class InjectionHistoryEntry {

    public static final String PREFS_NAME = "injection_console_prefs";
    public static final String KEY_ENHANCED_HISTORY = "enhanced_history_json_v2";
    public static final int MAX_ENHANCED_HISTORY = 50;

    // ── Fields ─────────────────────────────────────────────────────────────────

    /** Unique ID (UUID or incrementing int cast to String). */
    public String id;
    /** Script body. */
    public String code;
    /** Wall-clock time the script was sent for execution, ms since epoch. */
    public long timestampMs;
    /**
     * If true, the recovery system has marked this injection as "reverted"
     * so it is skipped on the next auto-run check.
     */
    public boolean reverted;

    public InjectionHistoryEntry() {}

    public InjectionHistoryEntry(String id, String code, long timestampMs) {
        this.id          = id;
        this.code        = code;
        this.timestampMs = timestampMs;
        this.reverted    = false;
    }

    // ── JSON serialisation ─────────────────────────────────────────────────────

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id",          id);
        obj.put("code",        code);
        obj.put("ts",          timestampMs);
        obj.put("reverted",    reverted);
        return obj;
    }

    public static InjectionHistoryEntry fromJson(JSONObject obj) throws JSONException {
        InjectionHistoryEntry e = new InjectionHistoryEntry();
        e.id          = obj.getString("id");
        e.code        = obj.getString("code");
        e.timestampMs = obj.getLong("ts");
        e.reverted    = obj.optBoolean("reverted", false);
        return e;
    }
}

/**
 * InjectionHistoryManager – static helpers for reading / writing the
 * enhanced injection history from / to SharedPreferences.
 *
 * Called by:
 *  - InjectionConsoleFragment (write after each successful execution).
 *  - CrashAnalyzer             (read to check injection suspect).
 *  - CrashRecoveryDialog       (mark entry as reverted).
 */
class InjectionHistoryManager {

    private static final String TAG = "InjHistoryManager";

    /** Load entries, most-recent first. */
    public static List<InjectionHistoryEntry> loadHistory(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            InjectionHistoryEntry.PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(InjectionHistoryEntry.KEY_ENHANCED_HISTORY, "[]");
        List<InjectionHistoryEntry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(InjectionHistoryEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse injection history", e);
        }
        return list;
    }

    /** Prepend a new entry and save. Trims to MAX_ENHANCED_HISTORY. */
    public static void recordExecution(Context context, String id, String code) {
        List<InjectionHistoryEntry> history = loadHistory(context);

        InjectionHistoryEntry entry = new InjectionHistoryEntry(
            id, code, System.currentTimeMillis());
        history.add(0, entry);

        while (history.size() > InjectionHistoryEntry.MAX_ENHANCED_HISTORY) {
            history.remove(history.size() - 1);
        }
        save(context, history);
    }

    /**
     * Mark the most-recent (non-reverted) entry as reverted.
     * Returns the entry that was marked, or null if none found.
     */
    public static InjectionHistoryEntry markLatestReverted(Context context) {
        List<InjectionHistoryEntry> history = loadHistory(context);
        for (InjectionHistoryEntry e : history) {
            if (!e.reverted) {
                e.reverted = true;
                save(context, history);
                return e;
            }
        }
        return null;
    }

    /** Return the latest non-reverted entry, or null. */
    public static InjectionHistoryEntry getLatestActive(Context context) {
        List<InjectionHistoryEntry> history = loadHistory(context);
        for (InjectionHistoryEntry e : history) {
            if (!e.reverted) return e;
        }
        return null;
    }

    private static void save(Context context, List<InjectionHistoryEntry> list) {
        JSONArray arr = new JSONArray();
        for (InjectionHistoryEntry e : list) {
            try { arr.put(e.toJson()); } catch (JSONException ignored) {}
        }
        context.getSharedPreferences(InjectionHistoryEntry.PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(InjectionHistoryEntry.KEY_ENHANCED_HISTORY, arr.toString())
               .apply();
    }
}
