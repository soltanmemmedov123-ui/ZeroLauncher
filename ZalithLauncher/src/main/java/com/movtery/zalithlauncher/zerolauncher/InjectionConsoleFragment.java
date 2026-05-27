package com.movtery.zalithlauncher.zerolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.movtery.zalithlauncher.R;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * InjectionConsoleFragment – a live Bytecode Injection Console for ZeroLauncher DevTools.
 *
 * Sends command ID 4 (INJECT_CODE) over IPC. The agent evaluates BeanShell code
 * inside the running Minecraft JVM and returns captured stdout + return value (or
 * an error message), all without restarting the game.
 *
 * Protocol for CMD_INJECT_CODE (id=4):
 *   Request  payload : BinaryProtocol.encodeString(codeSnippet)
 *   Response payload : [1-byte status: 0=success, 1=error]
 *                      [length-prefixed UTF-8 string: output or error text]
 *
 * Template and history features are backed by SharedPreferences.
 */
public class InjectionConsoleFragment extends Fragment {

    // ── IPC command constant ───────────────────────────────────────────────────
    static final int CMD_INJECT_CODE = 4;

    // ── Timeouts ───────────────────────────────────────────────────────────────
    private static final long EXECUTION_TIMEOUT_MS = 15_000;

    // ── History ────────────────────────────────────────────────────────────────
    private static final String PREFS_NAME      = "injection_console_prefs";
    private static final String KEY_HISTORY     = "history_json";
    private static final int    MAX_HISTORY     = 20;

    // ── UI handles ─────────────────────────────────────────────────────────────
    private EditText   etCode;
    private Button     btnExecute;
    private Button     btnClear;
    private Button     btnHistory;
    private Button     btnTemplates;
    private TextView   tvOutput;
    private ScrollView outputScroll;
    private View       statusDot;
    private TextView   tvStatus;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private volatile boolean executionInFlight = false;

    // ── Built-in script templates ──────────────────────────────────────────────
    // G3 FIX: All templates use anonymous inner classes instead of lambda syntax.
    // BeanShell 2.0b6 supports the Java 5 grammar; lambda expressions (()->{})
    // were introduced in Java 8 and may fail to parse on older BeanShell builds.
    // Use "new Runnable() { public void run() { … } }" style throughout.
    private static final String[][] TEMPLATES = {
        { "Player Coords",
          "// Print the local player's current coordinates\n" +
          "double x = player.posX;\n" +
          "double y = player.posY;\n" +
          "double z = player.posZ;\n" +
          "print(\"Player pos: X=\" + x + \"  Y=\" + y + \"  Z=\" + z);\n" +
          "return \"x=\" + (int)x + \" y=\" + (int)y + \" z=\" + (int)z;" },

        { "Creative Mode",
          "// Switch the local player to Creative mode (singleplayer / LAN host)\n" +
          "import net.minecraft.world.GameType;\n" +
          "mc.playerController.setGameType(GameType.CREATIVE);\n" +
          "print(\"Game type set to CREATIVE\");" },

        { "Spawn Creeper",
          "// Spawn a Creeper at the player's feet (server-side, requires cheats)\n" +
          "import net.minecraft.entity.monster.EntityCreeper;\n" +
          "EntityCreeper e = new EntityCreeper(world);\n" +
          "e.setPosition(player.posX, player.posY, player.posZ);\n" +
          "world.spawnEntity(e);\n" +
          "print(\"Creeper spawned at \" + (int)player.posX + \" \" + (int)player.posY + \" \" + (int)player.posZ);" },

        { "Print MC Version",
          "// Print Minecraft version\n" +
          "print(\"Minecraft version: \" + mc.getVersion());\n" +
          "return mc.getVersion();" },

        { "List Loaded Mods",
          "// List Forge/Fabric mods currently loaded (Forge 1.12 example)\n" +
          "import net.minecraftforge.fml.common.Loader;\n" +
          "java.util.List mods = Loader.instance().getActiveModList();\n" +
          "for (int i = 0; i < mods.size(); i++) {\n" +
          "    Object mod = mods.get(i);\n" +
          "    print(mod.getModId() + \" \" + mod.getVersion());\n" +
          "}\n" +
          "return mods.size() + \" mods loaded\";" },

        { "Player Health",
          "// Print player health and max health\n" +
          "float hp    = player.getHealth();\n" +
          "float maxHp = player.getMaxHealth();\n" +
          "print(\"Health: \" + hp + \" / \" + maxHp);\n" +
          "return hp + \"/\" + maxHp;" },

        { "Run on Game Thread",
          "// G3 example: schedule work on the Minecraft main thread using\n" +
          "// an anonymous inner class (lambdas may fail on older BeanShell)\n" +
          "mc.addScheduledTask(new Runnable() {\n" +
          "    public void run() {\n" +
          "        print(\"Running on game thread: \" + mc.getVersion());\n" +
          "    }\n" +
          "});\n" +
          "return \"scheduled\";" },

        { "Hello World",
          "// The simplest possible script\n" +
          "print(\"Hello from BeanShell!\");\n" +
          "return \"done\";" },
    };

