package com.movtery.zalithlauncher.zerolauncher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * CrashHistoryDatabase – SQLite store for crash records.
 *
 * Schema (v1):
 *   crashes(
 *     id              INTEGER PRIMARY KEY AUTOINCREMENT,
 *     timestamp       INTEGER NOT NULL,        -- ms since epoch
 *     exception_type  TEXT,
 *     exception_msg   TEXT,
 *     cause_chain     TEXT,
 *     suspect_mod     TEXT,                    -- first suspected mod name, or NULL
 *     suspect_jar     TEXT,                    -- suspect JAR filename
 *     injection_flag  INTEGER DEFAULT 0,       -- 1 if injection was suspect
 *     chosen_fix      TEXT,                    -- "disable_mod","revert_injection","retry"
 *     fix_succeeded   INTEGER DEFAULT -1,      -- -1=unknown, 0=no, 1=yes
 *     crash_log_path  TEXT,
 *     log_tail        TEXT,                    -- last-50-lines snapshot
 *     crash_loop_count INTEGER DEFAULT 0       -- incremented if crash immediately re-occurs
 *   )
 */
public class CrashHistoryDatabase extends SQLiteOpenHelper {

    private static final String TAG     = "CrashHistoryDB";
    private static final String DB_NAME = "crash_history.db";
    private static final int    DB_VER  = 1;

    // ── Column names ───────────────────────────────────────────────────────────
    public static final String TABLE       = "crashes";
    public static final String COL_ID      = "id";
    public static final String COL_TS      = "timestamp";
    public static final String COL_EX_TYPE = "exception_type";
    public static final String COL_EX_MSG  = "exception_msg";
    public static final String COL_CAUSE   = "cause_chain";
    public static final String COL_MOD     = "suspect_mod";
    public static final String COL_JAR     = "suspect_jar";
    public static final String COL_INJ     = "injection_flag";
    public static final String COL_FIX     = "chosen_fix";
    public static final String COL_SUCCESS = "fix_succeeded";
    public static final String COL_LOG_PATH= "crash_log_path";
    public static final String COL_LOG_TAIL= "log_tail";
    public static final String COL_LOOP    = "crash_loop_count";

    // Fix type constants
    public static final String FIX_DISABLE_MOD        = "disable_mod";
    public static final String FIX_REVERT_INJECTION   = "revert_injection";
    public static final String FIX_RETRY              = "retry";
    public static final String FIX_NONE               = "none";

    // ── Singleton ──────────────────────────────────────────────────────────────

    private static CrashHistoryDatabase sInstance;

    public static synchronized CrashHistoryDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new CrashHistoryDatabase(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private CrashHistoryDatabase(Context context) {
        super(context, DB_NAME, null, DB_VER);
    }

    // ── SQLiteOpenHelper ───────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
            COL_ID      + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COL_TS      + " INTEGER NOT NULL," +
            COL_EX_TYPE + " TEXT," +
            COL_EX_MSG  + " TEXT," +
            COL_CAUSE   + " TEXT," +
            COL_MOD     + " TEXT," +
            COL_JAR     + " TEXT," +
            COL_INJ     + " INTEGER DEFAULT 0," +
            COL_FIX     + " TEXT DEFAULT '" + FIX_NONE + "'," +
            COL_SUCCESS + " INTEGER DEFAULT -1," +
            COL_LOG_PATH+ " TEXT," +
            COL_LOG_TAIL+ " TEXT," +
            COL_LOOP    + " INTEGER DEFAULT 0" +
            ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future: migrate schema
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ── Write operations ───────────────────────────────────────────────────────

    /**
     * Insert a new crash record from the given {@link CrashAnalyzer.CrashReport}.
     *
     * @return the row id of the inserted record.
     */
    public long insertCrash(CrashAnalyzer.CrashReport report) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TS,      report.analysisTimestamp);
        cv.put(COL_EX_TYPE, report.exceptionType);
        cv.put(COL_EX_MSG,  report.exceptionMessage);
        cv.put(COL_CAUSE,   report.causeChain);

        // Primary suspect mod
        if (!report.suspectMods.isEmpty()) {
            CrashAnalyzer.SuspectMod m = report.suspectMods.get(0);
            cv.put(COL_MOD, m.modName);
            cv.put(COL_JAR, m.jarFilename);
        }
        cv.put(COL_INJ,      report.injectionSuspect ? 1 : 0);
        cv.put(COL_FIX,      FIX_NONE);
        cv.put(COL_SUCCESS,  -1);
        cv.put(COL_LOG_PATH, report.crashLogPath);
        cv.put(COL_LOG_TAIL, report.logTail);
        cv.put(COL_LOOP,     0);

