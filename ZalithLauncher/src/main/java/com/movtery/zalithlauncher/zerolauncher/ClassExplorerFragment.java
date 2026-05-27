package com.movtery.zalithlauncher.zerolauncher;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.zerolauncher.DevToolsActivity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ClassExplorerFragment extends Fragment {

    private final Handler UI = new Handler(Looper.getMainLooper());
    private static final long TIMEOUT_MS = 15_000;

    private View         btnLoad;
    private EditText     etSearch;
    private RecyclerView rvClasses;
    private TextView     tvStatus;
    private LinearLayout detailPanel;
    private TextView     tvDetailContent;

    private final List<String> allClasses      = new ArrayList<>();
    private final List<String> filteredClasses = new ArrayList<>();
    private ClassAdapter adapter;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_class_explorer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        btnLoad         = view.findViewById(R.id.btn_load_classes);
        etSearch        = view.findViewById(R.id.et_class_search);
        rvClasses       = view.findViewById(R.id.rv_classes);
        tvStatus        = view.findViewById(R.id.tv_class_status);
        detailPanel     = view.findViewById(R.id.detail_panel);
        tvDetailContent = view.findViewById(R.id.tv_detail_content);

        adapter = new ClassAdapter(filteredClasses, this::onClassTapped, this::onClassLongPressed);
        rvClasses.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvClasses.setAdapter(adapter);

        btnLoad.setOnClickListener(v -> loadClasses());
        view.findViewById(R.id.btn_detail_close).setOnClickListener(v -> detailPanel.setVisibility(View.GONE));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        UI.removeCallbacksAndMessages(null); // cancel all pending UI posts on detach
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Post to UI only if the fragment is still attached. */
    private void postIfAdded(Runnable r) {
        UI.post(() -> { if (isAdded()) r.run(); });
    }

    private void setStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applyFilter(String query) {
        filteredClasses.clear();
        if (query.isEmpty()) {
            filteredClasses.addAll(allClasses);
        } else {
            String q = query.toLowerCase();
            for (String c : allClasses) if (c.toLowerCase().contains(q)) filteredClasses.add(c);
        }
        adapter.notifyDataSetChanged();
    }

    // ── Load class list ───────────────────────────────────────────────────────

    private void loadClasses() {
        IpcClient client = IpcClient.getInstance();
        if (client == null) { setStatus("IPC Disconnected"); return; }

        btnLoad.setEnabled(false);
        setStatus("Loading…");

        Runnable timeout = () -> {   // already on UI thread via postDelayed
            if (!isAdded()) return;
            setStatus("Timeout — no response after 15 s");
            btnLoad.setEnabled(true);
        };
        UI.postDelayed(timeout, TIMEOUT_MS);

        new Thread(() -> client.sendRequest(new byte[0], IpcClient.CMD_GET_CLASS_LIST,
            new IpcClient.ResponseCallback() {
                @Override public void onResponse(byte[] data) {
                    UI.removeCallbacks(timeout);
                    String body = new String(data, StandardCharsets.UTF_8).trim();
                    List<String> names = new ArrayList<>();
                    if (!body.isEmpty()) {
                        for (String line : body.split("\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) names.add(t);
                        }
                    }
                    postIfAdded(() -> {
                        allClasses.clear();
                        allClasses.addAll(names);
                        applyFilter(etSearch.getText().toString());
                        setStatus(names.isEmpty() ? "No classes found" : names.size() + " classes loaded");
                        btnLoad.setEnabled(true);
                    });
                }
                @Override public void onError(Exception e) {
                    UI.removeCallbacks(timeout);
                    postIfAdded(() -> {
                        setStatus("Error: " + e.getMessage());
                        btnLoad.setEnabled(true);
                    });
                }
            }), "ipc-class-list").start();
    }

    // ── Class detail ──────────────────────────────────────────────────────────

    private void onClassTapped(String className) {
        IpcClient client = IpcClient.getInstance();
        if (client == null) { setStatus("IPC Disconnected"); return; }

        detailPanel.setVisibility(View.VISIBLE);
        tvDetailContent.setText("Loading " + className + "…");

        byte[] payload = BinaryProtocol.encodeString(className);

        Runnable timeout = () -> {
            if (!isAdded()) return;
            tvDetailContent.setText("Timeout loading class detail");
        };
        UI.postDelayed(timeout, TIMEOUT_MS);

        new Thread(() -> client.sendRequest(payload, IpcClient.CMD_GET_CLASS_DETAIL,
            new IpcClient.ResponseCallback() {
                @Override public void onResponse(byte[] data) {
                    UI.removeCallbacks(timeout);
                    try {
                        BinaryProtocol.ClassDetail detail = BinaryProtocol.parseClassDetail(data);
                        String text = buildDetailText(detail);
                        postIfAdded(() -> tvDetailContent.setText(text));
                    } catch (Exception e) {
                        postIfAdded(() -> tvDetailContent.setText("Parse error: " + e.getMessage()));
                    }
                }
                @Override public void onError(Exception e) {
                    UI.removeCallbacks(timeout);
                    postIfAdded(() -> tvDetailContent.setText("Error: " + e.getMessage()));
                }
            }), "ipc-class-detail").start();
    }

    private boolean onClassLongPressed(String className) {
        if (!isAdded()) return false;
        DevToolsActivity host = (DevToolsActivity) getActivity();
        if (host != null) {
            host.trackClassInMemoryProfiler(className);
            setStatus("Tracking allocations for " + className);
            return true;
        }
        return false;
    }

    private String buildDetailText(BinaryProtocol.ClassDetail d) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLASS\n").append(d.className).append("\n\n");
        sb.append("SUPERCLASS\n").append(d.superClass.isEmpty() ? "(none)" : d.superClass).append("\n\n");

        if (!d.interfaces.isEmpty()) {
            sb.append("INTERFACES\n");
            for (String i : d.interfaces) sb.append("  ").append(i).append("\n");
            sb.append("\n");
        }
        if (!d.annotations.isEmpty()) {
            sb.append("ANNOTATIONS\n");
            for (BinaryProtocol.AnnotationInfo a : d.annotations) {
                sb.append("  @").append(a.name).append("\n");
                for (int k = 0; k < a.keys.size(); k++)
                    sb.append("    ").append(a.keys.get(k)).append(" = ").append(a.values.get(k)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("FIELDS (").append(d.fields.size()).append(")\n");
        for (BinaryProtocol.FieldInfo f : d.fields)
            sb.append("  ").append(modString(f.modifiers)).append(f.name).append("  ").append(f.descriptor).append("\n");

        sb.append("\nMETHODS (").append(d.methods.size()).append(")\n");
        for (BinaryProtocol.MethodInfo m : d.methods)
            sb.append("  ").append(modString(m.modifiers)).append(m.name).append(m.descriptor).append("\n");

        return sb.toString();
    }

    private static String modString(int mod) {
        StringBuilder sb = new StringBuilder();
        if ((mod & 0x0001) != 0) sb.append("public ");
        if ((mod & 0x0002) != 0) sb.append("private ");
        if ((mod & 0x0004) != 0) sb.append("protected ");
        if ((mod & 0x0008) != 0) sb.append("static ");
        if ((mod & 0x0010) != 0) sb.append("final ");
        if ((mod & 0x0400) != 0) sb.append("abstract ");
        return sb.toString();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    interface OnClassClickListener { void onClick(String className); }
    interface OnClassLongClickListener { boolean onLongClick(String className); }

    static class ClassAdapter extends RecyclerView.Adapter<ClassAdapter.VH> {
        private final List<String>             data;
        private final OnClassClickListener      listener;
        private final OnClassLongClickListener longClickListener;

        ClassAdapter(List<String> data, OnClassClickListener listener, OnClassLongClickListener longClickListener) {
            this.data = data;
            this.listener = listener;
            this.longClickListener = longClickListener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_class, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String name = data.get(pos);
            int dot = name.lastIndexOf('.');
            h.tvSimple.setText(dot >= 0 ? name.substring(dot + 1) : name);
            h.tvPackage.setText(dot >= 0 ? name.substring(0, dot) : "");
            h.itemView.setOnClickListener(v -> listener.onClick(name));
            h.itemView.setOnLongClickListener(v -> longClickListener != null && longClickListener.onLongClick(name));
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvSimple, tvPackage;
            VH(View v) {
                super(v);
                tvSimple  = v.findViewById(R.id.tv_class_simple);
                tvPackage = v.findViewById(R.id.tv_class_package);
            }
        }
    }
}
