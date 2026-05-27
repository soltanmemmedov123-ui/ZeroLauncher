package com.movtery.zalithlauncher.zerolauncher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.movtery.zalithlauncher.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * NetworkMonitorFragment – the "Network" tab inside DevToolsActivity.
 *
 * Provides a complete UI for the local TCP proxy:
 *   • Control bar: Start/Stop proxy, status TextView, port settings.
 *   • Packet list: filterable RecyclerView, auto-scroll, pause toggle.
 *   • Detail panel: full packet info + hex dump + clipboard copy.
 *
 * The proxy itself ({@link MinecraftProxy}) is a process-scoped singleton –
 * it continues running if the user navigates away from this tab or even closes
 * the DevTools screen.  The fragment re-attaches its callback in onResume and
 * detaches it in onPause so events are only delivered when visible.
 */
public class NetworkMonitorFragment extends Fragment {

    // ── SharedPreferences keys ─────────────────────────────────────────────────
    private static final String PREFS_NAME      = "network_monitor_prefs";
    private static final String KEY_PROXY_PORT  = "proxy_port";

    // ── Views ──────────────────────────────────────────────────────────────────
    private Button   btnStartStop;
    private TextView tvStatus;
    private TextView tvPortLabel;
    private Button   btnChangePort;

    private EditText etFilter;
    private RecyclerView rvPackets;
    private Button   btnPauseScroll;
    private Button   btnClearPackets;
    private TextView tvEmptyHint;
    private TextView tvOverflowWarning;

    private View     detailPanel;
    private TextView tvDetailTimestamp;
    private TextView tvDetailState;
    private TextView tvDetailId;
    private TextView tvDetailLength;
    private TextView tvDetailHex;
    private Button   btnCopyHex;
    private Button   btnCloseDetail;

    // ── Adapter & data ─────────────────────────────────────────────────────────
    private PacketListAdapter adapter;
    private final List<CapturedPacket> allPackets      = new ArrayList<>();
    private final List<CapturedPacket> filteredPackets = new ArrayList<>();
    private String                     currentFilter   = "";

    // ── Scroll control ─────────────────────────────────────────────────────────
    private boolean autoScroll  = true;
    private boolean paused      = false;

    // ── Selected packet ────────────────────────────────────────────────────────
    private CapturedPacket selectedPacket;

