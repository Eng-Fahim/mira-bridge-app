package com.example.uhf.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.*;
import com.example.uhf.utils.DateHelper;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * ✅ ViewModel لإدارة حالة شاشات المحاسبة
 */
public class AccountingViewModel extends AndroidViewModel {

    private final MiraApiClient api;

    // ═══ LiveData للقيود ═══
    private final MutableLiveData<JournalEntryDto[]> journalEntries
        = new MutableLiveData<>();
    private final MutableLiveData<JournalEntryDto> selectedEntry
        = new MutableLiveData<>();
    private final MutableLiveData<JournalEntryDetailsDto> journalEntryDetails
        = new MutableLiveData<>();

    // ═══ LiveData للحسابات ═══
    private final MutableLiveData<AccountDto[]> accounts
        = new MutableLiveData<>();
    private final MutableLiveData<AccountDto[]> accountsTree
        = new MutableLiveData<>();

    // ═══ LiveData للتقارير ═══
    private final MutableLiveData<TrialBalanceDto> trialBalance
        = new MutableLiveData<>();
    private final MutableLiveData<BalanceSheetDto> balanceSheet
        = new MutableLiveData<>();
    private final MutableLiveData<IncomeStatementDto> incomeStatement
        = new MutableLiveData<>();
    private final MutableLiveData<GoldMovementDto> goldMovement
        = new MutableLiveData<>();

    // ═══ LiveData لدفتر الأستاذ ═══
    private final MutableLiveData<GeneralLedgerDto> generalLedger
        = new MutableLiveData<>();

    // ═══ LiveData للحالة ═══
    private final MutableLiveData<Boolean> loading
        = new MutableLiveData<>(false);
    private final MutableLiveData<String> error
        = new MutableLiveData<>();

    // ═══ الفلاتر الحالية ═══
    private String currentFromDate = DateHelper.startOfYearApi();
    private String currentToDate   = DateHelper.todayApi();
    private int    currentBranchId = 0;

    public AccountingViewModel(@NonNull Application application) {
        super(application);
        this.api = MiraApiClient.getInstance(application);
    }

    // ═══════════════════════════════════════════════════════
    //  Getters للـ LiveData
    // ═══════════════════════════════════════════════════════

    public LiveData<JournalEntryDto[]> getJournalEntries() { return journalEntries; }
    public LiveData<JournalEntryDto> getSelectedEntry() { return selectedEntry; }
    public LiveData<JournalEntryDetailsDto> getJournalEntryDetails() { return journalEntryDetails; }
    public LiveData<AccountDto[]> getAccounts() { return accounts; }
    public LiveData<AccountDto[]> getAccountsTree() { return accountsTree; }
    public LiveData<TrialBalanceDto> getTrialBalance() { return trialBalance; }
    public LiveData<BalanceSheetDto> getBalanceSheet() { return balanceSheet; }
    public LiveData<IncomeStatementDto> getIncomeStatement() { return incomeStatement; }
    public LiveData<GoldMovementDto> getGoldMovement() { return goldMovement; }
    public LiveData<GeneralLedgerDto> getGeneralLedger() { return generalLedger; }

    public LiveData<Boolean> getLoading() { return loading; }
    public LiveData<String> getError()   { return error; }

    public void clearError() { error.setValue(null); }

    public String getFromDate()   { return currentFromDate; }
    public String getToDate()     { return currentToDate; }
    public int    getBranchId()   { return currentBranchId; }

    // ═══════════════════════════════════════════════════════
    //  القيود
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ الدالة القديمة (بدون تواريخ) — للتوافق
     */
    public void loadJournalEntries(int page, int perPage, String status) {
        loadJournalEntriesWithFilters(page, perPage, status, null, null);
    }

    /**
     * ✅ الدالة الجديدة (مع التواريخ) — التي تستخدمها الشاشة الآن
     */
    public void loadJournalEntriesWithFilters(int page, int perPage, String status, String from, String to) {
        loading.setValue(true);
        error.setValue(null);

        api.accounting().getJournalEntries(
            null,        // branch_id
            from,        // from_date
            to,          // to_date
            status,      // status
            perPage,     // per_page
            page         // page
        ).enqueue(new Callback<ApiResponseDto<JournalEntryDto[]>>() {
            @Override
            public void onResponse(Call<ApiResponseDto<JournalEntryDto[]>> call,
                                  Response<ApiResponseDto<JournalEntryDto[]>> response) {
                loading.setValue(false);
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().success) {
                        journalEntries.setValue(response.body().data);
                    } else {
                        error.setValue(response.body().error);
                    }
                } else {
                    error.setValue("فشل تحميل القيود: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiResponseDto<JournalEntryDto[]>> call, Throwable t) {
                loading.setValue(false);
                error.setValue("خطأ في الشبكة: " + t.getMessage());
            }
        });
    }

    /**
     * ✅ تحميل تفاصيل قيد كامل
     */
    public void loadJournalEntryDetails(long entryId) {
        if (entryId <= 0) {
            error.setValue("معرف القيد غير صالح");
            return;
        }

        loading.setValue(true);
        error.setValue(null);

        api.accounting().getJournalEntryDetails(entryId)
            .enqueue(new Callback<JournalEntryDetailsDto>() {
                @Override
                public void onResponse(Call<JournalEntryDetailsDto> call,
                                      Response<JournalEntryDetailsDto> response) {
                    loading.setValue(false);
                    if (response.isSuccessful()
                            && response.body() != null
                            && response.body().success
                            && response.body().entry != null) {
                        journalEntryDetails.setValue(response.body());
                    } else {
                        String msg = (response.body() != null && response.body().error != null)
                                ? response.body().error
                                : "فشل تحميل تفاصيل القيد: " + response.code();
                        error.setValue(msg);
                    }
                }

                @Override
                public void onFailure(Call<JournalEntryDetailsDto> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue("خطأ في الشبكة: " + t.getMessage());
                }
            });
    }

