package com.movtery.zalithlauncher.zerolauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.snackbar.Snackbar;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.feature.version.Version;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashRecoveryDialog – shown automatically after a game crash.
 *
 * Displays the exception summary, identifies the likely culprit,
 * and offers a one-tap fix with immediate relaunch.
 *
 * Usage:
 *   CrashRecoveryDialog dlg = CrashRecoveryDialog.newInstance(report, rowId, version, modsDir);
 *   dlg.show(getSupportFragmentManager(), "crash_recovery");
 */
public class CrashRecoveryDialog extends DialogFragment {

    private static final String TAG = "CrashRecoveryDlg";

    /** Max automatic relaunch attempts before forcing manual intervention. */
    public static final int MAX_AUTO_RELAUNCHES = 3;

    // ── Arguments ──────────────────────────────────────────────────────────────
    private static final String ARG_EX_TYPE        = "ex_type";
    private static final String ARG_EX_MSG         = "ex_msg";
    private static final String ARG_CAUSE_CHAIN    = "cause_chain";
    private static final String ARG_SUSPECT_MOD    = "suspect_mod";
    private static final String ARG_SUSPECT_JAR    = "suspect_jar";
    private static final String ARG_INJECTION_FLAG = "injection_flag";
    private static final String ARG_INJECTION_LINE = "injection_line";
    private static final String ARG_INJECTION_TIME = "injection_time";
    private static final String ARG_PRE_MOD_CRASH  = "pre_mod_crash";
    private static final String ARG_LOG_PATH       = "log_path";
    private static final String ARG_ROW_ID         = "row_id";
    private static final String ARG_MODS_DIR       = "mods_dir";
    private static final String ARG_LOOP_COUNT     = "loop_count";

    // ── Callback ───────────────────────────────────────────────────────────────

    /** Implemented by the host Activity to relaunch the game after a fix. */
    public interface RelaunchCallback {
        void relaunch();
    }

    private RelaunchCallback relaunchCallback;

    // ── Fix selection ──────────────────────────────────────────────────────────
    private static final int FIX_DISABLE_MOD      = 0;
    private static final int FIX_REVERT_INJECTION = 1;
    private static final int FIX_RETRY            = 2;

    // ── Views ──────────────────────────────────────────────────────────────────
    private RadioGroup  rgFix;
    private RadioButton rbDisableMod;
    private RadioButton rbRevertInject;
    private RadioButton rbRetry;
    private Button      btnFixRelaunch;
    private Button      btnIgnore;
    private TextView    tvExSummary;
    private TextView    tvCulprit;
    private TextView    tvWarning;

    // ── State ──────────────────────────────────────────────────────────────────
    private long   rowId;
    private String modsDir;
    private String suspectJar;
    private boolean fixApplied = false;

    // ── Factory ────────────────────────────────────────────────────────────────

