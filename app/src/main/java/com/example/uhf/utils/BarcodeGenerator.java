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

    /**
     * توليد GS1 DataMatrix متوافق قياسياً مع FNC1 وبمضاعف بكسل صحيح للطباعة المجهرية
     */
    public static Bitmap generateGS1DataMatrix(String gs1Data, int width, int height) {
        if (gs1Data == null || gs1Data.trim().isEmpty() || width <= 0 || height <= 0) return null;
        try {
            String cleanData = gs1Data.trim();
            // إزالة FNC1 البادئة إن وجدت لأن تفعيل GS1_FORMAT يضيفها تلقائياً وفق المعيار
            if (cleanData.startsWith("\u001D")) {
                cleanData = cleanData.substring(1);
            }

            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
            hints.put(EncodeHintType.GS1_FORMAT, Boolean.TRUE);
            hints.put(EncodeHintType.MARGIN, 1);

            // طلب المصفوفة بأقل أبعاد للحصول على عدد الخانات الفعلي ثم تكبيرها بمضاعف صحيح
            BitMatrix rawMatrix = new MultiFormatWriter().encode(
                    cleanData,
                    BarcodeFormat.DATA_MATRIX,
                    1,
                    1,
                    hints
            );

            int mw = rawMatrix.getWidth();
            int mh = rawMatrix.getHeight();
            int targetSize = Math.min(width, height);
            int scale = Math.max(1, targetSize / Math.max(mw, mh));

            int exactW = mw * scale;
            int exactH = mh * scale;
            int offsetX = (width - exactW) / 2;
            int offsetY = (height - exactH) / 2;

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setAntiAlias(false);

            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    if (rawMatrix.get(x, y)) {
                        float left = offsetX + (x * scale);
                        float top = offsetY + (y * scale);
                        canvas.drawRect(left, top, left + scale, top + scale, paint);
                    }
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap generateMicroQRCode(String qrContent, int width, int height) {
        return generateMicroQRCode(qrContent, width, height, false);
    }

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

            ErrorCorrectionLevel ecLevel = monochromePrint
                    ? ErrorCorrectionLevel.M
                    : ErrorCorrectionLevel.H;

            QRCode qrCode = Encoder.encode(qrContent.trim(), ecLevel, hints);
            ByteMatrix matrix = qrCode.getMatrix();
            if (matrix == null) return null;

            int matrixSize = matrix.getWidth();

            if (monochromePrint) {
                return renderPixelExactPrintQr(matrix, matrixSize, width, height);
            } else {
                return renderLuxuryScreenQr(matrix, matrixSize, 1, matrixSize + 2, width, height);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * رسم QR بمضاعف بكسل صحيح 1:1 بدون أي تحجيم كسري لضمان حدة 100% على الطابعة الحرارية
     */
    private static Bitmap renderPixelExactPrintQr(
            ByteMatrix matrix,
            int matrixSize,
            int targetWidth,
            int targetHeight
    ) {
        int targetSize = Math.min(targetWidth, targetHeight);
        // ترك هامش أمان 2 خانة على الأقل من كل جانب لسرعة التقاط الكاميرا
        int minQuietModules = 2;
        int totalModules = matrixSize + (minQuietModules * 2);

        // حساب أكبر مضاعف صحيح للبكسل الواحد (مثلاً 3px لكل نقطة بدون أي كسور)
        int modulePx = Math.max(2, targetSize / totalModules);
        int exactQrPx = modulePx * matrixSize;

        int offsetX = (targetWidth - exactQrPx) / 2;
        int offsetY = (targetHeight - exactQrPx) / 2;

        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint dotPaint = new Paint();
        dotPaint.setColor(Color.BLACK);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(false);

        // نقطة مستديرة الزوايا عريضة (94% من الخانة) تمنع تلاصق الحبر وتعطي مظهراً حديثاً
        float inset = modulePx * 0.03f;
        float corner = modulePx * 0.38f;
        RectF cellRect = new RectF();

        for (int y = 0; y < matrixSize; y++) {
            for (int x = 0; x < matrixSize; x++) {
                if (matrix.get(x, y) != 1) continue;
                if (isFinderPatternCell(x, y, matrixSize)) continue;

                float left = offsetX + (x * modulePx) + inset;
                float top = offsetY + (y * modulePx) + inset;
                float right = offsetX + ((x + 1) * modulePx) - inset;
                float bottom = offsetY + ((y + 1) * modulePx) - inset;

                cellRect.set(left, top, right, bottom);
                canvas.drawRoundRect(cellRect, corner, corner, dotPaint);
            }
        }

        drawModernFinderEyePx(canvas, offsetX, offsetY, modulePx, Color.BLACK, false);
        drawModernFinderEyePx(canvas, offsetX + (matrixSize - 7) * modulePx, offsetY, modulePx, Color.BLACK, false);
        drawModernFinderEyePx(canvas, offsetX, offsetY + (matrixSize - 7) * modulePx, modulePx, Color.BLACK, false);

        return bitmap;
    }

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

        float qOffset = quietZone * cellSize;
        drawModernFinderEyePx(canvas, qOffset, qOffset, cellSize, COLOR_NAVY_DOT, true);
        drawModernFinderEyePx(canvas, qOffset + (matrixSize - 7) * cellSize, qOffset, cellSize, COLOR_NAVY_DOT, true);
        drawModernFinderEyePx(canvas, qOffset, qOffset + (matrixSize - 7) * cellSize, cellSize, COLOR_NAVY_DOT, true);

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

    private static void drawModernFinderEyePx(
            Canvas canvas,
            float left,
            float top,
            float cellSize,
            int color,
            boolean antiAlias
    ) {
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
