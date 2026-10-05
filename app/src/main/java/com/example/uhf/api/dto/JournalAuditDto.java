package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * ✅ سجل تدقيق لقيد — مطابق لبنية PHP الفعلية
 */
public class JournalAuditDto implements Serializable {

    @SerializedName("id")
    public long id;

    @SerializedName("entry_id")
    public long entryId;

    @SerializedName("action")
    public String action;   // created, posted, reversed, cancelled...

    @SerializedName("performed_by")
    public Long performedBy;   // ✅ user_id

    @SerializedName("performed_at")
    public String performedAt;  // ✅ تاريخ الإجراء

    @SerializedName("display_name")
    public String displayName;  // ✅ اسم المستخدم

    @SerializedName("ip_address")
    public String ipAddress;

    @SerializedName("device_id")
    public String deviceId;

    @SerializedName("before_json")
    public String beforeJson;

    @SerializedName("after_json")
    public String afterJson;

    @SerializedName("reason")
    public String reason;

    // ═══════════════════════════════════════════════════════
    //  Aliases للتوافق مع الكود القديم
    // ═══════════════════════════════════════════════════════

    public String getUserName() {
        return displayName != null ? displayName : "مستخدم";
    }

    public String getCreatedAt() {
        return performedAt;
    }

    public String getActionLabelAr() {
        if (action == null) return "—";
        switch (action.toLowerCase()) {
            case "created":   return "إنشاء";
            case "updated":   return "تعديل";
            case "posted":    return "ترحيل";
            case "reversed":  return "عكس";
            case "cancelled": return "إلغاء";
            case "approved":  return "اعتماد";
            default:          return action;
        }
    }
}
