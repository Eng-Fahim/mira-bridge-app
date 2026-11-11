package com.example.uhf.api;

import com.example.uhf.api.dto.*;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.*;

/**
 * ✅ خدمة API المحاسبة (النسخة المتوافقة بالكامل مع Laravel V1 / legacy.php)
 * Base URL: https://jewelry.ibreg.org/api/mira/v2/
 *
 * @version 3.1.0 (FIXED - Fully Synced with Laravel)
 */
public interface AccountingApiService {

    // ═══════════════════════════════════════════════════════
    //  القيود (Journal Entries)
    // ═══════════════════════════════════════════════════════

    @GET("accounting/journal-entries")
    Call<ApiResponseDto<JournalEntryDto[]>> getJournalEntries(
        @Query("branch_id") Integer branchId,
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("status") String status,
        @Query("per_page") Integer perPage,
        @Query("page") Integer page
    );

    @GET("accounting/journal-entries/{id}")
    Call<JournalEntryDetailsDto> getJournalEntryDetails(@Path("id") long id);

    @POST("accounting/journal-entries/create")
    Call<ApiResponseDto<JournalEntryDto>> createJournalEntryPayload(@Body Map<String, Object> payload);

    @POST("accounting/journal-entries/{id}/post")
    Call<ApiResponseDto<Object>> postJournalEntry(@Path("id") long id);

    @POST("accounting/journal-entries/{id}/reverse")
    Call<ApiResponseDto<Object>> reverseJournalEntry(
        @Path("id") long id,
        @Body Map<String, Object> body
    );

    // ═══════════════════════════════════════════════════════
    //  دليل الحسابات (Chart of Accounts)
    // ═══════════════════════════════════════════════════════

    @GET("accounting/chart-of-accounts")
    Call<ApiResponseDto<AccountDto[]>> getAccounts(
        @Query("type") String type,
        @Query("search") String search,
        @Query("leaf_only") Boolean leafOnly
    );

    @GET("accounting/chart-of-accounts/tree")
    Call<ApiResponseDto<AccountDto[]>> getAccountsTree();

    // ═══════════════════════════════════════════════════════
    //  التقارير (Reports)
    // ═══════════════════════════════════════════════════════

    @GET("accounting/trial-balance")
    Call<ApiResponseDto<TrialBalanceDto>> getTrialBalance(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId,
        @Query("include_zero") Boolean includeZero
    );

    @GET("accounting/balance-sheet")
    Call<ApiResponseDto<BalanceSheetDto>> getBalanceSheet(
        @Query("as_of") String asOf,
        @Query("branch_id") Integer branchId
    );

    @GET("accounting/income-statement")
    Call<ApiResponseDto<IncomeStatementDto>> getIncomeStatement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    @GET("accounting/gold-movement")
    Call<ApiResponseDto<GoldMovementDto>> getGoldMovement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    @GET("accounting/general-ledger/{account_id}")
    Call<ApiResponseDto<GeneralLedgerDto>> getGeneralLedger(
        @Path("account_id") long accountId,
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId,
        @Query("page") Integer page,
        @Query("per_page") Integer perPage
    );

    @GET("accounting/cash-flow")
    Call<ApiResponseDto<CashFlowDto>> getCashFlowStatement(
        @Query("date") String date,
        @Query("branch_id") Integer branchId
    );

    @GET("accounting/aging-report")
    Call<ApiResponseDto<Object>> getAgingReport(
        @Query("as_of") String asOfDate,
        @Query("type") String type
    );

    @GET("accounting/profit-analysis")
    Call<ApiResponseDto<ProfitAnalysisDto>> getProfitAnalysis(
        @Query("from") String fromDate,
        @Query("to") String toDate,
        @Query("branch_id") Integer branchId
    );

    @GET("accounting/inventory-revaluation/preview")
    Call<ApiResponseDto<InventoryRevaluationDto>> previewInventoryRevaluation(
        @Query("branch_id") Integer branchId
    );

    @POST("accounting/inventory-revaluation/post")
    Call<ApiResponseDto<Object>> postInventoryRevaluation(
        @Body Map<String, Object> payload
    );

    @POST("accounting/gold-conversion")
    Call<ApiResponseDto<Object>> processGoldConversion(
        @Body Map<String, Object> payload
    );

    @POST("accounting/fiscal-years/close")
    Call<ApiResponseDto<Object>> closeFiscalYear(@Body Map<String, Object> payload);

    // ═══════════════════════════════════════════════════════
    //  المشتريات (Purchases)
    // ═══════════════════════════════════════════════════════

    @POST("purchases/create")
    Call<ApiResponseDto<Object>> createPurchaseInvoice(@Body Map<String, Object> payload);

    @GET("purchases/list")
    Call<ApiResponseDto<Object[]>> listPurchaseInvoices(
        @Query("branch_id") Integer branchId
    );

    // ═══════════════════════════════════════════════════════
    //  العملات وإقفال الفترات
    // ═══════════════════════════════════════════════════════

    @GET("currencies")
    Call<ApiResponseDto<Object[]>> getCurrencies();

    @GET("currencies/rates")
    Call<ApiResponseDto<Object[]>> getExchangeRates();

    @POST("accounting/lock-period")
    Call<ApiResponseDto<Object>> togglePeriodLock(@Body Map<String, Object> payload);

    @GET("accounting/locked-periods")
    Call<ApiResponseDto<Object[]>> getLockedPeriods();
}
