package com.example.uhf.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;
import android.widget.Toast;

public class TagPrinter {

    private static final String TAG = "TagPrinter";

    // ==================== ✅ أبعاد الملصق الفعلية (60mm × 18mm) ====================
    // الدقة الافتراضية 203 DPI = 8 بكسل لكل ملم
    private static final float MM_TO_PX = 8.0f;
    private static final int TAG_WIDTH_PX  = Math.round(60 * MM_TO_PX);  // 480 px
    private static final int TAG_HEIGHT_PX = Math.round(18 * MM_TO_PX);  // 144 px

    // أحجام العناصر
    private static final int BARCODE_SIZE_PX = Math.round(11 * MM_TO_PX); // 88 px (مناسب لـ 18mm)
    private static final int MARGIN_PX       = Math.round(1.5f * MM_TO_PX); // 12 px

    /**
     * ✅ توليد صورة الملصق بالأبعاد الصحيحة (60mm × 18mm)
     *
     * التخطيط:
     * ┌──────────────────────────────────────────────────┐
     * │ [QR]      EPC: 3008A076...           [DM]        │
     * │           MIRA JEWELRY RFID                      │
     * └──────────────────────────────────────────────────┘
     */
    public static Bitmap generatePhysicalTagBitmap(String epc, String gs1Data, String qrUrl) {
        try {
            // 1) إنشاء Bitmap بالأبعاد الفعلية
            Bitmap tagBitmap = Bitmap.createBitmap(TAG_WIDTH_PX, TAG_HEIGHT_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tagBitmap);
            canvas.drawColor(Color.WHITE);

            // 2) إعداد فرشة أساسية (بدون anti-alias للحصول على حواف حادة للباركود)
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setAntiAlias(false);
            paint.setFilterBitmap(false);

            // 3) توليد الباركودات
            Bitmap qrBitmap = null;
            Bitmap dmBitmap = null;

            if (qrUrl != null && !qrUrl.isEmpty()) {
                qrBitmap = BarcodeGenerator.generateMicroQRCode(qrUrl, BARCODE_SIZE_PX, BARCODE_SIZE_PX);
            }
            if (gs1Data != null && !gs1Data.isEmpty()) {
                dmBitmap = BarcodeGenerator.generateGS1DataMatrix(gs1Data, BARCODE_SIZE_PX, BARCODE_SIZE_PX);
            }

            int barcodeY = (TAG_HEIGHT_PX - BARCODE_SIZE_PX) / 2;

            // 4) رسم الـ QR على اليسار
            if (qrBitmap != null) {
                canvas.drawBitmap(qrBitmap, MARGIN_PX, barcodeY, paint);
            }

            // 5) رسم الـ DataMatrix على اليمين
            if (dmBitmap != null) {
                int dmX = TAG_WIDTH_PX - BARCODE_SIZE_PX - MARGIN_PX;
                canvas.drawBitmap(dmBitmap, dmX, barcodeY, paint);
            }

            // 6) حساب منطقة النص الوسطى
            int textAreaLeft  = (qrBitmap != null) ? (MARGIN_PX + BARCODE_SIZE_PX + 6) : MARGIN_PX;
            int textAreaRight = (dmBitmap != null) ? (TAG_WIDTH_PX - BARCODE_SIZE_PX - 6) : (TAG_WIDTH_PX - MARGIN_PX);
            int textAreaWidth = textAreaRight - textAreaLeft;
            float centerX = textAreaLeft + (textAreaWidth / 2f);

            // 7) رسم عنوان MIRA (أعلى النص)
            Paint titlePaint = new Paint();
            titlePaint.setColor(Color.BLACK);
            titlePaint.setAntiAlias(true);
            titlePaint.setTextSize(10f);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("MIRA JEWELRY RFID", centerX, 28f, titlePaint);

            // 8) رسم EPC (مقسم على سطرين إذا لزم)
            Paint epcPaint = new Paint();
            epcPaint.setColor(Color.BLACK);
            epcPaint.setAntiAlias(true);
            epcPaint.setTextSize(12f);
            epcPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            epcPaint.setTextAlign(Paint.Align.CENTER);

            String epcLine1 = epc;
            String epcLine2 = "";
            if (epc != null && epc.length() > 14) {
                epcLine1 = epc.substring(0, 14);
                epcLine2 = epc.substring(14);
            }
            canvas.drawText(epcLine1 != null ? epcLine1 : "", centerX, 55f, epcPaint);
            if (!epcLine2.isEmpty()) {
                canvas.drawText(epcLine2, centerX, 72f, epcPaint);
            }

            // 9) رسم خط فاصل
            Paint linePaint = new Paint();
            linePaint.setColor(Color.LTGRAY);
            linePaint.setStrokeWidth(1f);
            canvas.drawLine(textAreaLeft + 4, 88f, textAreaRight - 4, 88f, linePaint);

            // 10) رسم GS1 المختصر (أسفل)
            Paint gs1Paint = new Paint();
            gs1Paint.setColor(Color.DKGRAY);
            gs1Paint.setAntiAlias(true);
            gs1Paint.setTextSize(8f);
            gs1Paint.setTextAlign(Paint.Align.CENTER);
            if (gs1Data != null && gs1Data.length() > 10) {
                String shortGs1 = gs1Data.length() > 22 ? gs1Data.substring(0, 22) + "..." : gs1Data;
                canvas.drawText(shortGs1, centerX, 105f, gs1Paint);
            }

            // ✅ لا نستدعي recycle() لأن الـ Canvas قد يكون لا يزال يستخدمها
            return tagBitmap;

        } catch (Exception e) {
            Log.e(TAG, "generatePhysicalTagBitmap error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * ⚠️ ملاحظة مهمة: PrintHelper لا يدعم طباعة الملصقات الحرارية بشكل صحيح.
     * هذه الدالة تعمل فقط مع طابعات Android المدعومة (PDF/Wi-Fi/Cloud).
     *
     * للطباعة الفعلية على طابعة ملصقات حرارية (Zebra, Xprinter, ...)
     * يجب استخدام SDK الخاص بالطابعة (Bluetooth/Wi-Fi) أو أوامر TSPL/ZPL/CPCL.
     */
    public static void printTagDirectly(Context context, Bitmap tagBitmap) {
        if (context == null || tagBitmap == null) return;

        try {
            if (!androidx.print.PrintHelper.systemSupportsPrint()) {
                Toast.makeText(context,
                        "⚠️ الطباعة غير مدعومة على هذا الجهاز، يرجى استخدام طابعة ملصقات حرارية",
                        Toast.LENGTH_LONG).show();
                return;
            }

            androidx.print.PrintHelper printHelper = new androidx.print.PrintHelper(context);
            printHelper.setScaleMode(androidx.print.PrintHelper.SCALE_MODE_FIT);
            printHelper.setColorMode(androidx.print.PrintHelper.COLOR_MODE_MONOCHROME);
            printHelper.setOrientation(androidx.print.PrintHelper.ORIENTATION_LANDSCAPE);
            printHelper.printBitmap("MIRA_TAG_60x18mm", tagBitmap);

        } catch (Exception e) {
            Log.e(TAG, "printTagDirectly error: " + e.getMessage(), e);
            Toast.makeText(context, "❌ فشل إرسال أمر الطباعة: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
