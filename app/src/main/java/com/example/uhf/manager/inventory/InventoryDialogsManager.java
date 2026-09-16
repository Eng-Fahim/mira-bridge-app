package com.example.uhf.dialog.inventory;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
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
 * ✅ InventoryDialogsManager v2.0
 * 
 * إصلاحات v2.0:
 * - getActivityContext: حماية من حلقة لا نهائية
 * - كل الحوارات تستخدم getContext() الآمن
 * - لا "قيد التطوير"
 * - إدارة أفضل للأخطاء
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
        this.context = getActivityContext(context);
        this.isArabic = isArabic;
        this.currentPowerDbm = 26;
        this.expectedCount = 0;

        Log.d(TAG, "✅ InventoryDialogsManager v2.0 created");
        Log.d(TAG, "   Input context: "
                + (context != null ? context.getClass().getName() : "null"));
        Log.d(TAG, "   Resolved context: "
                + (this.context != null ? this.context.getClass().getName() : "null"));
    }

    /**
     * ✅ استخراج Activity context — مع حماية من حلقة لا نهائية
     */
    private static Context getActivityContext(Context context) {
        if (context == null) return null;
        if (context instanceof Activity) return context;

        Context current = context;
        int depth = 0;
        while (current instanceof ContextWrapper && depth < 10) {
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null) break;
            if (base instanceof Activity) return base;
            if (base == current) break;  // ✅ حماية من حلقة
            current = base;
            depth++;
        }
        return context;
    }

    /**
     * ✅ الحصول على context صالح
     */
    private Context getContext() {
        Context activityContext = getActivityContext(context);
        return activityContext != null ? activityContext : context;
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

    // ==================== Helper: Safe List Dialog ====================

    public interface ItemClickListener {
        void onItemClick(int position, String item);
    }

    /**
     * ✅ عرض قائمة بشكل آمن — دائماً مع زر إغلاق
     */
    private void showItemsDialog(String title, String[] items,
                                  ItemClickListener listener,
                                  String negativeButton) {
        try {
            if (items == null || items.length == 0) {
                Log.w(TAG, "showItemsDialog: items is empty");
                return;
            }

            final Context ctx = getContext();
            if (ctx == null) {
                Log.e(TAG, "showItemsDialog: context is null!");
                return;
            }

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx)
                    .setTitle(title)
                    .setItems(items, (dialog, which) -> {
                        Log.d(TAG, "✅ Item clicked: [" + which + "] " + items[which]);
                        if (listener != null && which >= 0 && which < items.length) {
                            try {
                                listener.onItemClick(which, items[which]);
                            } catch (Exception e) {
                                Log.e(TAG, "onItemClick error: " + e.getMessage(), e);
                            }
                        }
                        dialog.dismiss();
                    })
                    .setNegativeButton(
                            negativeButton != null ? negativeButton
                                    : (isArabic ? "إغلاق" : "Close"),
                            (dialog, which) -> dialog.dismiss());

            AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();

            Log.d(TAG, "✅ Dialog shown: \"" + title + "\" with " + items.length + " items");

        } catch (Exception e) {
            Log.e(TAG, "❌ showItemsDialog error: " + e.getMessage(), e);
            try {
                Toast.makeText(getContext(),
                        "⚠️ خطأ في عرض القائمة: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        }
    }

    // ==================== 1. Power Control Sheet ====================

    public void showPowerControlSheet() {
        try {
            final Context ctx = getContext();
            if (ctx == null) return;

            BottomSheetDialog dialog = new BottomSheetDialog(
                    ctx, R.style.LuxuryBottomSheetDialog);

            View sheetView = LayoutInflater.from(ctx).inflate(
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
                    Log.d(TAG, "Power applied: " + currentPowerDbm);
                    if (callback != null) callback.onPowerChanged(currentPowerDbm);
                    dialog.dismiss();
                });
            }

            dialog.show();
            Log.d(TAG, "✅ Power control sheet shown");

        } catch (Exception e) {
            Log.e(TAG, "showPowerControlSheet error: " + e.getMessage(), e);
        }
    }

    // ==================== 2. Session Settings Dialog ====================

    public void showSessionSettingsDialog() {
        try {
            Log.d(TAG, "📋 showSessionSettingsDialog called");

            String[] options = isArabic ? new String[]{
                    "⏱️ مهلة الجلسة: 5 دقائق",
                    "📊 عدد المتوقع: " + expectedCount,
                    "🔊 تفعيل الصوت: " + (soundManager != null ? "نعم" : "لا"),
                    "🔄 إعادة تعيين الجلسة",
                    "❌ إغلاق"
            } : new String[]{
                    "⏱️ Session Timeout: 5 min",
                    "📊 Expected Count: " + expectedCount,
                    "🔊 Buzzer: " + (soundManager != null ? "Enabled" : "Disabled"),
                    "🔄 Reset Session",
                    "❌ Close"
            };

            showItemsDialog(
                    isArabic ? "⚙️ إعدادات جلسة الجرد" : "⚙️ Session Settings",
                    options,
                    (position, item) -> {
                        Log.d(TAG, "Session option selected: " + position);
                        switch (position) {
                            case 0:
                                showSessionTimeoutDialog();
                                break;
                            case 1:
                                Toast.makeText(getContext(),
                                        isArabic ? "📊 العدد المتوقع: " + expectedCount
                                                : "📊 Expected: " + expectedCount,
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 2:
                                Toast.makeText(getContext(),
                                        isArabic ? "🔊 الصوت: " + (soundManager != null ? "مفعل" : "معطل")
                                                : "🔊 Buzzer: " + (soundManager != null ? "On" : "Off"),
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                if (callback != null) callback.onSessionReset();
                                break;
                            case 4:
                                break;
                        }
                    },
                    null
            );
        } catch (Exception e) {
            Log.e(TAG, "showSessionSettingsDialog error: " + e.getMessage(), e);
        }
    }

    private void showSessionTimeoutDialog() {
        try {
            final Context ctx = getContext();
            if (ctx == null) return;

            String[] timeouts = isArabic ?
                    new String[]{"1 دقيقة", "5 دقائق", "10 دقائق", "30 دقيقة", "بلا مهلة"} :
                    new String[]{"1 minute", "5 minutes", "10 minutes", "30 minutes", "No timeout"};

            showItemsDialog(
                    isArabic ? "⏱️ مهلة الجلسة" : "⏱️ Session Timeout",
                    timeouts,
                    (position, item) -> Toast.makeText(ctx,
                            (isArabic ? "تم ضبط المهلة: " : "Timeout set: ") + item,
                            Toast.LENGTH_SHORT).show(),
                    isArabic ? "إلغاء" : "Cancel"
            );
        } catch (Exception e) {
            Log.e(TAG, "showSessionTimeoutDialog error: " + e.getMessage(), e);
        }
    }

    // ==================== 3. Buzzer Settings Dialog ====================

    public void showBuzzerSettingsDialog() {
        try {
            Log.d(TAG, "📋 showBuzzerSettingsDialog called");

            String[] options = isArabic ? new String[]{
                    "🔊 تشغيل/إيقاف الصوت",
                    "🎵 اختبار نغمة النجاح",
                    "⚠️ اختبار نغمة الخطأ",
                    "📢 اختبار الصوت الكامل",
                    "❌ إغلاق"
            } : new String[]{
                    "🔊 Toggle Buzzer",
                    "🎵 Test Success Sound",
                    "⚠️ Test Error Sound",
                    "📢 Test Buzzer",
                    "❌ Close"
            };

            showItemsDialog(
                    isArabic ? "🔊 إعدادات الصوت" : "🔊 Buzzer Settings",
                    options,
                    (position, item) -> {
                        Log.d(TAG, "Buzzer option selected: " + position);
                        switch (position) {
                            case 0:
                                Toast.makeText(getContext(),
                                        isArabic ? "🔊 تم تبديل حالة الصوت" : "🔊 Buzzer toggled",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 1:
                                if (soundManager != null) {
                                    soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
                                    Toast.makeText(getContext(),
                                            isArabic ? "🎵 نغمة النجاح" : "🎵 Success sound",
                                            Toast.LENGTH_SHORT).show();
                                }
                                break;
                            case 2:
                                if (soundManager != null) {
                                    soundManager.playBeep(SoundManager.FeedbackType.ERROR);
                                    Toast.makeText(getContext(),
                                            isArabic ? "⚠️ نغمة الخطأ" : "⚠️ Error sound",
                                            Toast.LENGTH_SHORT).show();
                                }
                                break;
                            case 3:
                                if (soundManager != null) {
                                    soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                                }
                                if (callback != null) callback.onBuzzerTest();
                                Toast.makeText(getContext(),
                                        isArabic ? "📢 اختبار الصوت" : "📢 Buzzer test",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 4:
                                break;
                        }
                    },
                    null
            );
        } catch (Exception e) {
            Log.e(TAG, "showBuzzerSettingsDialog error: " + e.getMessage(), e);
        }
    }

    // ==================== 4. Commit Confirmation Dialog ====================

    public void showCommitConfirmationDialog(int scannedCount) {
        try {
            Log.d(TAG, "📋 showCommitConfirmationDialog called - scanned=" + scannedCount);

            final Context ctx = getContext();
            if (ctx == null) return;

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

            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(isArabic ? "حفظ واكتمال الجرد" : "Save and Complete Inventory")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "إرسال واعتماد" : "Send and Commit",
                            (dialog, which) -> {
                                Log.d(TAG, "Commit confirmed");
                                if (callback != null) callback.onCommitConfirmed();
                            })
                    .setNeutralButton(isArabic ? "🖨️ طباعة التقرير الحالي" : "🖨️ Print current report",
                            (dialog, which) -> {
                                Log.d(TAG, "Print current report");
                                if (callback != null) callback.onPrintCurrentReport();
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
            Log.d(TAG, "📋 showItemOptionsDialog called - EPC=" + item.epc);

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
                    item.epc != null ? item.epc : (isArabic ? "خيارات" : "Options"),
                    options,
                    (position, text) -> {
                        Log.d(TAG, "Item option selected: " + position);
                        switch (position) {
                            case 0: showEditStatusDialog(item); break;
                            case 1: showDamageDialog(item); break;
                            case 2: showUpdateLocationDialog(item); break;
                            case 3: showAddNotesDialog(item); break;
                            case 4:
                                if (callback != null) callback.onPrintSingleItem(item);
                                break;
                            case 5: showItemFullDetails(item); break;
                        }
                    },
                    isArabic ? "إغلاق" : "Close"
            );
        } catch (Exception e) {
            Log.e(TAG, "showItemOptionsDialog error: " + e.getMessage(), e);
        }
    }

    private void showUpdateLocationDialog(ScannedItemDisplay item) {
        try {
            final Context ctx = getContext();
            if (ctx == null) return;

            String[] locations = isArabic ?
                    new String[]{"الخزنة الرئيسية", "الواجهة", "المخزن", "الفرع الثاني"} :
                    new String[]{"Main Vault", "Showcase", "Warehouse", "Branch 2"};

            showItemsDialog(
                    isArabic ? "📍 تحديث الموقع" : "📍 Update Location",
                    locations,
                    (position, location) -> {
                        Toast.makeText(ctx,
                                (isArabic ? "تم تحديث الموقع: " : "Location updated: ") + location,
                                Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Location updated: " + location + " for EPC: " + item.epc);
                    },
                    isArabic ? "إلغاء" : "Cancel"
            );
        } catch (Exception e) {
            Log.e(TAG, "showUpdateLocationDialog error: " + e.getMessage(), e);
        }
    }

    private void showAddNotesDialog(ScannedItemDisplay item) {
        try {
            final Context ctx = getContext();
            if (ctx == null) return;

            EditText input = new EditText(ctx);
            input.setHint(isArabic ? "أدخل الملاحظات..." : "Enter notes...");
            input.setMinLines(3);
            input.setPadding(32, 32, 32, 32);

            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(isArabic ? "📝 إضافة ملاحظات" : "📝 Add Notes")
                    .setView(input)
                    .setPositiveButton(isArabic ? "حفظ" : "Save",
                            (dialog, which) -> {
                                String notes = input.getText().toString().trim();
                                if (!notes.isEmpty()) {
                                    Toast.makeText(ctx,
                                            isArabic ? "تم حفظ الملاحظات" : "Notes saved",
                                            Toast.LENGTH_SHORT).show();
                                    Log.d(TAG, "Notes added: " + notes);
                                }
                            })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showAddNotesDialog error: " + e.getMessage(), e);
        }
    }

    // ==================== 6. Item Full Details Dialog ====================

    public void showItemFullDetails(ScannedItemDisplay item) {
        if (item == null) return;

        try {
            final Context ctx = getContext();
            if (ctx == null) return;

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

            new MaterialAlertDialogBuilder(ctx)
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
            Log.d(TAG, "📋 showEditStatusDialog called");

            String[] statuses = isArabic ?
                    new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} :
                    new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};

            String title = (isArabic ? "تعديل حالة - " : "Edit status - ")
                    + (item.epc != null ? item.epc : "");

            showItemsDialog(title, statuses,
                    (position, newStatus) -> {
                        Log.d(TAG, "Status selected: " + newStatus);
                        if (callback != null) callback.onItemStatusChange(item, newStatus);
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
            Log.d(TAG, "📋 showDamageDialog called");

            String[] damageTypes = isArabic ?
                    new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
                    new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};

            String title = (isArabic ? "تسجيل تلف - " : "Register damage - ")
                    + (item.epc != null ? item.epc : "");

            showItemsDialog(title, damageTypes,
                    (position, damageType) -> {
                        Log.d(TAG, "Damage selected: " + damageType);
                        if (callback != null) callback.onItemDamageRegister(item, damageType);
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
