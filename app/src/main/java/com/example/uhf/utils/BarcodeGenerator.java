package com.example.uhf.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.util.EnumMap;
import java.util.Map;

public class BarcodeGenerator {

    private static final int COLOR_NAVY_DOT = 0xFF0F172A;
    private static final int COLOR_MIRA_GOLD = 0xFFD4AF37;

    public static Bitmap generateGS1DataMatrix(String gs1Data, int width, int height) {
        if (gs1Data == null || gs1Data.trim().isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
            hints.put(EncodeHintType.MARGIN, 0);

            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    gs1Data,
                    BarcodeFormat.DATA_MATRIX,
                    width,
                    height,
                    hints
            );

            int w = bitMatrix.getWidth();
            int h = bitMatrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * الدالة الافتراضية المعتمدة في التطبيق: تُولّد تصميم MIRA الحديث (نقاط دائرية + إطار ذهبي وشعار MIRA)
     */
    public static Bitmap generateMicroQRCode(String qrContent, int width, int height) {
        return generateMicroQRCode(qrContent, width, height, false);
    }

    /**
     * نسخة تدعم التحويل للأسود الصافي عند الطباعة الحرارية (monochromePrint = true)
     */
    public static Bitmap generateMicroQRCode(
            String qrContent,
            int width,
            int height,
            boolean monochromePrint
    ) {
        if (qrContent == null || qrContent.trim().isEmpty() || width <= 0 || height <= 0) {
            return null;
        }

        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            // تصحيح أخطاء High (30%) لحماية البيانات مع وجود الشارة المركزية
            QRCode qrCode = Encoder.encode(qrContent.trim(), ErrorCorrectionLevel.H, hints);
            ByteMatrix matrix = qrCode.getMatrix();
            if (matrix == null) return null;

            int matrixSize = matrix.getWidth();
            int quietZone = 1;
            int totalModules = matrixSize + (quietZone * 2);

            // الرسم بدقة عالية (600px على الأقل) لضمان نعومة الحواف الدائرية
            int renderSize = Math.max(Math.max(width, height), 600);
            float cellSize = (float) renderSize / (float) totalModules;

            Bitmap highResBitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(highResBitmap);

            // 1. خلفية بيضاء نقية
            canvas.drawColor(Color.WHITE);

            int primaryColor = monochromePrint ? Color.BLACK : COLOR_NAVY_DOT;
            int accentRingColor = monochromePrint ? Color.BLACK : COLOR_MIRA_GOLD;

            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(primaryColor);
            dotPaint.setStyle(Paint.Style.FILL);

            float centerX = renderSize / 2f;
            float centerY = renderSize / 2f;
            float badgeRadius = renderSize * 0.132f;
            float exclusionRadius = badgeRadius + (cellSize * 0.55f);

            // 2. رسم نقاط البيانات الدائرية (مع استثناء زوايا التحديد ومنطقة الشعار الأوسط)
            float dotRadius = cellSize * 0.40f;
            for (int y = 0; y < matrixSize; y++) {
                for (int x = 0; x < matrixSize; x++) {
                    if (matrix.get(x, y) != 1) continue;
                    if (isFinderPatternCell(x, y, matrixSize)) continue;

                    float cx = (x + quietZone + 0.5f) * cellSize;
                    float cy = (y + quietZone + 0.5f) * cellSize;

                    if (Math.hypot(cx - centerX, cy - centerY) < exclusionRadius) {
                        continue;
                    }

                    canvas.drawCircle(cx, cy, dotRadius, dotPaint);
                }
            }

            // 3. رسم زوايا التحديد الثلاث (إطار منحني الزوايا + مركز دائري)
            drawModernFinderEye(canvas, quietZone, quietZone, cellSize, primaryColor);
            drawModernFinderEye(canvas, quietZone + matrixSize - 7, quietZone, cellSize, primaryColor);
            drawModernFinderEye(canvas, quietZone, quietZone + matrixSize - 7, cellSize, primaryColor);

            // 4. رسم الشارة الدائرية المركزية بإطار ذهبي وشعار MIRA
            drawCenterBrandBadge(
                    canvas,
                    centerX,
                    centerY,
                    badgeRadius,
                    accentRingColor,
                    primaryColor,
                    "MIRA"
            );

            // تحجيم النتيجة النهائية للأبعاد المطلوبة بدقة
            if (renderSize == width && renderSize == height) {
                return highResBitmap;
            }

            Bitmap finalBitmap = Bitmap.createScaledBitmap(highResBitmap, width, height, true);
            if (highResBitmap != finalBitmap) {
                highResBitmap.recycle();
            }
            return finalBitmap;

        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isFinderPatternCell(int x, int y, int matrixSize) {
        boolean inTopLeft = x < 7 && y < 7;
        boolean inTopRight = x >= matrixSize - 7 && y < 7;
        boolean inBottomLeft = x < 7 && y >= matrixSize - 7;
        return inTopLeft || inTopRight || inBottomLeft;
    }

    private static void drawModernFinderEye(
            Canvas canvas,
            int startModuleX,
            int startModuleY,
            float cellSize,
            int color
    ) {
        float left = startModuleX * cellSize;
        float top = startModuleY * cellSize;
        float eyeSize = 7f * cellSize;
        float halfStroke = cellSize * 0.5f;

        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setColor(color);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(cellSize);

        RectF outerRect = new RectF(
                left + halfStroke,
                top + halfStroke,
                left + eyeSize - halfStroke,
                top + eyeSize - halfStroke
        );
        float cornerRadius = cellSize * 1.85f;
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, ringPaint);

        Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        corePaint.setColor(color);
        corePaint.setStyle(Paint.Style.FILL);

        float eyeCenterX = left + (eyeSize / 2f);
        float eyeCenterY = top + (eyeSize / 2f);
        float coreRadius = cellSize * 1.50f;
        canvas.drawCircle(eyeCenterX, eyeCenterY, coreRadius, corePaint);
    }

    private static void drawCenterBrandBadge(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            int ringColor,
            int textColor,
            String label
    ) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, bgPaint);

        float ringStroke = Math.max(radius * 0.085f, 2.5f);
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setColor(ringColor);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringStroke);
        canvas.drawCircle(cx, cy, radius - (ringStroke / 2f), ringPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setLetterSpacing(0.08f);
        textPaint.setTextSize(radius * 0.54f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textBaselineY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, textBaselineY, textPaint);
    }
}