    public static CrashRecoveryDialog newInstance(
            CrashAnalyzer.CrashReport report,
            long dbRowId,
            String instanceModsDir,
            int crashLoopCount) {

        CrashRecoveryDialog dlg = new CrashRecoveryDialog();
        Bundle args = new Bundle();
        args.putString(ARG_EX_TYPE,        report.exceptionType);
        args.putString(ARG_EX_MSG,         report.exceptionMessage);
        args.putString(ARG_CAUSE_CHAIN,    report.causeChain);
        args.putString(ARG_SUSPECT_MOD,
            report.suspectMods.isEmpty() ? null : report.suspectMods.get(0).modName);
        args.putString(ARG_SUSPECT_JAR,
            report.suspectMods.isEmpty() ? null : report.suspectMods.get(0).jarFilename);
        args.putBoolean(ARG_INJECTION_FLAG, report.injectionSuspect);
        args.putString(ARG_INJECTION_LINE,  report.injectionFirstLine);
        args.putString(ARG_INJECTION_TIME,  report.injectionTime);
        args.putBoolean(ARG_PRE_MOD_CRASH,  report.preModCrash);
        args.putString(ARG_LOG_PATH,        report.crashLogPath);
        args.putLong(ARG_ROW_ID,            dbRowId);
        args.putString(ARG_MODS_DIR,        instanceModsDir);
        args.putInt(ARG_LOOP_COUNT,         crashLoopCount);
        dlg.setArguments(args);
        return dlg;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof RelaunchCallback) {
            relaunchCallback = (RelaunchCallback) context;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dlg = super.onCreateDialog(savedInstanceState);
        if (dlg.getWindow() != null) {
            dlg.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        return dlg;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.dialog_crash_recovery, container, false);

        Bundle args = requireArguments();
        rowId      = args.getLong(ARG_ROW_ID, -1);
        modsDir    = args.getString(ARG_MODS_DIR, "");
        suspectJar = args.getString(ARG_SUSPECT_JAR, "");

        String exType        = args.getString(ARG_EX_TYPE, "Unknown error");
        String exMsg         = args.getString(ARG_EX_MSG, "");
        String causeChain    = args.getString(ARG_CAUSE_CHAIN, "");
        String suspectMod    = args.getString(ARG_SUSPECT_MOD);
        boolean injFlag      = args.getBoolean(ARG_INJECTION_FLAG, false);
        String injLine       = args.getString(ARG_INJECTION_LINE, "");
        String injTime       = args.getString(ARG_INJECTION_TIME, "");
        boolean preMod       = args.getBoolean(ARG_PRE_MOD_CRASH, false);
        String logPath       = args.getString(ARG_LOG_PATH, "");
        int loopCount        = args.getInt(ARG_LOOP_COUNT, 0);

        // ── Bind views ─────────────────────────────────────────────────────────
        tvExSummary   = root.findViewById(R.id.tv_crash_ex_summary);
        tvCulprit     = root.findViewById(R.id.tv_crash_culprit);
        tvWarning     = root.findViewById(R.id.tv_crash_warning);
        rgFix         = root.findViewById(R.id.rg_crash_fix);
        rbDisableMod  = root.findViewById(R.id.rb_disable_mod);
        rbRevertInject= root.findViewById(R.id.rb_revert_inject);
        rbRetry       = root.findViewById(R.id.rb_retry);
        btnFixRelaunch= root.findViewById(R.id.btn_fix_relaunch);
        btnIgnore     = root.findViewById(R.id.btn_ignore);
        Button btnShowLog = root.findViewById(R.id.btn_show_log);

        // ── Exception summary ──────────────────────────────────────────────────
        String summary = exType;
        if (!exMsg.isEmpty()) summary += ": " + exMsg;
        tvExSummary.setText(summary);

        // ── Culprit ────────────────────────────────────────────────────────────
        if (preMod) {
            tvCulprit.setText("⚠ Crash before mods loaded – check Java version or launcher settings.");
        } else if (suspectMod != null) {
            tvCulprit.setText("Likely cause: " + suspectMod + " (file: " + suspectJar + ")");
        } else if (injFlag) {
            tvCulprit.setText("Likely cause: Code injection at " + injTime + "\n▸ " + injLine);
        } else {
            tvCulprit.setText("No specific mod identified. May be a vanilla bug or conflicting mods.");
        }

        // ── Crash-loop warning ─────────────────────────────────────────────────
        if (loopCount >= MAX_AUTO_RELAUNCHES) {
            tvWarning.setVisibility(View.VISIBLE);
            tvWarning.setText("⚡ The previous fix did not resolve the crash.\n"
                + "Consider disabling all mods or checking launcher settings.");
        } else {
            tvWarning.setVisibility(View.GONE);
        }

        // ── Radio options ──────────────────────────────────────────────────────
        if (suspectMod != null) {
            rbDisableMod.setText("Disable \"" + suspectMod + "\" and relaunch");
            rbDisableMod.setVisibility(View.VISIBLE);
            rbDisableMod.setChecked(true);
        } else {
            rbDisableMod.setVisibility(View.GONE);
        }

        if (injFlag) {
            rbRevertInject.setText("Revert injection (" + injTime + ") and relaunch");
            rbRevertInject.setVisibility(View.VISIBLE);
            if (suspectMod == null) rbRevertInject.setChecked(true);
        } else {
            rbRevertInject.setVisibility(View.GONE);
        }

        rbRetry.setChecked(!rbDisableMod.isChecked() && !rbRevertInject.isChecked());

        // ── Buttons ────────────────────────────────────────────────────────────
        btnFixRelaunch.setOnClickListener(v -> onFixRelaunch(logPath));
        btnIgnore.setOnClickListener(v -> dismiss());
        btnShowLog.setOnClickListener(v -> showFullLog(logPath));

        return root;
    }

    // ── Fix & Relaunch ─────────────────────────────────────────────────────────