    // ── History (runtime list, synced to SharedPreferences) ───────────────────
    private final LinkedList<String> history = new LinkedList<>();

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_injection_console, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etCode       = view.findViewById(R.id.et_injection_code);
        btnExecute   = view.findViewById(R.id.btn_inject_execute);
        btnClear     = view.findViewById(R.id.btn_inject_clear);
        btnHistory   = view.findViewById(R.id.btn_inject_history);
        btnTemplates = view.findViewById(R.id.btn_inject_templates);
        tvOutput     = view.findViewById(R.id.tv_injection_output);
        outputScroll = view.findViewById(R.id.scroll_injection_output);
        statusDot    = view.findViewById(R.id.inject_status_dot);
        tvStatus     = view.findViewById(R.id.tv_inject_status);

        // Monospace font for the code editor
        etCode.setTypeface(Typeface.MONOSPACE);

        // Wire up buttons
        btnExecute.setOnClickListener(v -> onExecuteClicked());
        btnClear.setOnClickListener(v -> clearOutput());
        btnHistory.setOnClickListener(v -> showHistoryMenu());
        btnTemplates.setOnClickListener(v -> showTemplatesMenu());

        // Restore saved code if rotating or coming back from backstack
        if (savedInstanceState != null) {
            String savedCode = savedInstanceState.getString("code", "");
            if (!savedCode.isEmpty()) etCode.setText(savedCode);
            String savedOutput = savedInstanceState.getString("output", "");
            if (!savedOutput.isEmpty()) tvOutput.setText(savedOutput);
        }

        // Load history from SharedPreferences
        loadHistory();