    // ── Main-thread handler ────────────────────────────────────────────────────
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ── Date formatter ─────────────────────────────────────────────────────────
    private final SimpleDateFormat dateFmt =
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network_monitor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        bindViews(root);
        setupRecyclerView();
        setupControlBar();
        setupFilter();
        setupDetailPanel();
        refreshProxyStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-attach callback if proxy is already running
        MinecraftProxy proxy = MinecraftProxy.getInstance();
        if (proxy != null) {
            proxy.setCallback(makeCallback());
            // Refresh the full packet list from the ring buffer
            reloadAllPackets(proxy.getPackets());
        }
        refreshProxyStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Detach UI callback but leave the proxy running
        MinecraftProxy proxy = MinecraftProxy.getInstance();
        if (proxy != null) proxy.setCallback(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews(View root) {
        btnStartStop       = root.findViewById(R.id.btn_proxy_start_stop);
        tvStatus           = root.findViewById(R.id.tv_proxy_status);
        tvPortLabel        = root.findViewById(R.id.tv_proxy_port_label);
        btnChangePort      = root.findViewById(R.id.btn_proxy_change_port);

        etFilter           = root.findViewById(R.id.et_packet_filter);
        rvPackets          = root.findViewById(R.id.rv_packets);
        btnPauseScroll     = root.findViewById(R.id.btn_pause_scroll);
        btnClearPackets    = root.findViewById(R.id.btn_clear_packets);
        tvEmptyHint        = root.findViewById(R.id.tv_empty_hint);
        tvOverflowWarning  = root.findViewById(R.id.tv_overflow_warning);

        detailPanel        = root.findViewById(R.id.detail_panel);
        tvDetailTimestamp  = root.findViewById(R.id.tv_detail_timestamp);
        tvDetailState      = root.findViewById(R.id.tv_detail_state);
        tvDetailId         = root.findViewById(R.id.tv_detail_id);
        tvDetailLength     = root.findViewById(R.id.tv_detail_length);
        tvDetailHex        = root.findViewById(R.id.tv_detail_hex);
        btnCopyHex         = root.findViewById(R.id.btn_copy_hex);
        btnCloseDetail     = root.findViewById(R.id.btn_close_detail);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new PacketListAdapter(filteredPackets, pkt -> {
            selectedPacket = pkt;
            showDetailPanel(pkt);
        });
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        llm.setStackFromEnd(true);
        rvPackets.setLayoutManager(llm);
        rvPackets.setAdapter(adapter);

        // Detect manual scroll (disable auto-scroll temporarily)
        rvPackets.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScroll = false;
                    updatePauseButton();
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control bar
    // ─────────────────────────────────────────────────────────────────────────

    private void setupControlBar() {
        int port = getSavedPort();
        tvPortLabel.setText("Port: " + port);

        btnStartStop.setOnClickListener(v -> {
            if (MinecraftProxy.getInstance() != null) {
                stopProxy();
            } else {
                startProxy();
            }
        });

        btnChangePort.setOnClickListener(v -> showPortDialog());

        btnPauseScroll.setOnClickListener(v -> {
            paused    = !paused;
            autoScroll = !paused;
            updatePauseButton();
            if (autoScroll) scrollToBottom();
        });

        btnClearPackets.setOnClickListener(v -> {
            MinecraftProxy proxy = MinecraftProxy.getInstance();
            if (proxy != null) proxy.clearPackets();
            clearLocalLists();
        });
    }

    private void updatePauseButton() {
        if (paused) {
            btnPauseScroll.setText("▶  RESUME");
            btnPauseScroll.setAlpha(0.65f);
        } else {
            btnPauseScroll.setText("⏸  PAUSE");
            btnPauseScroll.setAlpha(1.0f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter
    // ─────────────────────────────────────────────────────────────────────────

    private void setupFilter() {
        etFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                currentFilter = s.toString().trim().toLowerCase(Locale.US);
                applyFilter();
            }
        });
    }

    private void applyFilter() {
        List<CapturedPacket> newFiltered = new ArrayList<>();
        for (CapturedPacket p : allPackets) {
            if (currentFilter.isEmpty()
                    || p.packetName.toLowerCase(Locale.US).contains(currentFilter)
                    || String.format("%02x", p.packetId).contains(currentFilter)) {
                newFiltered.add(p);
            }
        }
        // DiffUtil for efficient update
        List<CapturedPacket> old = new ArrayList<>(filteredPackets);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new PacketDiff(old, newFiltered));
        filteredPackets.clear();
        filteredPackets.addAll(newFiltered);
        diff.dispatchUpdatesTo(adapter);
        updateEmptyHint();
        if (autoScroll) scrollToBottom();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detail panel
    // ─────────────────────────────────────────────────────────────────────────

    private void setupDetailPanel() {
        detailPanel.setVisibility(View.GONE);

        btnCloseDetail.setOnClickListener(v -> {
            detailPanel.setVisibility(View.GONE);
            selectedPacket = null;
        });

        btnCopyHex.setOnClickListener(v -> {
            if (selectedPacket == null) return;
            ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("hex_dump", selectedPacket.hexDump));
            Toast.makeText(requireContext(), "Hex dump copied", Toast.LENGTH_SHORT).show();
        });
    }

    private void showDetailPanel(CapturedPacket pkt) {
        tvDetailTimestamp.setText(dateFmt.format(new Date(pkt.timestamp)));
        tvDetailState.setText(pkt.stateName());
        tvDetailId.setText(pkt.packetIdFormatted());
        tvDetailLength.setText(pkt.length + " bytes");
        tvDetailHex.setText(pkt.hexDump);
        tvDetailHex.setTypeface(Typeface.MONOSPACE);
        detailPanel.setVisibility(View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proxy lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private void startProxy() {
        int port = getSavedPort();
        tvStatus.setText("Starting…");
        btnStartStop.setEnabled(false);

        new Thread(() -> {
            MinecraftProxy proxy = MinecraftProxy.start(port, makeCallback());
            uiHandler.post(() -> {
                btnStartStop.setEnabled(true);
                if (proxy == null) {
                    tvStatus.setText("Error: could not bind port " + port);
                    Toast.makeText(requireContext(),
                        "Cannot bind port " + port + " – try another port", Toast.LENGTH_LONG).show();
                } else {
                    refreshProxyStatus();
                    // Load any packets already captured
                    reloadAllPackets(proxy.getPackets());
                }
            });
        }, "proxy-start").start();
    }

    private void stopProxy() {
        MinecraftProxy.shutdown();
        uiHandler.post(this::refreshProxyStatus);
    }

    private void refreshProxyStatus() {
        MinecraftProxy proxy = MinecraftProxy.getInstance();
        boolean running = proxy != null && proxy.isRunning();

        btnStartStop.setText(running ? "■  STOP PROXY" : "▶  START PROXY");
        if (!running) {
            tvStatus.setText("Not running");
            tvEmptyHint.setText("Proxy not running. Tap Start to begin capturing.");
        }
        int port = getSavedPort();
        tvPortLabel.setText("Port: " + port);
        updateEmptyHint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proxy callback (called from relay background threads)
    // ─────────────────────────────────────────────────────────────────────────

    private MinecraftProxy.PacketCaptureCallback makeCallback() {
        return new MinecraftProxy.PacketCaptureCallback() {

            @Override
            public void onPacketCaptured(CapturedPacket packet) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    allPackets.add(packet);

                    // Apply filter
                    boolean passes = currentFilter.isEmpty()
                        || packet.packetName.toLowerCase(Locale.US).contains(currentFilter)
                        || String.format("%02x", packet.packetId).contains(currentFilter);
                    if (passes) {
                        filteredPackets.add(packet);
                        adapter.notifyItemInserted(filteredPackets.size() - 1);
                        updateEmptyHint();
                        if (autoScroll) scrollToBottom();
                    }
                });
            }

            @Override
            public void onStatusChanged(String status) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    tvStatus.setText(status);
                });
            }

