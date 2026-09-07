# ==================== إعدادات عامة ====================
-keepattributes *Annotation*,Signature,Exception,InnerClasses,EnclosingMethod
-dontwarn **

# ==================== حماية BroadcastReceiver ====================
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep class android.content.BroadcastReceiver { *; }
-keep class com.example.uhf.fragment.MiraDashboardFragment$** { *; }
-keep class com.example.uhf.fragment.MiraSalesHistoryFragment$** { *; }
-keep class com.example.uhf.fragment.MiraPosFragment$** { *; }
-keep class com.example.uhf.fragment.MiraIdentityStudioFragment$** { *; }

# ==================== حماية جميع Fragments ====================
-keep class com.example.uhf.fragment.** { *; }
-keepclassmembers class com.example.uhf.fragment.** { *; }

# ==================== حماية SessionManager & SecureSessionManager ====================
-keep class com.example.uhf.tools.SessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager$Role { *; }
-keep class com.example.uhf.tools.MemoryManager { *; }
-keep class com.example.uhf.tools.UIHelper { *; }
-keep class com.example.uhf.tools.CertificatePinningManager { *; }

# ==================== حماية MiraApiClient ====================
-keep class com.example.uhf.api.MiraApiClient { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiResponse { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiCallback { *; }

# ==================== حماية Managers ====================
-keep class com.example.uhf.manager.MiraBranchesManager { *; }
-keep class com.example.uhf.manager.MiraBranchesManager$* { *; }
-keep class com.example.uhf.manager.PermissionManager { *; }
-keep class com.example.uhf.manager.MiraPriceManager { *; }
-keep class com.example.uhf.manager.MiraPriceManager$* { *; }
-keep class com.example.uhf.manager.MiraOrgProfileManager { *; }
-keep class com.example.uhf.manager.MiraOrgProfileManager$* { *; }
-keep class com.example.uhf.manager.MiraServerConfig { *; }
-keep class com.example.uhf.manager.MiraQuickAccessSettings { *; }
-keep class com.example.uhf.manager.MiraQuickAccessSettings$* { *; }
-keep class com.example.uhf.manager.MiraLocalItemCache { *; }
-keep class com.example.uhf.manager.MiraItemSyncManager { *; }
-keep class com.example.uhf.manager.MiraItemSyncManager$* { *; }

# ==================== حماية Activities ====================
-keep class com.example.uhf.activity.** { *; }
-keepclassmembers class com.example.uhf.activity.** { *; }

# ==================== حماية Intent & IntentFilter ====================
-keep class android.content.Intent { *; }
-keep class android.content.IntentFilter { *; }
-keep class android.content.Context { *; }
-keep class android.content.ContextCompat { *; }

# ==================== حماية SharedPreferences ====================
-keep class android.content.SharedPreferences { *; }
-keep class android.content.SharedPreferences$* { *; }

# ==================== حماية EncryptedSharedPreferences ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

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

# ==================== حماية النماذج ====================
-keep class com.example.uhf.model.** { *; }
-keepclassmembers class com.example.uhf.model.** { <fields>; }
-keep class com.mira.jewelry.enterprise.models.** { *; }
-keepclassmembers class com.mira.jewelry.enterprise.models.** { <fields>; }
-keepclassmembers class com.mira.jewelry.enterprise.data.** { <fields>; }

# ==================== حماية POS Checkout ====================
-keep class com.example.uhf.dialog.POSCheckoutDialog { *; }
-keep class com.example.uhf.dialog.POSCheckoutDialog$* { *; }

# ==================== حماية Adapters ====================
-keep class com.example.uhf.adapter.** { *; }
-keepclassmembers class com.example.uhf.adapter.** { *; }

# ==================== حماية Engine ====================
-keep class com.example.uhf.engine.** { *; }
-keepclassmembers class com.example.uhf.engine.** { *; }

# ==================== حماية RFID ====================
-keep class com.example.uhf.MiraRfid.** { *; }
-keepclassmembers class com.example.uhf.MiraRfid.** { *; }
-keep class com.hsm.** { *; }
-keep class com.rscja.deviceapi.** { *; }
-keep class com.imagealgorithmlab.** { *; }

# ==================== ZXing & MLKit ====================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-dontwarn com.google.zxing.**
-dontwarn com.google.mlkit.vision.**

# ==================== Retrofit & OkHttp & Gson ====================
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
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
-keep class com.bumptech.glide.** { *; }

# ==================== CameraX ====================
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ==================== Biometric ====================
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ==================== Apache POI ====================
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.poi.xssf.usermodel.** { *; }
-keep class org.apache.poi.hssf.usermodel.** { *; }
-keep class org.apache.poi.ss.SpreadsheetVersion { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn schemaorg_apache_xmlbeans.**

# ==================== MPAndroidChart ====================
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ==================== Coroutines ====================
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ==================== Desugaring ====================
-keep class j$.time.** { *; }
-dontwarn j$.time.**

# ==================== حماية عامة للحقول ====================
-keepclassmembers class * {
    *** get*();
    *** set*(***);
}

# ==================== حذف سجلات Log في Release ====================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