    private void onFixRelaunch(String logPath) {
        if (fixApplied) return; // prevent double-tap

        int selectedId = rgFix.getCheckedRadioButtonId();
        int fixType;

        if (selectedId == R.id.rb_disable_mod) {
            fixType = FIX_DISABLE_MOD;
        } else if (selectedId == R.id.rb_revert_inject) {
            fixType = FIX_REVERT_INJECTION;
        } else {
            fixType = FIX_RETRY;
        }

        applyFix(fixType);
    }

    private void applyFix(int fixType) {
        Context ctx = requireContext();
        btnFixRelaunch.setEnabled(false);
        btnFixRelaunch.setText("Applying fix…");

        new Thread(() -> {
            boolean fixOk = true;
            String fixLabel;

            switch (fixType) {
                case FIX_DISABLE_MOD:
                    fixOk  = disableMod();
                    fixLabel = CrashHistoryDatabase.FIX_DISABLE_MOD;
                    break;
                case FIX_REVERT_INJECTION:
                    revertInjection();
                    fixLabel = CrashHistoryDatabase.FIX_REVERT_INJECTION;
                    break;
                default:
                    fixLabel = CrashHistoryDatabase.FIX_RETRY;
                    break;
            }

            // Persist chosen fix
            if (rowId >= 0) {
                CrashHistoryDatabase.getInstance(ctx).updateFix(rowId, fixLabel);
            }

            final boolean finalFixOk = fixOk;
            final String finalFixLabel = fixLabel;

            new Handler(Looper.getMainLooper()).post(() -> {
                fixApplied = true;
                if (!finalFixOk) {
                    Toast.makeText(ctx,
                        "Could not apply fix (mod file not found). Relaunching anyway.",
                        Toast.LENGTH_LONG).show();
                } else if (finalFixLabel.equals(CrashHistoryDatabase.FIX_DISABLE_MOD)) {
                    // Offer undo via Snackbar
                    View rootView = getDialog() != null && getDialog().getWindow() != null
                        ? getDialog().getWindow().getDecorView() : null;
                    if (rootView != null) {
                        Snackbar.make(rootView,
                            "Mod disabled. Relaunching…", Snackbar.LENGTH_LONG)
                            .setAction("Undo", v -> undoDisableMod())
                            .show();
                    }
                }

                // Schedule relaunch
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    dismiss();
                    if (relaunchCallback != null) relaunchCallback.relaunch();
                }, finalFixLabel.equals(CrashHistoryDatabase.FIX_DISABLE_MOD) ? 3500 : 300);
            });
        }, "crash-fix").start();
    }

    // ── Mod disable / undo ─────────────────────────────────────────────────────

    private File disabledMod;  // saved for undo

    private boolean disableMod() {
        if (modsDir == null || modsDir.isEmpty() || suspectJar == null) return false;
        File modFile = new File(modsDir, suspectJar);
        if (!modFile.exists()) return false;

        File disabledDir = new File(modsDir, "disabled-mods");
        disabledDir.mkdirs();
        disabledMod = new File(disabledDir, suspectJar);

        boolean ok = modFile.renameTo(disabledMod);
        if (!ok) {
            // Fallback: rename in-place
            disabledMod = new File(modsDir, suspectJar + ".disabled");
            ok = modFile.renameTo(disabledMod);
        }
        if (ok) {
            Log.i(TAG, "Mod disabled: " + modFile.getAbsolutePath()
                + " → " + disabledMod.getAbsolutePath());
        }
        return ok;
    }

    private void undoDisableMod() {
        if (disabledMod == null || !disabledMod.exists()) return;
        File original = new File(modsDir, suspectJar);
        if (disabledMod.renameTo(original)) {
            Toast.makeText(requireContext(),
                "Mod re-enabled: " + suspectJar, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Injection revert ───────────────────────────────────────────────────────

    private void revertInjection() {
        InjectionHistoryManager.markLatestReverted(requireContext());
        Log.i(TAG, "Most recent injection marked as reverted.");
    }

    // ── Full log viewer ────────────────────────────────────────────────────────

    private void showFullLog(String logPath) {
        if (TextUtils.isEmpty(logPath)) {
            Toast.makeText(requireContext(), "No crash log file found.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Open DevToolsActivity on the Crashes tab, passing the log path
        Intent intent = new Intent(requireContext(), DevToolsActivity.class);
        intent.putExtra(DevToolsActivity.EXTRA_OPEN_TAB, DevToolsActivity.TAB_CRASHES);
        intent.putExtra(CrashHistoryFragment.EXTRA_CRASH_LOG_PATH, logPath);
        startActivity(intent);
    }
}
