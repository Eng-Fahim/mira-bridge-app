package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ محولات مخصصة لكل نوع تقرير
 */
public class ReportAdapters {
    
    // ==================== محول المبيعات ====================
    public static class SalesAdapter extends RecyclerView.Adapter<SalesAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        private final String currency;
        
        public SalesAdapter(JSONArray data, boolean isArabic, String currency) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            this.currency = currency != null ? currency : "";
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_sales_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String invoiceNo = item.optString("sale_number", 
                item.optString("invoice_no", item.optString("id", "-")));
            String customer = item.optString("customer_name", 
                item.optString("customer", "-"));
            double amount = item.optDouble("final_amount", 
                item.optDouble("total", item.optDouble("amount", 0)));
            String payment = item.optString("payment_method", 
                item.optString("payment", "-"));
            String seller = item.optString("seller_name", 
                item.optString("seller", "-"));
            String date = item.optString("created_at", 
                item.optString("date", item.optString("sale_date", "")));
            
            h.tvInvoiceNo.setText(isArabic ? "فاتورة: " + invoiceNo : "Invoice: " + invoiceNo);
            h.tvCustomer.setText(isArabic ? "👤 " + customer : "👤 " + customer);
            h.tvAmount.setText(String.format(Locale.US, "%,.2f %s", amount, currency));
            h.tvPayment.setText(getPaymentLabel(payment, isArabic));
            h.tvSeller.setText(isArabic ? "👨‍💼 " + seller : "👨‍💼 " + seller);
            h.tvDate.setText(formatDate(date));
            
            // تلوين حسب طريقة الدفع
            int color = getPaymentColor(payment);
            h.tvPayment.setTextColor(color);
            
            // تلوين المبلغ
            h.tvAmount.setTextColor(Color.parseColor("#1a237e"));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvInvoiceNo, tvCustomer, tvAmount, tvPayment, tvSeller, tvDate;
            
