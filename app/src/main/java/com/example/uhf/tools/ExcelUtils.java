package com.example.uhf.tools;

import android.util.Log;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Created by Administrator on 2019-1-23.
 * Modified to use Apache POI instead of jxl
 */
public class ExcelUtils {
    private Workbook wb = null;
    private File excelFile = null;
    public static String TAG = "ExcelUtils";

    public ExcelUtils() {
    }

    /**
     * إنشاء ملف Excel جديد مع العناوين
     */
    public void createExcel(File file, String[] head) {
        excelFile = file;
        FileOutputStream fileOut = null;
        
        try {
            // حذف الملف القديم إذا كان موجوداً
            if (file.exists()) {
                file.delete();
            }
            
            // إنشاء مصنف Excel جديد (XLSX)
            wb = new XSSFWorkbook();
            Sheet ws = wb.createSheet("sheet1");
            
            // إنشاء صف العناوين
            Row headerRow = ws.createRow(0);
            for (int k = 0; k < head.length; k++) {
                headerRow.createCell(k).setCellValue(head[k]);
                // ضبط عرض العمود تلقائياً
                ws.setColumnWidth(k, 20 * 256);
            }
            
            // حفظ الملف
            fileOut = new FileOutputStream(file);
            wb.write(fileOut);
            
            Log.d(TAG, "تم إنشاء ملف Excel بنجاح: " + file.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "خطأ في إنشاء ملف Excel: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (fileOut != null) {
                    fileOut.flush();
                    fileOut.close();
                }
                if (wb != null) {
                    wb.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "خطأ في إغلاق الملف: " + e.getMessage());
            }
        }
    }

    /**
     * كتابة بيانات إلى ملف Excel
     */
    public void writeToExcel(String[] args) {
        if (args == null || args.length == 0) {
            Log.w(TAG, "writeToExcel: البيانات فارغة");
            return;
        }
        
        FileInputStream fileIn = null;
        FileOutputStream fileOut = null;
        
        try {
            // فتح الملف الموجود
            fileIn = new FileInputStream(excelFile);
            wb = WorkbookFactory.create(fileIn);
            fileIn.close();
            
            Sheet ws = wb.getSheetAt(0);
            
            // الحصول على الصف التالي
            int rowIndex = ws.getLastRowNum() + 1;
            Row row = ws.createRow(rowIndex);
            
            // كتابة البيانات
            for (int k = 0; k < args.length; k++) {
                row.createCell(k).setCellValue(args[k]);
            }
            
            // حفظ الملف
            fileOut = new FileOutputStream(excelFile);
            wb.write(fileOut);
            
            Log.d(TAG, "تمت كتابة البيانات بنجاح");
            
        } catch (Exception e) {
            Log.e(TAG, "خطأ في كتابة البيانات: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (fileIn != null) fileIn.close();
                if (fileOut != null) {
                    fileOut.flush();
                    fileOut.close();
                }
                if (wb != null) wb.close();
            } catch (IOException e) {
                Log.e(TAG, "خطأ في إغلاق الملف: " + e.getMessage());
            }
        }
    }

    /**
     * كتابة قائمة بيانات إلى ملف Excel
     */
    public void writeToExcel(List<String[]> listData) {
        if (listData == null || listData.size() == 0) {
            Log.w(TAG, "writeToExcel: القائمة فارغة");
            return;
        }

        Log.d(TAG, "writeToExcel: " + listData.size());
        
        FileInputStream fileIn = null;
        FileOutputStream fileOut = null;
        
        try {
            // فتح الملف الموجود
            fileIn = new FileInputStream(excelFile);
            wb = WorkbookFactory.create(fileIn);
            fileIn.close();
            
            Sheet ws = wb.getSheetAt(0);
            
            // الحصول على الصف التالي
            int rowIndex = ws.getLastRowNum() + 1;
            
            // كتابة جميع البيانات
            for (int s = 0; s < listData.size(); s++) {
                String[] args = listData.get(s);
                Row row = ws.createRow(rowIndex);
                
                for (int k = 0; k < args.length; k++) {
                    row.createCell(k).setCellValue(args[k]);
                }
                
                rowIndex++;
            }
            
            // حفظ الملف
            fileOut = new FileOutputStream(excelFile);
            wb.write(fileOut);
            
            Log.d(TAG, "تمت كتابة " + listData.size() + " صفوف بنجاح");
            
        } catch (Exception e) {
            Log.e(TAG, "خطأ في كتابة البيانات: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (fileIn != null) fileIn.close();
                if (fileOut != null) {
                    fileOut.flush();
                    fileOut.close();
                }
                if (wb != null) wb.close();
            } catch (IOException e) {
                Log.e(TAG, "خطأ في إغلاق الملف: " + e.getMessage());
            }
        }
    }
}
