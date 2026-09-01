# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in D:\soft\android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# ==================== قواعد عامة ====================
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# ==================== مكتبات الجهاز ====================
-dontwarn com.imagealgorithmlab.**
-dontwarn com.hsm.**
-dontwarn com.rscja.deviceapi.**
-keep class com.hsm.** {*; }
-keep class com.rscja.deviceapi.** {*; }

# ==================== ملفات البيانات ====================
#导出数据jar
-keep class common.** { *; }
-keep class jxl.** { *; }
-keep class aavax.**{*;}
-keep class schemaorg_apache_xmlbeans.system.**{*;}
-keep interface org.**{*;}

# ==================== حل مشكلة مكتبات XML/Office ====================
# تجاهل تحذيرات المكتبات القديمة
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.w3.x2000.**
-dontwarn schemaorg_apache_xmlbeans.**
-dontwarn org.apache.poi.**
-dontwarn com.microsoft.schemas.**
-dontwarn javax.xml.crypto.**
-dontwarn javax.xml.crypto.dsig.**

# الاحتفاظ بكلاسات XMLBeans
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.w3.x2000.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }

# ==================== Firebase ====================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ==================== الأمان والتشفير ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# ==================== حل نهائي لمشاكل Tink ====================
# تجاهل كل التحذيرات المتعلقة بالمكتبات الخارجية
-dontwarn android.os.SystemProperties

# تجاهل مكتبات Google API (غير مستخدمة فعلياً)
-dontwarn com.google.api.client.**
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**

# تجاهل مكتبات التوقيع الرقمي
-dontwarn org.etsi.uri.x01903.**
-dontwarn org.etsi.uri.x01903.v13.**

# تجاهل Joda Time
-dontwarn org.joda.time.**

# تجاهل Tink KeysDownloader (لا نستخدمه)
-dontwarn com.google.crypto.tink.util.KeysDownloader
-dontwarn com.google.crypto.tink.util.KeysDownloader$Builder

# ==================== المصادقة البيومترية ====================
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ==================== الشبكة Retrofit ====================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ==================== OkHttp ====================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ==================== Gson ====================
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ==================== Room Database ====================
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ==================== CameraX ====================
-keep class androidx.camera.** { *; }

# ==================== MPAndroidChart ====================
-keep class com.github.mikephil.charting.** { *; }

# ==================== Glide ====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ==================== ZXing ====================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# ==================== ML Kit ====================
-keep class com.google.mlkit.** { *; }

# ==================== حماية البيانات الحساسة ====================
# منع تسريب البيانات في السجلات للإنتاج
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ==================== الحفاظ على الخطوط ====================
# ✅ منع حذف ملفات الخطوط
-keep class androidx.core.content.res.ResourcesCompat { *; }
-keep class androidx.core.content.res.FontResourcesParserCompat { *; }
-dontwarn androidx.core.content.res.**

# ✅ الحفاظ على Typeface
-keep class androidx.core.graphics.TypefaceCompat { *; }
-keep class androidx.core.graphics.TypefaceCompatBaseImpl { *; }
-keep class androidx.core.graphics.TypefaceApi26Impl { *; }

# ✅ الحفاظ على ملفات الموارد (الخطوط)
-keepresources string/**

# ✅ بديل: تعطيل حذف الموارد غير المستخدمة
# إذا استمرت المشكلة، اجعل shrinkResources false في build.gradle