    public void loadJournalEntry(long id) {
        loadJournalEntryDetails(id);
    }

    public void postJournalEntry(long id) {
        loading.setValue(true);
        api.accounting().postJournalEntry(id)
            .enqueue(new Callback<ApiResponseDto<Object>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<Object>> call,
                                      Response<ApiResponseDto<Object>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful()) {
                        loadJournalEntries(1, 50, null);
                    } else {
                        error.setValue("فشل الاعتماد: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<Object>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    // ═══════════════════════════════════════════════════════
    //  دليل الحسابات
    // ═══════════════════════════════════════════════════════

    public void loadAccounts(String type, String search) {
        loadAccounts(type, search, null);
    }

    public void loadAccounts(String type, String search, Boolean leafOnly) {
        loading.setValue(true);
        error.setValue(null);

        api.accounting().getAccounts(type, search, leafOnly)
            .enqueue(new Callback<ApiResponseDto<AccountDto[]>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<AccountDto[]>> call,
                                      Response<ApiResponseDto<AccountDto[]>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        accounts.setValue(response.body().data);
                    } else {
                        error.setValue("فشل تحميل الحسابات: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<AccountDto[]>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue("خطأ في الشبكة: " + t.getMessage());
                }
            });
    }

    public void loadAccountsTree() {
        loading.setValue(true);
        api.accounting().getAccountsTree()
            .enqueue(new Callback<ApiResponseDto<AccountDto[]>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<AccountDto[]>> call,
                                      Response<ApiResponseDto<AccountDto[]>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        accountsTree.setValue(response.body().data);
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<AccountDto[]>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    // ═══════════════════════════════════════════════════════
    //  التقارير
    // ═══════════════════════════════════════════════════════

    public void loadTrialBalance(String from, String to, int branchId) {
        currentFromDate = from;
        currentToDate   = to;
        currentBranchId = branchId;
        loading.setValue(true);

        api.accounting().getTrialBalance(from, to, branchId > 0 ? branchId : null, false)
            .enqueue(new Callback<ApiResponseDto<TrialBalanceDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<TrialBalanceDto>> call,
                                      Response<ApiResponseDto<TrialBalanceDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        trialBalance.setValue(response.body().data);
                    } else {
                        error.setValue("فشل تحميل ميزان المراجعة");
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<TrialBalanceDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    public void loadBalanceSheet(String asOf) {
        loading.setValue(true);
        api.accounting().getBalanceSheet(asOf)
            .enqueue(new Callback<ApiResponseDto<BalanceSheetDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<BalanceSheetDto>> call,
                                      Response<ApiResponseDto<BalanceSheetDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        balanceSheet.setValue(response.body().data);
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<BalanceSheetDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    public void loadIncomeStatement(String from, String to, int branchId) {
        loading.setValue(true);
        api.accounting().getIncomeStatement(from, to, branchId > 0 ? branchId : null)
            .enqueue(new Callback<ApiResponseDto<IncomeStatementDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<IncomeStatementDto>> call,
                                      Response<ApiResponseDto<IncomeStatementDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        incomeStatement.setValue(response.body().data);
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<IncomeStatementDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    public void loadGoldMovement(String from, String to, int branchId) {
        loading.setValue(true);
        api.accounting().getGoldMovement(from, to, branchId > 0 ? branchId : null)
            .enqueue(new Callback<ApiResponseDto<GoldMovementDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<GoldMovementDto>> call,
                                      Response<ApiResponseDto<GoldMovementDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        goldMovement.setValue(response.body().data);
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<GoldMovementDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }

    // ═══════════════════════════════════════════════════════
    //  دفتر الأستاذ
    // ═══════════════════════════════════════════════════════

    public void loadGeneralLedger(long accountId, String from, String to, int branchId) {
        loadGeneralLedger(accountId, from, to, branchId, 1);
    }

    public void loadGeneralLedger(long accountId, String from, String to, int branchId, int page) {
        if (accountId <= 0) {
            error.setValue("يرجى اختيار حساب");
            return;
        }

        loading.setValue(true);
        error.setValue(null);

        final int perPage = 100;

        api.accounting().getGeneralLedger(accountId, from, to, branchId > 0 ? branchId : null, page, perPage)
            .enqueue(new Callback<ApiResponseDto<GeneralLedgerDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<GeneralLedgerDto>> call,
                                      Response<ApiResponseDto<GeneralLedgerDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        GeneralLedgerDto dto = response.body().data;
                        if (dto != null) dto.currentPage = page;
                        generalLedger.setValue(dto);
                    } else {
                        error.setValue("فشل تحميل دفتر الأستاذ: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<ApiResponseDto<GeneralLedgerDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue("خطأ في الشبكة: " + t.getMessage());
                }
            });
    }

    // ═══════════════════════════════════════════════════════
    //  إضافة قيد جديد
    // ═══════════════════════════════════════════════════════
    
    public void createJournalEntry(org.json.JSONObject payload, Runnable onSuccess) {
        loading.setValue(true);
        api.accounting().createJournalEntryPayload(payload)
            .enqueue(new Callback<ApiResponseDto<JournalEntryDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<JournalEntryDto>> call, Response<ApiResponseDto<JournalEntryDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        // ✅ استخدام الدالة الجديدة مع التواريخ لتحديث القائمة
                        loadJournalEntriesWithFilters(1, 50, null, currentFromDate, currentToDate); 
                        onSuccess.run();
                    } else {
                        error.setValue("فشل حفظ القيد: " + response.code());
                    }
                }
                @Override
                public void onFailure(Call<ApiResponseDto<JournalEntryDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
    }
}
