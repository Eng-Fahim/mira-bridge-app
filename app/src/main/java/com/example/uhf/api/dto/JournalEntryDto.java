package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;
import java.util.List;   // ✅ مضاف

/**
 * نموذج القيد المحاسبي — يطابق جدول wp_mira_jewelry_journal_entries
 */
public class JournalEntryDto implements Serializable {

    @SerializedName("id")
    public long id;

    @SerializedName("entry_number")
    public String entryNumber;

    @SerializedName("branch_id")
    public Long branchId;

    @SerializedName("entry_date")
    public String entryDate;

    @SerializedName("reference")
    public String reference;

    @SerializedName("source_type")
    public String sourceType;   // pos_sale, purchase, manual...

    @SerializedName("source_id")
    public Long sourceId;

    @SerializedName("voucher_id")
    public Long voucherId;

    @SerializedName("description")
    public String description;

    @SerializedName("notes")
    public String notes;

    @SerializedName("attachment_url")
    public String attachmentUrl;

    @SerializedName("total_debit")
    public double totalDebit;

    @SerializedName("total_credit")
    public double totalCredit;

    @SerializedName("total_debit_base")
    public double totalDebitBase;

    @SerializedName("total_credit_base")
    public double totalCreditBase;

    @SerializedName("total_gold_debit_grams")
    public double totalGoldDebitGrams;

    @SerializedName("total_gold_credit_grams")
    public double totalGoldCreditGrams;

    @SerializedName("currency")
    public String currency;

    @SerializedName("base_currency")
    public String baseCurrency;

    @SerializedName("exchange_rate")
    public double exchangeRate;

    @SerializedName("status")
    public String status;   // draft, pending_approval, posted, reversed, cancelled

    @SerializedName("created_by")
    public Long createdBy;

    @SerializedName("approved_by")
    public Long approvedBy;

    @SerializedName("approved_at")
    public String approvedAt;

    @SerializedName("posted_by")
    public Long postedBy;

    @SerializedName("posted_at")
    public String postedAt;

    @SerializedName("reversed_by")
    public Long reversedBy;

    @SerializedName("reversed_at")
    public String reversedAt;

    @SerializedName("reversal_entry_id")
    public Long reversalEntryId;

    @SerializedName("is_locked")
    public boolean isLocked;

    @SerializedName("lock_reason")
    public String lockReason;

    @SerializedName("created_at")
    public String createdAt;

    @SerializedName("updated_at")
    public String updatedAt;

    // ═══ سطور القيد (تُملأ عند استدعاء /journal/{id}) ═══
    @SerializedName("lines")
    public List<JournalLineDto> lines;

    // ==================== Helpers ====================

    /**
     * ✅ صافي الذهب (مدين - دائن) بالجرامات
     */
    public double getNetGold() {
        return totalGoldDebitGrams - totalGoldCreditGrams;
    }

    public boolean isBalanced() {
        return Math.abs(totalDebit - totalCredit) < 0.01;
    }

    public boolean isPosted() {
        return "posted".equalsIgnoreCase(status);
    }

    public boolean isDraft() {
        return "draft".equalsIgnoreCase(status);
    }

    public boolean isReversed() {
        return "reversed".equalsIgnoreCase(status);
    }

    public boolean isCancelled() {
        return "cancelled".equalsIgnoreCase(status);
    }

    public String getStatusLabelAr() {
        if (status == null) return "—";
        switch (status.toLowerCase()) {
            case "draft":            return "مسودة";
            case "pending_approval": return "بانتظار الاعتماد";
            case "posted":           return "مُرحَّل";
            case "reversed":         return "معكوس";
            case "cancelled":        return "ملغي";
            default:                 return status;
        }
    }

    public int getStatusColor() {
        if (status == null) return 0xFF7F8C8D;
        switch (status.toLowerCase()) {
            case "posted":           return 0xFF27AE60;   // أخضر
            case "pending_approval": return 0xFFF39C12;   // برتقالي
            case "reversed":         return 0xFFE74C3C;   // أحمر
            case "cancelled":        return 0xFF95A5A6;   // رمادي
            default:                 return 0xFF7F8C8D;   // رمادي فاتح (مسودة)
        }
    }

    public String getSourceTypeLabelAr() {
        if (sourceType == null) return "يدوي";
        switch (sourceType.toLowerCase()) {
            case "manual":         return "يدوي";
            case "pos_sale":       return "مبيعات POS";
            case "purchase":       return "مشتريات";
            case "inventory_adj":  return "تسوية مخزون";
            case "voucher":        return "سند";
            default:               return sourceType;
        }
    }
}
