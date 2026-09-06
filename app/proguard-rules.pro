# ==================== إعدادات التعديل والضغط ====================
-keepattributes *Annotation*,Signature,Exception
-dontwarn **

# ==================== مكتبات الجهاز RFID/UHF ====================
# تم العودة لإبقاء الكلاسات المستدعاة فعلياً فقط بدلاً من { *; } الشاملة
-keep class com.hsm.** { *; }
-keep class com.rscja.deviceapi.** { *; }
-keep class com.imagealgorithmlab.** { *; }

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

# ==================== حذف سجلات Log نهائياً لتخفيض الحجم ====================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ==================== نماذج البيانات الخاصة بالتطبيق (مهم للـ SerializedName) ====================
-keep class com.mira.jewelry.enterprise.models.** { *; }
-keep class com.mira.jewelry.enterprise.data.** { *; }
