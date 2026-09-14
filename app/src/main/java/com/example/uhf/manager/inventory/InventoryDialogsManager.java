package com.example.uhf.dialog.inventory;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.uhf.R;
import com.example.uhf.manager.MiraApiClient;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.utils.PrintUtils;
import com.example.uhf.utils.SoundManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ InventoryDialogsManager
 * 
 * مسؤول عن كل حوارات شاشة الجرد:
 * - Power Control Sheet
 * - Session Settings Dialog
 * - Buzzer Settings Dialog
 * - Commit Confirmation Dialog
 * - Item Options Dialog
 * - Item Details Dialog
 * - Edit Status Dialog
 * - Damage Dialog
 * 
 * لا يحتوي على مراجع للـ Fragment → آمن من التسريبات
 */
public class InventoryDialogsManager {

    private static final String TAG = "InventoryDialogs";

    private final Context context;
    private final boolean isArabic;
    
    // ==================== Callbacks ====================
    
    public interface DialogsCallback {
        void onPowerChanged(int newPowerDbm);
        void onSessionReset();
        void onBuzzerTest();
        void onCommitConfirmed();
        void onPrintCurrentReport();
        void onPrintSingleItem(ScannedItemDisplay item);
        void onItemStatusChange(ScannedItemDisplay item, String newStatus);
        void onItemDamageRegister(ScannedItemDisplay item, String damageType);
        void onShowArchive();
        void onShowSnapshots();
    }
    
    private DialogsCallback callback;
    private int currentPowerDbm;
    private int expectedCount;
    private SoundManager soundManager;
    
    // ==================== Constructor ====================
    
