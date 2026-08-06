package com.example.uhf.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.uhf.model.MiraAccountingItem;
import com.example.uhf.api.ApiClient;

import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MiraAccountingViewModel extends ViewModel {

    private final MutableLiveData<List<MiraAccountingItem>> accountingItems = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

    public LiveData<List<MiraAccountingItem>> getAccountingItems() { return accountingItems; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }
    public LiveData<String> getErrorMessage() { return errorMessage; }

    // دالة طلب القيود اليومية من السيرفر
    public void fetchJournalEntries() {
        isLoading.setValue(true);
        ApiClient.getApiService().getJournalEntries().enqueue(new Callback<List<MiraAccountingItem>>() {
            @Override
            public void onResponse(Call<List<MiraAccountingItem>> call, Response<List<MiraAccountingItem>> response) {
                isLoading.setValue(false);
                if (response.isSuccessful() && response.body() != null) {
                    accountingItems.setValue(response.body());
                } else {
                    errorMessage.setValue("فشل في استرجاع البيانات من السيرفر");
                }
            }

            @Override
            public void onFailure(Call<List<MiraAccountingItem>> call, Throwable t) {
                isLoading.setValue(false);
                errorMessage.setValue("خطأ في الاتصال: " + t.getLocalizedMessage());
            }
        });
    }
}
