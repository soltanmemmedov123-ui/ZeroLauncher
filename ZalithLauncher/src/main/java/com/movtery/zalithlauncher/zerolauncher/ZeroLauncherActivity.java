package com.movtery.zalithlauncher.zerolauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.ui.view.ZLToggle;

/**
 * ZeroLauncherActivity – main launcher UI (landscape-only).
 *
 * Layout: activity_zero_launcher.xml
 * 7 pages: Home, Settings, Downloads, Versions, Java, Layouts, About
 *
 * All settings persisted in SharedPreferences "zero_launcher_prefs".
 * Launch button triggers the existing game launch flow.
 */
public class ZeroLauncherActivity extends AppCompatActivity {

    // Prefs key
    private static final String PREFS = "zero_launcher_prefs";
    private static final String KEY_CURRENT_PAGE = "current_page";

    // Page indices
    static final int PAGE_HOME      = 0;
    static final int PAGE_SETTINGS  = 1;
    static final int PAGE_DOWNLOADS = 2;
    static final int PAGE_VERSIONS  = 3;
    static final int PAGE_JAVA      = 4;
    static final int PAGE_LAYOUTS   = 5;
    static final int PAGE_ABOUT     = 6;

    // Nav buttons
    private FrameLayout[] navButtons;
    private View[] navIndicators;
    private ImageView[] navIcons;

    // Pages
    private View[] pages;
    private int currentPage = PAGE_HOME;

    // Settings toggles
    private ZLToggle toggleMsaa, toggleHaptic, toggleGyro, toggleController,
            toggleSustainedPerf, toggleOpenGL, toggleFpsCounter,
            toggleAltRendering, toggleAutoUpdate, toggleVsyncZink, toggleSystemVulkan,
            toggleJavaSecurity, toggleOverlay, toggleVirtualMouse, toggleCursorAutoHide;

    // Settings sliders
    private SeekBar sliderResolution, sliderFps, sliderTouchSens, sliderGyrSens,
            sliderDeadzone, sliderMemory, sliderOverlayOpacity,
            sliderMouseSpeed, sliderCursorSize;

    // Value TextViews
    private TextView valResolution, valFps, valTouchSens, valGyrSens,
            valDeadzone, valMemory, valOverlayOpacity,
            valMouseSpeed, valCursorSize;

    // Memory bar fill
    private View memoryBarFill;

    // Downloads tab state
    private View[] dlTabs;
    private View[] dlChips;
    private int currentDlTab = 0;
    private int currentChip = 0;