        long id = -1;
        try {
            id = getWritableDatabase().insertOrThrow(TABLE, null, cv);
        } catch (Exception e) {
            Log.e(TAG, "insertCrash failed", e);
        }
        return id;
    }

    /** Update the chosen fix and reset success status. */
    public void updateFix(long rowId, String fixType) {
        ContentValues cv = new ContentValues();
        cv.put(COL_FIX,     fixType);
        cv.put(COL_SUCCESS, -1);
        try {
            getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{ String.valueOf(rowId) });
        } catch (Exception e) {
            Log.e(TAG, "updateFix failed", e);
        }
    }

    /**
     * Mark whether the fix resolved the problem.
     * Call this when the next launch either succeeds (succeeded=true) or crashes again
     * (succeeded=false).
     */
    public void setFixSucceeded(long rowId, boolean succeeded) {
        ContentValues cv = new ContentValues();
        cv.put(COL_SUCCESS, succeeded ? 1 : 0);
        try {
            getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{ String.valueOf(rowId) });
        } catch (Exception e) {
            Log.e(TAG, "setFixSucceeded failed", e);
        }
    }

    /** Increment the crash-loop counter for the given record. */
    public void incrementCrashLoop(long rowId) {
        try {
            getWritableDatabase().execSQL(
                "UPDATE " + TABLE + " SET " + COL_LOOP + "=" + COL_LOOP + "+1 WHERE " + COL_ID + "=?",
                new String[]{ String.valueOf(rowId) }
            );
        } catch (Exception e) {
            Log.e(TAG, "incrementCrashLoop failed", e);
        }
    }

    // ── Read operations ────────────────────────────────────────────────────────

    /** Load all records, newest first. */
    public List<CrashRecord> loadAll() {
        return query(null, null);
    }

    /** Simple text search across exception_type, exception_msg, suspect_mod. */
    public List<CrashRecord> search(String term) {
        String like = "%" + term + "%";
        return query(
            COL_EX_TYPE + " LIKE ? OR " + COL_EX_MSG + " LIKE ? OR " + COL_MOD + " LIKE ?",
            new String[]{ like, like, like }
        );
    }

    /** Load the single most-recent record (to check crash-loop counter). */
    public CrashRecord loadLatest() {
        List<CrashRecord> list = query(null, null);
        return list.isEmpty() ? null : list.get(0);
    }

    public CrashRecord loadById(long id) {
        List<CrashRecord> list = query(COL_ID + "=?", new String[]{ String.valueOf(id) });
        return list.isEmpty() ? null : list.get(0);
    }

    private List<CrashRecord> query(String selection, String[] args) {
        List<CrashRecord> results = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE, null, selection, args, null, null, COL_TS + " DESC")) {
            while (c.moveToNext()) {
                results.add(cursorToRecord(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed", e);
        }
        return results;
    }

    private static CrashRecord cursorToRecord(Cursor c) {
        CrashRecord r = new CrashRecord();
        r.id              = c.getLong(c.getColumnIndexOrThrow(COL_ID));
        r.timestamp       = c.getLong(c.getColumnIndexOrThrow(COL_TS));
        r.exceptionType   = c.getString(c.getColumnIndexOrThrow(COL_EX_TYPE));
        r.exceptionMsg    = c.getString(c.getColumnIndexOrThrow(COL_EX_MSG));
        r.causeChain      = c.getString(c.getColumnIndexOrThrow(COL_CAUSE));
        r.suspectMod      = c.getString(c.getColumnIndexOrThrow(COL_MOD));
        r.suspectJar      = c.getString(c.getColumnIndexOrThrow(COL_JAR));
        r.injectionFlag   = c.getInt(c.getColumnIndexOrThrow(COL_INJ)) == 1;
        r.chosenFix       = c.getString(c.getColumnIndexOrThrow(COL_FIX));
        r.fixSucceeded    = c.getInt(c.getColumnIndexOrThrow(COL_SUCCESS));
        r.crashLogPath    = c.getString(c.getColumnIndexOrThrow(COL_LOG_PATH));
        r.logTail         = c.getString(c.getColumnIndexOrThrow(COL_LOG_TAIL));
        r.crashLoopCount  = c.getInt(c.getColumnIndexOrThrow(COL_LOOP));
        return r;
    }

    // ── Data transfer object ───────────────────────────────────────────────────

    public static class CrashRecord {
        public long    id;
        public long    timestamp;
        public String  exceptionType;
        public String  exceptionMsg;
        public String  causeChain;
        public String  suspectMod;
        public String  suspectJar;
        public boolean injectionFlag;
        public String  chosenFix;
        public int     fixSucceeded;   // -1=unknown, 0=no, 1=yes
        public String  crashLogPath;
        public String  logTail;
        public int     crashLoopCount;

        public String fixStatusLabel() {
            if (fixSucceeded == 1) return "✓ Fixed";
            if (fixSucceeded == 0) return "✗ Still crashing";
            return "—";
        }
    }
}
