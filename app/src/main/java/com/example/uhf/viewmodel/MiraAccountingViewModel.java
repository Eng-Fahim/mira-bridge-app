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
    
    private final MiraApiClient apiClient;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public MiraAccountingViewModel(@NonNull Application application) {
        super(application);
        this.apiClient = MiraApiClient.getInstance(application);
    }

    public LiveData<List<MiraAccountingItem>> getAccountingItems() { return accountingItems; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }
    public LiveData<String> getErrorMessage() { return errorMessage; }

    public void fetchJournalEntries() {
        isLoading.setValue(true);

        executorService.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getJournalEntries(null);

            if (response.isSuccess) {
                List<MiraAccountingItem> items = parseJournalEntries(response.data);
                accountingItems.postValue(items);
                isLoading.postValue(false);
            } else {
                isLoading.postValue(false);
                String err = response.error != null ? response.error : "فشل في استرجاع البيانات من السيرفر";
                errorMessage.postValue(err);
            }
        });
    }

    private List<MiraAccountingItem> parseJournalEntries(JSONObject data) {
        List<MiraAccountingItem> list = new ArrayList<>();
        if (data == null) return list;

        try {
            JSONArray entriesArray = data.optJSONArray("entries");
            if (entriesArray == null) {
                entriesArray = data.optJSONArray("data");
            }

            if (entriesArray != null) {
                for (int i = 0; i < entriesArray.length(); i++) {
                    JSONObject obj = entriesArray.getJSONObject(i);
                    
                    String id = obj.optString("id", String.valueOf(i + 1));
                    String title = obj.optString("description", obj.optString("title", "قيد محاسبي"));
                    String amount = obj.optString("amount", "0.00");
                    String date = obj.optString("created_at", obj.optString("date", ""));
                    String status = obj.optString("status", "مكتمل");

                    // استخدام الكونستركتور المطابق لـ MiraAccountingItem
                    list.add(new MiraAccountingItem(
                            MiraAccountingItem.ItemType.JOURNAL_ENTRY, // أو النوع المعرف داخل Enum الكلاس
                            id,
                            title,
                            amount,
                            date,
                            status
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executorService.shutdown();
    }
}
