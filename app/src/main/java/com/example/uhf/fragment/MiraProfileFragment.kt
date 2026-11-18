package com.example.uhf.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.LoginActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

class MiraProfileFragment : Fragment() {

    companion object {
        private const val TAG = "MiraProfileFragment"
        private val executor = Executors.newFixedThreadPool(3)
    }

    private var sessionManager: SessionManager? = null
    private var apiClient: MiraApiClient? = null
    private var orgProfileManager: MiraOrgProfileManager? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDestroyed = AtomicBoolean(false)

    // Compose States - User
    private var isArabicState by mutableStateOf(true)
    private var isAdminState by mutableStateOf(false)
    private var displayNameState by mutableStateOf("")
    private var usernameState by mutableStateOf("")
    private var roleState by mutableStateOf("")
    private var phoneState by mutableStateOf("")
    private var emailState by mutableStateOf("")
    private var branchNameState by mutableStateOf("")
    private var avatarBitmapState by mutableStateOf<Bitmap?>(null)

    // Compose States - Organization
    private var orgNameArState by mutableStateOf("")
    private var orgNameEnState by mutableStateOf("")
    private var orgPhoneState by mutableStateOf("")
    private var orgEmailState by mutableStateOf("")
    private var taxNumberState by mutableStateOf("")
    private var orgSloganState by mutableStateOf("")
    private var orgAddressState by mutableStateOf("")
    private var orgWebsiteState by mutableStateOf("")

    // Compose States - Settings & Theme
    private var biometricLoginState by mutableStateOf(false)
    private var biometricConfirmState by mutableStateOf(false)
    private var selectedThemeModeState by mutableIntStateOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    // Dialog Visibility States
    private var showEditUserDialogState by mutableStateOf(false)
    private var showEditOrgDialogState by mutableStateOf(false)
    private var showChangePasswordDialogState by mutableStateOf(false)
    private var showLogoutConfirmDialogState by mutableStateOf(false)

    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

