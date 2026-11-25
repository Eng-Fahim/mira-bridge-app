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

    // ==================== أبعاد الملصق الفعلية (60mm × 18mm) ====================
    // الدقة الافتراضية 203 DPI = 8 بكسل لكل ملم
    private static final float MM_TO_PX = 8.0f;
    private static final int TAG_WIDTH_PX  = Math.round(60 * MM_TO_PX);    // 480 px
    private static final int TAG_HEIGHT_PX = Math.round(18 * MM_TO_PX);    // 144 px

    // تكبير الكود المجهري إلى 13.5mm (108px) مع هامش 2.25mm أعلى وأسفل لضمان قراءة الكاميرا
    private static final int BARCODE_SIZE_PX = Math.round(13.5f * MM_TO_PX); // 108 px
    private static final int MARGIN_PX       = Math.round(1.25f * MM_TO_PX); // 10 px

    public static Bitmap generatePhysicalTagBitmap(String epc, String gs1Data, String qrUrl) {
        try {
            Bitmap tagBitmap = Bitmap.createBitmap(TAG_WIDTH_PX, TAG_HEIGHT_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tagBitmap);
            canvas.drawColor(Color.WHITE);

            // فرشة حادة بدون Anti-Aliasing لضمان تطابق البكسلات مع رأس الطابعة الحرارية
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setAntiAlias(false);
            paint.setFilterBitmap(false);

            Bitmap qrBitmap = null;
            Bitmap dmBitmap = null;

            // تفعيل وضع الطباعة المجهرية الحاد (monochromePrint = true)
            if (qrUrl != null && !qrUrl.isEmpty()) {
                qrBitmap = BarcodeGenerator.generateMicroQRCode(qrUrl, BARCODE_SIZE_PX, BARCODE_SIZE_PX, true);
            }
            if (gs1Data != null && !gs1Data.isEmpty()) {
                dmBitmap = BarcodeGenerator.generateGS1DataMatrix(gs1Data, BARCODE_SIZE_PX, BARCODE_SIZE_PX);
            }

            int barcodeY = (TAG_HEIGHT_PX - BARCODE_SIZE_PX) / 2;

            // 1) رسم الـ QR على اليسار
            if (qrBitmap != null) {
                canvas.drawBitmap(qrBitmap, MARGIN_PX, barcodeY, paint);
            }

            // 2) رسم الـ DataMatrix على اليمين
            if (dmBitmap != null) {
                int dmX = TAG_WIDTH_PX - BARCODE_SIZE_PX - MARGIN_PX;
                canvas.drawBitmap(dmBitmap, dmX, barcodeY, paint);
            }

            // 3) حساب منطقة النص الوسطى
            int textAreaLeft  = (qrBitmap != null) ? (MARGIN_PX + BARCODE_SIZE_PX + 6) : MARGIN_PX;
            int textAreaRight = (dmBitmap != null) ? (TAG_WIDTH_PX - BARCODE_SIZE_PX - MARGIN_PX - 6) : (TAG_WIDTH_PX - MARGIN_PX);
            float centerX = textAreaLeft + ((textAreaRight - textAreaLeft) / 2f);

            // 4) عنوان MIRA
            Paint titlePaint = new Paint();
            titlePaint.setColor(Color.BLACK);
            titlePaint.setAntiAlias(false);
            titlePaint.setTextSize(10f);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("MIRA JEWELRY RFID", centerX, 28f, titlePaint);

            // 5) رقم EPC / التسلسلي
            Paint epcPaint = new Paint();
            epcPaint.setColor(Color.BLACK);
            epcPaint.setAntiAlias(false);
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

            // 6) خط فاصل أسود صافي (بدل الرمادي الذي يتلاشى في الطابعات الحرارية)
            Paint linePaint = new Paint();
            linePaint.setColor(Color.BLACK);
            linePaint.setAntiAlias(false);
            linePaint.setStrokeWidth(1f);
            canvas.drawLine(textAreaLeft + 4, 88f, textAreaRight - 4, 88f, linePaint);

            // 7) GS1 المختصر بالأسود الصافي
            Paint gs1Paint = new Paint();
            gs1Paint.setColor(Color.BLACK);
            gs1Paint.setAntiAlias(false);
            gs1Paint.setTextSize(8f);
            gs1Paint.setTextAlign(Paint.Align.CENTER);
            if (gs1Data != null && gs1Data.length() > 10) {
                String shortGs1 = gs1Data.length() > 22 ? gs1Data.substring(0, 22) + "..." : gs1Data;
                canvas.drawText(shortGs1, centerX, 106f, gs1Paint);
            }

            return tagBitmap;

        } catch (Exception e) {
            Log.e(TAG, "generatePhysicalTagBitmap error: " + e.getMessage(), e);
            return null;
        }
    }

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
