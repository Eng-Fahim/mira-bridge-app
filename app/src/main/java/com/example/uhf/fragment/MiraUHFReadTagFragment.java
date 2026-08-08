package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraUHFReadTagFragment extends Fragment {
    private static final String TAG = "MiraUHFInventory";

    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4);
    private final List<ScannedTag> recentTags = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scannedEPCs = Collections.synchronizedList(new ArrayList<>());

    // Reconciliation Engine
    private final ConcurrentHashMap<String, ExpectedItem> expectedItemsMap = new ConcurrentHashMap<>();
    private final AtomicInteger expectedTotal = new AtomicInteger(0);
    private final AtomicInteger foundCount = new AtomicInteger(0);
    private final AtomicInteger missingCount = new AtomicInteger(0);
    private final AtomicInteger unexpectedCount = new AtomicInteger(0);
    private String lastScreenshotPath = null;

    // UI
    private TextView tvTotalScanned, tvUniqueTags, tvScanRate, tvElapsed, tvBranchName;
    private TextView tvLastTagEPC, tvLastTagTitle, tvLastTagKarat, tvLastTagWeight, tvLastTagStatus;
    private TextView tvExpectedTotal, tvFoundCount, tvMissingCount, tvUnexpectedCount;
    private TextView tvReconciliationResult;
    private CardView cardLastTag;
    private ProgressBar pbScanProgress;
    private RecyclerView rvRecentTags;
    private Button btnStartInventory, btnStopInventory, btnClearInventory;
    private Button btnExportInventory, btnExportMissing, btnScreenshot;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;
    private String sessionId;

    private boolean isScanning = false;
    private long startTime = 0;
    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger uniqueTags = new AtomicInteger(0);
    private RecentTagsAdapter recentAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private int lastSecondCount = 0;

    // Data class
    public static class ExpectedItem {
        public String epc, title, karat;
        public double weight;
        public boolean isFound = false;
        public String scanTime;
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, status, itemStatus, category, timestamp;
        double price;
    }

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
        } catch (Exception e) {
            Log.e(TAG, "❌ inflate error: " + e.getMessage());
            if (getContext() != null) {
                Toast.makeText(getContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        sessionId = "INV-" + System.currentTimeMillis();

        if (!initViews(view)) return;
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
            tvExpectedTotal = v.findViewById(R.id.tvExpectedTotal);
            tvFoundCount = v.findViewById(R.id.tvFoundCount);
            tvMissingCount = v.findViewById(R.id.tvMissingCount);
            tvUnexpectedCount = v.findViewById(R.id.tvUnexpectedCount);
            tvReconciliationResult = v.findViewById(R.id.tvReconciliationResult);
            cardLastTag = v.findViewById(R.id.cardLastTag);
            pbScanProgress = v.findViewById(R.id.pbScanProgress);
            rvRecentTags = v.findViewById(R.id.rvRecentTags);
            btnStartInventory = v.findViewById(R.id.btnStartInventory);
            btnStopInventory = v.findViewById(R.id.btnStopInventory);
            btnClearInventory = v.findViewById(R.id.btnClearInventory);
            btnExportInventory = v.findViewById(R.id.btnExportInventory);
            btnExportMissing = v.findViewById(R.id.btnExportMissing);
            btnScreenshot = v.findViewById(R.id.btnScreenshot);

            if (tvTotalScanned == null || rvRecentTags == null || btnStartInventory == null) {
                Log.e(TAG, "❌ Critical views null");
                return false;
            }
            resetUI();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ initViews: " + e.getMessage());
            return false;
        }
    }

    private void setupRecyclerView() {
        try {
            if (getContext() == null || rvRecentTags == null) return;
            rvRecentTags.setLayoutManager(new LinearLayoutManager(getContext()));
            recentAdapter = new RecentTagsAdapter(recentTags);
            rvRecentTags.setAdapter(recentAdapter);
        } catch (Exception e) {
            Log.e(TAG, "❌ RecyclerView: " + e.getMessage());
        }
    }

    private void setupListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory());
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnClearInventory != null) btnClearInventory.setOnClickListener(v -> clearInventory());
        if (btnExportInventory != null) btnExportInventory.setOnClickListener(v -> exportFullReport());
        if (btnExportMissing != null) btnExportMissing.setOnClickListener(v -> exportMissingReport());
        if (btnScreenshot != null) btnScreenshot.setOnClickListener(v -> takeScreenshot());
    }

    private void updateBranchDisplay() {
        try {
            if (tvBranchName != null && branchesManager != null) {
                tvBranchName.setText("📍 " + branchesManager.getActiveBranchDisplayName());
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ branch: " + e.getMessage());
        }
    }

    // ==================== Inventory Control ====================

    private void startInventory() {
        loadExpectedInventory(() -> {
            isScanning = true;
            startTime = System.currentTimeMillis();
            totalScanned.set(0);
            uniqueTags.set(0);
            lastSecondCount = 0;
            recentTags.clear();
            scannedEPCs.clear();

            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            if (btnStartInventory != null) { btnStartInventory.setEnabled(false); btnStartInventory.setAlpha(0.5f); }
            if (btnStopInventory != null) { btnStopInventory.setEnabled(true); btnStopInventory.setAlpha(1f); }
            if (btnClearInventory != null) btnClearInventory.setEnabled(false);

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loopFlag = true;
            }

            startTimer();
            updateUI();
            updateReconciliationCounters();

            if (getContext() != null) {
                Toast.makeText(getContext(), ar("🔍 بدء الجرد...", "🔍 Inventory Started..."), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopInventory() {
        isScanning = false;
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loopFlag = false;
        }

        if (btnStartInventory != null) { btnStartInventory.setEnabled(true); btnStartInventory.setAlpha(1f); }
        if (btnStopInventory != null) { btnStopInventory.setEnabled(false); btnStopInventory.setAlpha(0.5f); }
        if (btnClearInventory != null) btnClearInventory.setEnabled(true);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);

        stopTimer();
        updateUI();
        updateReconciliationResult();

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String msg = ar(
            "✅ " + totalScanned.get() + " مسح | " + foundCount.get() + " مطابق | " + missingCount.get() + " ناقص | " + unexpectedCount.get() + " زائد",
            "✅ " + totalScanned.get() + " scans | " + foundCount.get() + " found | " + missingCount.get() + " missing | " + unexpectedCount.get() + " extra");

        if (getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        }
    }

    private void clearInventory() {
        totalScanned.set(0);
        uniqueTags.set(0);
        foundCount.set(0);
        missingCount.set(0);
        unexpectedCount.set(0);
        expectedTotal.set(0);
        lastSecondCount = 0;
        startTime = 0;
        recentTags.clear();
        scannedEPCs.clear();
        expectedItemsMap.clear();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        resetUI();
        if (getContext() != null) {
            Toast.makeText(getContext(), ar("🗑️ تم مسح الجرد", "🗑️ Cleared"), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText("0");
        if (tvUniqueTags != null) tvUniqueTags.setText("0");
        if (tvScanRate != null) tvScanRate.setText("0/s");
        if (tvElapsed != null) tvElapsed.setText("00:00");
        if (tvExpectedTotal != null) tvExpectedTotal.setText("0");
        if (tvFoundCount != null) tvFoundCount.setText("0");
        if (tvMissingCount != null) tvMissingCount.setText("0");
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText("0");
        if (tvReconciliationResult != null) tvReconciliationResult.setText(ar("في انتظار بدء الجرد...", "Awaiting inventory start..."));
        if (tvLastTagEPC != null) tvLastTagEPC.setText("—");
        if (tvLastTagTitle != null) tvLastTagTitle.setText(ar("في انتظار المسح...", "Awaiting scan..."));
        if (tvLastTagKarat != null) tvLastTagKarat.setText("—");
        if (tvLastTagWeight != null) tvLastTagWeight.setText("—");
        if (tvLastTagStatus != null) tvLastTagStatus.setText("⚪");
        if (btnStopInventory != null) { btnStopInventory.setEnabled(false); btnStopInventory.setAlpha(0.5f); }
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
    }

    public void onKeyDownTrigger() {
        if (!isScanning) startInventory();
        else stopInventory();
    }

    // ==================== Reconciliation Engine ====================

    private void loadExpectedInventory(Runnable onReady) {
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getBranchExpectedInventory(branchId);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray array = response.data.optJSONArray("items");
                    expectedItemsMap.clear();

                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            ExpectedItem item = new ExpectedItem();
                            item.epc = obj.optString("epc");
                            item.title = obj.optString("title", "");
                            item.karat = obj.optString("karat", "");
                            item.weight = obj.optDouble("weight", 0);
                            item.isFound = false;
                            expectedItemsMap.put(item.epc, item);
                        }
                    }

                    expectedTotal.set(expectedItemsMap.size());
                    missingCount.set(expectedItemsMap.size());
                    foundCount.set(0);
                    unexpectedCount.set(0);

                    mainHandler.post(() -> {
                        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                        updateReconciliationCounters();
                        if (onReady != null) onReady.run();
                    });
                } else {
                    mainHandler.post(() -> {
                        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), ar("تحذير: لم يتم تحميل المخزون المتوقع", "Warning: Expected inventory not loaded"), Toast.LENGTH_SHORT).show();
                        if (onReady != null) onReady.run();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadExpectedInventory: " + e.getMessage());
                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    if (onReady != null) onReady.run();
                });
            }
        });
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

        String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        // Reconciliation O(1)
        if (expectedItemsMap.containsKey(epc)) {
            ExpectedItem item = expectedItemsMap.get(epc);
            if (item != null && !item.isFound) {
                item.isFound = true;
                item.scanTime = currentTime;
                foundCount.incrementAndGet();
                missingCount.decrementAndGet();
                mainHandler.post(() -> updateLastTagDisplayFull(epc, item.title, item.karat, 
                    String.format(Locale.US, "%.2fg", item.weight), "✅"));
            }
        } else {
            unexpectedCount.incrementAndGet();
            mainHandler.post(() -> updateLastTagDisplayFull(epc, ar("غير مسجلة", "Unregistered"), "—", "—", "⚠️"));
        }

        ScannedTag tag = new ScannedTag();
        tag.epc = epc;
        tag.rssi = rssi != null ? rssi : "—";
        tag.timestamp = currentTime;
        tag.status = expectedItemsMap.containsKey(epc) ? "✓" : "⚠";

        synchronized (recentTags) {
            recentTags.add(0, tag);
            if (recentTags.size() > 500) recentTags.remove(recentTags.size() - 1);
        }

        mainHandler.post(() -> {
            if (recentAdapter != null) recentAdapter.notifyItemInserted(0);
            if (rvRecentTags != null) rvRecentTags.scrollToPosition(0);
            updateReconciliationCounters();
            updateUI();
        });
    }

    private void updateLastTagDisplayFull(String epc, String title, String karat, String weight, String statusIcon) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(title.isEmpty() ? epc : title);
        if (tvLastTagKarat != null) tvLastTagKarat.setText(karat);
        if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);
        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText(statusIcon);
            tvLastTagStatus.setTextColor(statusIcon.equals("✅") ? Color.parseColor("#22C55E") : Color.parseColor("#F59E0B"));
        }
        if (cardLastTag != null) cardLastTag.setVisibility(View.VISIBLE);
    }

    private void updateReconciliationCounters() {
        if (tvExpectedTotal != null) tvExpectedTotal.setText(String.valueOf(expectedTotal.get()));
        if (tvFoundCount != null) tvFoundCount.setText(String.valueOf(foundCount.get()));
        if (tvMissingCount != null) tvMissingCount.setText(String.valueOf(missingCount.get()));
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText(String.valueOf(unexpectedCount.get()));
    }

    private void updateReconciliationResult() {
        if (tvReconciliationResult == null) return;
        
        int found = foundCount.get();
        int missing = missingCount.get();
        int unexpected = unexpectedCount.get();
        int total = expectedTotal.get();
        
        if (total == 0) {
            tvReconciliationResult.setText(ar("لا يوجد مخزون متوقع للمقارنة", "No expected inventory"));
            tvReconciliationResult.setTextColor(Color.parseColor("#F59E0B"));
        } else if (missing == 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("✅ توافق تام - جميع القطع مطابقة", "✅ Full Match - All items accounted"));
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
        } else if (missing > 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("⚠️ نقص: " + missing + " قطعة مفقودة", "⚠️ Missing: " + missing + " items"));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        } else if (missing == 0 && unexpected > 0) {
            tvReconciliationResult.setText(ar("⚠️ زيادة: " + unexpected + " قطعة غير مسجلة", "⚠️ Extra: " + unexpected + " unregistered"));
            tvReconciliationResult.setTextColor(Color.parseColor("#F59E0B"));
        } else {
            tvReconciliationResult.setText(ar("🚨 نقص: " + missing + " | زيادة: " + unexpected, "🚨 Missing: " + missing + " | Extra: " + unexpected));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        }
    }

    // ==================== Export ====================

    private void exportFullReport() {
        StringBuilder sb = buildReportHeader();
        sb.append("No,EPC,Title,Karat,Weight,Match,Time,RSSI\n");
        synchronized (recentTags) {
            int i = 0;
            for (ScannedTag t : recentTags) {
                i++;
                sb.append(i).append(",").append(csv(t.epc)).append(",").append(csv(t.title)).append(",")
                  .append(csv(t.karat)).append(",").append(csv(t.weight)).append(",")
                  .append(csv(t.status)).append(",").append(csv(t.timestamp)).append(",").append(csv(t.rssi)).append("\n");
            }
        }
        shareCsv(sb, "MIRA_Full_Inventory_" + sessionId + ".csv");
    }

    private void exportMissingReport() {
        List<ExpectedItem> missing = getMissingItemsList();
        StringBuilder sb = buildReportHeader();
        sb.append(ar("النواقص: ", "Missing: ")).append(missing.size()).append("\n\n");
        sb.append("EPC,Title,Karat,Weight,Status\n");
        for (ExpectedItem item : missing) {
            sb.append(csv(item.epc)).append(",").append(csv(item.title)).append(",")
              .append(csv(item.karat)).append(",").append(item.weight).append(",MISSING\n");
        }
        shareCsv(sb, "MIRA_Missing_" + sessionId + ".csv");
    }

    private List<ExpectedItem> getMissingItemsList() {
        List<ExpectedItem> missing = new ArrayList<>();
        for (ExpectedItem item : expectedItemsMap.values()) {
            if (!item.isFound) missing.add(item);
        }
        return missing;
    }

    private StringBuilder buildReportHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MIRA INVENTORY REPORT ===\n");
        sb.append("Organization: ").append(orgProfile != null ? orgProfile.getOrgName() : "MIRA").append("\n");
        sb.append("Branch: ").append(branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "N/A").append("\n");
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        sb.append("Expected: ").append(expectedTotal.get()).append("\n");
        sb.append("Found: ").append(foundCount.get()).append("\n");
        sb.append("Missing: ").append(missingCount.get()).append("\n");
        sb.append("Unexpected: ").append(unexpectedCount.get()).append("\n");
        sb.append("Total Scans: ").append(totalScanned.get()).append("\n");
        sb.append("================================\n\n");
        return sb;
    }

    private void shareCsv(StringBuilder sb, String filename) {
        try {
            if (getContext() == null) return;
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_SUBJECT, filename);
            intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(intent, ar("تصدير", "Export")));
        } catch (Exception e) {
            Log.e(TAG, "shareCsv: " + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ==================== Screenshot ====================

    private void takeScreenshot() {
        try {
            View rootView = getView();
            if (rootView == null) {
                Toast.makeText(getContext(), ar("لا توجد واجهة", "No view"), Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            rootView.draw(canvas);

            String filename = "MIRA_Screenshot_" + sessionId + "_" + System.currentTimeMillis() + ".png";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            lastScreenshotPath = file.getAbsolutePath();
            
            // مقارنة مع لقطة سابقة
            if (lastScreenshotPath != null && !lastScreenshotPath.equals(file.getAbsolutePath())) {
                Toast.makeText(getContext(), 
                    ar("📸 تم الحفظ: " + filename + "\nللمقارنة مع اللقطة السابقة", 
                       "📸 Saved: " + filename + "\nCompare with previous screenshot"), 
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), ar("📸 تم الحفظ: " + filename, "📸 Saved: " + filename), Toast.LENGTH_SHORT).show();
            }

            // فتح الملف
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, ar("فتح اللقطة", "Open Screenshot")));

        } catch (Exception e) {
            Log.e(TAG, "screenshot: " + e.getMessage());
            Toast.makeText(getContext(), ar("فشل التقاط اللقطة", "Screenshot failed"), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Timer ====================

    private void startTimer() {
        stopTimer();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                if (elapsed < 0) elapsed = 0;
                if (tvElapsed != null) {
                    tvElapsed.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60));
                }
                int currentTotal = totalScanned.get();
                double rate = currentTotal - lastSecondCount;
                lastSecondCount = currentTotal;
                if (tvScanRate != null && rate >= 0) {
                    tvScanRate.setText(String.format(Locale.US, "%.0f/s", rate));
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
    }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
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
        if (queryExecutor != null && !queryExecutor.isShutdown()) queryExecutor.shutdownNow();
    }

    // ==================== Adapter ====================

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
            synchronized (list) { if (pos >= list.size()) return; t = list.get(pos); }
            if (t != null) {
                if (h.tvEPC != null) h.tvEPC.setText(t.epc);
                if (h.tvTitle != null) h.tvTitle.setText(t.title != null ? t.title : "—");
                if (h.tvDetails != null) h.tvDetails.setText((t.karat != null ? t.karat : "") + " | " + (t.weight != null ? t.weight : ""));
                if (h.tvStatus != null) h.tvStatus.setText(t.status != null ? t.status : "");
                if (h.tvTime != null) h.tvTime.setText(t.timestamp);
            }
        }

        @Override
        public int getItemCount() { synchronized (list) { return list.size(); } }

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
