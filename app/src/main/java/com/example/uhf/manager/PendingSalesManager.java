package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.CartItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PendingSalesManager {

    private static final String TAG = "PendingSalesManager";
    private static final String PREFS_OFFLINE = "pos_offline_sales_branch_";
    private static final String PREFS_SYNC_LOG = "pos_sync_log_branch_";

    private Context context;

    public PendingSalesManager(Context context) {
        this.context = context;
    }

    // ==================== الدوال الأساسية ====================

    public JSONArray getPendingSales(int branchId) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString("pending_sales", "[]"));
        } catch (Exception e) {
            Log.e(TAG, "getPendingSales error: " + e.getMessage());
            return new JSONArray();
        }
    }

    public void savePendingSales(int branchId, JSONArray salesArray) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            prefs.edit().putString("pending_sales", salesArray.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "savePendingSales error: " + e.getMessage());
        }
    }

    public int getPendingCount(int branchId) {
        return getPendingSales(branchId).length();
    }

    public boolean isItemSoldLocally(String epc, int branchId) {
        try {
            JSONArray arr = getPendingSales(branchId);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale == null) continue;
                
                JSONArray items = sale.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item != null && epc.equals(item.optString("epc", ""))) {
                            return true;
                        }
                    }
                }
                
                JSONObject payload = sale.optJSONObject("payload");
                if (payload != null) {
                    JSONArray payloadItems = payload.optJSONArray("items");
                    if (payloadItems != null) {
                        for (int j = 0; j < payloadItems.length(); j++) {
                            JSONObject item = payloadItems.optJSONObject(j);
                            if (item != null && epc.equals(item.optString("epc", ""))) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "isItemSoldLocally error: " + e.getMessage());
            return false;
        }
    }

    // ==================== سجل المزامنة ====================

    public void saveToSyncLog(String saleId, int branchId, int userId, String username, 
                              double amount, int itemCount, String status, String message) {
        try {
            String prefsName = PREFS_SYNC_LOG + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("sync_log", "[]");
            JSONArray arr = new JSONArray(existingJson);
            
            JSONObject logEntry = new JSONObject();
            logEntry.put("sale_id", saleId);
            logEntry.put("branch_id", branchId);
            logEntry.put("user_id", userId);
            logEntry.put("username", username);
            logEntry.put("amount", amount);
            logEntry.put("item_count", itemCount);
            logEntry.put("status", status);
            logEntry.put("message", message);
            logEntry.put("timestamp", System.currentTimeMillis());
            
            arr.put(logEntry);
            
            while (arr.length() > 100) {
                arr.remove(0);
            }
            
            prefs.edit().putString("sync_log", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveToSyncLog error: " + e.getMessage());
        }
    }

    public void showSyncHistoryLog(int branchId, boolean isArabic) {
        try {
            String prefsName = PREFS_SYNC_LOG + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("sync_log", "[]");
            JSONArray arr = new JSONArray(existingJson);
            
            if (arr.length() == 0) {
                Toast.makeText(context, isArabic ? "📋 لا يوجد سجل مزامنة" : "📋 No sync history", 
                              Toast.LENGTH_SHORT).show();
                return;
            }
            
            StringBuilder logText = new StringBuilder();
            logText.append(isArabic ? "📋 سجل المزامنة - الفرع الحالي:\n\n" : "📋 Sync History - Current Branch:\n\n");
            
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject entry = arr.optJSONObject(i);
                if (entry == null) continue;
                
                String saleId = entry.optString("sale_id", "");
                String username = entry.optString("username", "");
                String status = entry.optString("status", "");
                String message = entry.optString("message", "");
                double amount = entry.optDouble("amount", 0);
                long timestamp = entry.optLong("timestamp", 0);
                
                String statusEmoji = "❓";
                switch (status) {
                    case "synced": statusEmoji = "✅"; break;
                    case "syncing": statusEmoji = "⏳"; break;
                    case "failed": statusEmoji = "❌"; break;
                    case "pending": statusEmoji = "📦"; break;
                }
                
                logText.append(statusEmoji).append(" ").append(saleId)
                    .append(" | 💰 ").append(amount)
                    .append(" | 👤 ").append(username)
                    .append("\n  ").append(message)
                    .append("\n\n");
            }
            
            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "📋 سجل المزامنة" : "📋 Sync History")
                .setMessage(logText.toString())
                .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showSyncHistoryLog error: " + e.getMessage());
        }
    }

    // ==================== عرض العمليات المعلقة ====================

    public void showPendingSalesWithActions(int branchId, boolean isArabic, 
                                            com.example.uhf.manager.MiraPriceManager priceManager,
                                            Runnable syncAction) {
        try {
            JSONArray arr = getPendingSales(branchId);
            
            if (arr.length() == 0) {
                Toast.makeText(context, isArabic ? "📋 لا توجد عمليات معلقة" : "📋 No pending sales", 
                              Toast.LENGTH_SHORT).show();
                return;
            }
            
            String[] saleTitles = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale != null) {
                    String customerName = sale.optString("customer_name", "");
                    double amount = sale.optDouble("final_amount", 0);
                    String syncStatus = sale.optString("sync_status", "pending");
                    
                    String statusEmoji = "📦";
                    switch (syncStatus) {
                        case "synced": statusEmoji = "✅"; break;
                        case "syncing": statusEmoji = "⏳"; break;
                        case "failed": statusEmoji = "❌"; break;
                    }
                    
                    saleTitles[i] = statusEmoji + " " + 
                        (isArabic ? "عميل: " : "Customer: ") + customerName + 
                        " | " + priceManager.formatPriceDefault(amount);
                }
            }
            
            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "📋 العمليات المعلقة (" + arr.length() + ")" : "📋 Pending Sales (" + arr.length() + ")")
                .setItems(saleTitles, (dialog, which) -> {
                    try {
                        JSONObject selectedSale = arr.optJSONObject(which);
                        if (selectedSale != null) {
                            showPendingSaleDetails(branchId, selectedSale, which, isArabic, priceManager);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "showPendingSalesWithActions.onClick error: " + e.getMessage());
                    }
                })
                .setPositiveButton(isArabic ? "✅ مزامنة الكل" : "✅ Sync All", (dialog, which) -> {
                    dialog.dismiss();
                    if (syncAction != null) syncAction.run();
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showPendingSalesWithActions error: " + e.getMessage());
        }
    }

    public void showPendingSaleDetails(int branchId, JSONObject sale, int position, 
                                       boolean isArabic, com.example.uhf.manager.MiraPriceManager priceManager) {
        try {
            String saleId = sale.optString("sale_id", "Unknown");
            String customerName = sale.optString("customer_name", "");
            String paymentMethod = sale.optString("payment_method", "");
            double amount = sale.optDouble("final_amount", 0);
            int itemCount = sale.optInt("item_count", 0);
            long createdAt = sale.optLong("created_at", 0);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String dateStr = sdf.format(new Date(createdAt));
            
            StringBuilder itemsDetails = new StringBuilder();
            JSONArray items = sale.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        itemsDetails.append("  • ")
                            .append(item.optString("title", "Unknown"))
                            .append(" - ")
                            .append(item.optString("epc", ""))
                            .append("\n");
                    }
                }
            }
            
            String message = (isArabic ? 
                "🔑 رقم العملية: " : "🔑 Sale ID: ") + saleId + "\n" +
                (isArabic ? "👤 العميل: " : "👤 Customer: ") + customerName + "\n" +
                (isArabic ? "💳 الدفع: " : "💳 Payment: ") + paymentMethod + "\n" +
                (isArabic ? "💰 المبلغ: " : "💰 Amount: ") + priceManager.formatPriceDefault(amount) + "\n" +
                (isArabic ? "📦 عدد القطع: " : "📦 Items: ") + itemCount + "\n" +
                (isArabic ? "🕐 التاريخ: " : "🕐 Date: ") + dateStr + "\n\n" +
                (isArabic ? "القطع:\n" : "Items:\n") + itemsDetails.toString();
            
            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "📋 تفاصيل العملية المعلقة" : "📋 Pending Sale Details")
                .setMessage(message)
                .setPositiveButton(isArabic ? "✏️ تعديل" : "✏️ Edit", (dialog, which) -> {
                    dialog.dismiss();
                    showEditPendingSaleDialog(branchId, sale, position, isArabic, priceManager);
                })
                .setNegativeButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (dialog, which) -> {
                    dialog.dismiss();
                    confirmDeletePendingSale(branchId, saleId, position, isArabic);
                })
                .setNeutralButton(isArabic ? "إغلاق" : "Close", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showPendingSaleDetails error: " + e.getMessage());
        }
    }

    public void showEditPendingSaleDialog(int branchId, JSONObject sale, int position, 
                                          boolean isArabic, com.example.uhf.manager.MiraPriceManager priceManager) {
        try {
            final String saleId = sale.optString("sale_id", "");
            
            // إنشاء نافذة التعديل
            android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(40, 30, 40, 30);
            
            // حقل اسم العميل
            android.widget.TextView tvCustomerLabel = new android.widget.TextView(context);
            tvCustomerLabel.setText(isArabic ? "👤 اسم العميل:" : "👤 Customer Name:");
            tvCustomerLabel.setTextSize(14);
            tvCustomerLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(tvCustomerLabel);
            
            android.widget.EditText etCustomerName = new android.widget.EditText(context);
            etCustomerName.setText(sale.optString("customer_name", ""));
            etCustomerName.setSingleLine(true);
            layout.addView(etCustomerName);
            
            // حقل المبلغ
            android.widget.TextView tvAmountLabel = new android.widget.TextView(context);
            tvAmountLabel.setText(isArabic ? "💰 المبلغ:" : "💰 Amount:");
            tvAmountLabel.setTextSize(14);
            tvAmountLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(tvAmountLabel);
            
            android.widget.EditText etAmount = new android.widget.EditText(context);
            etAmount.setText(String.valueOf(sale.optDouble("final_amount", 0)));
            etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etAmount.setSingleLine(true);
            layout.addView(etAmount);
            
            // حقل طريقة الدفع
            android.widget.TextView tvPaymentLabel = new android.widget.TextView(context);
            tvPaymentLabel.setText(isArabic ? "💳 طريقة الدفع:" : "💳 Payment Method:");
            tvPaymentLabel.setTextSize(14);
            tvPaymentLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(tvPaymentLabel);
            
            android.widget.EditText etPaymentMethod = new android.widget.EditText(context);
            etPaymentMethod.setText(sale.optString("payment_method", "cash"));
            etPaymentMethod.setSingleLine(true);
            layout.addView(etPaymentMethod);
            
            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "✏️ تعديل العملية المعلقة" : "✏️ Edit Pending Sale")
                .setView(layout)
                .setPositiveButton(isArabic ? "💾 حفظ" : "💾 Save", (dialog, which) -> {
                    String newCustomerName = etCustomerName.getText().toString().trim();
                    String newAmountStr = etAmount.getText().toString().trim();
                    String newPaymentMethod = etPaymentMethod.getText().toString().trim();
                    
                    if (newCustomerName.isEmpty()) {
                        Toast.makeText(context, isArabic ? "⚠️ اسم العميل مطلوب" : "⚠️ Customer name required", 
                                      Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    double newAmount = 0;
                    try {
                        newAmount = Double.parseDouble(newAmountStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(context, isArabic ? "⚠️ مبلغ غير صحيح" : "⚠️ Invalid amount", 
                                      Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (newAmount <= 0) {
                        Toast.makeText(context, isArabic ? "⚠️ المبلغ يجب أن يكون أكبر من صفر" : "⚠️ Amount must be greater than zero", 
                                      Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    updatePendingSale(branchId, saleId, newCustomerName, newAmount, newPaymentMethod, 
                                     position, isArabic);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showEditPendingSaleDialog error: " + e.getMessage());
        }
    }

    public void confirmDeletePendingSale(int branchId, String saleId, int position, boolean isArabic) {
        try {
            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "🗑️ تأكيد الحذف" : "🗑️ Confirm Delete")
                .setMessage(isArabic ? 
                    "هل أنت متأكد من حذف هذه العملية المعلقة؟\n\n" +
                    "🔑 رقم العملية: " + saleId + "\n\n" +
                    "لا يمكن التراجع عن هذا الإجراء!"
                    :
                    "Are you sure you want to delete this pending sale?\n\n" +
                    "🔑 Sale ID: " + saleId + "\n\n" +
                    "This action cannot be undone!")
                .setPositiveButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (dialog, which) -> {
                    dialog.dismiss();
                    deletePendingSale(branchId, saleId, isArabic);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "confirmDeletePendingSale error: " + e.getMessage());
        }
    }

    public void updatePendingSale(int branchId, String saleId, String newCustomerName, 
                                  double newAmount, String newPaymentMethod, int position, boolean isArabic) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("pending_sales", "[]");
            JSONArray arr = new JSONArray(existingJson);
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale != null && sale.optString("sale_id").equals(saleId)) {
                    sale.put("customer_name", newCustomerName);
                    sale.put("final_amount", newAmount);
                    sale.put("payment_method", newPaymentMethod);
                    
                    JSONObject payload = sale.optJSONObject("payload");
                    if (payload != null) {
                        payload.put("customer_name", newCustomerName);
                        
                        JSONObject customer = payload.optJSONObject("customer");
                        if (customer == null) {
                            customer = new JSONObject();
                            payload.put("customer", customer);
                        }
                        customer.put("name", newCustomerName);
                        customer.put("payment_method", newPaymentMethod);
                        
                        JSONObject payment = payload.optJSONObject("payment");
                        if (payment == null) {
                            payment = new JSONObject();
                            payload.put("payment", payment);
                        }
                        payment.put("final_amount_usd", newAmount);
                        payment.put("payment_method", newPaymentMethod);
                        
                        payload.put("payment", payment);
                        sale.put("payload", payload);
                    }
                    
                    break;
                }
            }
            
            prefs.edit().putString("pending_sales", arr.toString()).apply();
            
            Toast.makeText(context, isArabic ? "✅ تم حفظ التعديلات" : "✅ Changes saved", 
                          Toast.LENGTH_SHORT).show();
                          
        } catch (Exception e) {
            Log.e(TAG, "updatePendingSale error: " + e.getMessage());
            Toast.makeText(context, isArabic ? "❌ فشل حفظ التعديلات" : "❌ Failed to save changes", 
                          Toast.LENGTH_SHORT).show();
        }
    }

    public void deletePendingSale(int branchId, String saleId, boolean isArabic) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("pending_sales", "[]");
            JSONArray arr = new JSONArray(existingJson);
            JSONArray newArr = new JSONArray();
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale != null && !sale.optString("sale_id").equals(saleId)) {
                    newArr.put(sale);
                }
            }
            
            prefs.edit().putString("pending_sales", newArr.toString()).apply();
            
            Toast.makeText(context, isArabic ? "🗑️ تم حذف العملية" : "🗑️ Sale deleted", 
                          Toast.LENGTH_SHORT).show();
                          
        } catch (Exception e) {
            Log.e(TAG, "deletePendingSale error: " + e.getMessage());
            Toast.makeText(context, isArabic ? "❌ فشل الحذف" : "❌ Failed to delete", 
                          Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== المزامنة ====================

    public void syncPendingSales(int branchId, boolean isArabic, 
                                 com.example.uhf.manager.MiraPriceManager priceManager,
                                 MiraApiClient apiClient,
                                 PendingSalesCallback callback) {
        JSONArray arr = getPendingSales(branchId);
        
        if (arr.length() == 0) {
            if (callback != null) callback.onComplete(0, 0, "No pending sales");
            return;
        }
        
        int synced = 0;
        int failed = 0;
        JSONArray remaining = new JSONArray();
        StringBuilder syncDetails = new StringBuilder();
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale == null) continue;
            
            try {
                String saleId = sale.optString("sale_id", "Unknown");
                int saleBranchId = sale.optInt("branch_id", -1);
                int saleUserId = sale.optInt("user_id", -1);
                String username = sale.optString("username", "Unknown");
                double amount = sale.optDouble("final_amount", 0);
                int itemCount = sale.optInt("item_count", 0);
                
                if (saleBranchId != branchId) {
                    continue;
                }
                
                sale.put("sync_status", "syncing");
                
                JSONObject payload = sale.optJSONObject("payload");
                if (payload == null) {
                    failed++;
                    sale.put("sync_status", "failed");
                    sale.put("last_error", "Payload missing");
                    remaining.put(sale);
                    continue;
                }
                
                payload.put("branch_id", branchId);
                payload.put("user_id", saleUserId);
                
                MiraApiClient.ApiResponse response = apiClient.checkout(payload);
                
                if (response != null && response.isSuccess) {
                    synced++;
                    sale.put("sync_status", "synced");
                    sale.put("last_error", "");
                    
                    syncDetails.append("✅ ").append(saleId)
                        .append(" | 💰 ").append(amount)
                        .append(" | 👤 ").append(username)
                        .append("\n");
                } else {
                    failed++;
                    String errorMsg = response != null ? (response.error != null ? response.error : "Unknown error") : "Network error";
                    sale.put("sync_status", "failed");
                    sale.put("last_error", errorMsg);
                    remaining.put(sale);
                    
                    syncDetails.append("❌ ").append(saleId)
                        .append(" | ").append(errorMsg)
                        .append("\n");
                }
            } catch (Exception e) {
                failed++;
                try {
                    sale.put("sync_status", "failed");
                    sale.put("last_error", e.getMessage());
                } catch (org.json.JSONException jsonException) {
                    Log.e(TAG, "JSON error: " + jsonException.getMessage());
                }
                remaining.put(sale);
            }
        }
        
        savePendingSales(branchId, remaining);
        
        if (callback != null) {
            callback.onComplete(synced, failed, syncDetails.toString());
        }
    }

    public interface PendingSalesCallback {
        void onComplete(int synced, int failed, String details);
    }
                                                   }
