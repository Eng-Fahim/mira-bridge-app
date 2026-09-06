# ==================== إعدادات التعديل والضغط العامة ====================
-keepattributes *Annotation*,Signature,Exception
-dontwarn **

# ==================== مكتبات الجهاز RFID/UHF (Chainway & Hardware) ====================
-keep class com.hsm.** { *; }
-keep class com.rscja.deviceapi.** { *; }
-keep class com.imagealgorithmlab.** { *; }

# ==================== ZXing & ZXing Android Embedded (إصلاح مسح الباركود) ====================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-dontwarn com.google.zxing.**

# ==================== Apache POI (تنظيف الحشو غير المستخدم) ====================
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.poi.xssf.usermodel.** { *; }
-keep class org.apache.poi.hssf.usermodel.** { *; }
-keep class org.apache.poi.ss.SpreadsheetVersion { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn schemaorg_apache_xmlbeans.**

# ==================== Retrofit & OkHttp & Gson ====================
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}

# ==================== Room Database ====================
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# ==================== Glide ====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule

# ==================== MLKit Barcode Scanning ====================
-keep class com.google.mlkit.vision.barcode.** { *; }
-dontwarn com.google.mlkit.vision.**

# ==================== نماذج البيانات والكود الخاص بالتطبيق ====================
# حماية النماذج من الضغط لتجنب مشاكل الـ Serialization/Deserialization مع API
-keep class com.example.uhf.model.** { *; }
-keep class com.mira.jewelry.enterprise.models.** { *; }
-keepclassmembers class com.mira.jewelry.enterprise.models.** { <fields>; }
-keepclassmembers class com.mira.jewelry.enterprise.data.** { <fields>; }

# ==================== حذف سجلات Log والتتبع نهائياً في Release ====================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-assumenosideeffects class okhttp3.internal.concurrent.TaskRunner {
    public static void logger(...);
}
