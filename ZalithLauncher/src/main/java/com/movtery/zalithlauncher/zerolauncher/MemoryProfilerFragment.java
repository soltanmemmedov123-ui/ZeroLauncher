package com.movtery.zalithlauncher.zerolauncher;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.movtery.zalithlauncher.R;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemoryProfilerFragment extends Fragment {

    private static final long REFRESH_INTERVAL_MS = 2_000;
    private final Handler UI = new Handler(Looper.getMainLooper());

    private TextView tvHeapUsed;
    private TextView tvHeapCommitted;
    private TextView tvHeapMax;
    private TextView tvGcName;
    private TextView tvStatus;
    private Button btnRefreshAllocations;
    private Button btnForceGc;
    private Button btnTrackClass;
    private EditText etTrackClass;
    private RecyclerView rvTopAllocations;
    private RecyclerView rvEventFeed;

    private final List<AllocationEntry> allocationEntries = new ArrayList<>();
    private final List<String> eventLines = new ArrayList<>();
    private final Map<String, AllocationTrend> trendMap = new HashMap<>();

    private TopAllocationAdapter allocationAdapter;
    private EventAdapter eventAdapter;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshHeapSummary();
            UI.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_memory_profiler, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvHeapUsed = view.findViewById(R.id.tv_heap_used);
        tvHeapCommitted = view.findViewById(R.id.tv_heap_committed);
        tvHeapMax = view.findViewById(R.id.tv_heap_max);
        tvGcName = view.findViewById(R.id.tv_gc_name);
        tvStatus = view.findViewById(R.id.tv_memory_status);
        btnRefreshAllocations = view.findViewById(R.id.btn_refresh_allocations);
        btnForceGc = view.findViewById(R.id.btn_force_gc);
        btnTrackClass = view.findViewById(R.id.btn_track_class);
        etTrackClass = view.findViewById(R.id.et_track_class);
        rvTopAllocations = view.findViewById(R.id.rv_top_allocations);
        rvEventFeed = view.findViewById(R.id.rv_event_feed);

        allocationAdapter = new TopAllocationAdapter(allocationEntries);
        rvTopAllocations.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTopAllocations.setAdapter(allocationAdapter);

        eventAdapter = new EventAdapter(eventLines);
        rvEventFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvEventFeed.setAdapter(eventAdapter);

        btnRefreshAllocations.setOnClickListener(v -> refreshTopAllocations());
        btnForceGc.setOnClickListener(v -> requestForceGc());
        btnTrackClass.setOnClickListener(v -> requestTrackClass());

        ensureConnected();
    }

    @Override
    public void onResume() {
        super.onResume();
        IpcClient.setPushListener(this::onPushEvent);
        UI.post(refreshTask);
    }

    @Override
    public void onPause() {
        super.onPause();
        IpcClient.setPushListener(null);
        UI.removeCallbacks(refreshTask);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        UI.removeCallbacksAndMessages(null);
    }

    public void prefillTrackedClass(String className) {
        if (etTrackClass != null) {
            etTrackClass.setText(className);
            setStatus("Ready to track " + className);
        }
    }

    private void ensureConnected() {
        if (IpcClient.getInstance() == null) {
            new Thread(IpcClient::connect, "ipc-connect").start();
        }
    }

    private void refreshHeapSummary() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) {
            setStatus("IPC disconnected");
            return;
        }

        client.sendRequest(new byte[0], IpcClient.CMD_HEAP_SUMMARY, new IpcClient.ResponseCallback() {
            @Override
            public void onResponse(byte[] data) {
                if (!isAdded()) return;
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data);
                    long used = buf.getLong();
                    long committed = buf.getLong();
                    long max = buf.getLong();
                    int loadedClasses = buf.getInt();
                    int threadCount = buf.getInt();
                    String gcName = BinaryProtocol.readString(buf);
                    postUi(() -> {
                        tvHeapUsed.setText(formatBytes(used));
                        tvHeapCommitted.setText(formatBytes(committed));
                        tvHeapMax.setText(formatBytes(max));
                        tvGcName.setText("GC: " + gcName + " • classes=" + loadedClasses + " threads=" + threadCount);
                        setStatus("");
                    });
                } catch (Exception e) {
                    postUi(() -> setStatus("Heap parse error: " + e.getMessage()));
                }
            }

            @Override
            public void onError(Exception e) {
                postUi(() -> setStatus("Heap summary failed: " + e.getMessage()));
            }
        });
    }

    private void refreshTopAllocations() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) {
            setStatus("IPC disconnected");
            return;
        }
        setStatus("Refreshing allocations…");
        client.sendRequest(ByteBuffer.allocate(4).putInt(20).array(), IpcClient.CMD_TOP_ALLOCATIONS,
                new IpcClient.ResponseCallback() {
                    @Override
                    public void onResponse(byte[] data) {
                        if (!isAdded()) return;
                        try {
                            ByteBuffer buf = ByteBuffer.wrap(data);
                            int count = buf.getInt();
                            allocationEntries.clear();
                            for (int i = 0; i < count; i++) {
                                String className = BinaryProtocol.readString(buf);
                                long instanceCount = buf.getLong();
                                long shallowSize = buf.getLong();
                                AllocationTrend trend = trendMap.get(className);
                                if (trend == null) {
                                    trend = new AllocationTrend();
                                    trendMap.put(className, trend);
                                }
                                trend.delta = instanceCount - trend.lastCount;
                                if (trend.delta > 0) {
                                    trend.consecutiveIncreases++;
                                } else {
                                    trend.consecutiveIncreases = 0;
                                }
                                trend.lastCount = instanceCount;
                                allocationEntries.add(new AllocationEntry(className, instanceCount, shallowSize, trend.consecutiveIncreases));
                            }
                            postUi(() -> {
                                allocationAdapter.setMaxCount(findMaxCount());
                                allocationAdapter.notifyDataSetChanged();
                                setStatus(count == 0 ? "No allocation entries available" : "Top allocations refreshed");
                            });
                        } catch (Exception e) {
                            postUi(() -> setStatus("Top allocations parse error: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        postUi(() -> setStatus("Top allocations failed: " + e.getMessage()));
                    }
                });
    }

    private void requestTrackClass() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) {
            setStatus("IPC disconnected");
            return;
        }
        String className = etTrackClass.getText().toString().trim();
        if (className.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a class name to track", Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus("Tracking " + className + "…");
        client.sendRequest(BinaryProtocol.encodeString(className), IpcClient.CMD_TRACK_CLASS,
                new IpcClient.ResponseCallback() {
                    @Override
                    public void onResponse(byte[] data) {
                        if (!isAdded()) return;
                        int code = data.length > 0 ? (data[0] & 0xFF) : 2;
                        String message;
                        if (code == 0) message = "Tracking started for " + className;
                        else if (code == 1) message = "Already tracking " + className;
                        else message = "Track failed for " + className;
                        postUi(() -> setStatus(message));
                    }

                    @Override
                    public void onError(Exception e) {
                        postUi(() -> setStatus("Track request failed: " + e.getMessage()));
                    }
                });
    }

    private void requestForceGc() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) {
            setStatus("IPC disconnected");
            return;
        }
        setStatus("Requesting GC…");
        client.sendRequest(new byte[0], IpcClient.CMD_RUN_GC, new IpcClient.ResponseCallback() {
            @Override
            public void onResponse(byte[] data) {
                postUi(() -> setStatus("GC request sent"));
            }

            @Override
            public void onError(Exception e) {
                postUi(() -> setStatus("GC request failed: " + e.getMessage()));
            }
        });
    }

    private void onPushEvent(int cmd, byte[] payload) {
        if (cmd != IpcClient.CMD_PUSH_EVENT) return;
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            String className = BinaryProtocol.readString(buf);
            int frameCount = buf.getInt();
            StringBuilder sb = new StringBuilder();
            sb.append("[" + className + "]\n");
            for (int i = 0; i < frameCount; i++) {
                sb.append("  ").append(BinaryProtocol.readString(buf)).append('\n');
            }
            postUi(() -> addEvent(sb.toString()));
        } catch (Exception e) {
            postUi(() -> setStatus("Malformed allocation event: " + e.getMessage()));
        }
    }

    private void addEvent(String eventText) {
        eventLines.add(0, eventText);
        if (eventLines.size() > 200) {
            eventLines.remove(eventLines.size() - 1);
        }
        eventAdapter.notifyDataSetChanged();
    }

    private void setStatus(String message) {
        if (tvStatus == null) return;
        tvStatus.setText(message);
        tvStatus.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void postUi(Runnable action) {
        UI.post(() -> {
            if (!isAdded()) return;
            action.run();
        });
    }

    private long findMaxCount() {
        long max = 1;
        for (AllocationEntry entry : allocationEntries) {
            max = Math.max(max, entry.instanceCount);
        }
        return max;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    public static class AllocationEntry {
        final String className;
        final long instanceCount;
        final long shallowSize;
        final int leakTrend;

        AllocationEntry(String className, long instanceCount, long shallowSize, int leakTrend) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.shallowSize = shallowSize;
            this.leakTrend = leakTrend;
        }
    }

    private static class AllocationTrend {
        long lastCount = 0;
        long delta = 0;
        int consecutiveIncreases = 0;
    }

    private static class TopAllocationAdapter extends RecyclerView.Adapter<TopAllocationAdapter.VH> {
        private final List<AllocationEntry> data;
        private long maxCount = 1;

        TopAllocationAdapter(List<AllocationEntry> data) {
            this.data = data;
        }

        void setMaxCount(long maxCount) {
            this.maxCount = Math.max(1, maxCount);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_allocation_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AllocationEntry entry = data.get(position);
            int dot = entry.className.lastIndexOf('.');
            holder.tvClass.setText(dot >= 0 ? entry.className.substring(dot + 1) : entry.className);
            holder.tvCount.setText(String.format("%,d", entry.instanceCount));

            float filledWeight = (float) entry.instanceCount / maxCount;
            holder.bar.getLayoutParams().width = 0;
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.bar.getLayoutParams();
            holder.bar.setLayoutParams(params);

            LinearLayout.LayoutParams barParams = (LinearLayout.LayoutParams) holder.bar.getLayoutParams();
            barParams.weight = filledWeight;
            holder.bar.setLayoutParams(barParams);
            LinearLayout.LayoutParams spacerParams = (LinearLayout.LayoutParams) holder.barSpacer.getLayoutParams();
            spacerParams.weight = 1.0f - filledWeight;
            holder.barSpacer.setLayoutParams(spacerParams);

            if (entry.leakTrend >= 2) {
                holder.itemView.setBackgroundColor(Color.parseColor("#44FF9999"));
            } else if (entry.leakTrend == 1) {
                holder.itemView.setBackgroundColor(Color.parseColor("#44FFFF99"));
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            }

            holder.itemView.setOnClickListener(v -> Toast.makeText(v.getContext(), entry.className, Toast.LENGTH_SHORT).show());
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvClass;
            final TextView tvCount;
            final View bar;
            final View barSpacer;

            VH(@NonNull View itemView) {
                super(itemView);
                tvClass = itemView.findViewById(R.id.tv_allocation_class);
                tvCount = itemView.findViewById(R.id.tv_allocation_count);
                bar = itemView.findViewById(R.id.allocation_bar);
                barSpacer = itemView.findViewById(R.id.allocation_bar_spacer);
            }
        }
    }

    private static class EventAdapter extends RecyclerView.Adapter<EventAdapter.VH> {
        private final List<String> data;

        EventAdapter(List<String> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(parent.getContext().getColor(R.color.white));
            tv.setTextSize(12);
            tv.setPadding(6, 6, 6, 6);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.textView.setText(data.get(position));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView textView;

            VH(@NonNull View itemView) {
                super(itemView);
                textView = (TextView) itemView;
            }
        }
    }
}
