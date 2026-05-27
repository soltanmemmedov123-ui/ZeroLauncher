package com.movtery.zalithlauncher.zerolauncher;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.movtery.zalithlauncher.R;

/**
 * DevToolsActivity – hosts four developer tool fragments behind a simple
 * four-tab toggle bar:
 *
 *  • Tab 0 – Class Explorer      (ClassExplorerFragment)
 *  • Tab 1 – Injection Console   (InjectionConsoleFragment)
 *  • Tab 2 – Network Monitor     (NetworkMonitorFragment)
 *  • Tab 3 – Crashes             (CrashHistoryFragment)        ← NEW
 *
 * INTEGRATION CHANGES vs original:
 *  - Added TAB_CRASHES constant and associated views/tags.
 *  - Added tab_crashes TextView to the tab bar (see activity_dev_tools.xml diff).
 *  - Added EXTRA_OPEN_TAB intent extra so external callers can open directly to Crashes.
 *  - restoreTabVisibility / tagForTab / updateTabVisuals extended to cover 4 tabs.
 */
public class DevToolsActivity extends AppCompatActivity {

    // ── Tool tab indices ───────────────────────────────────────────────────────
    public static final int TAB_CLASS_EXPLORER  = 0;
    public static final int TAB_INJECT_CONSOLE  = 1;
    public static final int TAB_NETWORK_MONITOR = 2;
    public static final int TAB_MEMORY         = 4;
    public static final int TAB_CRASHES         = 3;          // ← NEW

    /** Intent extra to open a specific tab on launch. */
    public static final String EXTRA_OPEN_TAB = "open_tab";

    private static final String KEY_ACTIVE_TAB = "active_tab";

    // ── Views ──────────────────────────────────────────────────────────────────
    private View     ipcStatusDot;
    private TextView tvIpcStatus;
    private TextView tvPingResult;

    private TextView tabClassExplorer;
    private TextView tabInjectionConsole;
    private TextView tabNetworkMonitor;
    private TextView tabMemory;
    private TextView tabCrashes;                              // ← NEW

    private int activeTab = TAB_CLASS_EXPLORER;

