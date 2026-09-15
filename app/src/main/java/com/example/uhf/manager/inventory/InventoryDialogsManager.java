package com.example.uhf.dialog.inventory;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
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
 * ✅ InventoryDialogsManager - الإصدار 3.0
 *
 * ✅ الإصلاحات في 3.0:
 * - استخراج Activity context بشكل صحيح (من أي ContextWrapper متداخل)
 * - استخدام AlertDialog.Builder العادي بدل MaterialAlertDialogBuilder للقوائم
 * - استخدام setItems بدل setAdapter (أكثر استقراراً)
 * - Logs شاملة لتشخيص المشاكل
 * - عرض Dialog بشكل صريح (create + show)
 * - حماية كاملة من null context
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

        Log.d(TAG, "✅ InventoryDialogsManager created");
        Log.d(TAG, "   Context type: " + (context != null ? context.getClass().getName() : "null"));
        Log.d(TAG, "   Resolved context: " + (this.context != null ? this.context.getClass().getName() : "null"));
    }

    /**
     * ✅ استخراج Activity context من أي Context متداخل
     */
    private static Context getActivityContext(Context context) {
        if (context == null) return null;

        // 1) Activity مباشرة
        if (context instanceof Activity) {
            return context;
        }

        // 2) ابحث داخل ContextWrapper بشكل متكرر (حتى 5 مستويات)
        Context current = context;
        int depth = 0;
        while (current instanceof ContextWrapper && depth < 5) {
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null) break;
            if (base instanceof Activity) {
                return base;
            }
            current = base;
            depth++;
        }

        // 3) Fallback: أعِد context الأصلي
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
        Log.d(TAG, "✅ Callback set: " + (callback != null));
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

    /**
     * ✅ عرض قائمة بشكل آمن (باستخدام AlertDialog.Builder + setItems)
     *    يعمل على كل الإصدارات ولا يحتاج item_dialog_list.xml
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

            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setTitle(title);

            builder.setItems(items, (dialog, which) -> {
                Log.d(TAG, "✅ Item clicked: [" + which + "] " + items[which]);

                if (listener != null && which >= 0 && which < items.length) {
                    try {
                        listener.onItemClick(which, items[which]);
                    } catch (Exception e) {
                        Log.e(TAG, "onItemClick error: " + e.getMessage(), e);
                    }
                }
                dialog.dismiss();
            });

            if (negativeButton != null) {
                builder.setNegativeButton(negativeButton,
                        (dialog, which) -> dialog.dismiss());
            }

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

    public interface ItemClickListener {
        void onItemClick(int position, String item);
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
                    if (callback != null) {
                        callback.onPowerChanged(currentPowerDbm);
                    }
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
            Log.d(TAG, "   callback = " + (callback != null));
            Log.d(TAG, "   expectedCount = " + expectedCount);

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
                        Log.d(TAG, "Session option selected: " + position);

                        switch (position) {
                            case 0:
                            case 1:
                            case 2:
                            case 3:
                                Toast.makeText(getContext(),
                                        isArabic ? "⚙️ " + item + " - قيد التطوير"
                                                : "⚙️ " + item + " - In development",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 4:
                                Log.d(TAG, "Calling onSessionReset");
                                if (callback != null) {
                                    callback.onSessionReset();
                                } else {
                                    Log.e(TAG, "❌ callback is null!");
                                    Toast.makeText(getContext(),
                                            isArabic ? "خطأ: callback غير مربوط" : "Error: callback not set",
                                            Toast.LENGTH_SHORT).show();
                                }
                                break;
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
            Log.d(TAG, "📋 showBuzzerSettingsDialog called");

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
                        Log.d(TAG, "Buzzer option selected: " + position);

                        if (position == 3) {
                            Log.d(TAG, "Testing buzzer");
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
                                if (callback != null) {
                                    callback.onCommitConfirmed();
                                }
                            })
                    .setNeutralButton(isArabic ? "🖨️ طباعة التقرير الحالي" : "🖨️ Print current report",
                            (dialog, which) -> {
                                Log.d(TAG, "Print current report");
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
                            case 0:
                                showEditStatusDialog(item);
                                break;
                            case 1:
                                showDamageDialog(item);
                                break;
                            case 2:
                                Toast.makeText(getContext(),
                                        isArabic ? "📍 تحديث الموقع - قيد التطوير"
                                                : "📍 Update Location - In development",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                Toast.makeText(getContext(),
                                        isArabic ? "📝 إضافة ملاحظات - قيد التطوير"
                                                : "📝 Add Notes - In development",
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
            Log.d(TAG, "📋 showDamageDialog called");

            String[] damageTypes = isArabic ?
                    new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
                    new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};

            String title = (isArabic ? "تسجيل تلف - " : "Register damage - ")
                    + (item.epc != null ? item.epc : "");

            showItemsDialog(title, damageTypes,
                    (position, damageType) -> {
                        Log.d(TAG, "Damage selected: " + damageType);

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
