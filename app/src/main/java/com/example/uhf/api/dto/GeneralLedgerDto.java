package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class GeneralLedgerDto {

    @SerializedName("account")
    public AccountDto account;

    @SerializedName("opening")
    public OpeningDto opening;

    @SerializedName("rows")
    public List<LedgerRowDto> rows;

    @SerializedName("has_gold")
    public boolean hasGold;

    @SerializedName("closing_balance")
    public double closingBalance;

    @SerializedName("closing_gold")
    public double closingGold;

    @SerializedName("from_date")
    public String fromDate;

    @SerializedName("to_date")
    public String toDate;

    @SerializedName("branch_id")
    public int branchId;

    // ==================== Nested DTOs ====================

    public static class OpeningDto {
        @SerializedName("total_debit")
        public double totalDebit;

        @SerializedName("total_credit")
        public double totalCredit;

        @SerializedName("balance")
        public double balance;

        @SerializedName("gold_debit")
        public double goldDebit;

        @SerializedName("gold_credit")
        public double goldCredit;

        @SerializedName("gold_balance")
        public double goldBalance;
    }

    public static class LedgerRowDto {
        @SerializedName("entry_id")
        public long entryId;

        @SerializedName("entry_number")
        public String entryNumber;

        @SerializedName("entry_date")
        public String entryDate;

        @SerializedName("entry_description")
        public String entryDescription;

        @SerializedName("source_type")
        public String sourceType;

        @SerializedName("reference")
        public String reference;

        @SerializedName("line_id")
        public long lineId;

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

        @SerializedName("party_id")
        public Long partyId;

        @SerializedName("running_balance")
        public double runningBalance;

        @SerializedName("running_gold_balance")
        public double runningGoldBalance;
    }
}
