package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * ✅ DTO لقيد التسوية
 */
public class AdjustingEntryDto implements Serializable {

    @SerializedName("entry_id")
    public long entryId;

    @SerializedName("entry_number")
    public String entryNumber;

    @SerializedName("entry_date")
    public String entryDate;

    @SerializedName("reference")
    public String reference;

    @SerializedName("description")
    public String description;

    @SerializedName("total_debit")
    public double totalDebit;

    @SerializedName("total_credit")
    public double totalCredit;

    @SerializedName("message")
    public String message;
}
