package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class AccountDto implements Serializable {

    // ==================== Fields ====================

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
    
    @SerializedName("is_leaf")
    public Boolean isLeafDb;
    
    public void setLeafDb(Boolean leafDb) { isLeafDb = leafDb; }

    @SerializedName("children")
    public List<AccountDto> children;

    /**
     * ✅ accountName — اسم مختصر (يُملأ من API أو من MiraAccountTreeFragment)
     */
    @SerializedName("account_name")
    public String accountName;

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
    public String getAccountName() { return accountName; }

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
    public void setAccountName(String accountName) { this.accountName = accountName; }

    // ==================== Helpers ====================

    /**
    * ✅ محدّثة — تعتمد على is_leaf من DB أولاً
    */
    public boolean isLeaf() {
    // 1. أفضل مصدر: is_leaf من DB
    if (isLeafDb != null) return isLeafDb;

    // 2. ثانياً: children من /accounts/tree
    if (children != null && !children.isEmpty()) return false;

    // 3. fallback: level >= 3 (عادةً leaf)
    return level >= 3;
    }

    /**
     * ✅ هل الحساب له أبناء
     */
    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    /**
     * ✅ هل الحساب ذهبي
     */
    public boolean isGold() {
        if (accountCategory != null
                && accountCategory.toLowerCase().contains("gold")) {
            return true;
        }
        if (accountNameAr != null
                && (accountNameAr.contains("ذهب") || accountNameAr.contains("الذهب"))) {
            return true;
        }
        return false;
    }

    /**
     * ✅ التسمية العربية لنوع الحساب
     */
    public String getTypeLabelAr() {
        if (accountType == null) return "—";
        switch (accountType.toLowerCase()) {
            case "asset":     return "أصول";
            case "liability": return "التزامات";
            case "equity":    return "حقوق ملكية";
            case "revenue":   return "إيرادات";
            case "expense":   return "مصروفات";
            case "cash":      return "نقدية";
            case "bank":      return "بنكية";
            default:          return accountType;
        }
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
