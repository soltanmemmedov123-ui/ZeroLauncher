package com.movtery.zalithlauncher.zerolauncher;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.movtery.zalithlauncher.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * CrashHistoryFragment – "Crashes" tab in DevToolsActivity.
 *
 * Shows a searchable list of crash records from {@link CrashHistoryDatabase}.
 * Tapping a row opens a detail view with the full log tail and fix applied.
 */
public class CrashHistoryFragment extends Fragment {

    public static final String EXTRA_CRASH_LOG_PATH = "crash_log_path";

    private LinearLayout listContainer;
    private EditText     etSearch;
    private TextView     tvEmpty;
    private ScrollView   scrollView;

    // Detail view
    private LinearLayout layoutDetail;
    private TextView     tvDetailTitle;
    private TextView     tvDetailMeta;
    private TextView     tvDetailLog;
    private View         btnDetailBack;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_crash_history, container, false);

        listContainer = root.findViewById(R.id.crash_list_container);
        etSearch      = root.findViewById(R.id.et_crash_search);
        tvEmpty       = root.findViewById(R.id.tv_crash_empty);
        scrollView    = root.findViewById(R.id.crash_scroll);
        layoutDetail  = root.findViewById(R.id.crash_detail_layout);
        tvDetailTitle = root.findViewById(R.id.tv_detail_title);
        tvDetailMeta  = root.findViewById(R.id.tv_detail_meta);
        tvDetailLog   = root.findViewById(R.id.tv_detail_log);
        btnDetailBack = root.findViewById(R.id.btn_detail_back);

        btnDetailBack.setOnClickListener(v -> showList());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                loadAndDisplay(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Check if we were launched to show a specific log
        String specificLog = null;
        if (getActivity() != null) {
            Intent intent = getActivity().getIntent();
            if (intent != null) specificLog = intent.getStringExtra(EXTRA_CRASH_LOG_PATH);
        }

        loadAndDisplay("");

        if (specificLog != null) {
            showLogFile(specificLog);
        }

        return root;
    }

    private void loadAndDisplay(String query) {
        CrashHistoryDatabase db = CrashHistoryDatabase.getInstance(requireContext());
        List<CrashHistoryDatabase.CrashRecord> records =
            query.isEmpty() ? db.loadAll() : db.search(query);

        listContainer.removeAllViews();

        if (records.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            listContainer.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            listContainer.setVisibility(View.VISIBLE);
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (CrashHistoryDatabase.CrashRecord r : records) {
                View item = inflater.inflate(R.layout.item_crash_record, listContainer, false);
                bindItem(item, r);
                listContainer.addView(item);
            }
        }
    }

    private void bindItem(View item, CrashHistoryDatabase.CrashRecord r) {
        TextView tvTime   = item.findViewById(R.id.tv_item_time);
        TextView tvEx     = item.findViewById(R.id.tv_item_exception);
        TextView tvMod    = item.findViewById(R.id.tv_item_mod);
        TextView tvFix    = item.findViewById(R.id.tv_item_fix);
        TextView tvStatus = item.findViewById(R.id.tv_item_status);

        String timeStr = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            .format(new Date(r.timestamp));
        tvTime.setText(timeStr);

        String exShort = r.exceptionType != null ? r.exceptionType : "Unknown";
        int dot = exShort.lastIndexOf('.');
        if (dot >= 0 && dot < exShort.length() - 1) exShort = exShort.substring(dot + 1);
        tvEx.setText(exShort);

        if (r.suspectMod != null) {
            tvMod.setText("● " + r.suspectMod);
            tvMod.setVisibility(View.VISIBLE);
        } else if (r.injectionFlag) {
            tvMod.setText("● Code injection");
            tvMod.setVisibility(View.VISIBLE);
        } else {
            tvMod.setVisibility(View.GONE);
        }

        tvFix.setText(r.chosenFix != null ? r.chosenFix.replace('_', ' ') : "none");
        tvStatus.setText(r.fixStatusLabel());

        item.setOnClickListener(v -> showDetail(r));
    }

    private void showDetail(CrashHistoryDatabase.CrashRecord r) {
        scrollView.setVisibility(View.GONE);
        layoutDetail.setVisibility(View.VISIBLE);

        String exShort = r.exceptionType != null ? r.exceptionType : "Unknown";
        tvDetailTitle.setText("💥 " + exShort);

        StringBuilder meta = new StringBuilder();
        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date(r.timestamp));
        meta.append("Time: ").append(timeStr).append('\n');
        if (r.exceptionMsg != null && !r.exceptionMsg.isEmpty())
            meta.append("Message: ").append(r.exceptionMsg).append('\n');
        if (r.suspectMod != null)
            meta.append("Suspect mod: ").append(r.suspectMod)
                .append(" (").append(r.suspectJar).append(")\n");
        if (r.injectionFlag) meta.append("Injection suspect: YES\n");
        meta.append("Fix applied: ").append(r.chosenFix).append('\n');
        meta.append("Fix result: ").append(r.fixStatusLabel()).append('\n');
        if (r.crashLoopCount > 0)
            meta.append("Crash loop count: ").append(r.crashLoopCount).append('\n');
        if (r.causeChain != null && !r.causeChain.isEmpty())
            meta.append("\nCause chain:\n").append(r.causeChain);

        tvDetailMeta.setText(meta.toString().trim());

        // Show log tail, or read full log if path is set
        String logContent = r.logTail;
        if ((logContent == null || logContent.isEmpty()) && r.crashLogPath != null) {
            logContent = readFileContent(r.crashLogPath, 200);
        }
        tvDetailLog.setText(logContent != null ? logContent : "(no log available)");
    }

    private void showLogFile(String path) {
        scrollView.setVisibility(View.GONE);
        layoutDetail.setVisibility(View.VISIBLE);
        tvDetailTitle.setText("📄 Crash Log");
        tvDetailMeta.setText(path);
        tvDetailLog.setText(readFileContent(path, 500));
    }

    private void showList() {
        layoutDetail.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
    }

    private static String readFileContent(String path, int maxLines) {
        if (path == null) return "(no log)";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(new File(path)))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < maxLines) {
                sb.append(line).append('\n');
                count++;
            }
        } catch (Exception e) {
            return "(could not read: " + e.getMessage() + ")";
        }
        return sb.toString();
    }
}
