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
 * ViewModel لإدارة حالة شاشات المحاسبة
 */
public class AccountingViewModel extends AndroidViewModel {
    
    private final MiraApiClient api;
    
    // ═══ LiveData للقيود ═══
    private final MutableLiveData<JournalEntryDto[]> journalEntries = new MutableLiveData<>();
    private final MutableLiveData<JournalEntryDto> selectedEntry = new MutableLiveData<>();
    
    // ═══ LiveData للحسابات ═══
    private final MutableLiveData<AccountDto[]> accounts = new MutableLiveData<>();
    private final MutableLiveData<AccountDto[]> accountsTree = new MutableLiveData<>();
    
    // ═══ LiveData للتقارير ═══
    private final MutableLiveData<TrialBalanceDto> trialBalance = new MutableLiveData<>();
    private final MutableLiveData<BalanceSheetDto> balanceSheet = new MutableLiveData<>();
    private final MutableLiveData<IncomeStatementDto> incomeStatement = new MutableLiveData<>();
    private final MutableLiveData<GoldMovementDto> goldMovement = new MutableLiveData<>();
    
    // ═══ LiveData للحالة ═══
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    
    // ═══ الفلاتر الحالية ═══
    private String currentFromDate = DateHelper.startOfYearApi();
    private String currentToDate = DateHelper.todayApi();
    private int currentBranchId = 0;
    
    public AccountingViewModel(@NonNull Application application) {
        super(application);
        this.api = MiraApiClient.getInstance(application);
    }
    
    // ═══════════════════════════════════════════════════════
    //  القيود
    // ═══════════════════════════════════════════════════════
    
    public LiveData<JournalEntryDto[]> getJournalEntries() {
        return journalEntries;
    }
    
    public LiveData<JournalEntryDto> getSelectedEntry() {
        return selectedEntry;
    }
    public void loadJournalEntries(int page, int perPage, String status) {
    loading.setValue(true);
    error.setValue(null);
    
    // ✅ لا ترسل فلاتر التاريخ مؤقتاً — لتجاوز مشكلة الصيغة
    api.accounting().getJournalEntries(
        null,   // branch_id: null
        null,   // from_date: null ← أزلنا
        null,   // to_date: null   ← أزلنا
        status,
        perPage,
        page
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
    
    public void loadJournalEntry(long id) {
        loading.setValue(true);
        api.accounting().getJournalEntry(id)
            .enqueue(new Callback<ApiResponseDto<JournalEntryDto>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<JournalEntryDto>> call,
                                      Response<ApiResponseDto<JournalEntryDto>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        selectedEntry.setValue(response.body().data);
                    }
                }
                
                @Override
                public void onFailure(Call<ApiResponseDto<JournalEntryDto>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
                }
            });
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
    
    public LiveData<AccountDto[]> getAccounts() {
        return accounts;
    }
    
    public LiveData<AccountDto[]> getAccountsTree() {
        return accountsTree;
    }
    
    public void loadAccounts(String type, String search) {
        loading.setValue(true);
        api.accounting().getAccounts(type, search)
            .enqueue(new Callback<ApiResponseDto<AccountDto[]>>() {
                @Override
                public void onResponse(Call<ApiResponseDto<AccountDto[]>> call,
                                      Response<ApiResponseDto<AccountDto[]>> response) {
                    loading.setValue(false);
                    if (response.isSuccessful() && response.body() != null && response.body().success) {
                        accounts.setValue(response.body().data);
                    }
                }
                
                @Override
                public void onFailure(Call<ApiResponseDto<AccountDto[]>> call, Throwable t) {
                    loading.setValue(false);
                    error.setValue(t.getMessage());
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
    
    public LiveData<TrialBalanceDto> getTrialBalance() {
        return trialBalance;
    }
    
    public LiveData<BalanceSheetDto> getBalanceSheet() {
        return balanceSheet;
    }
    
    public LiveData<IncomeStatementDto> getIncomeStatement() {
        return incomeStatement;
    }
    
    public LiveData<GoldMovementDto> getGoldMovement() {
        return goldMovement;
    }
    
    public void loadTrialBalance(String from, String to, int branchId) {
        currentFromDate = from;
        currentToDate = to;
        currentBranchId = branchId;
        loading.setValue(true);
        
        api.accounting().getTrialBalance(
            from, to, 
            branchId > 0 ? branchId : null,
            false
        ).enqueue(new Callback<ApiResponseDto<TrialBalanceDto>>() {
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
    //  الحالة
    // ═══════════════════════════════════════════════════════
    
    public LiveData<Boolean> getLoading() { return loading; }
    public LiveData<String> getError() { return error; }
    
    public void clearError() { error.setValue(null); }
    
    public String getFromDate() { return currentFromDate; }
    public String getToDate() { return currentToDate; }
    public int getBranchId() { return currentBranchId; }
    }