    private val profileListener = MiraOrgProfileManager.OnProfileChangedListener {
        mainHandler.post {
            if (!isDestroyed.get() && isAdded) {
                loadLocalProfileData()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    MiraProfileScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isDestroyed.set(false)
        isArabicState = Locale.getDefault().language == "ar"

        val ctx = requireContext()
        sessionManager = SessionManager(ctx)
        apiClient = MiraApiClient.getInstance(ctx)
        orgProfileManager = MiraOrgProfileManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)

        isAdminState = permissionManager?.isAdmin() == true

        setupImagePicker()
        orgProfileManager?.addListener(profileListener)

        loadLocalProfileData()
        loadUserAvatar()
        loadThemePreference()
        fetchProfileFromServer()
    }

    override fun onResume() {
        super.onResume()
        loadLocalProfileData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isDestroyed.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        orgProfileManager?.removeListener(profileListener)
        try {
            (activity as? UHFMainActivity)?.hideLoading()
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Data Loading & Sync
    // ═══════════════════════════════════════════════════════════════════

    private fun loadLocalProfileData() {
        try {
            val sm = sessionManager ?: return
            val opm = orgProfileManager ?: return

            displayNameState = sm.displayName ?: ""
            usernameState = sm.username ?: ""
            roleState = sm.role ?: if (isArabicState) "موظف" else "Employee"
            phoneState = sm.phone ?: ""
            emailState = sm.email ?: ""
            branchNameState = branchesManager?.activeBranchDisplayName ?: ""

            orgNameArState = opm.orgNameAr ?: ""
            orgNameEnState = opm.orgNameEn ?: ""
            orgPhoneState = opm.orgPhone ?: ""
            orgEmailState = opm.orgEmail ?: ""
            taxNumberState = opm.taxNumber ?: ""
            orgSloganState = opm.orgSlogan ?: ""
            orgAddressState = opm.orgAddress ?: ""
            orgWebsiteState = opm.orgWebsite ?: ""

            biometricLoginState = sm.isBiometricLoginEnabled
            biometricConfirmState = sm.isBiometricConfirmEnabled
            isAdminState = permissionManager?.isAdmin() == true
        } catch (e: Exception) {
            Log.e(TAG, "loadLocalProfileData error: ${e.message}", e)
        }
    }

    private fun fetchProfileFromServer() {
        if (isDestroyed.get() || !isAdded) return
        val client = apiClient ?: return

        executeSafely {
            try {
                val response = client.orgProfile
                if (response != null && response.isSuccess && response.data != null) {
                    val user = response.data?.optJSONObject("user")
                    val org = response.data?.optJSONObject("organization")

                    if (org != null) {
                        orgProfileManager?.saveFromServer(org)
                    }

                    if (user != null) {
                        val dName = user.optString("display_name", "")
                        val uPhone = user.optString("phone", "")
                        val uEmail = user.optString("email", "")
                        if (dName.isNotEmpty()) sessionManager?.displayName = dName
                        if (uPhone.isNotEmpty()) sessionManager?.phone = uPhone
                        if (uEmail.isNotEmpty()) sessionManager?.email = uEmail
                    }

                    mainHandler.post {
                        if (!isDestroyed.get() && isAdded) {
                            loadLocalProfileData()
                            if (user != null) {
                                val uName = user.optString("username", "")
                                if (uName.isNotEmpty()) usernameState = uName
                                val bName = user.optString("branch_name", "")
                                if (bName.isNotEmpty()) branchNameState = bName
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchProfileFromServer error: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Avatar Picker & Upload
    // ═══════════════════════════════════════════════════════════════════

    private fun setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                result.data?.data?.let { uploadProfileImage(it) }
            }
        }
    }

    private fun pickImage() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
            imagePickerLauncher.launch(intent)
        } catch (e: Exception) {
            showToast(if (isArabicState) "❌ تعذر فتح المعرض" else "❌ Could not open gallery")
        }
    }

    private fun loadUserAvatar() {
        executeSafely {
            try {
                val ctx = context ?: return@executeSafely
                val prefs = ctx.getSharedPreferences("user_avatar", Context.MODE_PRIVATE)
                val avatarPath = prefs.getString("avatar_path", "") ?: ""
                if (avatarPath.isNotEmpty()) {
                    val file = File(avatarPath)
                    if (file.exists()) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            mainHandler.post {
                                if (!isDestroyed.get() && isAdded) {
                                    avatarBitmapState = bmp
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadUserAvatar error: ${e.message}")
            }
        }
    }

    private fun uploadProfileImage(imageUri: Uri) {
        showLoading(true)
        executeSafely {
            try {
                val ctx = context ?: return@executeSafely
                val inputStream: InputStream? = ctx.contentResolver.openInputStream(imageUri)
                var bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    mainHandler.post {
                        showLoading(false)
                        showToast(if (isArabicState) "❌ فشل تحميل الصورة" else "❌ Image load error")
                    }
                    return@executeSafely
                }

                val maxDim = 512
                if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    bitmap = Bitmap.createScaledBitmap(
                        bitmap,
                        (ratio * bitmap.width).roundToInt(),
                        (ratio * bitmap.height).roundToInt(),
                        true
                    )
                }

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val imageBytes = baos.toByteArray()

                val avatarFile = File(ctx.filesDir, "user_avatar.jpg")
                FileOutputStream(avatarFile).use { it.write(imageBytes) }

                val prefs = ctx.getSharedPreferences("user_avatar", Context.MODE_PRIVATE)
                prefs.edit().putString("avatar_path", avatarFile.absolutePath).apply()

                val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)
                val payload = JSONObject().put("avatar_base64", base64Image)
                val response = apiClient?.uploadUserAvatar(payload)

                val finalBitmap = bitmap
                mainHandler.post {
                    if (isDestroyed.get() || !isAdded) return@post
                    showLoading(false)
                    avatarBitmapState = finalBitmap

                    if (response != null && response.isSuccess) {
                        val serverUrl = response.data?.optString("avatar_url", "") ?: ""
                        if (serverUrl.isNotEmpty()) {
                            prefs.edit().putString("avatar_url", serverUrl).apply()
                        }
                        showToast(if (isArabicState) "✅ تم تحديث الصورة" else "✅ Photo updated")
                    } else {
                        showToast(if (isArabicState) "💾 تم الحفظ محلياً" else "💾 Saved locally")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabicState) "❌ خطأ في رفع الصورة" else "❌ Upload error")
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Server Updates (User, Org, Password, Settings, Logout)
    // ═══════════════════════════════════════════════════════════════════

    private fun saveUserProfileToServer(
        displayName: String,
        phone: String,
        email: String,
        username: String
    ) {
        showLoading(true)
        executeSafely {
            try {
                val payload = JSONObject().apply {
                    put("display_name", displayName)
                    put("phone", phone)
                    put("email", email)
                    put("username", username)
                }

                val response = apiClient?.updateUserProfile(payload)
                val success = response != null && response.isSuccess

                mainHandler.post {
                    if (isDestroyed.get() || !isAdded) return@post
                    showLoading(false)

                    if (success) {
                        if (displayName.isNotEmpty()) {
                            sessionManager?.displayName = displayName
                            displayNameState = displayName
                        }
                        if (phone.isNotEmpty()) {
                            sessionManager?.phone = phone
                            phoneState = phone
                        }
                        if (email.isNotEmpty()) {
                            sessionManager?.email = email
                            emailState = email
                        }
                        if (username.isNotEmpty()) {
                            usernameState = username
                        }
                        showToast(if (isArabicState) "✅ تم تحديث الملف الشخصي" else "✅ Profile updated")
                    } else {
                        val err = response?.error ?: "Unknown error"
                        showToast((if (isArabicState) "❌ فشل: " else "❌ Failed: ") + err)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabicState) "❌ خطأ في الحفظ" else "❌ Save error")
                    }
                }
            }
        }
    }

    private fun saveOrgProfileToServer(
        nameAr: String,
        nameEn: String,
        phone: String,
        email: String,
        tax: String,
        slogan: String,
        address: String,
        website: String
    ) {
        if (!isAdminState) {
            showToast(if (isArabicState) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }

        showLoading(true)
        executeSafely {
            try {
                val payload = JSONObject().apply {
                    put("org_name_ar", nameAr)
                    put("org_name_en", nameEn)
                    put("org_phone", phone)
                    put("org_email", email)
                    put("tax_number", tax)
                    put("org_slogan", slogan)
                    put("org_address", address)
                    put("org_website", website)
                }

                val response = apiClient?.saveOrgProfile(payload)
                val success = response != null && response.isSuccess

                mainHandler.post {
                    if (isDestroyed.get() || !isAdded) return@post
                    showLoading(false)

                    if (success) {
                        orgProfileManager?.saveFullProfileExtended(
                            nameAr, nameEn, phone, email, tax, slogan, address, website
                        )
                        loadLocalProfileData()
                        showToast(if (isArabicState) "✅ تم حفظ بيانات المنظمة" else "✅ Organization saved")
                    } else {
                        val err = response?.error ?: "Unknown error"
                        showToast((if (isArabicState) "❌ فشل: " else "❌ Failed: ") + err)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabicState) "❌ خطأ في الحفظ" else "❌ Save error")
                    }
                }
            }
        }
    }

    private fun changePassword(currentPassword: String, newPassword: String) {
        showLoading(true)
        executeSafely {
            try {
                val response = apiClient?.changePassword(currentPassword, newPassword)
                mainHandler.post {
                    if (isDestroyed.get() || !isAdded) return@post
                    showLoading(false)
                    if (response != null && response.isSuccess) {
                        showToast(if (isArabicState) "✅ تم تغيير كلمة المرور بنجاح" else "✅ Password changed")
                    } else {
                        val err = response?.error ?: "Unknown error"
                        showToast((if (isArabicState) "❌ فشل: " else "❌ Failed: ") + err)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabicState) "❌ خطأ في الاتصال" else "❌ Connection error")
                    }
                }
            }
        }
    }

    private fun saveBiometricPreference(key: String, value: Boolean) {
        executeSafely {
            try {
                val payload = JSONObject().put(key, value)
                apiClient?.saveSettings(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun loadThemePreference() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        selectedThemeModeState = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun applyThemeMode(newMode: Int) {
        if (selectedThemeModeState == newMode) return
        selectedThemeModeState = newMode

        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_mode", newMode).commit()

        AppCompatDelegate.setDefaultNightMode(newMode)

        mainHandler.postDelayed({
            val act = activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                act.recreate()
            }
        }, 120)
    }

    private fun performLogout() {
        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.onPreLogout()
            }

            val token = sessionManager?.authToken ?: ""
            if (token.isNotEmpty()) {
                executeSafely {
                    try {
                        apiClient?.logout(token)
                    } catch (_: Exception) {
                    }
                }
            }

            sessionManager?.logout()
            orgProfileManager?.logout()

            if (act != null && !act.isFinishing) {
                val intent = Intent(act, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                act.finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "performLogout error: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraProfileScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabicState

        var securityExpanded by remember { mutableStateOf(false) }
        var themeExpanded by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.backgroundTop
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                        )
                    )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. بطاقة الملف الشخصي الفاخرة
                    item {
                        UserHeroCard(
                            isArabic = isAr,
                            displayName = displayNameState,
                            username = usernameState,
                            role = roleState,
                            phone = phoneState,
                            branchName = branchNameState,
                            avatarBitmap = avatarBitmapState,
                            onPickPhoto = { pickImage() },
                            onEditProfile = { showEditUserDialogState = true },
                            onChangePassword = { showChangePasswordDialogState = true }
                        )
                    }

                    // 2. بطاقة بيانات المنظمة
                    item {
                        OrganizationDetailsCard(
                            isArabic = isAr,
                            isAdmin = isAdminState,
                            nameAr = orgNameArState,
                            nameEn = orgNameEnState,
                            slogan = orgSloganState,
                            phone = orgPhoneState,
                            email = orgEmailState,
                            taxNumber = taxNumberState,
                            address = orgAddressState,
                            website = orgWebsiteState,
                            onEditOrg = { showEditOrgDialogState = true }
                        )
                    }

                    // 3. قسم الأمان والبصمة (قابل للطي)
                    item {
                        ExpandableSectionCard(
                            title = if (isAr) "الأمان والبصمة" else "Security & Biometrics",
                            subtitle = if (isAr) "تسجيل الدخول وتأكيد العمليات بالبصمة" else "Biometric login & operation approval",
                            icon = Icons.Rounded.Fingerprint,
                            accentColor = Color(0xFF10B981),
                            expanded = securityExpanded,
                            onToggle = { securityExpanded = !securityExpanded }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BiometricSwitchRow(
                                    title = if (isAr) "تسجيل الدخول بالبصمة" else "Biometric Login",
                                    subtitle = if (isAr) "الدخول السريع للحساب عبر بصمة الإصبع" else "Quick sign-in using fingerprint",
                                    checked = biometricLoginState,
                                    onCheckedChange = { checked ->
                                        biometricLoginState = checked
                                        sessionManager?.setBiometricLoginEnabled(checked)
                                        saveBiometricPreference("enable_biometric_login", checked)
                                    }
                                )
                                Divider(color = colors.textSecondary.copy(alpha = 0.12f))
                                BiometricSwitchRow(
                                    title = if (isAr) "تأكيد العمليات بالبصمة" else "Biometric Confirmation",
                                    subtitle = if (isAr) "طلب البصمة عند الاعتماد أو الحذف" else "Require fingerprint for sensitive actions",
                                    checked = biometricConfirmState,
                                    onCheckedChange = { checked ->
                                        biometricConfirmState = checked
                                        sessionManager?.setBiometricConfirmEnabled(checked)
                                        saveBiometricPreference("enable_biometric_confirm", checked)
                                    }
                                )
                            }
                        }
                    }

                    // 4. قسم مظهر التطبيق (قابل للطي)
                    item {
                        ExpandableSectionCard(
                            title = if (isAr) "مظهر التطبيق" else "App Appearance",
                            subtitle = if (isAr) "التبديل بين الوضع الليلي والنهاري" else "Switch between Dark, Light & System",
                            icon = Icons.Rounded.Palette,
                            accentColor = Color(0xFF06B6D4),
                            expanded = themeExpanded,
                            onToggle = { themeExpanded = !themeExpanded }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeOptionRow(
                                    label = if (isAr) "الوضع الليلي الفاخر (Dark)" else "Dark Mode",
                                    icon = Icons.Rounded.DarkMode,
                                    selected = selectedThemeModeState == AppCompatDelegate.MODE_NIGHT_YES,
                                    onClick = { applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES) }
                                )
                                ThemeOptionRow(
                                    label = if (isAr) "الوضع النهاري (Light)" else "Light Mode",
                                    icon = Icons.Rounded.LightMode,
                                    selected = selectedThemeModeState == AppCompatDelegate.MODE_NIGHT_NO,
                                    onClick = { applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO) }
                                )
                                ThemeOptionRow(
                                    label = if (isAr) "حسب إعدادات النظام" else "Follow System",
                                    icon = Icons.Rounded.SettingsBrightness,
                                    selected = selectedThemeModeState == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                                    onClick = { applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
                                )
                            }
                        }
                    }

                    // 5. زر تسجيل الخروج
                    item {
                        Button(
                            onClick = { showLogoutConfirmDialogState = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444).copy(alpha = 0.16f),
                                contentColor = Color(0xFFEF4444)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.45f))
                        ) {
                            Icon(
                                Icons.Rounded.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAr) "تسجيل الخروج من الحساب" else "Sign Out",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // النوافذ المنبثقة (Dialogs)
        if (showEditUserDialogState) {
            EditUserProfileDialog(
                isArabic = isAr,
                initialName = displayNameState,
                initialPhone = phoneState,
                initialEmail = emailState,
                initialUsername = usernameState,
                onDismiss = { showEditUserDialogState = false },
                onSave = { name, ph, em, user ->
                    showEditUserDialogState = false
                    saveUserProfileToServer(name, ph, em, user)
                }
            )
        }

        if (showEditOrgDialogState) {
            EditOrgProfileDialog(
                isArabic = isAr,
                initialNameAr = orgNameArState,
                initialNameEn = orgNameEnState,
                initialPhone = orgPhoneState,
                initialEmail = orgEmailState,
                initialTax = taxNumberState,
                initialSlogan = orgSloganState,
                initialAddress = orgAddressState,
                initialWebsite = orgWebsiteState,
                onDismiss = { showEditOrgDialogState = false },
                onSave = { nAr, nEn, ph, em, tx, sl, ad, wb ->
                    showEditOrgDialogState = false
                    saveOrgProfileToServer(nAr, nEn, ph, em, tx, sl, ad, wb)
                }
            )
        }

        if (showChangePasswordDialogState) {
            ChangePasswordDialog(
                isArabic = isAr,
                onDismiss = { showChangePasswordDialogState = false },
                onConfirm = { cur, newPass ->
                    showChangePasswordDialogState = false
                    changePassword(cur, newPass)
                }
            )
        }

        if (showLogoutConfirmDialogState) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirmDialogState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "تسجيل الخروج" else "Logout",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (isAr) "هل أنت متأكد من رغبتك في تسجيل الخروج من التطبيق؟" else "Are you sure you want to logout?",
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLogoutConfirmDialogState = false
                            performLogout()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(if (isAr) "تسجيل الخروج" else "Logout", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirmDialogState = false }) {
                        Text(if (isAr) "إلغاء" else "Cancel", color = colors.textSecondary)
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UI Components
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun UserHeroCard(
        isArabic: Boolean,
        displayName: String,
        username: String,
        role: String,
        phone: String,
        branchName: String,
        avatarBitmap: Bitmap?,
        onPickPhoto: () -> Unit,
        onEditProfile: () -> Unit,
        onChangePassword: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // صورة البروفايل مع زر الكاميرا
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(colors.goldPrimary.copy(alpha = 0.14f))
                            .border(2.dp, colors.goldPrimary, CircleShape)
                            .clickable { onPickPhoto() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { onPickPhoto() },
                        shape = CircleShape,
                        color = colors.goldPrimary,
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = Color(0xFF0A0F1D),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = displayName.ifEmpty { if (isArabic) "مستخدم ميرا" else "MIRA User" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                if (username.isNotEmpty()) {
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // شارات الصلاحية والفرع
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.goldPrimary.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = role,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.goldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (branchName.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Store,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = branchName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF10B981),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (phone.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Phone,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // أزرار تعديل الملف الشخصي وكلمة المرور
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onEditProfile,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "تعديل الملف" else "Edit Profile",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onChangePassword,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "كلمة المرور" else "Password",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun OrganizationDetailsCard(
        isArabic: Boolean,
        isAdmin: Boolean,
        nameAr: String,
        nameEn: String,
        slogan: String,
        phone: String,
        email: String,
        taxNumber: String,
        address: String,
        website: String,
        onEditOrg: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.goldPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Business,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isArabic) "بيانات المنظمة" else "Organization Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    if (isAdmin) {
                        TextButton(
                            onClick = onEditOrg,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isArabic) "تعديل" else "Edit",
                                color = colors.goldPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Divider(color = colors.textSecondary.copy(alpha = 0.12f))

                OrgInfoRow(
                    icon = Icons.Rounded.Business,
                    label = if (isArabic) "الاسم بالعربي" else "Arabic Name",
                    value = nameAr
                )
                OrgInfoRow(
                    icon = Icons.Rounded.Language,
                    label = if (isArabic) "الاسم بالإنجليزي" else "English Name",
                    value = nameEn
                )
                if (slogan.isNotEmpty()) {
                    OrgInfoRow(
                        icon = Icons.Rounded.Star,
                        label = if (isArabic) "الوصف" else "Slogan",
                        value = slogan
                    )
                }
                OrgInfoRow(
                    icon = Icons.Rounded.Phone,
                    label = if (isArabic) "الهاتف" else "Phone",
                    value = phone
                )
                OrgInfoRow(
                    icon = Icons.Rounded.Email,
                    label = if (isArabic) "البريد الإلكتروني" else "Email",
                    value = email
                )
                OrgInfoRow(
                    icon = Icons.Rounded.Badge,
                    label = if (isArabic) "الرقم الضريبي" else "Tax Number",
                    value = taxNumber
                )
                if (address.isNotEmpty()) {
                    OrgInfoRow(
                        icon = Icons.Rounded.LocationOn,
                        label = if (isArabic) "العنوان" else "Address",
                        value = address
                    )
                }
                if (website.isNotEmpty()) {
                    OrgInfoRow(
                        icon = Icons.Rounded.Public,
                        label = if (isArabic) "الموقع الإلكتروني" else "Website",
                        value = website
                    )
                }
            }
        }
    }

    @Composable
    private fun OrgInfoRow(
        icon: ImageVector,
        label: String,
        value: String
    ) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(0.45f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Text(
                text = value.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.55f)
            )
        }
    }

    @Composable
    private fun ExpandableSectionCard(
        title: String,
        subtitle: String,
        icon: ImageVector,
        accentColor: Color,
        expanded: Boolean,
        onToggle: () -> Unit,
        content: @Composable () -> Unit
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.textSecondary
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        Divider(
                            color = colors.textSecondary.copy(alpha = 0.12f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        content()
                    }
                }
            }
        }
    }

    @Composable
    private fun BiometricSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0A0F1D),
                    checkedTrackColor = colors.goldPrimary
                )
            )
        }
    }

    @Composable
    private fun ThemeOptionRow(
        label: String,
        icon: ImageVector,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() },
            shape = RoundedCornerShape(12.dp),
            color = if (selected) colors.goldPrimary.copy(alpha = 0.14f) else Color.Transparent,
            border = BorderStroke(
                1.dp,
                if (selected) colors.goldPrimary.copy(alpha = 0.5f) else colors.textSecondary.copy(alpha = 0.15f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (selected) colors.goldPrimary else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.goldPrimary else colors.textPrimary
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Compose Dialogs
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun EditUserProfileDialog(
        isArabic: Boolean,
        initialName: String,
        initialPhone: String,
        initialEmail: String,
        initialUsername: String,
        onDismiss: () -> Unit,
        onSave: (String, String, String, String) -> Unit
    ) {
        val colors = MiraTheme.colors
        var name by remember { mutableStateOf(initialName) }
        var phone by remember { mutableStateOf(initialPhone) }
        var email by remember { mutableStateOf(initialEmail) }
        var username by remember { mutableStateOf(initialUsername) }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.cardSurface,
            title = {
                Text(
                    text = if (isArabic) "تعديل الملف الشخصي" else "Edit Profile",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isArabic) "الاسم الكامل" else "Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (isArabic) "اسم المستخدم" else "Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(if (isArabic) "رقم الهاتف" else "Phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(if (isArabic) "البريد الإلكتروني" else "Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(name.trim(), phone.trim(), email.trim(), username.trim()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Text(if (isArabic) "حفظ" else "Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    @Composable
    private fun EditOrgProfileDialog(
        isArabic: Boolean,
        initialNameAr: String,
        initialNameEn: String,
        initialPhone: String,
        initialEmail: String,
        initialTax: String,
        initialSlogan: String,
        initialAddress: String,
        initialWebsite: String,
        onDismiss: () -> Unit,
        onSave: (String, String, String, String, String, String, String, String) -> Unit
    ) {
        val colors = MiraTheme.colors
        var nameAr by remember { mutableStateOf(initialNameAr) }
        var nameEn by remember { mutableStateOf(initialNameEn) }
        var phone by remember { mutableStateOf(initialPhone) }
        var email by remember { mutableStateOf(initialEmail) }
        var tax by remember { mutableStateOf(initialTax) }
        var slogan by remember { mutableStateOf(initialSlogan) }
        var address by remember { mutableStateOf(initialAddress) }
        var website by remember { mutableStateOf(initialWebsite) }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.cardSurface,
            title = {
                Text(
                    text = if (isArabic) "تعديل بيانات المنظمة" else "Edit Organization",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = nameAr,
                        onValueChange = { nameAr = it },
                        label = { Text(if (isArabic) "اسم المنظمة (عربي)" else "Name (Arabic)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nameEn,
                        onValueChange = { nameEn = it },
                        label = { Text(if (isArabic) "اسم المنظمة (إنجليزي)" else "Name (English)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = slogan,
                        onValueChange = { slogan = it },
                        label = { Text(if (isArabic) "الشعار اللفظي" else "Slogan") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(if (isArabic) "الهاتف" else "Phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(if (isArabic) "البريد الإلكتروني" else "Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tax,
                        onValueChange = { tax = it },
                        label = { Text(if (isArabic) "الرقم الضريبي" else "Tax Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(if (isArabic) "العنوان" else "Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = website,
                        onValueChange = { website = it },
                        label = { Text(if (isArabic) "الموقع الإلكتروني" else "Website") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(
                            nameAr.trim(), nameEn.trim(), phone.trim(), email.trim(),
                            tax.trim(), slogan.trim(), address.trim(), website.trim()
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Text(if (isArabic) "حفظ" else "Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    @Composable
    private fun ChangePasswordDialog(
        isArabic: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (String, String) -> Unit
    ) {
        val colors = MiraTheme.colors
        var currentPass by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }

        val visualTransform = if (showPass) VisualTransformation.None else PasswordVisualTransformation()

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.cardSurface,
            title = {
                Text(
                    text = if (isArabic) "تغيير كلمة المرور" else "Change Password",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPass,
                        onValueChange = { currentPass = it },
                        label = { Text(if (isArabic) "كلمة المرور الحالية" else "Current Password") },
                        visualTransformation = visualTransform,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text(if (isArabic) "كلمة المرور الجديدة" else "New Password") },
                        visualTransformation = visualTransform,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = { confirmPass = it },
                        label = { Text(if (isArabic) "تأكيد كلمة المرور" else "Confirm Password") },
                        visualTransformation = visualTransform,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showPass = !showPass }
                    ) {
                        Checkbox(checked = showPass, onCheckedChange = { showPass = it })
                        Text(
                            text = if (isArabic) "إظهار كلمات المرور" else "Show passwords",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            currentPass.isEmpty() || newPass.isEmpty() ->
                                showToast(if (isArabic) "⚠️ جميع الحقول مطلوبة" else "⚠️ All fields required")
                            newPass != confirmPass ->
                                showToast(if (isArabic) "⚠️ كلمتا المرور غير متطابقتين" else "⚠️ Passwords do not match")
                            newPass.length < 6 ->
                                showToast(if (isArabic) "⚠️ كلمة المرور قصيرة (6 أحرف على الأقل)" else "⚠️ Min 6 characters")
                            else -> onConfirm(currentPass, newPass)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Text(if (isArabic) "تغيير" else "Change", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun executeSafely(task: Runnable) {
        if (isDestroyed.get()) return
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            Thread(task).start()
        }
    }

    private fun showLoading(show: Boolean) {
        try {
            val act = activity
            if (act is UHFMainActivity && !isDestroyed.get()) {
                if (show) act.showLoading() else act.hideLoading()
            }
        } catch (_: Exception) {
        }
    }

    private fun showToast(message: String) {
        try {
            if (isAdded && !isDestroyed.get() && context != null) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
        }
    }
}
