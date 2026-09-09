package com.example.uhf.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.widget.Toast;

import androidx.print.PrintHelper;

public class TagPrinter {

    private static final float MM_TO_PX = 8.0f;

    public static Bitmap generatePhysicalTagBitmap(String epc, String gs1Data, String qrUrl) {
        int cardWidthPx = Math.round(30 * MM_TO_PX);
        int cardHeightPx = Math.round(15 * MM_TO_PX);
        int barcodeSizePx = Math.round(8 * MM_TO_PX);
        
        Bitmap tagBitmap = Bitmap.createBitmap(cardWidthPx, cardHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tagBitmap);
        canvas.drawColor(Color.WHITE);

        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.BLACK);
        titlePaint.setAntiAlias(true);
        titlePaint.setTextSize(2.2f * MM_TO_PX);
        titlePaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText("MIRA JEWELRY RFID", cardWidthPx / 2f, 3 * MM_TO_PX, titlePaint);

        if (gs1Data != null && !gs1Data.isEmpty()) {
            Bitmap dmBitmap = BarcodeGenerator.generateGS1DataMatrix(gs1Data, barcodeSizePx, barcodeSizePx);
            if (dmBitmap != null) {
                canvas.drawBitmap(dmBitmap, Math.round(1 * MM_TO_PX), Math.round(4.5f * MM_TO_PX), null);
                dmBitmap.recycle();
            }
        }

        int rfidCenterX = Math.round(15 * MM_TO_PX);
        int rfidCenterY = Math.round(8.5f * MM_TO_PX);

        Paint rfidPaint = new Paint();
        rfidPaint.setColor(Color.LTGRAY);
        rfidPaint.setStyle(Paint.Style.STROKE);
        rfidPaint.setStrokeWidth(1f);

        int rfidWidth = Math.round(7 * MM_TO_PX);
        int rfidHeight = Math.round(7 * MM_TO_PX);
        canvas.drawRect(rfidCenterX - rfidWidth/2f, rfidCenterY - rfidHeight/2f, 
                        rfidCenterX + rfidWidth/2f, rfidCenterY + rfidHeight/2f, rfidPaint);

        Paint chipPaint = new Paint();
        chipPaint.setColor(Color.BLACK);
        canvas.drawCircle(rfidCenterX, rfidCenterY, 1.5f * MM_TO_PX, chipPaint);

        if (qrUrl != null && !qrUrl.isEmpty()) {
            Bitmap qrBitmap = BarcodeGenerator.generateMicroQRCode(qrUrl, barcodeSizePx, barcodeSizePx);
            if (qrBitmap != null) {
                int qrX = cardWidthPx - barcodeSizePx - Math.round(1 * MM_TO_PX);
                canvas.drawBitmap(qrBitmap, qrX, Math.round(4.5f * MM_TO_PX), null);
                qrBitmap.recycle();
            }
        }

        Paint labelPaint = new Paint();
        labelPaint.setColor(Color.BLACK);
        labelPaint.setAntiAlias(true);
        labelPaint.setTextSize(1.8f * MM_TO_PX);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText("GS1", 5 * MM_TO_PX, 14 * MM_TO_PX, labelPaint);
        canvas.drawText("QR", cardWidthPx - (5 * MM_TO_PX), 14 * MM_TO_PX, labelPaint);

        return tagBitmap;
    }

    public static void printTagDirectly(Context context, Bitmap tagBitmap) {
        if (context == null || tagBitmap == null) return;
        try {
            if (!PrintHelper.systemSupportsPrint()) {
                Toast.makeText(context, "⚠️ Printing is not supported on this device", Toast.LENGTH_SHORT).show();
                return;
            }

            PrintHelper printHelper = new PrintHelper(context);
            printHelper.setScaleMode(PrintHelper.SCALE_MODE_FILL);
            printHelper.setColorMode(PrintHelper.COLOR_MODE_MONOCHROME);
            printHelper.setOrientation(PrintHelper.ORIENTATION_LANDSCAPE);
            
            printHelper.printBitmap("MIRA_TAG_30x15mm", tagBitmap);
        } catch (Exception e) {
            Toast.makeText(context, "❌ Print request failed", Toast.LENGTH_SHORT).show();
        }
    }
}
