package com.example.uhf.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

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
        
        // ✅✅✅ هذا هو الـ constructor المطلوب
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
        
        public GenericItem(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
    
    public static class SalesItem {
        public String invoiceNo;
        public String customer;
        public double amount;
        public String paymentMethod;
        public String seller;
        
        public SalesItem(String invoiceNo, String customer, double amount, 
                        String paymentMethod, String seller) {
            this.invoiceNo = invoiceNo;
            this.customer = customer;
            this.amount = amount;
            this.paymentMethod = paymentMethod;
            this.seller = seller;
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
        
        public GateInfo(int id, String gateId, String name, String location) {
            this.id = id;
            this.gateId = gateId;
            this.name = name;
            this.location = location;
        }
    }
}
