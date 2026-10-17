package com.example.uhf.manager;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PendingSale — نموذج بيانات بيع Offline في الانتظار
 * ═══════════════════════════════════════════════════════════════════
 * 
 * يُخزَّن في SQLite محلياً عند فشل الشبكة.
 * يُعاد إرساله تلقائياً عند عودة الاتصال.
 * 
 * @since v1.0
 */
public class PendingSale {

    // ═══════════════════════════════════════════════════════════════
    //  الحالات
    // ═══════════════════════════════════════════════════════════════
    public static final int STATUS_PENDING = 0;   // في الانتظار
    public static final int STATUS_SENT    = 1;   // أُرسل بنجاح
    public static final int STATUS_FAILED  = 2;   // فشل نهائي (تجاوز الحد)

    // ═══════════════════════════════════════════════════════════════
    //  الحقول
    // ═══════════════════════════════════════════════════════════════
    public long   id;
    public String localUuid;       // ← UNIQUE — مفتاح Idempotency
    public String payloadJson;     // ← كامل payload البيع (JSON)
    public int    branchId;
    public int    userId;
    public double totalUsd;
    public int    itemCount;
    public String customerName;
    public long   createdAt;
    public int    retryCount;
    public long   lastAttemptAt;
    public String lastError;
    public int    status;

    public PendingSale() {}

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    public boolean isPending() {
        return status == STATUS_PENDING;
    }

    public boolean hasReachedMaxRetries(int maxRetries) {
        return retryCount >= maxRetries;
    }

    /** الوقت المنسّق للعرض */
    public String getFormattedTime() {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                            java.util.Locale.US);
            return sdf.format(new java.util.Date(createdAt));
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "PendingSale{" +
                "uuid='" + localUuid + '\'' +
                ", total=" + totalUsd +
                ", items=" + itemCount +
                ", retries=" + retryCount +
                ", status=" + status +
                '}';
    }
}