            @Override
            public void onDisconnected(String reason) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    tvStatus.setText("Disconnected – " + reason);
                    refreshProxyStatus();
                });
            }

            @Override
            public void onRingBufferOverflow(int discardedCount) {
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    tvOverflowWarning.setVisibility(View.VISIBLE);
                    tvOverflowWarning.setText("⚠ Ring buffer full – " + discardedCount + " oldest packets discarded");
                });
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Port dialog
    // ─────────────────────────────────────────────────────────────────────────

    private void showPortDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(getSavedPort()));
        input.setSelectAllOnFocus(true);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Set Proxy Port")
            .setMessage("Default: " + MinecraftProxy.DEFAULT_PROXY_PORT
                + "\nRange: 1024 – 65535")
            .setView(input)
            .setPositiveButton("Apply", (d, w) -> {
                String txt = input.getText().toString().trim();
                try {
                    int p = Integer.parseInt(txt);
                    if (p < 1024 || p > 65535) throw new NumberFormatException();
                    savePort(p);
                    tvPortLabel.setText("Port: " + p);
                    Toast.makeText(requireContext(),
                        "Port set to " + p + ". Restart the proxy to apply.",
                        Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(requireContext(),
                        "Invalid port – must be 1024–65535", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void scrollToBottom() {
        int n = filteredPackets.size();
        if (n > 0) rvPackets.smoothScrollToPosition(n - 1);
    }

    private void updateEmptyHint() {
        boolean empty = filteredPackets.isEmpty();
        tvEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvPackets.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty && MinecraftProxy.getInstance() != null) {
            tvEmptyHint.setText(currentFilter.isEmpty()
                ? "Waiting for game connection…"
                : "No packets match "" + currentFilter + """);
        }
    }

    private void reloadAllPackets(List<CapturedPacket> packets) {
        allPackets.clear();
        allPackets.addAll(packets);
        applyFilter();
    }

    private void clearLocalLists() {
        allPackets.clear();
        List<CapturedPacket> old = new ArrayList<>(filteredPackets);
        filteredPackets.clear();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new PacketDiff(old, filteredPackets));
        diff.dispatchUpdatesTo(adapter);
        tvOverflowWarning.setVisibility(View.GONE);
        updateEmptyHint();
    }

    private int getSavedPort() {
        return requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PROXY_PORT, MinecraftProxy.DEFAULT_PROXY_PORT);
    }

    private void savePort(int port) {
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PROXY_PORT, port).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner: RecyclerView Adapter
    // ─────────────────────────────────────────────────────────────────────────

    interface OnPacketClickListener {
        void onPacketClick(CapturedPacket packet);
    }

    static class PacketListAdapter
            extends RecyclerView.Adapter<PacketListAdapter.VH> {

        private final List<CapturedPacket> items;
        private final OnPacketClickListener listener;

        PacketListAdapter(List<CapturedPacket> items, OnPacketClickListener listener) {
            this.items    = items;
            this.listener = listener;
            setHasStableIds(true);
        }

        @Override public long getItemId(int pos) { return items.get(pos).serialId; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_packet, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CapturedPacket pkt = items.get(pos);
            boolean isC2S = pkt.direction == CapturedPacket.DIR_CLIENT_TO_SERVER;

            h.tvArrow.setText(pkt.directionArrow());
            h.tvArrow.setTextColor(isC2S
                ? 0xFF4ADE80   // zl_green  – C→S
                : 0xFF38BDF8); // zl_cyan   – S→C

            h.tvName.setText(pkt.packetName);
            h.tvLength.setText(pkt.length + " B");
            h.tvId.setText(String.format("0x%02X", pkt.packetId));

            h.itemView.setOnClickListener(v -> listener.onPacketClick(pkt));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvArrow, tvName, tvLength, tvId;
            VH(View v) {
                super(v);
                tvArrow  = v.findViewById(R.id.tv_packet_arrow);
                tvName   = v.findViewById(R.id.tv_packet_name);
                tvLength = v.findViewById(R.id.tv_packet_length);
                tvId     = v.findViewById(R.id.tv_packet_id);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner: DiffUtil callback
    // ─────────────────────────────────────────────────────────────────────────

    static class PacketDiff extends DiffUtil.Callback {
        private final List<CapturedPacket> old;
        private final List<CapturedPacket> nw;

        PacketDiff(List<CapturedPacket> old, List<CapturedPacket> nw) {
            this.old = old; this.nw = nw;
        }

        @Override public int getOldListSize() { return old.size(); }
        @Override public int getNewListSize() { return nw.size(); }

        @Override
        public boolean areItemsTheSame(int oi, int ni) {
            return old.get(oi).serialId == nw.get(ni).serialId;
        }

        @Override
        public boolean areContentsTheSame(int oi, int ni) {
            // CapturedPacket is immutable; same serial = same content
            return old.get(oi).serialId == nw.get(ni).serialId;
        }
    }
}
