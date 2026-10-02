package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class AccountDto implements Serializable {

    @SerializedName("id")
    private int id;

    @SerializedName("account_code")
    private String accountCode;

    @SerializedName("account_name_ar")
    private String accountNameAr;

    @SerializedName("account_name_en")
    private String accountNameEn;

    @SerializedName("account_type")
    private String accountType;

    @SerializedName("account_category")
    private String accountCategory;

    @SerializedName("nature")
    private String nature;

    @SerializedName("parent_id")
    private int parentId;

    @SerializedName("level")
    private int level;

    @SerializedName("currency")
    private String currency;

    @SerializedName("opening_balance")
    private double openingBalance;

    @SerializedName("current_balance")
    private double currentBalance;

    @SerializedName("gold_balance")
    private double goldBalance;

    @SerializedName("is_active")
    private boolean isActive;

    @SerializedName("is_cash_account")
    private boolean isCashAccount;

    @SerializedName("is_bank_account")
    private boolean isBankAccount;

    @SerializedName("status")
    private String status;

    @SerializedName("children")
    private List<AccountDto> children;

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

    public String getDisplayName() {
        if (accountCode == null) return accountNameAr != null ? accountNameAr : "";
        return accountCode + " - " + (accountNameAr != null ? accountNameAr : "");
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
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
