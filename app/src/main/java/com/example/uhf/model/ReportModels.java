package com.example.uhf.models;


import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
/**
 * ✅ نماذج البيانات الموحدة لنظام التقارير
 */
public class ReportModels {
    
    // ==================== نموذج التقرير الأساسي ====================
    public static class ReportItem {
        public String icon;
        public String title;
        public String subtitle;
        public String type;
        public ReportCategory category;
        public boolean isAdvanced;
        public int color;
        
        public ReportItem(String icon, String title, String subtitle, String type) {
            this(icon, title, subtitle, type, ReportCategory.GENERAL, false, 0);
        }
        
        public ReportItem(String icon, String title, String subtitle, String type, 
                         ReportCategory category, boolean isAdvanced, int color) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.type = type;
            this.category = category;
            this.isAdvanced = isAdvanced;
            this.color = color;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ReportItem that = (ReportItem) obj;
            return type != null && type.equals(that.type);
        }
        
        @Override
        public int hashCode() {
            return type != null ? type.hashCode() : 0;
        }
    }
    
    // ==================== فئات التقارير ====================
    public enum ReportCategory {
        GENERAL("عام", "General"),
        SALES("المبيعات", "Sales"),
        INVENTORY("المخزون", "Inventory"),
        MOVEMENT("الحركة", "Movement"),
        SECURITY("الأمان", "Security"),
        FINANCIAL("المالية", "Financial"),
        ANALYTICS("التحليلات", "Analytics");
        
        private final String arabicName;
        private final String englishName;
        
        ReportCategory(String arabicName, String englishName) {
            this.arabicName = arabicName;
            this.englishName = englishName;
        }
        
        public String getName(boolean isArabic) {
            return isArabic ? arabicName : englishName;
        }
    }
    
    // ==================== نموذج الفلاتر ====================
    public static class ReportFilters {
        public String fromDate;
        public String toDate;
        public String searchQuery;
        public String filter1;
        public String filter2;
        public String filter3;
        public int branchId;
        public int userId;
        public int gateId;
        public String serialNumber;
        public String gtin13;
        public String sortBy;
        public String sortOrder;
        public int page;
        public int pageSize;
        
        public ReportFilters() {
            this.page = 1;
            this.pageSize = 50;
            this.sortOrder = "DESC";
            this.branchId = 1;
        }
        
        public ReportFilters(ReportFilters other) {
            this.fromDate = other.fromDate;
            this.toDate = other.toDate;
            this.searchQuery = other.searchQuery;
            this.filter1 = other.filter1;
            this.filter2 = other.filter2;
            this.filter3 = other.filter3;
            this.branchId = other.branchId;
            this.userId = other.userId;
            this.gateId = other.gateId;
            this.serialNumber = other.serialNumber;
            this.gtin13 = other.gtin13;
            this.sortBy = other.sortBy;
            this.sortOrder = other.sortOrder;
            this.page = other.page;
            this.pageSize = other.pageSize;
        }
        
        public String toQueryString() {
            StringBuilder sb = new StringBuilder();
            
            if (fromDate != null && !fromDate.isEmpty()) 
                sb.append("&from=").append(fromDate);
            if (toDate != null && !toDate.isEmpty()) 
                sb.append("&to=").append(toDate);
            if (searchQuery != null && !searchQuery.isEmpty()) 
                sb.append("&search=").append(searchQuery);
            if (filter1 != null && !filter1.isEmpty()) 
                sb.append("&filter1=").append(filter1);
            if (filter2 != null && !filter2.isEmpty()) 
                sb.append("&filter2=").append(filter2);
            if (filter3 != null && !filter3.isEmpty()) 
                sb.append("&filter3=").append(filter3);
            if (branchId > 0) 
                sb.append("&branch_id=").append(branchId);
            if (userId > 0) 
                sb.append("&user_id=").append(userId);
            if (gateId > 0) 
                sb.append("&gate_id=").append(gateId);
            if (serialNumber != null && !serialNumber.isEmpty()) 
                sb.append("&serial_number=").append(serialNumber);
            if (gtin13 != null && !gtin13.isEmpty()) 
                sb.append("&gtin13=").append(gtin13);
            if (sortBy != null && !sortBy.isEmpty()) 
                sb.append("&sort_by=").append(sortBy);
            if (sortOrder != null && !sortOrder.isEmpty()) 
                sb.append("&sort_order=").append(sortOrder);
            if (page > 0) 
                sb.append("&page=").append(page);
            if (pageSize > 0) 
                sb.append("&page_size=").append(pageSize);
            
            return sb.length() > 0 ? sb.substring(1) : "";
        }
    }
    
    // ==================== نموذج نتيجة التقرير ====================
    public static class ReportResult {
        public boolean success;
        public String errorMessage;
        public JSONObject data;
        public String rawData;
        public long timestamp;
        public String cacheKey;
        public boolean fromCache;
        
        public ReportResult(boolean success, JSONObject data) {
            this.success = success;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
            this.fromCache = false;
        }
        
        public static ReportResult success(JSONObject data) {
            return new ReportResult(true, data);
        }
        
        public static ReportResult error(String message) {
            ReportResult result = new ReportResult(false, null);
            result.errorMessage = message;
            return result;
        }
        
        public static ReportResult fromCache(JSONObject data, String cacheKey) {
            ReportResult result = new ReportResult(true, data);
            result.fromCache = true;
            result.cacheKey = cacheKey;
            return result;
        }
    }
    
    // ==================== عناصر البيانات ====================
    public static class GenericItem {
        public String key;
        public String value;
        public String icon;
        public int color;
        public boolean isHighlighted;
        
        public GenericItem(String key, String value) {
            this(key, value, null, 0, false);
        }
        
        public GenericItem(String key, String value, String icon, int color, boolean isHighlighted) {
            this.key = key;
            this.value = value;
            this.icon = icon;
            this.color = color;
            this.isHighlighted = isHighlighted;
        }
    }
    
    // ==================== 1. نموذج مكافئ الذهب الصافي ====================
    public static class GoldEquivalentsData {
        public double pureGold24k;
        public double standardGold21k;
        public List<KaratItem> breakdown = new ArrayList<>();

        public static class KaratItem {
            public String karat;
            public int count;
            public double grossWeight;
            public double eq24k;
        }

        public static GoldEquivalentsData parse(JSONObject json) {
            GoldEquivalentsData result = new GoldEquivalentsData();
            if (json == null) return result;

            JSONObject summary = json.optJSONObject("summary");
            if (summary != null) {
                result.pureGold24k = summary.optDouble("total_pure_gold_24k", 0.0);
                result.standardGold21k = summary.optDouble("total_standard_21k", 0.0);
            }

            JSONArray array = json.optJSONArray("breakdown_by_karat");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj != null) {
                        KaratItem item = new KaratItem();
                        item.karat = obj.optString("gold_karat", "N/A");
                        item.count = obj.optInt("items_count", 0);
                        item.grossWeight = obj.optDouble("gross_weight", 0.0);
                        item.eq24k = obj.optDouble("weight_24k_eq", 0.0);
                        result.breakdown.add(item);
                    }
                }
            }
            return result;
        }
    }

    // ==================== 2. محول تقرير أداء البائعين ====================
    public static List<SalesItem> parseSellerPerformance(JSONArray array) {
        List<SalesItem> list = new ArrayList<>();
        if (array == null) return list;

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) {
                String sellerName = obj.optString("seller_name", "غير معروف");
                int invoices = obj.optInt("total_invoices", 0);
                double totalSales = obj.optDouble("total_sales_amount", 0.0);

                SalesItem item = new SalesItem(
                        "فواتير: " + invoices,
                        "بائع",
                        totalSales,
                        "نقدي/شبكة",
                        sellerName
                );
                list.add(item);
            }
        }
        return list;
    }

    // ==================== 3. محول القطع المفقودة بالجرد ====================
    public static List<InventoryItem> parseMissingItems(JSONObject json) {
        List<InventoryItem> list = new ArrayList<>();
        if (json == null) return list;

        JSONArray array = json.optJSONArray("items");
        if (array == null) return list;

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) {
                InventoryItem item = new InventoryItem(
                        obj.optString("item_title", "قطعة مفقودة"),
                        obj.optString("gold_karat", "21K"),
                        obj.optDouble("gross_weight_grams", 0.0),
                        "مفقود بالجرد",
                        obj.optString("current_location", "الفرع")
                );
                item.serialNumber = obj.optString("serial_number", "");
                list.add(item);
            }
        }
        return list;
    }
}

    
    public static class SalesItem {
        public String invoiceNo;
        public String customer;
        public double amount;
        public String paymentMethod;
        public String seller;
        public String date;
        public String status;
        
        public SalesItem(String invoiceNo, String customer, double amount, 
                        String paymentMethod, String seller) {
            this.invoiceNo = invoiceNo;
            this.customer = customer;
            this.amount = amount;
            this.paymentMethod = paymentMethod;
            this.seller = seller;
        }
    }
    
    public static class InventoryItem {
        public String title;
        public String karat;
        public double weight;
        public String status;
        public String location;
        public String serialNumber;
        public double price;
        
        public InventoryItem(String title, String karat, double weight, 
                            String status, String location) {
            this.title = title;
            this.karat = karat;
            this.weight = weight;
            this.status = status;
            this.location = location;
        }
    }
    
    public static class ChartData {
        public String label;
        public float value;
        public int color;
        
        public ChartData(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }
    
    public static class UserInfo {
        public int id;
        public String name;
        public String username;
        public String role;
        public String status;
        
        public UserInfo(int id, String name, String username, String role) {
            this.id = id;
            this.name = name;
            this.username = username;
            this.role = role;
        }
    }
    
    public static class GateInfo {
        public int id;
        public String gateId;
        public String name;
        public String location;
        public String status;
        
        public GateInfo(int id, String gateId, String name, String location) {
            this.id = id;
            this.gateId = gateId;
            this.name = name;
            this.location = location;
        }
    }
                    
