package com.example.uhf.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ InventorySnapshot — لقطة كاملة للمخزون
 *
 * تحتوي على:
 * - البيانات الأساسية (التاريخ، المستخدم)
 * - الإجماليات (العدد، الوزن، القيمة)
 * - توزيع العيارات
 * - قائمة كاملة بـ EPCs
 */
public class InventorySnapshot {

    // ==================== الحقول ====================
    private String id;
    private String name;               // اسم مخصص (اختياري)
    private String sessionId;          // ID الجلسة التي أنشأت اللقطة
    private long timestamp;            // وقت الإنشاء
    private String createdBy;          // المستخدم

    private int totalItems;
    private double totalWeight;
    private double totalValue;
    private int expectedCount;         // المتوقع في تلك اللحظة

    private String branchName;
    private String mode;               // full / category / blind

    // ✅ توزيع العيارات
    private int count24K;
    private int count22K;
    private int count21K;
    private int count18K;
    private int count14K;
    private int countOther;

    private double weight24K;
    private double weight22K;
    private double weight21K;
    private double weight18K;
    private double weight14K;
    private double weightOther;

    // ✅ قائمة الـ EPCs
    private JSONArray epcs;            // ["EPC1", "EPC2", ...]

    // ✅ للتحسينات المستقبلية
    private JSONArray items;           // تفاصيل كل قطعة (اختياري)
    private String notes;

    // ==================== Constructor ====================

    public InventorySnapshot() {
        this.id = "SNAP-" + System.currentTimeMillis();
        this.timestamp = System.currentTimeMillis();
        this.epcs = new JSONArray();
        this.items = new JSONArray();
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }

    public double getTotalWeight() { return totalWeight; }
    public void setTotalWeight(double totalWeight) { this.totalWeight = totalWeight; }

    public double getTotalValue() { return totalValue; }
    public void setTotalValue(double totalValue) { this.totalValue = totalValue; }

    public int getExpectedCount() { return expectedCount; }
    public void setExpectedCount(int expectedCount) { this.expectedCount = expectedCount; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public int getCount24K() { return count24K; }
    public void setCount24K(int count24K) { this.count24K = count24K; }

    public int getCount22K() { return count22K; }
    public void setCount22K(int count22K) { this.count22K = count22K; }

    public int getCount21K() { return count21K; }
    public void setCount21K(int count21K) { this.count21K = count21K; }

    public int getCount18K() { return count18K; }
    public void setCount18K(int count18K) { this.count18K = count18K; }

    public int getCount14K() { return count14K; }
    public void setCount14K(int count14K) { this.count14K = count14K; }

    public int getCountOther() { return countOther; }
    public void setCountOther(int countOther) { this.countOther = countOther; }

    public double getWeight24K() { return weight24K; }
    public void setWeight24K(double weight24K) { this.weight24K = weight24K; }

    public double getWeight22K() { return weight22K; }
    public void setWeight22K(double weight22K) { this.weight22K = weight22K; }

    public double getWeight21K() { return weight21K; }
    public void setWeight21K(double weight21K) { this.weight21K = weight21K; }

    public double getWeight18K() { return weight18K; }
    public void setWeight18K(double weight18K) { this.weight18K = weight18K; }

    public double getWeight14K() { return weight14K; }
    public void setWeight14K(double weight14K) { this.weight14K = weight14K; }

    public double getWeightOther() { return weightOther; }
    public void setWeightOther(double weightOther) { this.weightOther = weightOther; }

    public JSONArray getEpcs() { return epcs != null ? epcs : new JSONArray(); }
    public void setEpcs(JSONArray epcs) { this.epcs = epcs; }

    public JSONArray getItems() { return items != null ? items : new JSONArray(); }
    public void setItems(JSONArray items) { this.items = items; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    // ==================== Helpers ====================

    /**
     * ✅ تنسيق التاريخ للعرض
     */
    public String getFormattedDate() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * ✅ تنسيق مختصر
     */
    public String getShortDate() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * ✅ عرض التاريخ النسبي (منذ...)
     */
    public String getRelativeTime(boolean isArabic) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (seconds < 60) {
            return isArabic ? "الآن" : "Just now";
        } else if (minutes < 60) {
            return isArabic ? "منذ " + minutes + " دقيقة" : minutes + " min ago";
        } else if (hours < 24) {
            return isArabic ? "منذ " + hours + " ساعة" : hours + "h ago";
        } else if (days < 7) {
            return isArabic ? "منذ " + days + " يوم" : days + "d ago";
        } else {
            return getFormattedDate();
        }
    }

    /**
     * ✅ إجمالي العيارات (تحقق)
     */
    public int getTotalFromKarats() {
        return count24K + count22K + count21K + count18K + count14K + countOther;
    }

    /**
     * ✅ إجمالي الوزن (تحقق)
     */
    public double getTotalWeightFromKarats() {
        return weight24K + weight22K + weight21K + weight18K + weight14K + weightOther;
    }

    // ==================== JSON ====================

    public JSONObject toJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name != null ? name : "");
        obj.put("session_id", sessionId != null ? sessionId : "");
        obj.put("timestamp", timestamp);
        obj.put("created_by", createdBy != null ? createdBy : "");
        obj.put("total_items", totalItems);
        obj.put("total_weight", totalWeight);
        obj.put("total_value", totalValue);
        obj.put("expected_count", expectedCount);
        obj.put("branch_name", branchName != null ? branchName : "");
        obj.put("mode", mode != null ? mode : "full");

