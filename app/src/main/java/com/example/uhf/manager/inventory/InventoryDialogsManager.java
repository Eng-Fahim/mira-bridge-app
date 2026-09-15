package com.example.uhf.dialog.inventory;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.uhf.R;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.utils.SoundManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.Locale;

/**
 * ✅ InventoryDialogsManager - الإصدار 2.0
 * 
 * ✅ الإصلاحات:
 * - استخدام Activity context بدل applicationContext (يمنع crash)
 * - استبدال setItems بـ setAdapter (يمنع crash الفاتح)
 * - دعم الفاتح والداكن
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
        // ✅ استخدام Activity context بدل applicationContext
        this.context = getActivityContext(context);
        this.isArabic = isArabic;
        this.currentPowerDbm = 26;
        this.expectedCount = 0;
    }
    
    /**
     * ✅ استخراج Activity context من أي Context
     */
    private static Context getActivityContext(Context context) {
        if (context instanceof Activity) {
            return context;
        }
        if (context instanceof ContextWrapper) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base instanceof Activity) {
                return base;
            }
        }
        // fallback
        return context.getApplicationContext();
    }
    
    /**
     * ✅ الحصول على Activity context الحالي
     */
    private Context getContext() {
        return context;
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
    
    // ==================== Helper for List Dialogs ====================
    
    /**
     * ✅ عرض قائمة بـ setAdapter (بدل setItems)
     */
    private void showItemsDialog(String title, String[] items, 
                                  ItemClickListener listener,
                                  String negativeButton) {
        try {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getContext(),
                    R.layout.item_dialog_list,
                    android.R.id.text1,
                    items
            );
            
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                    .setTitle(title)
                    .setAdapter(adapter, (dialog, which) -> {
                        if (listener != null && which >= 0 && which < items.length) {
                            listener.onItemClick(which, items[which]);
                        }
                        dialog.dismiss();
                    });
            
            if (negativeButton != null) {
                builder.setNegativeButton(negativeButton, null);
            }
            
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "showItemsDialog error: " + e.getMessage(), e);
        }
    }
    
    public interface ItemClickListener {
        void onItemClick(int position, String item);
    }
    
    // ==================== 1. Power Control Sheet ====================
    
    public void showPowerControlSheet() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    getContext(), R.style.LuxuryBottomSheetDialog);
            
            View sheetView = LayoutInflater.from(getContext()).inflate(
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
            Log.e(TAG, "showPowerControlSheet error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 2. Session Settings Dialog ====================
    
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
            
            showItemsDialog(
                    isArabic ? "⚙️ إعدادات جلسة الجرد" : "⚙️ Session Settings",
                    options,
                    (position, item) -> {
                        switch (position) {
                            case 0:
                                Toast.makeText(getContext(),
                                        isArabic ? "⏱️ قيد التطوير" : "⏱️ In development",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 4:
                                if (callback != null) {
                                    callback.onSessionReset();
                                }
                                break;
                            default:
                                Toast.makeText(getContext(),
                                        isArabic ? "قيد التطوير" : "In development",
                                        Toast.LENGTH_SHORT).show();
                        }
                    },
                    isArabic ? "إغلاق" : "Close"
            );
        } catch (Exception e) {
            Log.e(TAG, "showSessionSettingsDialog error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 3. Buzzer Settings Dialog ====================
    
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
            
            showItemsDialog(
                    isArabic ? "🔊 إعدادات الصوت" : "🔊 Buzzer Settings",
                    options,
                    (position, item) -> {
                        if (position == 3) {
                            if (soundManager != null) {
                                soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                            }
                            if (callback != null) {
                                callback.onBuzzerTest();
                            }
                        } else {
                            Toast.makeText(getContext(),
                                    isArabic ? "قيد التطوير" : "In development",
                                    Toast.LENGTH_SHORT).show();
                        }
                    },
                    isArabic ? "إغلاق" : "Close"
            );
        } catch (Exception e) {
            Log.e(TAG, "showBuzzerSettingsDialog error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 4. Commit Confirmation Dialog ====================
    
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
            
            new MaterialAlertDialogBuilder(getContext())
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
            Log.e(TAG, "showCommitConfirmationDialog error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 5. Item Options Dialog ====================
    
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
            
            showItemsDialog(
                    item.epc != null ? item.epc : "",
                    options,
                    (position, text) -> {
                        switch (position) {
                            case 0:
                                showEditStatusDialog(item);
                                break;
                            case 1:
                                showDamageDialog(item);
                                break;
                            case 2:
                                Toast.makeText(getContext(),
                                        isArabic ? "📍 قيد التطوير" : "📍 In development",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                Toast.makeText(getContext(),
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
                    },
                    isArabic ? "إغلاق" : "Close"
            );
        } catch (Exception e) {
            Log.e(TAG, "showItemOptionsDialog error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 6. Item Full Details Dialog ====================
    
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
            
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle(isArabic ? "تفاصيل القطعة" : "Item Details")
                    .setMessage(sb.toString())
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showItemFullDetails error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 7. Edit Status Dialog ====================
    
    public void showEditStatusDialog(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            String[] statuses = isArabic ?
                    new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} :
                    new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};
            
            String title = (isArabic ? "تعديل حالة - " : "Edit status - ")
                    + (item.epc != null ? item.epc : "");
            
            showItemsDialog(title, statuses,
                    (position, newStatus) -> {
                        if (callback != null) {
                            callback.onItemStatusChange(item, newStatus);
                        }
                        
                        if (soundManager != null) {
                            soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                        }
                        
                        Toast.makeText(getContext(),
                                (isArabic ? "تم تحديث الحالة: " : "Status updated: ") + newStatus,
                                Toast.LENGTH_SHORT).show();
                    },
                    null
            );
        } catch (Exception e) {
            Log.e(TAG, "showEditStatusDialog error: " + e.getMessage(), e);
        }
    }
    
    // ==================== 8. Damage Dialog ====================
    
    public void showDamageDialog(ScannedItemDisplay item) {
        if (item == null) return;
        
        try {
            String[] damageTypes = isArabic ?
                    new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
                    new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};
            
            String title = (isArabic ? "تسجيل تلف - " : "Register damage - ")
                    + (item.epc != null ? item.epc : "");
            
            showItemsDialog(title, damageTypes,
                    (position, damageType) -> {
                        if (callback != null) {
                            callback.onItemDamageRegister(item, damageType);
                        }
                        
                        if (soundManager != null) {
                            soundManager.playBeep(SoundManager.FeedbackType.ERROR);
                        }
                        
                        Toast.makeText(getContext(),
                                (isArabic ? "تم تسجيل التلف: " : "Damage registered: ") + damageType,
                                Toast.LENGTH_SHORT).show();
                    },
                    null
            );
        } catch (Exception e) {
            Log.e(TAG, "showDamageDialog error: " + e.getMessage(), e);
        }
    }
}
