# ==================== إعدادات ProGuard الأساسية ====================
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Services

# ==================== مكتبات الجهاز RFID/UHF ====================
-dontwarn com.imagealgorithmlab.**
-dontwarn com.hsm.**
-dontwarn com.rscja.deviceapi.**
-keep class com.hsm.** { *; }
-keep class com.rscja.deviceapi.** { *; }
-keep class com.imagealgorithmlab.** { *; }

# ==================== ملفات البيانات والتصدير ====================
-keep class common.** { *; }
-keep class aavax.** { *; }
-keep class schemaorg_apache_xmlbeans.system.** { *; }
-keep interface org.** { *; }

# ==================== مكتبات XML/Office (Apache POI 5.2.5) ====================
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.w3.x2000.**
-dontwarn schemaorg_apache_xmlbeans.**
-dontwarn org.apache.poi.**
-dontwarn com.microsoft.schemas.**
-dontwarn javax.xml.crypto.**
-dontwarn javax.xml.crypto.dsig.**
-dontwarn org.apache.xmlbeans.impl.common.**

-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.w3.x2000.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.apache.xmlbeans.impl.values.** { *; }
-keep class org.apache.xmlbeans.XmlObject { *; }

# ==================== Apache POI 5.2.5 ====================
-keep class org.apache.poi.** { *; }
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.poi.xssf.usermodel.** { *; }
-keep class org.apache.poi.hssf.usermodel.** { *; }
-keep class org.apache.poi.ss.SpreadsheetVersion { *; }
-keep class org.apache.poi.ooxml.** { *; }
-keep class org.apache.poi.ss.usermodel.WorkbookFactory { *; }
-dontwarn org.apache.poi.**

# ==================== Firebase ====================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ==================== الأمان والتشفير ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# تجاهل تحذيرات Tink غير المستخدمة
-dontwarn android.os.SystemProperties
-dontwarn com.google.api.client.**
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn org.etsi.uri.x01903.**
-dontwarn org.etsi.uri.x01903.v13.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.util.KeysDownloader
-dontwarn com.google.crypto.tink.util.KeysDownloader$Builder

# ==================== المصادقة البيومترية ====================
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ==================== Retrofit ====================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ==================== OkHttp ====================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okhttp3.** {
    <init>(...);
}

# ==================== Gson ====================
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# ==================== Room Database ====================
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static <methods>;
}
-dontwarn androidx.room.paging.**

# ==================== CameraX ====================
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ==================== MPAndroidChart ====================
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ==================== Glide ====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# ==================== ZXing ====================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ==================== ML Kit ====================
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ==================== Coroutines ====================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ==================== حماية البيانات الحساسة (Logs) ====================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ==================== نماذج البيانات الخاصة بالتطبيق ====================
-keep class com.mira.jewelry.enterprise.models.** { *; }
-keep class com.mira.jewelry.enterprise.data.** { *; }
-keep class com.mira.jewelry.enterprise.api.** { *; }

# ==================== معالجة الأخطاء ====================
-dontwarn java.lang.invoke.**
-dontwarn java.nio.file.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**

# تجاهل أخطاء الموارد
-keepclassmembers class * {
    *** *();
}