        obj.put("count_24k", count24K);
        obj.put("count_22k", count22K);
        obj.put("count_21k", count21K);
        obj.put("count_18k", count18K);
        obj.put("count_14k", count14K);
        obj.put("count_other", countOther);

        obj.put("weight_24k", weight24K);
        obj.put("weight_22k", weight22K);
        obj.put("weight_21k", weight21K);
        obj.put("weight_18k", weight18K);
        obj.put("weight_14k", weight14K);
        obj.put("weight_other", weightOther);

        obj.put("epcs", epcs != null ? epcs : new JSONArray());
        obj.put("items", items != null ? items : new JSONArray());
        obj.put("notes", notes != null ? notes : "");

        return obj;
    }

    public static InventorySnapshot fromJson(JSONObject obj) {
        if (obj == null) return null;

        try {
            InventorySnapshot snapshot = new InventorySnapshot();
            snapshot.id = obj.optString("id", "SNAP-unknown");
            snapshot.name = obj.optString("name", "");
            snapshot.sessionId = obj.optString("session_id", "");
            snapshot.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
            snapshot.createdBy = obj.optString("created_by", "");
            snapshot.totalItems = obj.optInt("total_items", 0);
            snapshot.totalWeight = obj.optDouble("total_weight", 0);
            snapshot.totalValue = obj.optDouble("total_value", 0);
            snapshot.expectedCount = obj.optInt("expected_count", 0);
            snapshot.branchName = obj.optString("branch_name", "");
            snapshot.mode = obj.optString("mode", "full");

            snapshot.count24K = obj.optInt("count_24k", 0);
            snapshot.count22K = obj.optInt("count_22k", 0);
            snapshot.count21K = obj.optInt("count_21k", 0);
            snapshot.count18K = obj.optInt("count_18k", 0);
            snapshot.count14K = obj.optInt("count_14k", 0);
            snapshot.countOther = obj.optInt("count_other", 0);

            snapshot.weight24K = obj.optDouble("weight_24k", 0);
            snapshot.weight22K = obj.optDouble("weight_22k", 0);
            snapshot.weight21K = obj.optDouble("weight_21k", 0);
            snapshot.weight18K = obj.optDouble("weight_18k", 0);
            snapshot.weight14K = obj.optDouble("weight_14k", 0);
            snapshot.weightOther = obj.optDouble("weight_other", 0);

            snapshot.epcs = obj.optJSONArray("epcs");
            if (snapshot.epcs == null) snapshot.epcs = new JSONArray();
            
            snapshot.items = obj.optJSONArray("items");
            if (snapshot.items == null) snapshot.items = new JSONArray();

            snapshot.notes = obj.optString("notes", "");

            return snapshot;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "InventorySnapshot{" +
                "id='" + id + '\'' +
                ", items=" + totalItems +
                ", weight=" + totalWeight +
                ", value=" + totalValue +
                '}';
    }
}