            VH(View v) {
                super(v);
                tvInvoiceNo = v.findViewById(R.id.tvInvoiceNo);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvAmount = v.findViewById(R.id.tvAmount);
                tvPayment = v.findViewById(R.id.tvPayment);
                tvSeller = v.findViewById(R.id.tvSeller);
                tvDate = v.findViewById(R.id.tvDate);
            }
        }
    }
    
    // ==================== محول المخزون ====================
    public static class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        
        public InventoryAdapter(JSONArray data, boolean isArabic) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_inventory_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String title = item.optString("item_title", 
                item.optString("title", item.optString("name", "-")));
            String karat = item.optString("gold_karat", 
                item.optString("karat", "-"));
            double weight = item.optDouble("gross_weight_grams", 
                item.optDouble("weight", 0));
            String status = item.optString("vault_status", 
                item.optString("status", "-"));
            String location = item.optString("current_location", 
                item.optString("location", "-"));
            String serial = item.optString("serial_number", "-");
            
            h.tvTitle.setText(title);
            h.tvKarat.setText(karat);
            h.tvWeight.setText(String.format(Locale.US, "%.2f g", weight));
            h.tvStatus.setText(getStatusLabel(status, isArabic));
            h.tvLocation.setText(isArabic ? "📍 " + location : "📍 " + location);
            h.tvSerial.setText(isArabic ? "🔢 " + serial : "🔢 " + serial);
            
            // تلوين الحالة
            h.tvStatus.setTextColor(getStatusColor(status));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvKarat, tvWeight, tvStatus, tvLocation, tvSerial;
            
            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvItemTitle);
                tvKarat = v.findViewById(R.id.tvKarat);
                tvWeight = v.findViewById(R.id.tvWeight);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvLocation = v.findViewById(R.id.tvLocation);
                tvSerial = v.findViewById(R.id.tvSerial);
            }
        }
    }
    
    // ==================== محول البوابات ====================
    public static class GatesAdapter extends RecyclerView.Adapter<GatesAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        
        public GatesAdapter(JSONArray data, boolean isArabic) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_gates_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String gateName = item.optString("gate_name", 
                item.optString("gate", "-"));
            String epc = item.optString("epc", 
                item.optString("code", item.optString("tag_epc", "-")));
            String date = item.optString("created_at", 
                item.optString("date", item.optString("timestamp", "")));
            String status = item.optString("status", "allowed");
            
            h.tvGate.setText(isArabic ? "🚪 " + gateName : "🚪 " + gateName);
            h.tvEpc.setText(isArabic ? "📡 " + epc : "📡 " + epc);
            h.tvDate.setText(formatDate(date));
            
            // حالة المرور
            if (status.toLowerCase().contains("alert") || status.toLowerCase().contains("denied")) {
                h.tvStatus.setText(isArabic ? "⚠️ إنذار" : "⚠️ Alert");
                h.tvStatus.setTextColor(Color.parseColor("#F44336"));
            } else {
                h.tvStatus.setText(isArabic ? "✅ مسموح" : "✅ Allowed");
                h.tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            }
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvGate, tvEpc, tvDate, tvStatus;
            
            VH(View v) {
                super(v);
                tvGate = v.findViewById(R.id.tvGateName);
                tvEpc = v.findViewById(R.id.tvEpc);
                tvDate = v.findViewById(R.id.tvDate);
                tvStatus = v.findViewById(R.id.tvStatus);
            }
        }
    }
    
    // ==================== محول المستخدمين ====================
    public static class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        
        public UsersAdapter(JSONArray data, boolean isArabic) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_users_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String name = item.optString("display_name", 
                item.optString("name", item.optString("username", "-")));
            String role = item.optString("role_name", 
                item.optString("role", "-"));
            String status = item.optString("status", 
                item.optString("is_active", "active"));
            
            h.tvName.setText(isArabic ? "👤 " + name : "👤 " + name);
            h.tvRole.setText(isArabic ? "💼 " + role : "💼 " + role);
            
            if (status.toLowerCase().contains("active")) {
                h.tvStatus.setText(isArabic ? "✅ نشط" : "✅ Active");
                h.tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            } else {
                h.tvStatus.setText(isArabic ? "⛔ موقوف" : "⛔ Suspended");
                h.tvStatus.setTextColor(Color.parseColor("#F44336"));
            }
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvRole, tvStatus;
            
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvRole = v.findViewById(R.id.tvRole);
                tvStatus = v.findViewById(R.id.tvStatus);
            }
        }
    }
    
    // ==================== محول الحركة المالية ====================
    public static class FinancialAdapter extends RecyclerView.Adapter<FinancialAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        private final String currency;
        
        public FinancialAdapter(JSONArray data, boolean isArabic, String currency) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            this.currency = currency != null ? currency : "";
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_financial_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String description = item.optString("description", 
                item.optString("details", "-"));
            double debit = item.optDouble("debit", 0);
            double credit = item.optDouble("credit", 0);
            String date = item.optString("created_at", 
                item.optString("date", ""));
            
            h.tvDescription.setText(description);
            h.tvDate.setText(formatDate(date));
            
            if (debit > 0) {
                h.tvDebit.setText(String.format(Locale.US, "%,.2f %s", debit, currency));
                h.tvDebit.setTextColor(Color.parseColor("#F44336"));
                h.tvCredit.setText("-");
            } else if (credit > 0) {
                h.tvCredit.setText(String.format(Locale.US, "%,.2f %s", credit, currency));
                h.tvCredit.setTextColor(Color.parseColor("#4CAF50"));
                h.tvDebit.setText("-");
            }
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvDescription, tvDebit, tvCredit, tvDate;
            
            VH(View v) {
                super(v);
                tvDescription = v.findViewById(R.id.tvDescription);
                tvDebit = v.findViewById(R.id.tvDebit);
                tvCredit = v.findViewById(R.id.tvCredit);
                tvDate = v.findViewById(R.id.tvDate);
            }
        }
    }
    
    // ==================== محول التحويلات ====================
    public static class MovementAdapter extends RecyclerView.Adapter<MovementAdapter.VH> {
        private final List<JSONObject> items;
        private final boolean isArabic;
        
        public MovementAdapter(JSONArray data, boolean isArabic) {
            this.items = new ArrayList<>();
            this.isArabic = isArabic;
            
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.optJSONObject(i);
                    if (obj != null) items.add(obj);
                }
            }
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_movement_custom, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject item = items.get(pos);
            
            String from = item.optString("from_branch", 
                item.optString("from", item.optString("source", "-")));
            String to = item.optString("to_branch", 
                item.optString("to", item.optString("destination", "-")));
            String status = item.optString("status", "draft");
            String date = item.optString("created_at", 
                item.optString("date", ""));
            
            h.tvFrom.setText(isArabic ? "📤 " + from : "📤 " + from);
            h.tvTo.setText(isArabic ? "📥 " + to : "📥 " + to);
            h.tvDate.setText(formatDate(date));
            
            // حالة التحويل
            switch (status.toLowerCase()) {
                case "approved":
                    h.tvStatus.setText(isArabic ? "✅ معتمد" : "✅ Approved");
                    h.tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                    break;
                case "rejected":
                    h.tvStatus.setText(isArabic ? "❌ مرفوض" : "❌ Rejected");
                    h.tvStatus.setTextColor(Color.parseColor("#F44336"));
                    break;
                default:
                    h.tvStatus.setText(isArabic ? "📝 مسودة" : "📝 Draft");
                    h.tvStatus.setTextColor(Color.parseColor("#FF9800"));
                    break;
            }
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvFrom, tvTo, tvDate, tvStatus;
            
            VH(View v) {
                super(v);
                tvFrom = v.findViewById(R.id.tvFrom);
                tvTo = v.findViewById(R.id.tvTo);
                tvDate = v.findViewById(R.id.tvDate);
                tvStatus = v.findViewById(R.id.tvStatus);
            }
        }
    }
    
    // ==================== دوال مساعدة ====================
    
    private static String getPaymentLabel(String payment, boolean isArabic) {
        switch (payment.toLowerCase()) {
            case "cash":
                return isArabic ? "💵 نقدي" : "💵 Cash";
            case "card":
                return isArabic ? "💳 بطاقة" : "💳 Card";
            case "transfer":
                return isArabic ? "🏦 تحويل" : "🏦 Transfer";
            default:
                return isArabic ? "💰 " + payment : "💰 " + payment;
        }
    }
    
    private static int getPaymentColor(String payment) {
        switch (payment.toLowerCase()) {
            case "cash":
                return Color.parseColor("#4CAF50");
            case "card":
                return Color.parseColor("#2196F3");
            case "transfer":
                return Color.parseColor("#9C27B0");
            default:
                return Color.parseColor("#666666");
        }
    }
    
    private static String getStatusLabel(String status, boolean isArabic) {
        switch (status.toLowerCase()) {
            case "in_stock":
            case "available":
                return isArabic ? "✅ متاح" : "✅ Available";
            case "sold":
                return isArabic ? "💰 مباع" : "💰 Sold";
            case "displayed":
                return isArabic ? "🏪 معروض" : "🏪 Displayed";
            default:
                return isArabic ? "📦 " + status : "📦 " + status;
        }
    }
    
    private static int getStatusColor(String status) {
        switch (status.toLowerCase()) {
            case "in_stock":
            case "available":
                return Color.parseColor("#4CAF50");
            case "sold":
                return Color.parseColor("#F44336");
            case "displayed":
                return Color.parseColor("#2196F3");
            default:
                return Color.parseColor("#666666");
        }
    }
    
    private static String formatDate(String dateStr) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
            Date date = inputFormat.parse(dateStr);
            return outputFormat.format(date);
        } catch (Exception e) {
            return dateStr;
        }
    }
}
