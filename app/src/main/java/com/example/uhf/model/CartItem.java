package com.example.uhf.model;

import org.json.JSONObject;

/**
 * CartItem v2.0
 * - حقول إضافية للمزامنة الكاملة
 * - Helper methods
 */
public class CartItem {

    // ==================== Basic Fields ====================
    public String epc, name, karat, status, serialNo, gtin, location;
    public double weight, scaleWeight, price;
    public boolean isAvailable;
    public int itemId;

    // ==================== Extended Fields ====================
    public String tid = "";              // RFID TID
    public String rfidEpc = "";          // EPC الفعلي من RFID
    public String category = "";         // الفئة
    public String stoneType = "";        // نوع الحجر
    public double stoneWeight = 0;       // وزن الحجر
    public int stoneCount = 0;           // عدد الأحجار
    public double goldValue = 0;         // قيمة الذهب
    public double makingCharge = 0;      // الصياغة
    public double profitMargin = 0;      // هامش الربح

    // ==================== Audit Fields ====================
    public long addedAt = System.currentTimeMillis();
    public String addedBy = "";          // اسم المستخدم المُضيف

    // ==================== Constructors ====================

    public CartItem(String epc, String name, String karat, double weight,
                    double scaleWeight, double price, boolean isAvailable, String status) {
        this.epc = epc;
        this.name = name;
        this.karat = karat;
        this.weight = weight;
        this.scaleWeight = scaleWeight;
        this.price = price;
        this.isAvailable = isAvailable;
        this.status = status;
    }

    // ==================== Helper Methods ====================

    /**
     * ✅ الحصول على الوزن الفعلي (scaleWeight إذا موجود، وإلا weight)
     */
    public double getEffectiveWeight() {
        return scaleWeight > 0 ? scaleWeight : weight;
    }

    /**
     * ✅ بناء JSON كامل للإرسال
     */
    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("epc", safe(epc));
            obj.put("serial_no", safe(serialNo));
            obj.put("gtin", safe(gtin));
            obj.put("tid", safe(tid));
            obj.put("rfid_epc", safe(rfidEpc));
            obj.put("title", safe(name));
            obj.put("karat", safe(karat));
            obj.put("category", safe(category));

            double finalWeight = getEffectiveWeight();
            obj.put("weight", finalWeight);
            obj.put("gross_weight", finalWeight);

            obj.put("price_usd", price);
            obj.put("item_id", itemId);
            obj.put("location", safe(location));
            obj.put("status", safe(status));

            obj.put("stone_type", safe(stoneType));
            obj.put("stone_weight", stoneWeight);
            obj.put("stone_count", stoneCount);
            obj.put("gold_value", goldValue);
            obj.put("making_charge", makingCharge);
            obj.put("profit_margin", profitMargin);

            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * ✅ استخراج Identifier موحّد (EPC أو Serial أو GTIN)
     */
    public String getPrimaryIdentifier() {
        if (epc != null && !epc.isEmpty()) return epc;
        if (serialNo != null && !serialNo.isEmpty()) return serialNo;
        if (gtin != null && !gtin.isEmpty()) return gtin;
        return "";
    }

    /**
     * ✅ كل الـ Identifiers
     */
    public String[] getAllIdentifiers() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (epc != null && !epc.isEmpty()) ids.add(epc);
        if (serialNo != null && !serialNo.isEmpty()) ids.add(serialNo);
        if (gtin != null && !gtin.isEmpty()) ids.add(gtin);
        if (tid != null && !tid.isEmpty()) ids.add(tid);
        if (rfidEpc != null && !rfidEpc.isEmpty()) ids.add(rfidEpc);
        return ids.toArray(new String[0]);
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    @Override
    public String toString() {
        return "CartItem{" +
                "epc='" + epc + '\'' +
                ", serialNo='" + serialNo + '\'' +
                ", gtin='" + gtin + '\'' +
                ", tid='" + tid + '\'' +
                ", name='" + name + '\'' +
                ", price=" + price +
                '}';
    }
}
