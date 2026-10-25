package com.example.uhf.api;

import com.example.uhf.api.dto.*;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.*;

/**
 * ✅ خدمة API المحاسبة
 * Base URL: https://id.ibreg.org/wp-json/mira/v2/
 *
 * @version 3.0.0 (FINAL - Full ERP Endpoints)
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
    Call<ApiResponseDto<JournalEntryDto>> createJournalEntryPayload(@Body org.json.JSONObject payload);

    @POST("journal/{id}/post")
    Call<ApiResponseDto<Object>> postJournalEntry(@Path("id") long id);

    @POST("journal/{id}/reverse")
    Call<ApiResponseDto<Object>> reverseJournalEntry(
        @Path("id") long id,
        @Body Map<String, String> body
    );

    @POST("accounting/journal-entries/{id}/reverse")
    Call<ApiResponseDto<Object>> reverseJournalEntryAlias(
        @Path("id") long id,
        @Body org.json.JSONObject payload
    );

    // ═══════════════════════════════════════════════════════
    //  دليل الحسابات (Chart of Accounts)
    // ═══════════════════════════════════════════════════════

    @GET("accounts")
    Call<ApiResponseDto<AccountDto[]>> getAccounts(
        @Query("type") String type,
        @Query("search") String search,
        @Query("leaf_only") Boolean leafOnly
    );

    @GET("accounts/tree")
    Call<ApiResponseDto<AccountDto[]>> getAccountsTree();

    // ═══════════════════════════════════════════════════════
    //  التقارير (Reports)
    // ═══════════════════════════════════════════════════════

    @GET("reports/trial-balance")
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

    @GET("reports/income-statement")
    Call<ApiResponseDto<IncomeStatementDto>> getIncomeStatement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    @GET("reports/gold-movement")
    Call<ApiResponseDto<GoldMovementDto>> getGoldMovement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    @GET("reports/general-ledger/{account_id}")
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

    // ✅ [جديد] تحليل الأرباح
    @GET("accounting/profit-analysis")
    Call<ApiResponseDto<ProfitAnalysisDto>> getProfitAnalysis(
        @Query("from") String fromDate,
        @Query("to") String toDate,
        @Query("branch_id") Integer branchId
    );

    // ✅ [جديد] معاينة إعادة تقييم المخزون
    @GET("accounting/inventory-revaluation/preview")
    Call<ApiResponseDto<InventoryRevaluationDto>> previewInventoryRevaluation(
        @Query("branch_id") Integer branchId
    );

    // ✅ [جديد] ترحيل إعادة تقييم المخزون
    @POST("accounting/inventory-revaluation/post")
    Call<ApiResponseDto<Object>> postInventoryRevaluation(
        @Body org.json.JSONObject payload
    );

    // ✅ [جديد] تحويل/صياغة الذهب
    @POST("accounting/gold-conversion")
    Call<ApiResponseDto<Object>> processGoldConversion(
        @Body org.json.JSONObject payload
    );

    // ✅ [جديد] إقفال السنة المالية
    @POST("accounting/fiscal-years/close")
    Call<ApiResponseDto<Object>> closeFiscalYear(@Body org.json.JSONObject payload);

    // ═══════════════════════════════════════════════════════
    //  المشتريات (Purchases) - [جديد]
    // ═══════════════════════════════════════════════════════

    @POST("purchases/create")
    Call<ApiResponseDto<Object>> createPurchaseInvoice(@Body org.json.JSONObject payload);

    @GET("purchases/list")
    Call<ApiResponseDto<Object[]>> listPurchaseInvoices(
        @Query("branch_id") Integer branchId
    );

    // ═══════════════════════════════════════════════════════
    //  العملات (Currencies)
    // ═══════════════════════════════════════════════════════

    @GET("currencies")
    Call<ApiResponseDto<Object[]>> getCurrencies();

    @GET("currencies/rates")
    Call<ApiResponseDto<Object[]>> getExchangeRates();
}
