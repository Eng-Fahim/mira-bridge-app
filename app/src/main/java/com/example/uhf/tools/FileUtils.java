package com.example.uhf.tools;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.util.Xml;

import com.example.uhf.AppContext;


import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Created by Administrator on 2017-5-22.
 */

public class FileUtils {
    static String TAG = "FileUtils";
    public static String ADDR = "btaddress";
    public static String NAME = "btname";
    public static String filePath = Environment.getExternalStorageDirectory() + File.separator + "BTDeviceList.xml";

    public static void saveXmlList(List<String[]> data) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                file.delete();
            }
            FileOutputStream fos = new FileOutputStream(file);
            // 获得一个序列化工具
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(fos, "utf-8");
            // 设置文件头
            serializer.startDocument("utf-8", true);
            serializer.startTag(null, "root");
            for (int i = 0; i < data.size(); i++) {
                serializer.startTag(null, "bt");
                // 蓝牙地址
                serializer.startTag(null, ADDR);
                serializer.text(data.get(i)[0]);
                serializer.endTag(null, ADDR);
                // 蓝牙名称
                serializer.startTag(null, NAME);
                serializer.text(data.get(i)[1]);
                serializer.endTag(null, NAME);
                serializer.endTag(null, "bt");
            }
            serializer.endTag(null, "root");
            serializer.endDocument();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static ArrayList<String[]> readXmlList() {
        ArrayList<String[]> list = new ArrayList<String[]>();
        try {
            File path = new File(filePath);
            if (!path.exists()) {
                return list;
            }
            FileInputStream fis = new FileInputStream(path);

            // 获得pull解析器对象
            XmlPullParser parser = Xml.newPullParser();
            // 指定解析的文件和编码格式
            parser.setInput(fis, "utf-8");
            int eventType = parser.getEventType(); // 获得事件类型
            String addr = null;
            String name = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName(); // 获得当前节点的名称
                switch (eventType) {
                    case XmlPullParser.START_TAG: // 当前等于开始节点
                        if ("root".equals(tagName)) { //
                        } else if ("bt".equals(tagName)) { //
                        } else if (ADDR.equals(tagName)) { // <name>
                            addr = parser.nextText();
                        } else if (NAME.equals(tagName)) { // <age>
                            name = parser.nextText();
                        }
                        break;
                    case XmlPullParser.END_TAG: // </persons>
                        if ("bt".equals(tagName)) {
                            Log.i(TAG, "addr---" + addr);
                            Log.i(TAG, "name---" + name);
                            String[] str = new String[2];
                            str[0] = addr;
                            str[1] = name;
                            list.add(str);
                        }
                        break;
                    default:
                        break;
                }
                eventType = parser.next(); // 获得下一个事件类型
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
        }
        return list;
    }

    public static void clearXmlList() {
        List<String[]> list = readXmlList();
        list.clear();
        saveXmlList(list);
    }
/**
 * ✅ كتابة ملف CSV (بديل خفيف لـ Excel)
 * - Excel يفتحه مباشرة
 * - حجم صفر تقريباً
 */
/**
 * ✅ كتابة ملف CSV (بديل خفيف لـ Excel)
 * - Excel يفتحه مباشرة
 * - حجم صفر تقريباً
 * - يدعم النص العربي (UTF-8 BOM)
 */
public static void writeFile(String fileName, List<String> data, boolean append) {
    Log.e(TAG, "بدء كتابة ملف CSV");

    // ✅ تغيير الامتداد إلى .csv
    String csvFileName = fileName;
    if (csvFileName.toLowerCase().endsWith(".xlsx")) {
        csvFileName = csvFileName.substring(0, csvFileName.length() - 5) + ".csv";
    } else if (csvFileName.toLowerCase().endsWith(".xls")) {
        csvFileName = csvFileName.substring(0, csvFileName.length() - 4) + ".csv";
    }

    Writer writer = null;
    try {
        // ✅ فتح الملف (append = true للإضافة)
        writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(csvFileName, append),
                java.nio.charset.StandardCharsets.UTF_8);

        // ✅ كتابة BOM لـ Excel العربي (UTF-8 BOM)
        if (!append) {
            writer.write('\ufeff');
            // العناوين
            writer.write("EPC Data,TID Data,USER Data\n");
        }

        // ✅ كتابة البيانات (كل 3 عناصر = صف واحد)
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (i > 0 && i % 3 == 0) {
                row.append('\n');
                writer.write(row.toString());
                row.setLength(0);
            }
            if (i % 3 > 0) row.append(',');
            row.append(escapeCsv(data.get(i)));
        }
        // كتابة آخر صف
        if (row.length() > 0) {
            row.append('\n');
            writer.write(row.toString());
        }

        writer.flush();
        Log.e(TAG, "✅ تم كتابة ملف CSV بنجاح: " + csvFileName);

    } catch (IOException e) {
        Log.e(TAG, "خطأ في كتابة ملف CSV: " + e.getMessage());
    } finally {
        try {
            if (writer != null) writer.close();
        } catch (IOException e) {
            Log.e(TAG, "خطأ في إغلاق الملف: " + e.getMessage());
        }
    }
}

