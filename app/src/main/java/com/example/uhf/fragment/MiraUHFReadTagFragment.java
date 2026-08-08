package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraUHFReadTagFragment extends Fragment {
    private static final String TAG = "MiraUHFInventory";

    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(2);
    private final List<ScannedTag> recentTags = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scannedEPCs = Collections.synchronizedList(new ArrayList<>());

    private TextView tvTotalScanned, tvUniqueTags, tvScanRate, tvElapsed, tvBranchName;
    private TextView tvLastTagEPC, tvLastTagTitle, tvLastTagKarat, tvLastTagWeight, tvLastTagStatus;
    private CardView cardLastTag;

    private ProgressBar pbScanProgress;
    private RecyclerView rvRecentTags;
    private Button btnStartInventory, btnStopInventory, btnClearInventory, btnExportInventory;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;

    private boolean isScanning = false;
    private long startTime = 0;
    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger uniqueTags = new AtomicInteger(0);
    private RecentTagsAdapter recentAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private double scanRatePerSec = 0;
    private int lastSecondCount = 0;

    public void onKeyDownTrigger() {
        if (!isScanning) {
            startInventory();
        } else {
            stopInventory();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
        } catch (Exception e) {
            Log.e(TAG, "❌ onCreateView inflate error: " + e.getMessage());
            e.printStackTrace();
            if (getContext() != null) {
                Toast.makeText(getContext(), "خطأ في تحميل واجهة الجرد", Toast.LENGTH_LONG).show();
            }
            return new View(getContext());
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = getContext();
        if (ctx != null) {
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        if (!initViews(view)) {
            Log.e(TAG, "❌ initViews failed - views missing");
            return;
        }

        setupRecyclerView();
        setupListeners();
        updateBranchDisplay();
    }

    private boolean initViews(View v) {
        try {
            tvTotalScanned = v.findViewById(R.id.tvTotalScanned);
            tvUniqueTags = v.findViewById(R.id.tvUniqueTags);
            tvScanRate = v.findViewById(R.id.tvScanRate);
            tvElapsed = v.findViewById(R.id.tvElapsed);
            tvBranchName = v.findViewById(R.id.tvBranchName);

            tvLastTagEPC = v.findViewById(R.id.tvLastTagEPC);
            tvLastTagTitle = v.findViewById(R.id.tvLastTagTitle);
            tvLastTagKarat = v.findViewById(R.id.tvLastTagKarat);
            tvLastTagWeight = v.findViewById(R.id.tvLastTagWeight);
            tvLastTagStatus = v.findViewById(R.id.tvLastTagStatus);
            cardLastTag = v.findViewById(R.id.cardLastTag);

            pbScanProgress = v.findViewById(R.id.pbScanProgress);
            rvRecentTags = v.findViewById(R.id.rvRecentTags);

            btnStartInventory = v.findViewById(R.id.btnStartInventory);
            btnStopInventory = v.findViewById(R.id.btnStopInventory);
            btnClearInventory = v.findViewById(R.id.btnClearInventory);
            btnExportInventory = v.findViewById(R.id.btnExportInventory);

            // التحقق من وجود جميع العناصر
            if (tvTotalScanned == null || tvUniqueTags == null || tvScanRate == null ||
                tvElapsed == null || rvRecentTags == null || btnStartInventory == null ||
                btnStopInventory == null || btnClearInventory == null || btnExportInventory == null) {
                Log.e(TAG, "❌ One or more views are null! Check XML IDs.");
                return false;
            }

            resetUI();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ initViews error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void setupRecyclerView() {
        try {
            Context ctx = getContext();
            if (ctx == null) {
                Log.e(TAG, "❌ setupRecyclerView: Context is null");
                return;
            }
            if (rvRecentTags == null) {
                Log.e(TAG, "❌ setupRecyclerView: rvRecentTags is null");
                return;
            }
            rvRecentTags.setLayoutManager(new LinearLayoutManager(ctx));
            recentAdapter = new RecentTagsAdapter(recentTags);
            rvRecentTags.setAdapter(recentAdapter);
        } catch (Exception e) {
            Log.e(TAG, "❌ setupRecyclerView error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupListeners() {
        try {
            if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory());
            if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
            if (btnClearInventory != null) btnClearInventory.setOnClickListener(v -> clearInventory());
            if (btnExportInventory != null) btnExportInventory.setOnClickListener(v -> exportResults());
        } catch (Exception e) {
            Log.e(TAG, "❌ setupListeners error: " + e.getMessage());
        }
    }

    private void updateBranchDisplay() {
        if (tvBranchName != null && branchesManager != null) {
            try {
                tvBranchName.setText("📍 " + branchesManager.getActiveBranchDisplayName());
            } catch (Exception e) {
                Log.e(TAG, "❌ updateBranchDisplay error: " + e.getMessage());
            }
        }
    }

    private void startInventory() {
        isScanning = true;
        startTime = System.currentTimeMillis();
        totalScanned.set(0);
        uniqueTags.set(0);
        lastSecondCount = 0;
        recentTags.clear();
        scannedEPCs.clear();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();

        if (btnStartInventory != null) {
            btnStartInventory.setEnabled(false);
            btnStartInventory.setAlpha(0.5f);
        }
        if (btnStopInventory != null) {
            btnStopInventory.setEnabled(true);
            btnStopInventory.setAlpha(1f);
        }
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loopFlag = true;
        }

        startTimer();
        updateUI();

        if (getContext() != null) {
            Toast.makeText(getContext(), ar("🔍 بدء الجرد المباشر...", "🔍 Live Inventory Started..."), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopInventory() {
        isScanning = false;

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loopFlag = false;
        }

        if (btnStartInventory != null) {
            btnStartInventory.setEnabled(true);
            btnStartInventory.setAlpha(1f);
        }
        if (btnStopInventory != null) {
            btnStopInventory.setEnabled(false);
            btnStopInventory.setAlpha(0.5f);
        }
        if (btnClearInventory != null) btnClearInventory.setEnabled(true);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);

        stopTimer();
        updateUI();

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String msg = ar(
            "✅ اكتمل: " + totalScanned.get() + " مسح، " + uniqueTags.get() + " قطعة في " + elapsed + " ثانية",
            "✅ Done: " + totalScanned.get() + " scans, " + uniqueTags.get() + " items in " + elapsed + "s");

        if (getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        }
    }

    private void clearInventory() {
        totalScanned.set(0);
        uniqueTags.set(0);
        lastSecondCount = 0;
        scanRatePerSec = 0;
        startTime = 0;
        recentTags.clear();
        scannedEPCs.clear();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        resetUI();
        if (getContext() != null) {
            Toast.makeText(getContext(), ar("🗑️ تم مسح الجرد", "🗑️ Cleared"), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetUI() {
        try {
            if (tvTotalScanned != null) tvTotalScanned.setText("0");
            if (tvUniqueTags != null) tvUniqueTags.setText("0");
            if (tvScanRate != null) tvScanRate.setText("0/s");
            if (tvElapsed != null) tvElapsed.setText("00:00");
            if (tvLastTagEPC != null) tvLastTagEPC.setText("—");
            if (tvLastTagTitle != null) tvLastTagTitle.setText(ar("في انتظار المسح...", "Awaiting scan..."));
            if (tvLastTagKarat != null) tvLastTagKarat.setText("—");
            if (tvLastTagWeight != null) tvLastTagWeight.setText("—");
            if (tvLastTagStatus != null) tvLastTagStatus.setText("⚪");
            if (btnStopInventory != null) {
                btnStopInventory.setEnabled(false);
                btnStopInventory.setAlpha(0.5f);
            }
            if (btnClearInventory != null) btnClearInventory.setEnabled(false);
            if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
        } catch (Exception e) {
            Log.e(TAG, "❌ resetUI error: " + e.getMessage());
        }
    }

    public void onTagScanned(String epc, String rssi) {
        if (!isScanning || epc == null || epc.isEmpty()) return;

        if (scannedEPCs.contains(epc)) {
            totalScanned.incrementAndGet();
            mainHandler.post(this::updateUI);
            return;
        }

        scannedEPCs.add(epc);
        totalScanned.incrementAndGet();
        uniqueTags.incrementAndGet();

        ScannedTag tag = new ScannedTag();
        tag.epc = epc;
        tag.rssi = rssi;
        tag.timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        synchronized (recentTags) {
            recentTags.add(0, tag);
            if (recentTags.size() > 100) {
                recentTags.remove(recentTags.size() - 1);
            }
        }

        mainHandler.post(() -> {
            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            if (rvRecentTags != null) rvRecentTags.scrollToPosition(0);
            updateLastTagDisplay(epc, rssi);
            updateUI();
        });

        queryItemDetails(epc, rssi);
    }

    private void updateLastTagDisplay(String epc, String rssi) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(ar("جاري الاستعلام...", "Querying..."));
        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText("🔍");
            tvLastTagStatus.setTextColor(Color.parseColor("#F59E0B"));
        }
        if (cardLastTag != null) cardLastTag.setVisibility(View.VISIBLE);
    }

    private void queryItemDetails(String epc, String rssi) {
        queryExecutor.execute(() -> {
            if (!isAdded() || apiClient == null || branchesManager == null) return;
            try {
                JSONObject extraParams = new JSONObject();
                extraParams.put("branch_id", branchesManager.getActiveBranchId());
                if (rssi != null) extraParams.put("rssi", rssi);

                MiraApiClient.ApiResponse response = apiClient.authorize(epc, "inventory", extraParams);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        String title = item.optString("title", "");
                        String karat = item.optString("karat", "");
                        String weight = String.format(Locale.US, "%.2fg", item.optDouble("weight", 0));
                        String status = item.optString("status", "");

                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            if (tvLastTagTitle != null) tvLastTagTitle.setText(title.isEmpty() ? epc : title);
                            if (tvLastTagKarat != null) tvLastTagKarat.setText(karat);
                            if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);

                            boolean ok = "in_stock".equals(status) || "available".equals(status);
                            if (tvLastTagStatus != null) {
                                tvLastTagStatus.setText(ok ? "✅" : "⚠️");
                                tvLastTagStatus.setTextColor(ok ? Color.parseColor("#22C55E") : Color.parseColor("#F59E0B"));
                            }

                            synchronized (recentTags) {
                                for (ScannedTag t : recentTags) {
                                    if (t.epc.equals(epc)) {
                                        t.title = title;
                                        t.karat = karat;
                                        t.weight = weight;
                                        t.status = ok ? "✓" : "⚠";
                                        break;
                                    }
                                }
                            }
                            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Query error: " + e.getMessage());
            }
        });
    }

    private void startTimer() {
        timerRunnable = () -> {
            if (!isAdded()) return;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (tvElapsed != null) {
                tvElapsed.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60));
            }

            int currentTotal = totalScanned.get();
            scanRatePerSec = currentTotal - lastSecondCount;
            lastSecondCount = currentTotal;
            if (tvScanRate != null) {
                tvScanRate.setText(String.format(Locale.US, "%.0f/s", scanRatePerSec));
            }

            timerHandler.postDelayed(timerRunnable, 1000);
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
    }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
    }

    private void exportResults() {
        if (getContext() == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(orgProfile != null ? orgProfile.getOrgName() : "MIRA Jewelry").append("\n");
        sb.append(ar("تقرير الجرد", "Inventory Report")).append("\n");
        sb.append(ar("الفرع: ", "Branch: ")).append(branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "").append("\n");
        sb.append(ar("التاريخ: ", "Date: ")).append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append(ar("إجمالي المسوحات: ", "Total Scans: ")).append(totalScanned.get()).append("\n");
        sb.append(ar("القطع الفريدة: ", "Unique Items: ")).append(uniqueTags.get()).append("\n\n");
        sb.append("EPC,Title,Karat,Weight,Status,Time\n");

        synchronized (recentTags) {
            for (ScannedTag t : recentTags) {
                sb.append(t.epc).append(",").append(t.title).append(",").append(t.karat)
                  .append(",").append(t.weight).append(",").append(t.status).append(",").append(t.timestamp).append("\n");
            }
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, ar("تصدير الجرد", "Export Inventory")));
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        timerHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (queryExecutor != null && !queryExecutor.isShutdown()) {
            queryExecutor.shutdownNow();
        }
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, status, timestamp;
    }

    class RecentTagsAdapter extends RecyclerView.Adapter<RecentTagsAdapter.VH> {
        final List<ScannedTag> list;
        RecentTagsAdapter(List<ScannedTag> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scanned_tag, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ScannedTag t;
            synchronized (list) {
                if (pos >= list.size()) return;
                t = list.get(pos);
            }
            if (t != null) {
                if (h.tvEPC != null) h.tvEPC.setText(t.epc);
                if (h.tvTitle != null) h.tvTitle.setText(t.title != null ? t.title : "—");
                if (h.tvDetails != null) h.tvDetails.setText((t.karat != null ? t.karat : "") + " | " + (t.weight != null ? t.weight : ""));
                if (h.tvStatus != null) h.tvStatus.setText(t.status != null ? t.status : "");
                if (h.tvTime != null) h.tvTime.setText(t.timestamp);
            }
        }

        @Override public int getItemCount() {
            synchronized (list) {
                return list.size();
            }
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEPC, tvTitle, tvDetails, tvStatus, tvTime;
            VH(View v) {
                super(v);
                tvEPC = v.findViewById(R.id.tvTagEPC);
                tvTitle = v.findViewById(R.id.tvTagTitle);
                tvDetails = v.findViewById(R.id.tvTagDetails);
                tvStatus = v.findViewById(R.id.tvTagStatus);
                tvTime = v.findViewById(R.id.tvTagTime);
            }
        }
    }
}
