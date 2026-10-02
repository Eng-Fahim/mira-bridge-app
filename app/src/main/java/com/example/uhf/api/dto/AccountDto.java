package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class AccountDto implements Serializable {

    // ==================== Fields ====================
    // ✅ public للتوافق مع MiraCreateJournalFragment
    
    @SerializedName("id")
    public int id;

    @SerializedName("account_code")
    public String accountCode;

    @SerializedName("account_name_ar")
    public String accountNameAr;

    @SerializedName("account_name_en")
    public String accountNameEn;

    @SerializedName("account_type")
    public String accountType;

    @SerializedName("account_category")
    public String accountCategory;

    @SerializedName("nature")
    public String nature;

    @SerializedName("parent_id")
    public int parentId;

    @SerializedName("level")
    public int level;

    @SerializedName("currency")
    public String currency;

    @SerializedName("opening_balance")
    public double openingBalance;

    @SerializedName("current_balance")
    public double currentBalance;

    @SerializedName("gold_balance")
    public double goldBalance;

    @SerializedName("is_active")
    public boolean isActive;

    @SerializedName("is_cash_account")
    public boolean isCashAccount;

    @SerializedName("is_bank_account")
    public boolean isBankAccount;

    @SerializedName("status")
    public String status;

    @SerializedName("children")
    public List<AccountDto> children;

    // ==================== Constructor ====================

    public AccountDto() {
        this.currency = "YER";
        this.status = "active";
        this.children = new ArrayList<>();
    }

    // ==================== Getters ====================

    public int getId() { return id; }
    public String getAccountCode() { return accountCode; }
    public String getAccountNameAr() { return accountNameAr; }
    public String getAccountNameEn() { return accountNameEn; }
    public String getAccountType() { return accountType; }
    public String getAccountCategory() { return accountCategory; }
    public String getNature() { return nature; }
    public int getParentId() { return parentId; }
    public int getLevel() { return level; }
    public String getCurrency() { return currency; }
    public double getOpeningBalance() { return openingBalance; }
    public double getCurrentBalance() { return currentBalance; }
    public double getGoldBalance() { return goldBalance; }
    public boolean isActive() { return isActive; }
    public boolean isCashAccount() { return isCashAccount; }
    public boolean isBankAccount() { return isBankAccount; }
    public String getStatus() { return status; }
    public List<AccountDto> getChildren() { return children; }

    // ==================== Setters ====================

    public void setId(int id) { this.id = id; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }
    public void setAccountNameAr(String accountNameAr) { this.accountNameAr = accountNameAr; }
    public void setAccountNameEn(String accountNameEn) { this.accountNameEn = accountNameEn; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public void setAccountCategory(String accountCategory) { this.accountCategory = accountCategory; }
    public void setNature(String nature) { this.nature = nature; }
    public void setParentId(int parentId) { this.parentId = parentId; }
    public void setLevel(int level) { this.level = level; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setOpeningBalance(double openingBalance) { this.openingBalance = openingBalance; }
    public void setCurrentBalance(double currentBalance) { this.currentBalance = currentBalance; }
    public void setGoldBalance(double goldBalance) { this.goldBalance = goldBalance; }
    public void setActive(boolean active) { isActive = active; }
    public void setCashAccount(boolean cashAccount) { isCashAccount = cashAccount; }
    public void setBankAccount(boolean bankAccount) { isBankAccount = bankAccount; }
    public void setStatus(String status) { this.status = status; }
    public void setChildren(List<AccountDto> children) { this.children = children; }

    // ==================== Helpers ====================

    /**
     * ✅ هل الحساب ورقة (بدون أبناء) — قابل للاستخدام في القيود
     * هذا هو الحل لخطأ: cannot find symbol method isLeaf()
     */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    /**
     * ✅ هل الحساب له أبناء
     */
    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    public String getDisplayName() {
        if (accountCode == null) return accountNameAr != null ? accountNameAr : "";
        return accountCode + " - " + (accountNameAr != null ? accountNameAr : "");
    }

    public boolean isDebitNature() {
        return "debit".equalsIgnoreCase(nature);
    }

    public boolean isCreditNature() {
        return "credit".equalsIgnoreCase(nature);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountDto that = (AccountDto) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
