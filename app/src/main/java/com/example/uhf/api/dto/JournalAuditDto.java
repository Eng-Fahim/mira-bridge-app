package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * سجل تدقيق لقيد
 */
public class JournalAuditDto implements Serializable {

    @SerializedName("id")
    public long id;

    @SerializedName("entry_id")
    public long entryId;

    @SerializedName("user_id")
    public Long userId;

    @SerializedName("user_name")
    public String userName;

    @SerializedName("action")
    public String action;   // created, posted, reversed, cancelled...

    @SerializedName("old_status")
    public String oldStatus;

    @SerializedName("new_status")
    public String newStatus;

    @SerializedName("reason")
    public String reason;

    @SerializedName("created_at")
    public String createdAt;

    public String getActionLabelAr() {
        if (action == null) return "—";
        switch (action.toLowerCase()) {
            case "created":  return "إنشاء";
            case "updated":  return "تعديل";
            case "posted":   return "ترحيل";
            case "reversed": return "عكس";
            case "cancelled":return "إلغاء";
            case "approved": return "اعتماد";
            default:         return action;
        }
    }
}
