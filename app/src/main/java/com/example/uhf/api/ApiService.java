package com.example.uhf.com.example.uhf.api;

import com.example.uhf.model.MiraAccountingItem;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;

public interface ApiService {

    // جلب قائمة القيود اليومية من السيرفر
    @GET("accounting/journal-entries")
    Call<List<MiraAccountingItem>> getJournalEntries();

    // جلب دليل الحسابات
    @GET("accounting/chart-of-accounts")
    Call<List<MiraAccountingItem>> getChartOfAccounts();

    // جلب ميزان المراجعة
    @GET("accounting/trial-balance")
    Call<List<MiraAccountingItem>> getTrialBalance();
}