/**
 * ✅ تهريب القيم لـ CSV
 */
private static String escapeCsv(String value) {
    if (value == null) return "";
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
}

    private static String readInStream(FileInputStream inStream) {
        if(inStream==null)
            return null;
        try {
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int length = -1;
            while ((length = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, length);
            }

            outStream.close();
            inStream.close();
            return outStream.toString();
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
        return null;
    }

    /**
     * 读取文件内容
     *
     * @param path
     * @return
     */
    public static String readFile(String path) {

        FileInputStream fin = null;
        try {
            fin = new FileInputStream(path);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            Log.e(TAG, e.getMessage());
        }

        return readInStream(fin);
    }

    /**
     * 保存crash到文件
     */
    @SuppressLint("SimpleDateFormat")
    public static void saveCrashFile(Throwable throwable) {
        try {
            File crashPathFile = new File(AppContext.DEFAULT_PATH + "error/");
            if (!crashPathFile.exists()) { // 创建文件夹
                crashPathFile.mkdirs();
            } else {
                File[] files = crashPathFile.listFiles();
                if (files != null && files.length > 20) {
                    Log.i(TAG, "files.length=" + files.length);
                    List<File> fileList = Arrays.asList(crashPathFile.listFiles());
                    Collections.sort(fileList, new Comparator<File>() {
                        @Override
                        public int compare(File o1, File o2) {
                            if (o1.isDirectory() && o2.isFile())
                                return -1;
                            if (o1.isFile() && o2.isDirectory())
                                return 1;
                            return o1.getName().compareTo(o2.getName());
                        }
                    });
                    int c = files.length - 20;
                    for (int k = 0; k < c; k++) {
                        Log.i(TAG, "删除文件 files.name=" + fileList.get(k).getName());
                        fileList.get(k).delete();
                    }
                }

            }
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String time = df.format(new Date());

            SimpleDateFormat df2 = new SimpleDateFormat("yyyyMMdd");
            String time2 = df2.format(new Date());

            StringBuilder sb = new StringBuilder();
            sb.append("DateTime: ")
                    .append(time)
                    .append("\n");
            sb.append("DeviceInfo: ")
                    .append(Build.MANUFACTURER)
                    .append(" ")
                    .append(Build.MODEL)
                    .append("\n");
            sb.append("AppVersion: ")
                    .append(AppContext.getInstance().getVerName())
                    .append("_")
                    .append(AppContext.getInstance().getVerCode())
                    .append("\n");

            Writer writer = new StringWriter();
            PrintWriter pw = new PrintWriter(writer);
            throwable.printStackTrace(pw);
            Throwable cause = throwable.getCause();
            while (cause != null) {
                cause.printStackTrace(pw);
                cause = cause.getCause();
            }
            pw.close();
            String result = writer.toString();
            sb.append("Exception: \n");
            sb.append(result);
            Log.i(TAG, result); // 打印输出，方便开发调试
            // 记录异常到特定文件中
            File crashFile = new File(crashPathFile, "UHF-serial-" + time2 + ".txt");
            crashFile.setReadOnly();
            try {
                writer = new FileWriter(crashFile, true);
                writer.write(sb.toString());
                writer.write("\n");
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception1  " + e.getMessage());
            }

        } catch (Exception exception) {
            Log.e(TAG, "Exception2  " + exception.getMessage());
        }

    }
}
