package com.movtery.zalithlauncher.zerolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.util.List;

/**
 * CrashSessionManager – singleton orchestrator for crash recovery.
 *
 * Responsibilities:
 *  1. Called by the main launcher immediately after a non-zero JVM exit.
 *  2. Runs {@link CrashAnalyzer} on a background thread.
 *  3. Inserts the crash record into {@link CrashHistoryDatabase}.
 *  4. Manages the crash-loop counter (preventing infinite fix cycles).
 *  5. Shows {@link CrashRecoveryDialog} on the UI thread.
 *
 * Typical call site (inside onJvmExit handler in MainActivity):
 *
 *   {@code
 *   int code = event.getExitCode();
 *   if (code != 0) {
 *       CrashSessionManager.get().handleCrash(this, code, currentVersion, modsDir);
 *   } else {
 *       finish();
 *   }
 *   }
 */
public class CrashSessionManager {

    private static final String TAG = "CrashSessionManager";

    /** SharedPreferences key tracking the DB row of the last recorded crash. */
    private static final String PREFS_NAME       = "crash_session_prefs";
    private static final String KEY_LAST_ROW_ID  = "last_crash_row_id";
    private static final String KEY_LOOP_COUNT   = "crash_loop_count";

    private static final int MAX_LOOP = CrashRecoveryDialog.MAX_AUTO_RELAUNCHES;

    private static CrashSessionManager sInstance;

    /** The DB row id written during the current session (for "fix succeeded" update). */
    private long pendingRowId = -1;

    public static CrashSessionManager get() {
        if (sInstance == null) sInstance = new CrashSessionManager();
        return sInstance;
    }

    private CrashSessionManager() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Call immediately after detecting a non-zero game exit.
     *
     * @param activity      Host activity (must implement {@link CrashRecoveryDialog.RelaunchCallback}).
     * @param exitCode      The non-zero JVM exit code.
     * @param instanceModsDir Absolute path to the mods/ folder of the crashed instance (may be null).
     */
    public void handleCrash(FragmentActivity activity, int exitCode, String instanceModsDir) {
        Log.i(TAG, "handleCrash: exitCode=" + exitCode + ", modsDir=" + instanceModsDir);

        Context ctx = activity.getApplicationContext();

        // Mark the previous fix as "failed" if we had one
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastRowId = prefs.getLong(KEY_LAST_ROW_ID, -1);
        int  loopCount = prefs.getInt(KEY_LOOP_COUNT, 0);

        if (lastRowId >= 0) {
            CrashHistoryDatabase.getInstance(ctx).setFixSucceeded(lastRowId, false);
            CrashHistoryDatabase.getInstance(ctx).incrementCrashLoop(lastRowId);
            loopCount++;
        }

        final int finalLoopCount = loopCount;

        new Thread(() -> {
            // Run analysis
            CrashAnalyzer.CrashReport report =
                CrashAnalyzer.analyze(ctx, null, instanceModsDir);

            // Insert into DB
            long rowId = CrashHistoryDatabase.getInstance(ctx).insertCrash(report);
            pendingRowId = rowId;

            // Update prefs with new row id and loop count
            prefs.edit()
                 .putLong(KEY_LAST_ROW_ID, rowId)
                 .putInt(KEY_LOOP_COUNT, finalLoopCount)
                 .apply();

            // Show dialog on UI thread
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                CrashRecoveryDialog dlg = CrashRecoveryDialog.newInstance(
                    report, rowId, instanceModsDir, finalLoopCount);
                dlg.setCancelable(false);
                try {
                    dlg.show(activity.getSupportFragmentManager(), "crash_recovery");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show CrashRecoveryDialog", e);
                }
            });
        }, "crash-analyze").start();
    }

    /**
     * Call when a launch succeeds (game reaches main menu) to mark the previous
     * fix as successful and reset the loop counter.
     */
    public void onLaunchSucceeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastRowId = prefs.getLong(KEY_LAST_ROW_ID, -1);
        if (lastRowId >= 0) {
            CrashHistoryDatabase.getInstance(context).setFixSucceeded(lastRowId, true);
        }
        prefs.edit()
             .putLong(KEY_LAST_ROW_ID, -1)
             .putInt(KEY_LOOP_COUNT, 0)
             .apply();
        pendingRowId = -1;
        Log.i(TAG, "Launch succeeded – crash loop reset.");
    }

    /**
     * Snapshot the current mod list and injection history for correlation.
     * Call this just before the game process starts.
     *
     * The data is embedded in the crash record's log_tail field as a header block
     * so no extra schema columns are needed.
     *
     * @return A text block suitable for prepending to the log_tail.
     */
    public static String buildLaunchSnapshot(Context context, String modsDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== LAUNCH SNAPSHOT ===\n");
        sb.append("Time: ").append(System.currentTimeMillis()).append('\n');

        // Mods list
        if (modsDir != null) {
            File dir = new File(modsDir);
            if (dir.isDirectory()) {
                File[] jars = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
                sb.append("Mods (").append(jars == null ? 0 : jars.length).append("):\n");
                if (jars != null) {
                    for (File jar : jars) sb.append("  ").append(jar.getName()).append('\n');
                }
            }
        }

        // Last injection
        InjectionHistoryEntry latest = InjectionHistoryManager.getLatestActive(context);
        if (latest != null) {
            sb.append("Last injection: ").append(latest.timestampMs)
              .append(" – ").append(CrashAnalyzer.firstLineOf(latest.code)).append('\n');
        } else {
            sb.append("No recent injections.\n");
        }

        sb.append("======================\n");
        return sb.toString();
    }
}
