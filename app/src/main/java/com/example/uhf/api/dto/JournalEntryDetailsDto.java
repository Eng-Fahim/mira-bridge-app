package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * الغلاف الذي يعيده endpoint /journal/{id}
 * 
 * PHP return:
 * {
 *   "success": true,
 *   "entry": {...},
 *   "lines": [...],
 *   "audit": [...]
 * }
 */
public class JournalEntryDetailsDto implements Serializable {

    @SerializedName("success")
    public boolean success;

    @SerializedName("entry")
    public JournalEntryDto entry;

    @SerializedName("lines")
    public JournalLineDto[] lines;

    @SerializedName("audit")
    public JournalAuditDto[] audit;

    @SerializedName("message")
    public String message;

    @SerializedName("error")
    public String error;
}