        // Refresh IPC status
        refreshStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
        // Listen for disconnect events so we can grey-out the Run button
        IpcClient.setOnDisconnectListener(() -> uiHandler.post(this::refreshStatus));
    }

    @Override
    public void onPause() {
        super.onPause();
        IpcClient.setOnDisconnectListener(null);
        // Cancel any pending timeout runnable to avoid leaking the fragment
        if (timeoutRunnable != null) {
            uiHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (etCode  != null) out.putString("code",   etCode.getText().toString());
        if (tvOutput != null) out.putString("output", tvOutput.getText().toString());
    }

    // ── IPC Status ─────────────────────────────────────────────────────────────

    private void refreshStatus() {
        boolean connected = (IpcClient.getInstance() != null);
        if (statusDot != null) {
            statusDot.setBackgroundResource(
                connected ? R.drawable.dot_green : R.drawable.dot_amber);
        }
        if (tvStatus != null) {
            tvStatus.setText(connected ? "Game connected" : "Launch a game instance first.");
        }
        if (btnExecute != null) {
            btnExecute.setEnabled(connected && !executionInFlight);
        }
    }

    // ── Execute ────────────────────────────────────────────────────────────────

    private void onExecuteClicked() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) {
            appendOutput("[ERROR] No game running. Launch a game instance first.", true);
            return;
        }

        String code = etCode.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(requireContext(), "Code editor is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to history
        saveToHistory(code);
        // NEW – record with timestamp for crash correlation
        InjectionHistoryManager.recordExecution(
            requireContext(),
            String.valueOf(System.currentTimeMillis()),
            code
        );

        // Disable button, show pending state
        setExecutionInFlight(true);
        clearOutput();
        appendOutput("⏳ Executing…", false);

        // Schedule timeout
        timeoutRunnable = () -> {
            if (!executionInFlight) return;
            setExecutionInFlight(false);
            clearOutput();
            appendOutput(
                "[TIMEOUT] Execution timed out after 15 s.\n" +
                "The game may be unresponsive or the script is in an infinite loop.", true);
        };
        uiHandler.postDelayed(timeoutRunnable, EXECUTION_TIMEOUT_MS);

        // Build request payload: length-prefixed UTF-8 string
        final byte[] payload = BinaryProtocol.encodeString(code);

        // Send on a background thread — never block the UI thread with socket I/O
        new Thread(() -> {
            client.sendRequest(payload, CMD_INJECT_CODE, new IpcClient.ResponseCallback() {
                @Override
                public void onResponse(byte[] data) {
                    // Cancel the timeout
                    uiHandler.removeCallbacks(timeoutRunnable);
                    timeoutRunnable = null;

                    // Parse response: [1-byte status][length-prefixed string]
                    boolean success = true;
                    String  message = "(no output)";
                    try {
                        ByteBuffer buf    = ByteBuffer.wrap(data);
                        byte       status = buf.get();
                        success = (status == 0);
                        message = BinaryProtocol.readString(buf);
                    } catch (Exception e) {
                        success = false;
                        message = "[PARSE ERROR] Could not decode agent response:\n" + e.getMessage();
                    }

                    final boolean finalSuccess = success;
                    final String  finalMessage = message;
                    uiHandler.post(() -> {
                        setExecutionInFlight(false);
                        clearOutput();
                        appendOutput(finalMessage, !finalSuccess);
                        scrollOutputToBottom();
                    });
                }

                @Override
                public void onError(Exception e) {
                    uiHandler.removeCallbacks(timeoutRunnable);
                    timeoutRunnable = null;
                    final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    uiHandler.post(() -> {
                        setExecutionInFlight(false);
                        clearOutput();
                        appendOutput("[IPC ERROR] " + msg, true);
                        refreshStatus(); // may have disconnected
                    });
                }
            });
        }, "ipc-inject").start();
    }

    // ── Output helpers ─────────────────────────────────────────────────────────

    private static final int MAX_OUTPUT_CHARS = 50_000;

    private void clearOutput() {
        tvOutput.setText("");
    }

    private void appendOutput(String text, boolean isError) {
        // G5 FIX: Cap the total output at MAX_OUTPUT_CHARS to prevent the
        // TextView from accumulating megabytes of text, which causes severe UI jank.
        // When the cap is hit, the oldest portion is trimmed from the front.
        CharSequence current = tvOutput.getText();
        int available = MAX_OUTPUT_CHARS - current.length();

        if (available <= 0) {
            // Already at cap — trim the front half to make room, then re-append.
            String trimmed = current.toString().substring(MAX_OUTPUT_CHARS / 2);
            tvOutput.setText("[…output truncated — cap of " + MAX_OUTPUT_CHARS +
                             " chars reached…]\n" + trimmed);
            current   = tvOutput.getText();
            // Re-calculate; use Math.max to guard against the banner itself
            // consuming all the freed space (available would go negative → crash).
            available = Math.max(0, MAX_OUTPUT_CHARS - current.length());
        }

        String displayText = text;
        if (available == 0) {
            // No room at all even after trim — skip appending to avoid a zero-length substring.
            displayText = "\n[…output cap of " + MAX_OUTPUT_CHARS + " chars reached — clear output to continue]";
            available = displayText.length(); // allow the warning through
        } else if (text.length() > available) {
            displayText = text.substring(0, available) +
                          "\n[…truncated — output cap of " + MAX_OUTPUT_CHARS + " chars reached]";
        }

        if (isError) {
            SpannableStringBuilder sb = new SpannableStringBuilder(tvOutput.getText());
            SpannableString span = new SpannableString(displayText);
            span.setSpan(
                new ForegroundColorSpan(0xFFEF4444), // zl_red
                0, displayText.length(),
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(span);
            tvOutput.setText(sb);
        } else {
            tvOutput.append(displayText);
        }
    }

    private void scrollOutputToBottom() {
        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setExecutionInFlight(boolean inFlight) {
        executionInFlight = inFlight;
        btnExecute.setEnabled(!inFlight && IpcClient.getInstance() != null);
        btnExecute.setText(inFlight ? "Running…" : "▶  Execute");
    }

    // ── Templates menu ─────────────────────────────────────────────────────────

    private void showTemplatesMenu() {
        PopupMenu menu = new PopupMenu(requireContext(), btnTemplates);
        for (int i = 0; i < TEMPLATES.length; i++) {
            menu.getMenu().add(0, i, i, TEMPLATES[i][0]);
        }
        menu.setOnMenuItemClickListener(item -> {
            int idx = item.getItemId();
            if (idx >= 0 && idx < TEMPLATES.length) {
                etCode.setText(TEMPLATES[idx][1]);
                etCode.setSelection(etCode.getText().length());
            }
            return true;
        });
        menu.show();
    }

    // ── History menu ───────────────────────────────────────────────────────────

    private void showHistoryMenu() {
        if (history.isEmpty()) {
            Toast.makeText(requireContext(),
                "No script history yet. Execute a script first.", Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu menu = new PopupMenu(requireContext(), btnHistory);
        int i = 0;
        for (String entry : history) {
            // Truncate long scripts for the menu label
            String label = entry.length() > 60
                ? entry.substring(0, 57).replace('\n', ' ') + "…"
                : entry.replace('\n', ' ');
            menu.getMenu().add(0, i, i, (i + 1) + ". " + label);
            i++;
        }
        menu.setOnMenuItemClickListener(item -> {
            int idx = item.getItemId();
            List<String> list = new ArrayList<>(history);
            if (idx >= 0 && idx < list.size()) {
                etCode.setText(list.get(idx));
                etCode.setSelection(etCode.getText().length());
            }
            return true;
        });
        menu.show();
    }

    // ── SharedPreferences history ──────────────────────────────────────────────

    private void loadHistory() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        history.clear();
        if (!raw.isEmpty()) {
            // Entries are stored newest-first (saveToHistory always prepends).
            // Use addLast() so the in-memory list preserves that same order.
            // addFirst() was wrong — it reversed the list on every load, making
            // the most-recent entry appear last in the history menu.
            for (String entry : raw.split("\u0000", -1)) {
                if (!entry.isEmpty()) history.addLast(entry);
            }
        }
    }

    private void saveToHistory(String code) {
        // Remove duplicate if already present
        history.remove(code);
        history.addFirst(code);
        while (history.size() > MAX_HISTORY) history.removeLast();

        if (getContext() == null) return;
        StringBuilder sb = new StringBuilder();
        for (String entry : history) {
            if (sb.length() > 0) sb.append('\u0000');
            sb.append(entry);
        }
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, sb.toString())
            .apply();
    }
}
