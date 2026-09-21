# ==================== إعدادات عامة ====================
-keepattributes *Annotation*,Signature,Exception,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ✅ استبدال -dontwarn ** بـ قائمة محددة
-dontwarn com.rscja.**
-dontwarn com.chainway.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.brotli.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**
-dontwarn com.bumptech.glide.**
-dontwarn androidx.camera.**

# ==================== 🔴 حماية Native Methods (حرج!) ====================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== 🔴 حماية Chainway SDK كاملاً ====================
-keep class com.rscja.** { *; }
-keepclassmembers class com.rscja.** { *; }
-keep class com.rscja.deviceapi.RFIDWithUHFUART { *; }
-keep class com.rscja.deviceapi.RFIDWithUHFUART$* { *; }
-keep class com.rscja.deviceapi.entity.** { *; }
-keep class com.chainway.** { *; }
-keepclassmembers class com.chainway.** { *; }

# ==================== حماية BroadcastReceiver ====================
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep class com.example.uhf.MiraRfid.TriggerKeyReceiver { *; }
-keep class com.example.uhf.BootBroadcastReceiver { *; }

# ==================== حماية Fragments ====================
-keep class com.example.uhf.fragment.** { *; }
-keepclassmembers class com.example.uhf.fragment.** { *; }

# ==================== حماية SessionManager ====================
-keep class com.example.uhf.tools.SessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager$Role { *; }
-keep class com.example.uhf.tools.MemoryManager { *; }
-keep class com.example.uhf.tools.UIHelper { *; }
-keep class com.example.uhf.tools.CertificatePinningManager { *; }
-keep class com.example.uhf.tools.RecentActivityCache { *; }
-keep class com.example.uhf.tools.DeviceDetector { *; }

# ==================== حماية MiraApiClient ====================
-keep class com.example.uhf.api.MiraApiClient { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiResponse { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiCallback { *; }

# ==================== حماية Managers ====================
-keep class com.example.uhf.manager.** { *; }
-keepclassmembers class com.example.uhf.manager.** { *; }

# ==================== حماية Activities ====================
-keep class com.example.uhf.activity.** { *; }
-keepclassmembers class com.example.uhf.activity.** { *; }

# ==================== حماية Intent & Broadcast ====================
-keep class android.content.Intent { *; }
-keep class android.content.IntentFilter { *; }

# ==================== حماية SharedPreferences ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# ==================== حماية JSON ====================
-keep class org.json.** { *; }
-keepclassmembers class * {
    *** *optString*(...);
    *** *optInt*(...);
    *** *optDouble*(...);
    *** *optBoolean*(...);
    *** *optJSONObject*(...);
    *** *optJSONArray*(...);
    *** *optLong*(...);
    *** *has*(...);
    *** *put*(...);
}

# ==================== حماية Models ====================
-keep class com.example.uhf.model.** { *; }
-keepclassmembers class com.example.uhf.model.** { <fields>; }

# ==================== حماية Adapters & Dialogs ====================
-keep class com.example.uhf.adapter.** { *; }
-keepclassmembers class com.example.uhf.adapter.** { *; }
-keep class com.example.uhf.dialog.** { *; }
-keepclassmembers class com.example.uhf.dialog.** { *; }

# ==================== حماية Engine ====================
-keep class com.example.uhf.engine.** { *; }
-keepclassmembers class com.example.uhf.engine.** { *; }

# ==================== حماية MiraRfid كاملاً ====================
-keep class com.example.uhf.MiraRfid.** { *; }
-keepclassmembers class com.example.uhf.MiraRfid.** { *; }

# ==================== Room ====================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ==================== Glide ====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }

# ==================== CameraX ====================
-keep class androidx.camera.** { *; }

# ==================== Biometric ====================
-keep class androidx.biometric.** { *; }

# ==================== MPAndroidChart ====================
-keep class com.github.mikephil.charting.** { *; }

# ==================== Coroutines ====================
-keep class kotlinx.coroutines.** { *; }

# ==================== Desugaring ====================
-keep class j$.time.** { *; }

# ==================== Getters & Setters ====================
-keepclassmembers class * {
    *** get*();
    *** set*(***);
}

# ==================== Log removal في Release ====================
# (مؤقتاً معطّل — سنُفعّله بعد نجاح الاختبار)
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
#     public static int w(...);
#     public static int e(...);
# }
