package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * ✅ مدير مشاركة السندات بصيغة JSON مع RFID و DataMatrix
 */
public class MiraVoucherShareManager {

    private static final String TAG = "MiraVoucherShareManager";
    private static MiraVoucherShareManager instance;
    private final Context context;

    private MiraVoucherShareManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static MiraVoucherShareManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraVoucherShareManager(context);
        }
        return instance;
    }

    /**
     * ✅ مشاركة سند كـ JSON
     */
    public void shareVoucherAsJson(JSONObject voucherData) {
        try {
            // ✅ إضافة بيانات إضافية
            JSONObject enrichedData = enrichWithRfidAndDataMatrix(voucherData);
            
            // ✅ تحويل المبلغ إلى كلمات عربية
            double amount = enrichedData.optDouble("final_amount", 0);
            String amountInWords = ArabicNumberToWords.convert(amount, "ريال يمني");
            enrichedData.put("amount_in_words", amountInWords);
            
            // ✅ إنشاء ملف JSON
            String jsonString = enrichedData.toString(2); // تنسيق جميل
            
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), 
                "voucher_" + System.currentTimeMillis() + ".json");
            
            FileWriter writer = new FileWriter(file);
            writer.write(jsonString);
            writer.close();
            
            // ✅ مشاركة الملف
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            shareIntent.putExtra(Intent.EXTRA_TEXT, jsonString);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "سند: " + 
                enrichedData.optString("sale_number", "Voucher"));
            
            context.startActivity(Intent.createChooser(shareIntent, "مشاركة السند"));
            
        } catch (Exception e) {
            Log.e(TAG, "Error sharing voucher: " + e.getMessage());
        }
    }

    /**
     * ✅ إضافة RFID و DataMatrix للسند
     */
    private JSONObject enrichWithRfidAndDataMatrix(JSONObject voucherData) {
        try {
            JSONArray items = voucherData.optJSONArray("items");
            
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    
                    String serial = item.optString("serial", "");
                    String gtin = item.optString("gtin", "");
                    String epc = item.optString("epc", "");
                    
                    // ✅ إضافة RFID EPC
                    if (!epc.isEmpty()) {
                        item.put("rfid_epc", epc);
                    }
                    
                    // ✅ إضافة GS1 DataMatrix
                    if (!gtin.isEmpty()) {
                        String dataMatrix = buildGS1DataMatrix(gtin, serial);
                        item.put("gs1_datamatrix", dataMatrix);
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error enriching voucher: " + e.getMessage());
        }
        
        return voucherData;
    }

    /**
     * ✅ بناء GS1 DataMatrix string
     */
    private String buildGS1DataMatrix(String gtin, String serial) {
        StringBuilder sb = new StringBuilder();
        sb.append("01").append(gtin.length() == 14 ? gtin : String.format("%014d", Long.parseLong(gtin)));
        
        if (serial != null && !serial.isEmpty()) {
            sb.append("21").append(serial);
        }
        
        return sb.toString();
    }
}
