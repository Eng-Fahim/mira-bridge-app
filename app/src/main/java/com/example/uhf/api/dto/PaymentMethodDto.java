package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class PaymentMethodDto {

    @SerializedName("id")
    public long id;

    @SerializedName("code")
    public String code;

    @SerializedName("name_ar")
    public String nameAr;

    @SerializedName("name_en")
    public String nameEn;

    @SerializedName("icon")
    public String icon;

    @SerializedName("account_id")
    public long accountId;

    @SerializedName("account_code")
    public String accountCode;

    @SerializedName("account_name_ar")
    public String accountNameAr;

    @SerializedName("account_name")
    public String accountName;

    @SerializedName("requires_account")
    public boolean requiresAccount;

    @SerializedName("sort_order")
    public int sortOrder;

    public String getDisplayName(boolean isArabic) {
        String name = isArabic ? nameAr : nameEn;
        if (name == null || name.isEmpty()) {
            name = nameAr != null ? nameAr : (code != null ? code : "");
        }
        if (icon != null && !icon.isEmpty()) {
            return icon + " " + name;
        }
        return name;
    }

    public String getAccountDisplay(boolean isArabic) {
        String accName = isArabic ? accountNameAr : accountName;
        if (accName == null || accName.isEmpty()) {
            accName = accountNameAr != null ? accountNameAr : "";
        }
        if (accountCode == null || accountCode.isEmpty()) {
            return accName;
        }
        if (accName.isEmpty()) {
            return (isArabic ? "الحساب: " : "Account: ") + accountCode;
        }
        return accName + ": " + accountCode;
    }

    public boolean hasValidAccount() {
        return accountId > 0
                || (accountCode != null && !accountCode.isEmpty());
    }
}