    // Layout selection
    private View cardDefault, cardGamepad, cardMinimal;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zero_launcher);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        currentPage = prefs.getInt(KEY_CURRENT_PAGE, PAGE_HOME);

        initNavigation();
        initPages();
        initSettingsControls();
        initDownloadsPage();
        initVersionsPage();
        initJavaPage();
        initLayoutsPage();
        initAboutPage();
        initLaunchButton();

        // Show saved page
        switchToPage(currentPage, false);
        checkOrientation();
    }

    // ──────────────── Navigation ────────────────

    private void initNavigation() {
        navButtons = new FrameLayout[]{
            findViewById(R.id.nav_home),
            findViewById(R.id.nav_settings),
            findViewById(R.id.nav_downloads),
            findViewById(R.id.nav_versions),
            findViewById(R.id.nav_java),
            findViewById(R.id.nav_layouts),
            findViewById(R.id.nav_about)
        };

        navIndicators = new View[]{
            findViewById(R.id.nav_indicator_home),
            findViewById(R.id.nav_indicator_settings),
            findViewById(R.id.nav_indicator_downloads),
            findViewById(R.id.nav_indicator_versions),
            findViewById(R.id.nav_indicator_java),
            findViewById(R.id.nav_indicator_layouts),
            findViewById(R.id.nav_indicator_about)
        };

        navIcons = new ImageView[]{
            findViewById(R.id.nav_icon_home),
            findViewById(R.id.nav_icon_settings),
            findViewById(R.id.nav_icon_downloads),
            findViewById(R.id.nav_icon_versions),
            findViewById(R.id.nav_icon_java),
            findViewById(R.id.nav_icon_layouts),
            findViewById(R.id.nav_icon_about)
        };

        for (int i = 0; i < navButtons.length; i++) {
            final int pageIdx = i;
            navButtons[i].setOnClickListener(v -> switchToPage(pageIdx, true));
        }
    }

    private void initPages() {
        pages = new View[]{
            findViewById(R.id.page_home),
            findViewById(R.id.page_settings),
            findViewById(R.id.page_downloads),
            findViewById(R.id.page_versions),
            findViewById(R.id.page_java),
            findViewById(R.id.page_layouts),
            findViewById(R.id.page_about)
        };
    }

    private void switchToPage(int pageIdx, boolean animate) {
        if (pageIdx == currentPage && pages[pageIdx].getVisibility() == View.VISIBLE) {
            return;
        }
        final int prev = currentPage;
        currentPage = pageIdx;

        // Update nav state
        for (int i = 0; i < navButtons.length; i++) {
            boolean active = (i == pageIdx);
            navButtons[i].setSelected(active);
            navIndicators[i].setVisibility(active ? View.VISIBLE : View.GONE);
            navIcons[i].setAlpha(active ? 1.0f : 0.55f);
        }

        // Switch pages
        if (animate) {
            View outPage = pages[prev];
            View inPage  = pages[pageIdx];

            inPage.setAlpha(0f);
            inPage.setTranslationX(16f);
            inPage.setVisibility(View.VISIBLE);

            inPage.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();

            outPage.animate()
                .alpha(0f)
                .setDuration(120)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        outPage.setVisibility(View.GONE);
                        outPage.setAlpha(1f);
                    }
                })
                .start();
        } else {
            for (int i = 0; i < pages.length; i++) {
                pages[i].setVisibility(i == pageIdx ? View.VISIBLE : View.GONE);
            }
        }

        prefs.edit().putInt(KEY_CURRENT_PAGE, pageIdx).apply();
    }

    // ──────────────── Settings Controls ────────────────

    private void initSettingsControls() {
        // Toggles
        toggleMsaa            = findViewById(R.id.toggle_msaa);
        toggleHaptic          = findViewById(R.id.toggle_haptic);
        toggleGyro            = findViewById(R.id.toggle_gyro);
        toggleController      = findViewById(R.id.toggle_controller);
        toggleSustainedPerf   = findViewById(R.id.toggle_sustained_perf);
        toggleOpenGL          = findViewById(R.id.toggle_opengl);
        toggleFpsCounter      = findViewById(R.id.toggle_fps_counter);
        toggleAltRendering    = findViewById(R.id.toggle_alt_rendering);
        toggleAutoUpdate      = findViewById(R.id.toggle_auto_update);
        toggleVsyncZink       = findViewById(R.id.toggle_vsync_zink);
        toggleSystemVulkan    = findViewById(R.id.toggle_system_vulkan);
        toggleJavaSecurity    = findViewById(R.id.toggle_java_security);
        toggleOverlay         = findViewById(R.id.toggle_overlay);
        toggleVirtualMouse    = findViewById(R.id.toggle_virtual_mouse);
        toggleCursorAutoHide  = findViewById(R.id.toggle_cursor_autohide);

        // Restore toggle states
        restoreToggle(toggleMsaa,           "msaa",           true);
        restoreToggle(toggleHaptic,         "haptic",         true);
        restoreToggle(toggleGyro,           "gyro",           false);
        restoreToggle(toggleController,     "controller",     true);
        restoreToggle(toggleSustainedPerf,  "sustained_perf", false);
        restoreToggle(toggleOpenGL,         "opengl",         true);
        restoreToggle(toggleFpsCounter,     "fps_counter",    false);
        restoreToggle(toggleAltRendering,   "alt_rendering",  false);
        restoreToggle(toggleAutoUpdate,     "auto_update",    true);
        restoreToggle(toggleVsyncZink,      "vsync_zink",     true);
        restoreToggle(toggleSystemVulkan,   "system_vulkan",  false);
        restoreToggle(toggleJavaSecurity,   "java_security",  false);
        restoreToggle(toggleOverlay,        "overlay",        true);
        restoreToggle(toggleVirtualMouse,   "virtual_mouse",  true);
        restoreToggle(toggleCursorAutoHide, "cursor_autohide",true);

        // Sliders + value labels
        sliderResolution = findViewById(R.id.slider_resolution);
        valResolution    = findViewById(R.id.val_resolution);
        setupSlider(sliderResolution, valResolution, "resolution", 60, 0, 100,
            v -> v + "%");

        sliderFps = findViewById(R.id.slider_fps);
        valFps    = findViewById(R.id.val_fps);
        setupSlider(sliderFps, valFps, "fps", 30, 0, 90,
            v -> String.valueOf(v + 30));

        sliderTouchSens = findViewById(R.id.slider_touch_sens);
        valTouchSens    = findViewById(R.id.val_touch_sens);
        setupSlider(sliderTouchSens, valTouchSens, "touch_sens", 7, 0, 10,
            v -> String.valueOf(v));

        sliderGyrSens = findViewById(R.id.slider_gyro_sens);
        valGyrSens    = findViewById(R.id.val_gyro_sens);
        setupSlider(sliderGyrSens, valGyrSens, "gyro_sens", 5, 0, 10,
            v -> String.valueOf(v));

        sliderDeadzone = findViewById(R.id.slider_deadzone);
        valDeadzone    = findViewById(R.id.val_deadzone);
        setupSlider(sliderDeadzone, valDeadzone, "deadzone", 10, 0, 30,
            v -> v + "%");

        sliderOverlayOpacity = findViewById(R.id.slider_overlay_opacity);
        valOverlayOpacity    = findViewById(R.id.val_overlay_opacity);
        setupSlider(sliderOverlayOpacity, valOverlayOpacity, "overlay_opacity", 80, 0, 100,
            v -> v + "%");

        sliderMouseSpeed = findViewById(R.id.slider_mouse_speed);
        valMouseSpeed    = findViewById(R.id.val_mouse_speed);
        setupSlider(sliderMouseSpeed, valMouseSpeed, "mouse_speed", 5, 0, 10,
            v -> String.valueOf(v));

        String[] cursorLabels = {"XS", "S", "M", "L", "XL"};
        sliderCursorSize = findViewById(R.id.slider_cursor_size);
        valCursorSize    = findViewById(R.id.val_cursor_size);
        setupSlider(sliderCursorSize, valCursorSize, "cursor_size", 1, 0, 4,
            v -> cursorLabels[Math.min(v, cursorLabels.length - 1)]);
    }

    private void restoreToggle(ZLToggle toggle, String key, boolean defaultVal) {
        if (toggle == null) return;
        boolean saved = prefs.getBoolean(key, defaultVal);
        toggle.setChecked(saved);
        toggle.setOnCheckedChangeListener((t, checked) ->
            prefs.edit().putBoolean(key, checked).apply()
        );
    }

    interface SliderFormatter { String format(int value); }

    private void setupSlider(SeekBar slider, TextView label, String key,
                              int defaultVal, int min, int max, SliderFormatter fmt) {
        if (slider == null || label == null) return;
        int saved = prefs.getInt(key, defaultVal);
        slider.setMax(max - min);
        slider.setProgress(saved - min);
        label.setText(fmt.format(saved));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int actual = progress + min;
                label.setText(fmt.format(actual));
                if (fromUser) prefs.edit().putInt(key, actual).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    // ──────────────── Memory Slider (Java page) ────────────────

    private void initJavaPage() {
        memoryBarFill = findViewById(R.id.memory_bar_fill);
        sliderMemory  = findViewById(R.id.slider_memory);
        valMemory     = findViewById(R.id.val_memory);

        // Memory: steps of 256MB from 512 to 4096 → 14 steps
        int[] memSteps = {512, 768, 1024, 1280, 1536, 1792, 2048, 2304, 2560, 2816, 3072, 3328, 3584, 3840, 4096};
        int savedMemIdx = prefs.getInt("memory_idx", 6); // default 2048

        if (sliderMemory != null) {
            sliderMemory.setMax(memSteps.length - 1);
            sliderMemory.setProgress(savedMemIdx);
            updateMemoryDisplay(memSteps[savedMemIdx], memSteps.length, savedMemIdx);

            sliderMemory.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    updateMemoryDisplay(memSteps[progress], memSteps.length, progress);
                    if (fromUser) prefs.edit().putInt("memory_idx", progress).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        // JVM args
        EditText jvmInput = findViewById(R.id.jvm_args_input);
        if (jvmInput != null) {
            String savedJvm = prefs.getString("jvm_args", "-XX:+UseG1GC -XX:MaxGCPauseMillis=50");
            jvmInput.setText(savedJvm);
        }

        View btnApplyJvm = findViewById(R.id.btn_apply_jvm);
        if (btnApplyJvm != null && jvmInput != null) {
            btnApplyJvm.setOnClickListener(v -> {
                String args = jvmInput.getText().toString().trim();
                prefs.edit().putString("jvm_args", args).apply();
                Toast.makeText(this, "JVM arguments applied", Toast.LENGTH_SHORT).show();
            });
        }

        View btnResetJvm = findViewById(R.id.btn_reset_jvm);
        if (btnResetJvm != null && jvmInput != null) {
            btnResetJvm.setOnClickListener(v -> {
                String def = "-XX:+UseG1GC -XX:MaxGCPauseMillis=50";
                jvmInput.setText(def);
                prefs.edit().putString("jvm_args", def).apply();
                Toast.makeText(this, "JVM arguments reset", Toast.LENGTH_SHORT).show();
            });
        }

        // JRE17 Set button
        View btnSetJre17 = findViewById(R.id.btn_set_jre17);
        if (btnSetJre17 != null) {
            btnSetJre17.setOnClickListener(v ->
                Toast.makeText(this, "Switching to JRE 17…", Toast.LENGTH_SHORT).show()
            );
        }

        // Add / Remove JRE
        View btnAddJre = findViewById(R.id.btn_add_jre);
        if (btnAddJre != null) btnAddJre.setOnClickListener(v ->
            Toast.makeText(this, "Add runtime…", Toast.LENGTH_SHORT).show());

        View btnRemoveJre = findViewById(R.id.btn_remove_jre);
        if (btnRemoveJre != null) btnRemoveJre.setOnClickListener(v ->
            Toast.makeText(this, "Remove runtime…", Toast.LENGTH_SHORT).show());
    }

    private void updateMemoryDisplay(int mb, int total, int idx) {
        if (valMemory != null) valMemory.setText(mb + " MB");
        if (memoryBarFill != null) {
            memoryBarFill.post(() -> {
                ViewGroup parent = (ViewGroup) memoryBarFill.getParent();
                if (parent == null) return;
                int parentW = parent.getWidth();
                float fraction = (float) idx / (total - 1);
                ViewGroup.LayoutParams lp = memoryBarFill.getLayoutParams();
                lp.width = (int)(parentW * fraction);
                memoryBarFill.setLayoutParams(lp);
            });
        }
    }

    // ──────────────── Downloads Page ────────────────

    private void initDownloadsPage() {
        dlTabs = new View[]{
            findViewById(R.id.tab_mod),
            findViewById(R.id.tab_resource),
            findViewById(R.id.tab_world),
            findViewById(R.id.tab_shader)
        };

        dlChips = new View[]{
            findViewById(R.id.chip_all),
            findViewById(R.id.chip_121x),
            findViewById(R.id.chip_fabric),
            findViewById(R.id.chip_forge),
            findViewById(R.id.chip_popular)
        };

        for (int i = 0; i < dlTabs.length; i++) {
            if (dlTabs[i] == null) continue;
            final int tabIdx = i;
            dlTabs[i].setOnClickListener(v -> selectDlTab(tabIdx));
        }

        for (int i = 0; i < dlChips.length; i++) {
            if (dlChips[i] == null) continue;
            final int chipIdx = i;
            dlChips[i].setOnClickListener(v -> selectChip(chipIdx));
        }

        selectDlTab(0);
        selectChip(0);

        // Get buttons
        View btnSodium = findViewById(R.id.btn_get_sodium);
        if (btnSodium != null) btnSodium.setOnClickListener(v ->
            Toast.makeText(this, "Downloading Sodium…", Toast.LENGTH_SHORT).show());

        View btnLithium = findViewById(R.id.btn_get_lithium);
        if (btnLithium != null) btnLithium.setOnClickListener(v ->
            Toast.makeText(this, "Downloading Lithium…", Toast.LENGTH_SHORT).show());

        View btnIris = findViewById(R.id.btn_get_iris);
        if (btnIris != null) btnIris.setOnClickListener(v ->
            Toast.makeText(this, "Downloading Iris Shaders…", Toast.LENGTH_SHORT).show());
    }

    private void selectDlTab(int idx) {
        currentDlTab = idx;
        String[] labels = {"Mod", "Resource Pack", "World", "Shader Pack"};
        for (int i = 0; i < dlTabs.length; i++) {
            if (dlTabs[i] == null) continue;
            boolean sel = (i == idx);
            dlTabs[i].setSelected(sel);
            if (dlTabs[i] instanceof TextView) {
                ((TextView) dlTabs[i]).setTextColor(sel ? 0xFF38BDF8 : 0xFF9A9AB0);
            }
        }
    }

    private void selectChip(int idx) {
        currentChip = idx;
        for (int i = 0; i < dlChips.length; i++) {
            if (dlChips[i] == null) continue;
            boolean sel = (i == idx);
            dlChips[i].setSelected(sel);
            if (dlChips[i] instanceof TextView) {
                ((TextView) dlChips[i]).setTextColor(sel ? 0xFF38BDF8 : 0xFF9A9AB0);
            }
        }
    }

    // ──────────────── Versions Page ────────────────

    private void initVersionsPage() {
        // Rename/Copy/Delete for OptiMobile
        View btnRename = findViewById(R.id.btn_rename_optim);
        if (btnRename != null) btnRename.setOnClickListener(v ->
            showTextInputDialog("Rename Version", "OptiMobile (Fabric)", newName ->
                Toast.makeText(this, "Renamed to: " + newName, Toast.LENGTH_SHORT).show()));

        View btnCopy = findViewById(R.id.btn_copy_optim);
        if (btnCopy != null) btnCopy.setOnClickListener(v ->
            Toast.makeText(this, "Copying OptiMobile…", Toast.LENGTH_SHORT).show());

        View btnDelete = findViewById(R.id.btn_delete_optim);
        if (btnDelete != null) btnDelete.setOnClickListener(v ->
            showConfirmDialog("Delete Version",
                "Delete OptiMobile (Fabric)? This cannot be undone.",
                () -> Toast.makeText(this, "Version deleted", Toast.LENGTH_SHORT).show()));

        // Folder shortcuts
        setupFolderShortcut(R.id.folder_resource_packs, "resourcepacks");
        setupFolderShortcut(R.id.folder_world_saves, "saves");
        setupFolderShortcut(R.id.folder_shaders, "shaderpacks");
        setupFolderShortcut(R.id.folder_crash, "crash-reports");
        setupFolderShortcut(R.id.folder_logs, "logs");
        setupFolderShortcut(R.id.folder_screenshots, "screenshots");

        // Home shortcuts
        setupShortcut(R.id.shortcut_mods, "Mod Management");
        setupShortcut(R.id.shortcut_game_path, "Game Path");
        setupShortcut(R.id.shortcut_resource_packs, "Resource Packs");
        setupShortcut(R.id.shortcut_world_saves, "World Saves");
        setupShortcut(R.id.shortcut_shaders, "Shader Packs");
        setupShortcut(R.id.shortcut_logs, "Log Folder");
        setupShortcut(R.id.vm_settings, "Version Settings");
        setupShortcut(R.id.vm_rename, "Rename Version");
        setupShortcut(R.id.vm_copy, "Copy Version");
        setupShortcut(R.id.vm_delete, "Delete Version");
        setupShortcut(R.id.vm_execute_jar, "Execute .jar");
        setupShortcut(R.id.vm_share_logs, "Share Logs");
    }

    private void setupFolderShortcut(int viewId, String folder) {
        View v = findViewById(viewId);
        if (v != null) v.setOnClickListener(view ->
            Toast.makeText(this, "Opening " + folder + "…", Toast.LENGTH_SHORT).show());
    }

    private void setupShortcut(int viewId, String name) {
        View v = findViewById(viewId);
        if (v != null) v.setOnClickListener(view ->
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show());
    }

    // ──────────────── Layouts Page ────────────────

    private void initLayoutsPage() {
        cardDefault = findViewById(R.id.layout_card_default);
        cardGamepad = findViewById(R.id.layout_card_gamepad);
        cardMinimal = findViewById(R.id.layout_card_minimal);

        View btnSelectDefault = findViewById(R.id.btn_select_default);
        View btnSelectGamepad = findViewById(R.id.btn_select_gamepad);
        View btnSelectMinimal = findViewById(R.id.btn_select_minimal);

        if (btnSelectGamepad != null) btnSelectGamepad.setOnClickListener(v ->
            Toast.makeText(this, "Layout set to Gamepad", Toast.LENGTH_SHORT).show());

        if (btnSelectMinimal != null) btnSelectMinimal.setOnClickListener(v ->
            Toast.makeText(this, "Layout set to Minimal", Toast.LENGTH_SHORT).show());

        View btnNewLayout = findViewById(R.id.btn_new_layout);
        if (btnNewLayout != null) btnNewLayout.setOnClickListener(v ->
            Toast.makeText(this, "Creating new layout…", Toast.LENGTH_SHORT).show());

        View btnImportLayout = findViewById(R.id.btn_import_layout);
        if (btnImportLayout != null) btnImportLayout.setOnClickListener(v ->
            Toast.makeText(this, "Importing layout…", Toast.LENGTH_SHORT).show());
    }

    // ──────────────── About Page ────────────────

    private void initAboutPage() {
        View badgeDiscord = findViewById(R.id.badge_discord);
        if (badgeDiscord != null) badgeDiscord.setOnClickListener(v ->
            openLink("https://discord.gg/zerolauncher"));

        View badgeGithub = findViewById(R.id.badge_github);
        if (badgeGithub != null) badgeGithub.setOnClickListener(v ->
            openLink("https://github.com/zerolauncher/zerolauncher"));

        View badgeGpl = findViewById(R.id.badge_gpl);
        if (badgeGpl != null) badgeGpl.setOnClickListener(v ->
            openLink("https://www.gnu.org/licenses/gpl-3.0.html"));
    }

    private void openLink(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────── Launch Button ────────────────

    private void initLaunchButton() {
        View btnLaunch = findViewById(R.id.btn_launch);
        if (btnLaunch != null) {
            btnLaunch.setOnClickListener(v -> {
                // Scale feedback
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                    .withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80)
                            .withEndAction(this::launchGame).start()
                    ).start();
            });
        }
    }

    private void launchGame() {
        // Delegate to existing launch flow
        Toast.makeText(this, "Launching Minecraft…", Toast.LENGTH_SHORT).show();
        try {
            // Try to use existing launcher infrastructure
            Class<?> launchActivity = Class.forName(
                "com.movtery.zalithlauncher.ui.activity.LauncherActivity");
            Intent intent = new Intent(this, launchActivity);
            startActivity(intent);
        } catch (ClassNotFoundException e) {
            // Fallback – the existing project structure handles this
            Toast.makeText(this, "Select a version to launch", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────── Orientation ────────────────

    private void checkOrientation() {
        View overlay = findViewById(R.id.portrait_overlay);
        if (overlay == null) return;

        int orientation = getResources().getConfiguration().orientation;
        overlay.setVisibility(
            orientation == Configuration.ORIENTATION_PORTRAIT
                ? View.VISIBLE : View.GONE
        );
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        checkOrientation();
    }

    // ──────────────── Dialogs ────────────────

    private void showTextInputDialog(String title, String initial, java.util.function.Consumer<String> onDone) {
        EditText input = new EditText(this);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", (d, w) -> onDone.accept(input.getText().toString().trim()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm", (d, w) -> onConfirm.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Persist current page
        prefs.edit().putInt(KEY_CURRENT_PAGE, currentPage).apply();
    }
}
