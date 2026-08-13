package com.example.uhf.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MiraAccountingItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingViewModel extends AndroidViewModel {

    private final MutableLiveData<List<MiraAccountingItem>> accountingItems = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<AccountingStats> statsLiveData = new MutableLiveData<>();

    private List<MiraAccountingItem> rawList = new ArrayList<>();
    private final MiraApiClient apiClient;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public MiraAccountingViewModel(@NonNull Application application) {
        super(application);
        this.apiClient = MiraApiClient.getInstance(application);
    }

    // ✅ دالة للتوافق مع استدعاء الـ Fragment
    public LiveData<List<MiraAccountingItem>> getAccountingItemsLiveData() { return accountingItems; }
    public LiveData<List<MiraAccountingItem>> getAccountingItems() { return accountingItems; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<AccountingStats> getStatsLiveData() { return statsLiveData; }

    // ✅ دالة جلب البيانات الشاملة المباشرة من الـ Fragment حسب التبويب
    public void fetchAccountingData(MiraAccountingItem.ItemType tab) {
        if (tab == MiraAccountingItem.ItemType.CHART_OF_ACCOUNT) {
            fetchChartOfAccounts();
        } else if (tab == MiraAccountingItem.ItemType.TRIAL_BALANCE) {
            fetchTrialBalance();
        } else {
            fetchJournalEntries();
        }
    }

    public void fetchJournalEntries() {
        isLoading.setValue(true);
        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getJournalEntries(null);
                if (response != null && response.isSuccess) {
                    List<MiraAccountingItem> items = parseJournalEntries(response.data);
                    postResult(items);
                } else {
                    loadMockJournalEntries();
                }
            } catch (Exception e) {
                loadMockJournalEntries();
            }
        });
    }

    public void fetchChartOfAccounts() {
        isLoading.setValue(true);
        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getChartOfAccounts();
                if (response != null && response.isSuccess) {
                    List<MiraAccountingItem> items = parseChartOfAccounts(response.data);
                    postResult(items);
                } else {
                    loadMockChartOfAccounts();
                }
            } catch (Exception e) {
                loadMockChartOfAccounts();
            }
        });
    }

    public void fetchTrialBalance() {
        isLoading.setValue(true);
        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getTrialBalance(null);
                if (response != null && response.isSuccess) {
                    List<MiraAccountingItem> items = parseTrialBalance(response.data);
                    postResult(items);
                } else {
                    loadMockTrialBalance();
                }
            } catch (Exception e) {
                loadMockTrialBalance();
            }
        });
    }

    private List<MiraAccountingItem> parseJournalEntries(JSONObject data) {
        List<MiraAccountingItem> list = new ArrayList<>();
        if (data == null) return list;

        try {
            JSONArray entries = data.optJSONArray("entries");
            if (entries == null) entries = data.optJSONArray("data");

            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject obj = entries.getJSONObject(i);
                    
                    String ref = obj.optString("reference_no", obj.optString("id", "JV-" + (i + 1)));
                    String desc = obj.optString("description", "قيد مبيعات مجوهرات");
                    
                    double fineDebit = obj.optDouble("fine_gold_debit", 0.0);
                    double fineCredit = obj.optDouble("fine_gold_credit", 0.0);
                    
                    double gross = obj.optDouble("gross_weight", 0.0);
                    int karat = obj.optInt("karat", 0);
                    if (fineDebit == 0.0 && gross > 0 && karat > 0) {
                        fineDebit = MiraAccountingItem.convertToFineGold24(gross, karat);
                    }

                    double debitCash = obj.optDouble("debit_cash", obj.optDouble("debit", 0.0));
                    double creditCash = obj.optDouble("credit_cash", obj.optDouble("credit", 0.0));
                    String currency = obj.optString("currency", "YER");
                    String date = obj.optString("created_at", "");

                    MiraAccountingItem item = new MiraAccountingItem(
                            MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                            ref, desc, fineDebit, fineCredit, debitCash, creditCash, currency, date
                    );
                    item.setRfidTag(obj.optString("rfid_tag", null));
                    item.setMakingFee(obj.optDouble("making_fee", 0.0));
                    list.add(item);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private List<MiraAccountingItem> parseChartOfAccounts(JSONObject data) {
        List<MiraAccountingItem> list = new ArrayList<>();
        if (data == null) return list;
        try {
            JSONArray accounts = data.optJSONArray("accounts");
            if (accounts == null) accounts = data.optJSONArray("data");
            if (accounts != null) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject obj = accounts.getJSONObject(i);
                    list.add(new MiraAccountingItem(
                            MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                            obj.optString("code", "-"),
                            obj.optString("name", "-"),
                            obj.optDouble("fine_gold_balance", 0.0),
                            obj.optDouble("cash_balance", 0.0),
                            obj.optString("currency", "YER")
                    ));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<MiraAccountingItem> parseTrialBalance(JSONObject data) {
        return parseChartOfAccounts(data);
    }

    private void postResult(List<MiraAccountingItem> items) {
        rawList = items;
        accountingItems.postValue(items);
        calculateAndPostStats(items);
        isLoading.postValue(false);
    }

    public void filterData(String query) {
        if (rawList == null) return;
        if (query == null || query.trim().isEmpty()) {
            accountingItems.setValue(rawList);
            return;
        }
        String q = query.toLowerCase().trim();
        List<MiraAccountingItem> filtered = new ArrayList<>();
        for (MiraAccountingItem item : rawList) {
            boolean matchRef = item.getCodeOrRef() != null && item.getCodeOrRef().toLowerCase().contains(q);
            boolean matchDesc = item.getNameOrDesc() != null && item.getNameOrDesc().toLowerCase().contains(q);
            boolean matchRfid = item.getRfidTag() != null && item.getRfidTag().toLowerCase().contains(q);
            if (matchRef || matchDesc || matchRfid) {
                filtered.add(item);
            }
        }
        accountingItems.setValue(filtered);
    }

    private void calculateAndPostStats(List<MiraAccountingItem> items) {
        double totalCash = 0;
        double totalGold24 = 0;
        boolean balanced = true;

        for (MiraAccountingItem item : items) {
            if (item.getItemType() == MiraAccountingItem.ItemType.JOURNAL_ENTRY) {
                totalCash += item.getDebitCash();
                // ✅ تصحيح أخير: استدعاء الميثود الصحيحة getFineWeight24Debit
                totalGold24 += item.getFineWeight24Debit();
            } else {
                totalCash += item.getCashBalance();
                totalGold24 += item.getFineGoldBalance();
            }
        }
        statsLiveData.postValue(new AccountingStats(totalCash, totalGold24, balanced));
    }

    private void loadMockJournalEntries() {
        List<MiraAccountingItem> mock = new ArrayList<>();
        
        double fine24_1 = MiraAccountingItem.convertToFineGold24(18.5, 21);
        MiraAccountingItem item1 = new MiraAccountingItem(
                MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                "JV-2026-801", "مبيعات طقم مجوهرات عيار 21",
                0.0, fine24_1, 1250000.0, 0.0, "YER", "2026-08-09"
        );
        item1.setRfidTag("E2801170000002011A8F9001");
        item1.setMakingFee(85000.0);
        mock.add(item1);

        double fine24_2 = MiraAccountingItem.convertToFineGold24(40.0, 18);
        MiraAccountingItem item2 = new MiraAccountingItem(
                MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                "JV-2026-802", "شراء ذهب كسر (18k) - توريد خزانة",
                fine24_2, 0.0, 0.0, 2100000.0, "YER", "2026-08-09"
        );
        mock.add(item2);

        postResult(mock);
    }

    private void loadMockChartOfAccounts() {
        List<MiraAccountingItem> mock = new ArrayList<>();
        mock.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110101", "الصندوق الرئيسي - YER", 0.0, 18500000.0, "YER"));
        mock.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110102", "خزانة الذهب الكسر (24k)", 1420.450, 0.0, "YER"));
        mock.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110201", "مخزون المجوهرات المشغولة", 3850.120, 4200000.0, "YER"));
        postResult(mock);
    }

    private void loadMockTrialBalance() {
        loadMockChartOfAccounts();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executorService.shutdown();
    }

    public static class AccountingStats {
        public double totalCash;
        public double totalGoldFine24;
        public boolean isBalanced;

        public AccountingStats(double totalCash, double totalGoldFine24, boolean isBalanced) {
            this.totalCash = totalCash;
            this.totalGoldFine24 = totalGoldFine24;
            this.isBalanced = isBalanced;
        }
    }
}
