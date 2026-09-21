# ═══════════════════════════════════════════════════════════════════════════
#  MIRA ID Enterprise — ProGuard Rules v3.0
#  متوافق مع: DeviceAPI_ver20251103_release.aar (Chainway)
# ═══════════════════════════════════════════════════════════════════════════

# ==================== ✅ إعدادات عامة ====================
-keepattributes *Annotation*,Signature,Exception,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ✅ استبدال -dontwarn ** بـ قائمة محددة
-dontwarn com.rscja.**
-dontwarn com.chainway.**
-dontwarn com.hsm.**
-dontwarn com.imagealgorithmlab.**
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
-dontwarn androidx.biometric.**
-dontwarn com.google.crypto.tink.**

# ==================== 🔴 Chainway SDK Protection (CRITICAL) ====================

# ✅ Native Methods (بدونها SDK يفشل على C72!)
-keepclasseswithmembernames class * {
    native <methods>;
}

# ✅ Chainway SDK الرئيسي — لا تحذفه أبداً
-keep class com.rscja.** { *; }
-keepclassmembers class com.rscja.** { *; }

# ✅ الفئات الأساسية للـ RFID
-keep class com.rscja.deviceapi.RFIDWithUHFUART { *; }
-keep class com.rscja.deviceapi.RFIDWithUHFUART$* { *; }
-keep class com.rscja.deviceapi.entity.** { *; }
-keep class com.rscja.deviceapi.entity.UHFTAGInfo { *; }
-keep class com.rscja.deviceapi.entity.BatteryEntity { *; }
-keep class com.rscja.deviceapi.interfaces.** { *; }
-keep class com.rscja.deviceapi.enums.** { *; }
-keep class com.rscja.deviceapi.exception.** { *; }

# ✅ Utilities — يستخدمها SDK داخلياً
-keep class com.rscja.utility.** { *; }
-keepclassmembers class com.rscja.utility.** { *; }
-keep class com.rscja.utility.StringUtility { *; }

# ✅ HSM (Hardware Security Module)
-keep class com.hsm.** { *; }
-keepclassmembers class com.hsm.** { *; }

# ✅ Fingerprint Algorithm Lab (libIDFingerprintAlg.so)
-keep class com.imagealgorithmlab.** { *; }
-keepclassmembers class com.imagealgorithmlab.** { *; }

# ✅ Chainway (بعض الإصدارات تستخدم هذا الـ package)
-keep class com.chainway.** { *; }
-keepclassmembers class com.chainway.** { *; }

