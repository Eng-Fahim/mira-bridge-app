package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * سطر قيد — يطابق جدول wp_mira_jewelry_journal_lines
 */
public class JournalLineDto implements Serializable {

    @SerializedName("id")
    public long id;

    @SerializedName("entry_id")
    public long entryId;

    @SerializedName("account_id")
    public long accountId;

    // ← يُرسَل من JOIN مع chart_of_accounts في بعض الـ endpoints
    @SerializedName("account_code")
    public String accountCode;

    @SerializedName("account_name_ar")
    public String accountNameAr;

    @SerializedName("account_name")
    public String accountName;

    @SerializedName("description")
    public String description;

    @SerializedName("debit")
    public double debit;

    @SerializedName("credit")
    public double credit;

    @SerializedName("base_debit")
    public double baseDebit;

    @SerializedName("base_credit")
    public double baseCredit;

    @SerializedName("currency")
    public String currency;

    @SerializedName("exchange_rate")
    public double exchangeRate;

    @SerializedName("gold_debit_grams")
    public double goldDebitGrams;

    @SerializedName("gold_credit_grams")
    public double goldCreditGrams;

    @SerializedName("original_karat")
    public String originalKarat;

    @SerializedName("original_weight_grams")
    public double originalWeightGrams;

    @SerializedName("karat_factor")
    public double karatFactor;

    @SerializedName("branch_id")
    public Long branchId;

    @SerializedName("party_id")
    public Long partyId;

    @SerializedName("applied_gold_rate")
    public double appliedGoldRate;

    // ==================== Helpers ====================

    public boolean isDebitLine() {
        return baseDebit > 0.01;
    }

    public boolean isCreditLine() {
        return baseCredit > 0.01;
    }

    public boolean hasGold() {
        return Math.abs(goldDebitGrams) > 0.001
                || Math.abs(goldCreditGrams) > 0.001;
    }

    public double getGoldSigned() {
        return goldDebitGrams - goldCreditGrams;
    }

    /**
 * ✅ صافي الذهب (مدين - دائن) بالجرامات
 */
public double getNetGold() {
    return goldDebitGrams - goldCreditGrams;
}

/**
 * ✅ صافي الوزن الأصلي
 */
public double getNetOriginalWeight() {
    return originalWeightGrams;
}
    public String getDisplayAccount() {
        StringBuilder sb = new StringBuilder();
        if (accountCode != null) sb.append(accountCode);
        if (accountNameAr != null && !accountNameAr.isEmpty()) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(accountNameAr);
        }
        return sb.length() > 0 ? sb.toString() : "حساب #" + accountId;
    }
}