    public InventoryDialogsManager(Context context, boolean isArabic) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
        this.currentPowerDbm = 26;
        this.expectedCount = 0;
    }
    
    public void setCallback(DialogsCallback callback) {
        this.callback = callback;
    }
    
    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }
    
    public void setCurrentPowerDbm(int powerDbm) {
        this.currentPowerDbm = powerDbm;
    }
    
    public int getCurrentPowerDbm() {
        return currentPowerDbm;
    }
    
    public void setExpectedCount(int count) {
        this.expectedCount = count;
    }
    
    // ==================== 1. Power Control Sheet ====================
    
    /**
     * ✅ عرض BottomSheet للتحكم بقدرة الهوائي
     */
    public void showPowerControlSheet() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    context, R.style.LuxuryBottomSheetDialog);
            
            View sheetView = LayoutInflater.from(context).inflate(
                    R.layout.bottom_sheet_power_control, null);
            dialog.setContentView(sheetView);
            
            Slider sliderPower = sheetView.findViewById(R.id.sliderPower);
            TextView tvPowerValue = sheetView.findViewById(R.id.tvPowerValue);
            MaterialButton btnApply = sheetView.findViewById(R.id.btnApplyPower);
            
            if (sliderPower != null) {
                sliderPower.setValue(currentPowerDbm);
                
                sliderPower.addOnChangeListener((slider, value, fromUser) -> {
                    currentPowerDbm = (int) value;
                    if (tvPowerValue != null) {
                        tvPowerValue.setText(currentPowerDbm + " dBm");
                    }
                });
            }
            
            if (tvPowerValue != null) {
                tvPowerValue.setText(currentPowerDbm + " dBm");
            }
            
            if (btnApply != null) {
                btnApply.setOnClickListener(v -> {
                    if (callback != null) {
                        callback.onPowerChanged(currentPowerDbm);
                    }
                    dialog.dismiss();
                });
            }
            
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showPowerControlSheet error: " + e.getMessage());
        }
    }
    
    // ==================== 2. Session Settings Dialog ====================
    
    /**
     * ✅ عرض حوار إعدادات الجلسة
     */
    public void showSessionSettingsDialog() {
        try {
            String[] options = isArabic ? new String[]{
                "⏱️ مهلة الجلسة: 5 دقائق",
                "📊 عدد المتوقع: " + expectedCount,
                "🔊 تفعيل الصوت: نعم",
                "🎯 وضع القراءة: Fast",
                "🔄 إعادة تعيين الجلسة"
            } : new String[]{
                "⏱️ Session Timeout: 5 min",
                "📊 Expected Count: " + expectedCount,
                "🔊 Buzzer: Enabled",
                "🎯 Read Mode: Fast",
                "🔄 Reset Session"
            };
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(isArabic ? "⚙️ إعدادات جلسة الجرد" : "⚙️ Session Settings")
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                Toast.makeText(context, 
                                    isArabic ? "⏱️ قيد التطوير" : "⏱️ In development", 
                                    Toast.LENGTH_SHORT).show();
                                break;
                            case 4:
                                if (callback != null) {
                                    callback.onSessionReset();
                                }
                                break;
                            default:
                                Toast.makeText(context, 
                                    isArabic ? "قيد التطوير" : "In development", 
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showSessionSettingsDialog error: " + e.getMessage());
        }
    }
    
    // ==================== 3. Buzzer Settings Dialog ====================
    
    /**
     * ✅ عرض حوار إعدادات Buzzer
     */
    public void showBuzzerSettingsDialog() {
        try {
            String[] options = isArabic ? new String[]{
                "🔊 تشغيل/إيقاف الصوت",
                "🎵 تغيير نغمة النجاح",
                "⚠️ تغيير نغمة الخطأ",
                "📢 اختبار الصوت"
            } : new String[]{
                "🔊 Toggle Buzzer",
                "🎵 Change Success Sound",
                "⚠️ Change Error Sound",
                "📢 Test Buzzer"
            };
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(isArabic ? "🔊 إعدادات الصوت" : "🔊 Buzzer Settings")
                    .setItems(options, (dialog, which) -> {
                        if (which == 3) {
                            if (soundManager != null) {
                                soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                            }
                            if (callback != null) {
                                callback.onBuzzerTest();
                            }
                        } else {
                            Toast.makeText(context, 
                                isArabic ? "قيد التطوير" : "In development", 
                                Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showBuzzerSettingsDialog error: " + e.getMessage());
        }
    }
        // ==================== 4. Commit Confirmation Dialog ====================
    
    /**
     * ✅ عرض تأكيد الاعتماد
     * 
     * @param scannedCount  عدد القطع الممسوحة
     */
    public void showCommitConfirmationDialog(int scannedCount) {
        try {
            int missing = Math.max(0, expectedCount - scannedCount);
            
            String message = isArabic ? 
                String.format("📊 ملخص عمليات الجرد:\n\n" +
                              "• المتوقع: %d\n" +
                              "• الممسوح: %d\n" +
                              "• المفقود: %d\n\n" +
                              "هل تريد اعتماد العملية وإرسالها لقاعدة البيانات؟",
                        expectedCount, scannedCount, missing) :
                String.format("📊 Inventory Summary:\n\n" +
                              "• Expected: %d\n" +
                              "• Scanned: %d\n" +
                              "• Missing: %d\n\n" +
                              "Do you want to commit and send to database?",
                        expectedCount, scannedCount, missing);
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(isArabic ? "حفظ واكتمال الجرد" : "Save and Complete Inventory")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "إرسال واعتماد" : "Send and Commit",
                            (dialog, which) -> {
                                if (callback != null) {
                                    callback.onCommitConfirmed();
                                }
                            })
                    .setNeutralButton(isArabic ? "🖨️ طباعة التقرير الحالي" : "🖨️ Print current report",
                            (dialog, which) -> {
                                if (callback != null) {
                                    callback.onPrintCurrentReport();
                                }
                            })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showCommitConfirmationDialog error: " + e.getMessage());
        }
    }
    
    // ==================== 5. Item Options Dialog ====================
    
    /**
     * ✅ عرض خيارات القطعة
     * 
     * @param item  القطعة المضغوطة
     */
    public void showItemOptionsDialog(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            String[] options = isArabic ? new String[]{
                "✏️ تعديل الحالة",
                "⚠️ تسجيل تلف",
                "📍 تحديث الموقع",
                "📝 إضافة ملاحظات",
                "🖨️ طباعة الملصق",
                "🔍 تفاصيل كاملة"
            } : new String[]{
                "✏️ Edit Status",
                "⚠️ Register Damage",
                "📍 Update Location",
                "📝 Add Notes",
                "🖨️ Print Label",
                "🔍 Full Details"
            };
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(item.epc != null ? item.epc : "")
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                showEditStatusDialog(item);
                                break;
                            case 1:
                                showDamageDialog(item);
                                break;
                            case 2:
                                Toast.makeText(context, 
                                    isArabic ? "📍 قيد التطوير" : "📍 In development", 
                                    Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                Toast.makeText(context, 
                                    isArabic ? "📝 قيد التطوير" : "📝 In development", 
                                    Toast.LENGTH_SHORT).show();
                                break;
                            case 4:
                                if (callback != null) {
                                    callback.onPrintSingleItem(item);
                                }
                                break;
                            case 5:
                                showItemFullDetails(item);
                                break;
                        }
                    })
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showItemOptionsDialog error: " + e.getMessage());
        }
    }
    
    // ==================== 6. Item Full Details Dialog ====================
    
    /**
     * ✅ عرض التفاصيل الكاملة للقطعة
     */
    public void showItemFullDetails(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("📋 ").append(isArabic ? "تفاصيل القطعة" : "Item Details").append("\n\n");
            sb.append("🔖 EPC: ").append(item.epc != null ? item.epc : "—").append("\n");
            sb.append("📝 ").append(isArabic ? "الاسم: " : "Title: ")
              .append(item.title != null ? item.title : "—").append("\n");
            sb.append("💎 ").append(isArabic ? "العيار: " : "Karat: ")
              .append(item.karat != null ? item.karat : "—").append("\n");
            sb.append("⚖️ ").append(isArabic ? "الوزن: " : "Weight: ")
              .append(String.format(Locale.US, "%.2f g", item.weight)).append("\n");
            sb.append("💰 ").append(isArabic ? "السعر: " : "Price: ")
              .append(String.format(Locale.US, "$%.2f", item.price)).append("\n");
            sb.append("🔢 ").append(isArabic ? "مرات القراءة: " : "Read Count: ")
              .append(item.count).append("\n");
            sb.append("📶 RSSI: ").append(item.rssi).append(" dBm\n");
            sb.append("⏰ ").append(isArabic ? "آخر قراءة: " : "Last Read: ")
              .append(item.time != null ? item.time : "—");
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(isArabic ? "تفاصيل القطعة" : "Item Details")
                    .setMessage(sb.toString())
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showItemFullDetails error: " + e.getMessage());
        }
    }
    
    // ==================== 7. Edit Status Dialog ====================
    
    /**
     * ✅ عرض حوار تعديل الحالة
     */
    public void showEditStatusDialog(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            String[] statuses = isArabic ? 
                new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} :
                new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};
            
            String title = (isArabic ? "تعديل حالة - " : "Edit status - ") 
                    + (item.epc != null ? item.epc : "");
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setItems(statuses, (dialog, which) -> {
                        String newStatus = statuses[which];
                        
                        if (callback != null) {
                            callback.onItemStatusChange(item, newStatus);
                        }
                        
                        if (soundManager != null) {
                            soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                        }
                        
                        Toast.makeText(context, 
                            (isArabic ? "تم تحديث الحالة: " : "Status updated: ") + newStatus, 
                            Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showEditStatusDialog error: " + e.getMessage());
        }
    }
    
    // ==================== 8. Damage Dialog ====================
    
    /**
     * ✅ عرض حوار تسجيل التلف
     */
    public void showDamageDialog(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            String[] damageTypes = isArabic ?
                new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
                new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};
            
            String title = (isArabic ? "تسجيل تلف - " : "Register damage - ") 
                    + (item.epc != null ? item.epc : "");
            
            new MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setItems(damageTypes, (dialog, which) -> {
                        String damageType = damageTypes[which];
                        
                        if (callback != null) {
                            callback.onItemDamageRegister(item, damageType);
                        }
                        
                        if (soundManager != null) {
                            soundManager.playBeep(SoundManager.FeedbackType.ERROR);
                        }
                        
                        Toast.makeText(context, 
                            (isArabic ? "تم تسجيل التلف: " : "Damage registered: ") + damageType, 
                            Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showDamageDialog error: " + e.getMessage());
        }
    }
}