    // ── Fragment tags ──────────────────────────────────────────────────────────
    private static final String TAG_CLASS_EXPLORER   = "frag_class_explorer";
    private static final String TAG_INJECT_CONSOLE   = "frag_inject_console";
    private static final String TAG_NETWORK_MONITOR  = "frag_network_monitor";
    private static final String TAG_MEMORY           = "frag_memory";
    private static final String TAG_CRASHES          = "frag_crashes";           // ← NEW

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dev_tools);

        ipcStatusDot = findViewById(R.id.ipc_status_dot);
        tvIpcStatus  = findViewById(R.id.tv_ipc_status);
        tvPingResult = findViewById(R.id.tv_ping_result);

        tabClassExplorer    = findViewById(R.id.tab_class_explorer);
        tabInjectionConsole = findViewById(R.id.tab_injection_console);
        tabNetworkMonitor   = findViewById(R.id.tab_network_monitor);
        tabMemory           = findViewById(R.id.tab_memory);
        tabCrashes          = findViewById(R.id.tab_crashes);                    // ← NEW

        // Back button
        findViewById(R.id.dev_tools_back).setOnClickListener(v -> finish());

        // Ping button
        Button btnPing = findViewById(R.id.btn_ping);
        if (btnPing != null) btnPing.setOnClickListener(v -> onPingClick());

        // Tab buttons
        tabClassExplorer.setOnClickListener(v    -> selectTab(TAB_CLASS_EXPLORER));
        tabInjectionConsole.setOnClickListener(v -> selectTab(TAB_INJECT_CONSOLE));
        tabNetworkMonitor.setOnClickListener(v   -> selectTab(TAB_NETWORK_MONITOR));
        tabMemory.setOnClickListener(v           -> selectTab(TAB_MEMORY));
        tabCrashes.setOnClickListener(v          -> selectTab(TAB_CRASHES));     // ← NEW

        // Determine initial tab (from intent or saved state)
        int initialTab = TAB_CLASS_EXPLORER;
        if (getIntent() != null) {
            initialTab = getIntent().getIntExtra(EXTRA_OPEN_TAB, TAB_CLASS_EXPLORER);
        }
        if (savedInstanceState != null) {
            initialTab = savedInstanceState.getInt(KEY_ACTIVE_TAB, initialTab);
        }
        activeTab = initialTab;

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .add(R.id.dev_tools_fragment_container,
                     new ClassExplorerFragment(), TAG_CLASS_EXPLORER)
                .add(R.id.dev_tools_fragment_container,
                     new InjectionConsoleFragment(), TAG_INJECT_CONSOLE)
                .add(R.id.dev_tools_fragment_container,
                     new NetworkMonitorFragment(), TAG_NETWORK_MONITOR)
            .add(R.id.dev_tools_fragment_container,
                     new MemoryProfilerFragment(), TAG_MEMORY)
            .add(R.id.dev_tools_fragment_container,                          // ← NEW
                     new CrashHistoryFragment(), TAG_CRASHES)
            .hide(getFragment(TAG_INJECT_CONSOLE))
            .hide(getFragment(TAG_NETWORK_MONITOR))
            .hide(getFragment(TAG_MEMORY))

            // Show the requested initial tab
            if (activeTab != TAB_CLASS_EXPLORER) {
                getSupportFragmentManager().beginTransaction()
                    .hide(getFragment(TAG_CLASS_EXPLORER))
                    .show(getFragment(tagForTab(activeTab)))
                    .commit();
            }
        } else {
            restoreTabVisibility(activeTab);
        }

        updateTabVisuals(activeTab);
        refreshIpcStatus();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_ACTIVE_TAB, activeTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshIpcStatus();
        IpcClient.setOnDisconnectListener(() -> runOnUiThread(this::refreshIpcStatus));
        updateNetworkProxyIndicator();
    }

    @Override
    protected void onPause() {
        super.onPause();
        IpcClient.setOnDisconnectListener(null);
    }

    // ── Tab navigation ─────────────────────────────────────────────────────────

    private void selectTab(int tab) {
        if (tab == activeTab) return;

        Fragment fromFrag = getFragment(tagForTab(activeTab));
        Fragment toFrag   = getFragment(tagForTab(tab));
        if (fromFrag == null || toFrag == null) return;

        activeTab = tab;
        updateTabVisuals(tab);

        getSupportFragmentManager().beginTransaction()
            .hide(fromFrag)
            .show(toFrag)
            .commit();
    }

    private void updateTabVisuals(int tab) {
        applyTabStyle(tabClassExplorer,    tab == TAB_CLASS_EXPLORER);
        applyTabStyle(tabInjectionConsole, tab == TAB_INJECT_CONSOLE);
        applyTabStyle(tabNetworkMonitor,   tab == TAB_NETWORK_MONITOR);
        applyTabStyle(tabMemory,           tab == TAB_MEMORY);
        applyTabStyle(tabCrashes,          tab == TAB_CRASHES);                  // ← NEW
    }

    private void applyTabStyle(TextView tv, boolean active) {
        if (tv == null) return;
        tv.setTextColor(active
            ? getColor(R.color.zl_cyan)
            : getColor(R.color.zl_text_secondary));
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        tv.setAlpha(active ? 1.0f : 0.55f);
    }

    private void restoreTabVisibility(int active) {
        int totalTabs = 5;
        getSupportFragmentManager().beginTransaction()
            .runOnCommit(() -> {
                for (int t = 0; t < totalTabs; t++) {
                    Fragment f = getFragment(tagForTab(t));
                    if (f == null) continue;
                    getSupportFragmentManager().beginTransaction()
                        .apply(f, t == active)
                        .commitAllowingStateLoss();
                }
            })
            .commitAllowingStateLoss();
    }

    private Fragment getFragment(String tag) {
        return getSupportFragmentManager().findFragmentByTag(tag);
    }

    private static String tagForTab(int tab) {
        switch (tab) {
            case TAB_CLASS_EXPLORER:  return TAG_CLASS_EXPLORER;
            case TAB_INJECT_CONSOLE:  return TAG_INJECT_CONSOLE;
            case TAB_NETWORK_MONITOR: return TAG_NETWORK_MONITOR;
            case TAB_MEMORY:          return TAG_MEMORY;
            case TAB_CRASHES:         return TAG_CRASHES;                        // ← NEW
            default:                  return TAG_CLASS_EXPLORER;
        }
    }

    // ── Network proxy status ───────────────────────────────────────────────────

    private void updateNetworkProxyIndicator() {
        if (tabNetworkMonitor == null) return;
        boolean proxyActive = MinecraftProxy.getInstance() != null;
        if (proxyActive && activeTab != TAB_NETWORK_MONITOR) {
            tabNetworkMonitor.setText("📡  NETWORK  ●");
        } else {
            tabNetworkMonitor.setText("📡  NETWORK");
        }
    }

    // ── IPC Status ─────────────────────────────────────────────────────────────

    private void refreshIpcStatus() {
        boolean connected = (IpcClient.getInstance() != null);
        tvIpcStatus.setText(connected ? "Connected" : "Disconnected");
        ipcStatusDot.setBackgroundResource(
            connected ? R.drawable.dot_green : R.drawable.dot_amber);
        updateNetworkProxyIndicator();
    }

    public void trackClassInMemoryProfiler(String className) {
        Fragment memoryFragment = getFragment(TAG_MEMORY);
        if (memoryFragment instanceof MemoryProfilerFragment) {
            ((MemoryProfilerFragment) memoryFragment).prefillTrackedClass(className);
        }
        selectTab(TAB_MEMORY);
    }

    // ── Ping ───────────────────────────────────────────────────────────────────

    private void onPingClick() {
        Button btnPing = findViewById(R.id.btn_ping);
        if (btnPing != null) btnPing.setEnabled(false);
        showPingResult("Pinging…");

        new Thread(() -> {
            IpcClient.connect();
            IpcClient client = IpcClient.getInstance();
            if (client == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "No game process running", Toast.LENGTH_SHORT).show();
                    showPingResult("No game process running.");
                    if (btnPing != null) btnPing.setEnabled(true);
                });
                return;
            }
            String result = client.sendPing();
            runOnUiThread(() -> {
                showPingResult(result);
                if (btnPing != null) btnPing.setEnabled(true);
                refreshIpcStatus();
            });
        }, "ipc-ping").start();
    }

    private void showPingResult(String text) {
        tvPingResult.setText(text);
        tvPingResult.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Helper extension ───────────────────────────────────────────────────────

    /** Small helper to avoid duplication in restoreTabVisibility. */
    private androidx.fragment.app.FragmentTransaction apply(
            androidx.fragment.app.FragmentTransaction tx,
            Fragment f, boolean show) {
        return show ? tx.show(f) : tx.hide(f);
    }
}
