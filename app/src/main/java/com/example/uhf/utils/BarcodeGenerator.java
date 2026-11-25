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
     * الوضع الافتراضي للعرض الرقمي في التطبيق (تصميم حديث كامل مع الشارة الذهبية وشعار MIRA)
     */
    public static Bitmap generateMicroQRCode(String qrContent, int width, int height) {
        return generateMicroQRCode(qrContent, width, height, false);
    }

    /**
     * @param monochromePrint عند تمرير true (للطباعة الحرارية المجهرية):
     *                        - يعتمد تصحيح أخطاء M لتكبير حجم النقاط لأقصى حد
     *                        - يرسم بحواف حادة 1-bit بدون Anti-Aliasing لمنع تلاصق الحبر
     *                        - يستبعد الشعار الأوسط لضمان قراءة فورية بكاميرا الهاتف
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

            // في الطباعة المجهرية نستخدم M لتقليل عدد الخانات وتكبير النقطة الواحدة
            ErrorCorrectionLevel ecLevel = monochromePrint
                    ? ErrorCorrectionLevel.M
                    : ErrorCorrectionLevel.H;

            QRCode qrCode = Encoder.encode(qrContent.trim(), ecLevel, hints);
            ByteMatrix matrix = qrCode.getMatrix();
            if (matrix == null) return null;

            int matrixSize = matrix.getWidth();
            int quietZone = 1;
            int totalModules = matrixSize + (quietZone * 2);

            if (monochromePrint) {
                return renderMicroscopicPrintQr(matrix, matrixSize, quietZone, totalModules, width, height);
            } else {
                return renderLuxuryScreenQr(matrix, matrixSize, quietZone, totalModules, width, height);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * محرك الطباعة المجهرية (203 DPI): نقاط عريضة حادة + زوايا حديثة بدون ضبابية
     */
    private static Bitmap renderMicroscopicPrintQr(
            ByteMatrix matrix,
            int matrixSize,
            int quietZone,
            int totalModules,
            int targetWidth,
            int targetHeight
    ) {
        int targetSize = Math.min(targetWidth, targetHeight);
        // مضاعف صحيح لكل خانة لضمان تطابق البكسلات مع رأس الطابعة الحرارية 1:1
        int modulePx = Math.max(3, (int) Math.ceil((double) targetSize / (double) totalModules));
        int exactCanvasSize = modulePx * totalModules;

        Bitmap crispBitmap = Bitmap.createBitmap(exactCanvasSize, exactCanvasSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(crispBitmap);
        canvas.drawColor(Color.WHITE);

        // إيقاف Anti-Aliasing تماماً لمنع البكسلات الرمادية عند الطباعة الحرارية
        Paint dotPaint = new Paint();
        dotPaint.setColor(Color.BLACK);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(false);

        // نقطة دائرية/منحنية عريضة تغطي 90% من الخانة لضمان وضوحها للكاميرا
        float inset = modulePx * 0.06f;
        float dotCorner = modulePx * 0.42f;
        RectF cellRect = new RectF();

        for (int y = 0; y < matrixSize; y++) {
            for (int x = 0; x < matrixSize; x++) {
                if (matrix.get(x, y) != 1) continue;
                if (isFinderPatternCell(x, y, matrixSize)) continue;

                float left = (x + quietZone) * modulePx + inset;
                float top = (y + quietZone) * modulePx + inset;
                float right = (x + quietZone + 1) * modulePx - inset;
                float bottom = (y + quietZone + 1) * modulePx - inset;

                cellRect.set(left, top, right, bottom);
                canvas.drawRoundRect(cellRect, dotCorner, dotCorner, dotPaint);
            }
        }

        // رسم زوايا التحديد الثلاث بتصميم حديث وحواف حادة
        drawModernFinderEye(canvas, quietZone, quietZone, modulePx, Color.BLACK, false);
        drawModernFinderEye(canvas, quietZone + matrixSize - 7, quietZone, modulePx, Color.BLACK, false);
        drawModernFinderEye(canvas, quietZone, quietZone + matrixSize - 7, modulePx, Color.BLACK, false);

        if (exactCanvasSize == targetWidth && exactCanvasSize == targetHeight) {
            return crispBitmap;
        }

        // filter = false للحفاظ على حدة البكسل 100% دون تنعيم
        Bitmap finalBitmap = Bitmap.createScaledBitmap(crispBitmap, targetWidth, targetHeight, false);
        if (crispBitmap != finalBitmap) {
            crispBitmap.recycle();
        }
        return finalBitmap;
    }

    /**
     * محرك العرض الرقمي الفاخر للشاشات (نقاط دائرية + إطار ذهبي وشعار MIRA)
     */
    private static Bitmap renderLuxuryScreenQr(
            ByteMatrix matrix,
            int matrixSize,
            int quietZone,
            int totalModules,
            int targetWidth,
            int targetHeight
    ) {
        int renderSize = Math.max(Math.max(targetWidth, targetHeight), 600);
        float cellSize = (float) renderSize / (float) totalModules;

        Bitmap highResBitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(highResBitmap);
        canvas.drawColor(Color.WHITE);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(COLOR_NAVY_DOT);
        dotPaint.setStyle(Paint.Style.FILL);

        float centerX = renderSize / 2f;
        float centerY = renderSize / 2f;
        float badgeRadius = renderSize * 0.132f;
        float exclusionRadius = badgeRadius + (cellSize * 0.55f);

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

        drawModernFinderEye(canvas, quietZone, quietZone, cellSize, COLOR_NAVY_DOT, true);
        drawModernFinderEye(canvas, quietZone + matrixSize - 7, quietZone, cellSize, COLOR_NAVY_DOT, true);
        drawModernFinderEye(canvas, quietZone, quietZone + matrixSize - 7, cellSize, COLOR_NAVY_DOT, true);

        drawCenterBrandBadge(
                canvas,
                centerX,
                centerY,
                badgeRadius,
                COLOR_MIRA_GOLD,
                COLOR_NAVY_DOT,
                "MIRA"
        );

        if (renderSize == targetWidth && renderSize == targetHeight) {
            return highResBitmap;
        }

        Bitmap finalBitmap = Bitmap.createScaledBitmap(highResBitmap, targetWidth, targetHeight, true);
        if (highResBitmap != finalBitmap) {
            highResBitmap.recycle();
        }
        return finalBitmap;
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
            int color,
            boolean antiAlias
    ) {
        float left = startModuleX * cellSize;
        float top = startModuleY * cellSize;
        float eyeSize = 7f * cellSize;
        float halfStroke = cellSize * 0.5f;

        Paint ringPaint = new Paint();
        ringPaint.setAntiAlias(antiAlias);
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

        Paint corePaint = new Paint();
        corePaint.setAntiAlias(antiAlias);
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
