package com.example.uhf.api;

import com.example.uhf.api.dto.*;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.*;

/**
 * ✅ خدمة API المحاسبة
 * Base URL: https://id.ibreg.org/wp-json/mira/v2/
 *
 * @version 2.1.0 (FINAL)
 */
public interface AccountingApiService {

    // ═══════════════════════════════════════════════════════
    //  القيود (Journal Entries)
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ قائمة القيود مع دعم الصفحات
     */
    @GET("accounting/journal-entries") // ✅ المسار الذي تم اختباره ويعمل
Call<ApiResponseDto<JournalEntryDto[]>> getJournalEntries(
    @Query("branch_id") Integer branchId,
    @Query("from_date") String fromDate,
    @Query("to_date") String toDate,
    @Query("status") String status,
    @Query("per_page") Integer perPage,
    @Query("page") Integer page
);

@GET("accounting/journal-entries/{id}") // ✅ المسار الجديد
Call<JournalEntryDetailsDto> getJournalEntryDetails(
    @Path("id") long id
);
@POST("accounting/journal-entries/create")
Call<ApiResponseDto<JournalEntryDto>> createJournalEntryPayload(@Body org.json.JSONObject payload);
    /**
     * ✅ ترحيل قيد
     */
    @POST("journal/{id}/post")
    Call<ApiResponseDto<Object>> postJournalEntry(@Path("id") long id);

    /**
     * ✅ عكس قيد
     */
    @POST("journal/{id}/reverse")
    Call<ApiResponseDto<Object>> reverseJournalEntry(
        @Path("id") long id,
        @Body Map<String, String> body
    );

    // ═══════════════════════════════════════════════════════
    //  دليل الحسابات (Chart of Accounts)
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ قائمة الحسابات — مع دعم leaf_only
     */
    @GET("accounts")
    Call<ApiResponseDto<AccountDto[]>> getAccounts(
        @Query("type") String type,
        @Query("search") String search,
        @Query("leaf_only") Boolean leafOnly
    );

    /**
     * ✅ شجرة الحسابات
     */
    @GET("accounts/tree")
    Call<ApiResponseDto<AccountDto[]>> getAccountsTree();

    // ═══════════════════════════════════════════════════════
    //  التقارير (Reports)
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ ميزان المراجعة
     */
    @GET("reports/trial-balance")
    Call<ApiResponseDto<TrialBalanceDto>> getTrialBalance(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId,
        @Query("include_zero") Boolean includeZero
    );

    /**
     * ✅ الميزانية العمومية
     */
    @GET("accounting/balance-sheet")
Call<ApiResponseDto<BalanceSheetDto>> getBalanceSheet(
    @Query("as_of") String asOf,
    @Query("branch_id") Integer branchId
);

    /**
     * ✅ قائمة الدخل
     */
    @GET("reports/income-statement")
    Call<ApiResponseDto<IncomeStatementDto>> getIncomeStatement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    /**
     * ✅ حركة الذهب
     */
    @GET("reports/gold-movement")
    Call<ApiResponseDto<GoldMovementDto>> getGoldMovement(
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId
    );

    /**
     * ✅✅✅ دفتر الأستاذ العام — مع دعم Pagination
     */
    @GET("reports/general-ledger/{account_id}")
    Call<ApiResponseDto<GeneralLedgerDto>> getGeneralLedger(
        @Path("account_id") long accountId,
        @Query("from_date") String fromDate,
        @Query("to_date") String toDate,
        @Query("branch_id") Integer branchId,
        @Query("page") Integer page,          // ✅ مضاف
        @Query("per_page") Integer perPage    // ✅ مضاف
    );

    // ═══════════════════════════════════════════════════════
    //  العملات (Currencies)
    // ═══════════════════════════════════════════════════════

    @GET("currencies")
    Call<ApiResponseDto<Object[]>> getCurrencies();

    @GET("currencies/rates")
    Call<ApiResponseDto<Object[]>> getExchangeRates();

    @GET("accounting/cash-flow")
    Call<ApiResponseDto<CashFlowDto>> getCashFlowStatement(
    @Query("date") String date,
    @Query("branch_id") Integer branchId
);
}