# ✅ Native Library Loading
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== 🔴 BroadcastReceiver Protection ====================
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep class com.example.uhf.MiraRfid.TriggerKeyReceiver { *; }
-keep class com.example.uhf.BootBroadcastReceiver { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ==================== 🔴 Fragments Protection ====================
-keep class com.example.uhf.fragment.** { *; }
-keepclassmembers class com.example.uhf.fragment.** { *; }
-keep class com.example.uhf.fragment.MiraDashboardFragment$** { *; }
-keep class com.example.uhf.fragment.MiraSalesHistoryFragment$** { *; }
-keep class com.example.uhf.fragment.MiraPosFragment$** { *; }
-keep class com.example.uhf.fragment.MiraIdentityStudioFragment$** { *; }
-keep class com.example.uhf.fragment.MiraAllActivitiesFragment$** { *; }

# ==================== 🔴 Activities Protection ====================
-keep class com.example.uhf.activity.** { *; }
-keepclassmembers class com.example.uhf.activity.** { *; }
-keep class com.example.uhf.activity.BaseTabFragmentActivity { *; }
-keep class com.example.uhf.activity.UHFMainActivity { *; }

# ==================== 🔴 SessionManager Protection ====================
-keep class com.example.uhf.tools.SessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager { *; }
-keep class com.example.uhf.tools.SecureSessionManager$Role { *; }
-keep class com.example.uhf.tools.MemoryManager { *; }
-keep class com.example.uhf.tools.UIHelper { *; }
-keep class com.example.uhf.tools.CertificatePinningManager { *; }
-keep class com.example.uhf.tools.RecentActivityCache { *; }
-keep class com.example.uhf.tools.DeviceDetector { *; }
-keep class com.example.uhf.tools.MiraLanguageHelper { *; }
-keep class com.example.uhf.tools.ToastUtil { *; }

# ==================== 🔴 MiraApiClient Protection ====================
-keep class com.example.uhf.api.MiraApiClient { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiResponse { *; }
-keep class com.example.uhf.api.MiraApiClient$ApiCallback { *; }
-keep class com.example.uhf.api.MiraApiClient$* { *; }

# ==================== 🔴 Managers Protection ====================
-keep class com.example.uhf.manager.MiraBranchesManager { *; }
-keep class com.example.uhf.manager.MiraBranchesManager$* { *; }
-keep class com.example.uhf.manager.PermissionManager { *; }
-keep class com.example.uhf.manager.PermissionManager$* { *; }
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
-keep class com.example.uhf.manager.** { *; }
-keepclassmembers class com.example.uhf.manager.** { *; }

# ==================== 🔴 Inventory Managers ====================
-keep class com.example.uhf.manager.inventory.** { *; }
-keepclassmembers class com.example.uhf.manager.inventory.** { *; }

# ==================== 🔴 MiraRfid Package (كامل) ====================
-keep class com.example.uhf.MiraRfid.** { *; }
-keepclassmembers class com.example.uhf.MiraRfid.** { *; }

# ==================== 🔴 Engine ====================
-keep class com.example.uhf.engine.** { *; }
-keepclassmembers class com.example.uhf.engine.** { *; }

# ==================== 🔴 Models ====================
-keep class com.example.uhf.model.** { *; }
-keepclassmembers class com.example.uhf.model.** { <fields>; }
-keep class com.mira.jewelry.enterprise.models.** { *; }
-keepclassmembers class com.mira.jewelry.enterprise.models.** { <fields>; }
-keepclassmembers class com.mira.jewelry.enterprise.data.** { <fields>; }

# ==================== 🔴 Adapters ====================
-keep class com.example.uhf.adapter.** { *; }
-keepclassmembers class com.example.uhf.adapter.** { *; }

# ==================== 🔴 Dialogs ====================
-keep class com.example.uhf.dialog.** { *; }
-keepclassmembers class com.example.uhf.dialog.** { *; }
-keep class com.example.uhf.dialog.inventory.** { *; }
-keepclassmembers class com.example.uhf.dialog.inventory.** { *; }

# ==================== 🔴 Database (Room) ====================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**
-keep class com.example.uhf.database.** { *; }
-keepclassmembers class com.example.uhf.database.** { *; }

# ==================== 🔴 Security & Encryption ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.example.uhf.security.** { *; }
-keepclassmembers class com.example.uhf.security.** { *; }
-keep class com.example.uhf.storage.** { *; }
-keepclassmembers class com.example.uhf.storage.** { *; }

# ==================== 🔴 JSON ====================
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

# ==================== 🔴 SharedPreferences & Context ====================
-keep class android.content.SharedPreferences { *; }
-keep class android.content.SharedPreferences$* { *; }
-keep class android.content.Intent { *; }
-keep class android.content.IntentFilter { *; }
-keep class android.content.Context { *; }
-keep class android.content.ContextCompat { *; }

# ==================== 🔴 ZXing & MLKit ====================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.** { *; }

# ==================== 🔴 Glide ====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ==================== 🔴 CameraX ====================
-keep class androidx.camera.** { *; }

# ==================== 🔴 Biometric ====================
-keep class androidx.biometric.** { *; }

# ==================== 🔴 MPAndroidChart ====================
-keep class com.github.mikephil.charting.** { *; }

# ==================== 🔴 Coroutines ====================
-keep class kotlinx.coroutines.** { *; }

# ==================== 🔴 Desugaring ====================
-keep class j$.time.** { *; }

# ==================== 🔴 Getters & Setters (لـ JSON serialization) ====================
-keepclassmembers class * {
    *** get*();
    *** set*(***);
}

# ==================== 🔴 Enum Values ====================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==================== 🔴 Serializable & Parcelable ====================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ==================== 🔴 Annotations ====================
-keep @androidx.annotation.Keep class * {*;}
-keep @androidx.annotation.Keep interface * {*;}
-keep @androidx.annotation.Keep enum * {*;}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}

# ==================== 🔴 Reflection ====================
-keepclassmembers class * {
    @java.lang.reflect.* <fields>;
    @java.lang.reflect.* <methods>;
}

# ==================== ⚠️ Log Removal (مؤقتاً معطّل) ====================
# ⚠️ سنُفعّلها بعد نجاح الاختبار على C72
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
#     public static int w(...);
#     public static int e(...);
# }

# ==================== ✅ نهاية الملف ====================
