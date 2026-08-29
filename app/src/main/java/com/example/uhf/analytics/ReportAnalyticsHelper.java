package com.example.uhf.analytics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ مساعد التحليلات والتلخيصات
 */
public class ReportAnalyticsHelper {
    
    /**
     * ✅ حساب ملخص المبيعات
     */
    public static SalesSummary calculateSalesSummary(JSONArray salesData) {
        SalesSummary summary = new SalesSummary();
        
        if (salesData == null) return summary;
        
        for (int i = 0; i < salesData.length(); i++) {
            JSONObject sale = salesData.optJSONObject(i);
            if (sale == null) continue;
            
            double amount = sale.optDouble("final_amount", 
                sale.optDouble("amount", sale.optDouble("total", 0)));
            
            summary.totalSales += amount;
            summary.invoiceCount++;
            
            String paymentMethod = sale.optString("payment_method", 
                sale.optString("payment", "unknown"));
            
            switch (paymentMethod.toLowerCase()) {
                case "cash":
                    summary.cashSales += amount;
                    summary.cashCount++;
                    break;
                case "card":
                    summary.cardSales += amount;
                    summary.cardCount++;
                    break;
                case "transfer":
                    summary.transferSales += amount;
                    summary.transferCount++;
                    break;
                default:
                    summary.otherSales += amount;
                    summary.otherCount++;
                    break;
            }
        }
        
        summary.averageInvoiceValue = summary.invoiceCount > 0 ? 
            summary.totalSales / summary.invoiceCount : 0;
        
        return summary;
    }
    
    /**
     * ✅ حساب ملخص المخزون
     */
    public static InventorySummary calculateInventorySummary(JSONArray inventoryData) {
        InventorySummary summary = new InventorySummary();
        
        if (inventoryData == null) return summary;
        
        for (int i = 0; i < inventoryData.length(); i++) {
            JSONObject item = inventoryData.optJSONObject(i);
            if (item == null) continue;
            
            summary.totalItems++;
            
            double weight = item.optDouble("gross_weight_grams", 
                item.optDouble("weight", 0));
            summary.totalWeight += weight;
            
            String status = item.optString("vault_status", 
                item.optString("status", "unknown"));
            
            switch (status.toLowerCase()) {
                case "in_stock":
                case "available":
                    summary.availableCount++;
                    break;
                case "sold":
                    summary.soldCount++;
                    break;
                case "displayed":
                    summary.displayedCount++;
                    break;
                default:
                    summary.otherCount++;
                    break;
            }
            
            // تجميع حسب العيار
            String karat = item.optString("gold_karat", item.optString("karat", "unknown"));
            summary.karatCounts.merge(karat, 1, Integer::sum);
        }
        
        return summary;
    }
    
    /**
     * ✅ حساب معدلات الأمان
     */
    public static SecuritySummary calculateSecuritySummary(JSONArray gateData) {
        SecuritySummary summary = new SecuritySummary();
        
        if (gateData == null) return summary;
        
        for (int i = 0; i < gateData.length(); i++) {
            JSONObject gate = gateData.optJSONObject(i);
            if (gate == null) continue;
            
            summary.totalEvents += gate.optInt("total_events", 0);
            summary.alerts += gate.optInt("alerts", gate.optInt("alert_count", 0));
            summary.passed += gate.optInt("passed", gate.optInt("pass_count", 0));
        }
        
        summary.securityRate = summary.totalEvents > 0 ? 
            ((double)(summary.totalEvents - summary.alerts) / summary.totalEvents * 100) : 100.0;
        
        return summary;
    }
    
    // ==================== نماذج الملخصات ====================
    
    public static class SalesSummary {
        public double totalSales = 0;
        public int invoiceCount = 0;
        public double cashSales = 0;
        public double cardSales = 0;
        public double transferSales = 0;
        public double otherSales = 0;
        public int cashCount = 0;
        public int cardCount = 0;
        public int transferCount = 0;
        public int otherCount = 0;
        public double averageInvoiceValue = 0;
        
        public double getCashPercentage() {
            return totalSales > 0 ? (cashSales / totalSales) * 100 : 0;
        }
        
        public double getCardPercentage() {
            return totalSales > 0 ? (cardSales / totalSales) * 100 : 0;
        }
    }
    
    public static class InventorySummary {
        public int totalItems = 0;
        public double totalWeight = 0;
        public int availableCount = 0;
        public int soldCount = 0;
        public int displayedCount = 0;
        public int otherCount = 0;
        public java.util.Map<String, Integer> karatCounts = new java.util.HashMap<>();
        
        public double getAverageWeight() {
            return totalItems > 0 ? totalWeight / totalItems : 0;
        }
    }
    
    public static class SecuritySummary {
        public int totalEvents = 0;
        public int alerts = 0;
        public int passed = 0;
        public double securityRate = 100.0;
        
        public String getSecurityLevel() {
            if (securityRate >= 95) return "ممتاز";
            if (securityRate >= 85) return "جيد";
            if (securityRate >= 70) return "متوسط";
            return "ضعيف";
        }
    }
}
