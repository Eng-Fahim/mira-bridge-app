package com.example.uhf.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.LoginActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class MiraProfileFragment : Fragment() {

    companion object {
        private const val TAG = "MiraProfileFragment"
        private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    }

    // UI Views
    private var imgUserAvatar: ShapeableImageView? = null
    private var tvProfileName: TextView? = null
    private var tvRole: TextView? = null
    private var tvAccountNum: TextView? = null
    private var tvBranchName: TextView? = null
    private var tvUsername: TextView? = null
    private var tvOrgNameAr: TextView? = null
    private var tvOrgNameEn: TextView? = null
    private var tvPhone: TextView? = null
    private var tvEmail: TextView? = null
    private var tvTaxNum: TextView? = null

    private var imgArrowSecurity: ImageView? = null
    private var imgArrowTheme: ImageView? = null
    private var layoutSecurityExpandable: LinearLayout? = null
    private var layoutAppThemeExpandable: LinearLayout? = null

    private var switchBiometricLogin: Switch? = null
    private var switchBiometricConfirm: Switch? = null

    private var radioThemeGroup: RadioGroup? = null
    private var radioThemeLight: RadioButton? = null
    private var radioThemeDark: RadioButton? = null
    private var radioThemeSystem: RadioButton? = null

    private var btnEditOrgProfile: MaterialButton? = null
    private var btnEditProfile: MaterialButton? = null
    private var btnChangePassword: MaterialButton? = null
    private var btnChangePhoto: MaterialButton? = null
    private var btnLogout: MaterialButton? = null

    // Managers & Tools
    private var sessionManager: SessionManager? = null
    private var apiClient: MiraApiClient? = null
    private var orgProfileManager: MiraOrgProfileManager? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDestroyed = AtomicBoolean(false)
    private var isBindingTheme = false
    private var isArabic = true

    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_mira_profile, container, false)

        isDestroyed.set(false)
        isArabic = Locale.getDefault().language == "ar"

        val ctx = requireContext()
        sessionManager = SessionManager(ctx)
        apiClient = MiraApiClient.getInstance(ctx)
        orgProfileManager = MiraOrgProfileManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)

        initViews(root)
        setupImagePicker()
        setupListeners(root)

        loadLocalProfileData()
        fetchProfileFromServer()

        return root
    }

    private fun initViews(root: View) {
        imgUserAvatar = root.findViewById(R.id.imgUserAvatar)
        tvProfileName = root.findViewById(R.id.tvProfileName)
        tvUsername = root.findViewById(R.id.tvUsername)
        tvRole = root.findViewById(R.id.tvRole)
        tvAccountNum = root.findViewById(R.id.tvAccountNum)
        tvBranchName = root.findViewById(R.id.tvBranchName)

        tvOrgNameAr = root.findViewById(R.id.tvOrgNameAr)
        tvOrgNameEn = root.findViewById(R.id.tvOrgNameEn)
        tvPhone = root.findViewById(R.id.tvPhone)
        tvEmail = root.findViewById(R.id.tvEmail)
        tvTaxNum = root.findViewById(R.id.tvTaxNum)

        imgArrowSecurity = root.findViewById(R.id.imgArrowSecurity)
        imgArrowTheme = root.findViewById(R.id.imgArrowTheme)

        layoutSecurityExpandable = root.findViewById(R.id.layoutSecurityExpandable)
        layoutAppThemeExpandable = root.findViewById(R.id.layoutAppThemeExpandable)

        switchBiometricLogin = root.findViewById(R.id.switchBiometricLogin)
        switchBiometricConfirm = root.findViewById(R.id.switchBiometricConfirm)

        radioThemeGroup = root.findViewById(R.id.radioThemeGroup)
        radioThemeLight = root.findViewById(R.id.radioThemeLight)
        radioThemeDark = root.findViewById(R.id.radioThemeDark)
        radioThemeSystem = root.findViewById(R.id.radioThemeSystem)

        btnEditOrgProfile = root.findViewById(R.id.btnEditOrgProfile)
        btnEditProfile = root.findViewById(R.id.btnEditProfile)
        btnChangePassword = root.findViewById(R.id.btnChangePassword)
        btnChangePhoto = root.findViewById(R.id.btnChangePhoto)
        btnLogout = root.findViewById(R.id.btnLogout)
    }

    private fun setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val imageUri = result.data?.data
                if (imageUri != null) uploadProfileImage(imageUri)
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
            logNonFatal("pickImage", e)
            showToast(if (isArabic) "❌ تعذر فتح المعرض" else "❌ Could not open gallery")
        }
    }

    private fun setupListeners(root: View) {
        btnEditOrgProfile?.let { btn ->
            if (permissionManager?.isAdmin() == true) {
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { showEditOrgDialog() }
            } else {
                btn.visibility = View.GONE
            }
        }

        btnEditProfile?.setOnClickListener { showEditUserDialog() }
        btnChangePassword?.setOnClickListener { showChangePasswordDialog() }
        btnChangePhoto?.setOnClickListener { pickImage() }
        btnLogout?.setOnClickListener { showLogoutDialog() }

        root.findViewById<View>(R.id.headerSecurity)?.setOnClickListener {
            toggleSection(layoutSecurityExpandable, imgArrowSecurity)
        }

        root.findViewById<View>(R.id.headerAppTheme)?.setOnClickListener {
            toggleSection(layoutAppThemeExpandable, imgArrowTheme)
        }

        imgUserAvatar?.setOnClickListener { pickImage() }

        switchBiometricLogin?.setOnCheckedChangeListener { _, isChecked ->
            sessionManager?.setBiometricLoginEnabled(isChecked)
            saveBiometricPreference("enable_biometric_login", isChecked)
        }

        switchBiometricConfirm?.setOnCheckedChangeListener { _, isChecked ->
            sessionManager?.setBiometricConfirmEnabled(isChecked)
            saveBiometricPreference("enable_biometric_confirm", isChecked)
        }

        radioThemeGroup?.setOnCheckedChangeListener { _, checkedId ->
            if (isDestroyed.get() || isBindingTheme || !isAdded) return@setOnCheckedChangeListener

            val newMode = when (checkedId) {
                R.id.radioThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                R.id.radioThemeSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                R.id.radioThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                else -> return@setOnCheckedChangeListener
            }

            val currentMode = AppCompatDelegate.getDefaultNightMode()
            if (currentMode == newMode) return@setOnCheckedChangeListener

            Log.i(TAG, "Theme changing: $currentMode → $newMode")

            // 1) حفظ متزامن
            saveThemePreferenceSync(newMode)

            // 2) تطبيق الثيم
            AppCompatDelegate.setDefaultNightMode(newMode)

            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
            }

            // 3) إعادة إنشاء النشاط لتحديث واجهة المستخدم
            mainHandler.postDelayed({
                try {
                    val act = activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        act.recreate()
                    }
                } catch (e: Exception) {
                    logNonFatal("recreate", e)
                }
            }, 150)
        }
    }

    private fun toggleSection(layout: LinearLayout?, arrow: ImageView?) {
        if (layout == null) return
        val isVisible = layout.visibility == View.VISIBLE
        layout.visibility = if (isVisible) View.GONE else View.VISIBLE
        arrow?.animate()?.rotation(if (isVisible) 0f else 180f)?.duration = 200
    }

    private fun loadLocalProfileData() {
        try {
            val sm = sessionManager ?: return
            val displayName = sm.displayName
            val username = sm.username
            val phone = sm.phone
            val role = sm.role

            if (tvProfileName != null && !displayName.isNullOrEmpty()) tvProfileName?.text = displayName
            if (tvUsername != null && !username.isNullOrEmpty()) tvUsername?.text = "@$username"
            if (tvRole != null) tvRole?.text = role ?: if (isArabic) "موظف" else "Employee"
            if (tvAccountNum != null && !phone.isNullOrEmpty()) tvAccountNum?.text = phone
            if (tvBranchName != null && branchesManager != null) {
                tvBranchName?.text = "🏢 " + branchesManager?.activeBranchDisplayName
            }

            loadUserAvatar()

            val opm = orgProfileManager ?: return
            if (tvOrgNameAr != null) tvOrgNameAr?.text = opm.orgNameAr
            if (tvOrgNameEn != null) tvOrgNameEn?.text = opm.orgNameEn
            if (tvPhone != null) tvPhone?.text = opm.orgPhone
            if (tvEmail != null) tvEmail?.text = opm.orgEmail
            if (tvTaxNum != null) {
                val tax = opm.taxNumber
                if (!tax.isNullOrEmpty()) {
                    tvTaxNum?.text = (if (isArabic) "الرقم الضريبي: " else "Tax Number: ") + tax
                }
            }

            switchBiometricLogin?.isChecked = sm.isBiometricLoginEnabled
            switchBiometricConfirm?.isChecked = sm.isBiometricConfirmEnabled

            loadThemePreference()
        } catch (e: Exception) {
            logNonFatal("loadLocalProfileData", e)
        }
    }

    private fun loadThemePreference() {
        try {
            val ctx = context ?: return
            if (radioThemeGroup == null) return

            val prefs = ctx.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            val mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

            val selectedId = when (mode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.radioThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.radioThemeDark
                else -> R.id.radioThemeSystem
            }

            isBindingTheme = true
            radioThemeGroup?.check(selectedId)
            Log.d(TAG, "Theme loaded: mode=$mode, id=$selectedId")
        } catch (e: Exception) {
            logNonFatal("loadThemePreference", e)
        } finally {
            isBindingTheme = false
        }
    }

    private fun saveThemePreferenceSync(mode: Int) {
        try {
            val ctx = context ?: return
            val prefs = ctx.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            val saved = prefs.edit().putInt("theme_mode", mode).commit()
            Log.d(TAG, "Theme saved: mode=$mode, success=$saved")
        } catch (e: Exception) {
            logNonFatal("saveThemePreferenceSync", e)
        }
    }

    private fun loadUserAvatar() {
        executeSafely {
            try {
                val ctx = context ?: return@executeSafely
                val prefs = ctx.getSharedPreferences("user_avatar", Context.MODE_PRIVATE)
                val avatarPath = prefs.getString("avatar_path", "") ?: ""

                if (avatarPath.isNotEmpty()) {
                    val avatarFile = File(avatarPath)
                    if (avatarFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
                        if (bitmap != null) {
                            mainHandler.post {
                                if (!isDestroyed.get() && isAdded && imgUserAvatar != null) {
                                    imgUserAvatar?.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logNonFatal("loadUserAvatar", e)
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
                        showToast(if (isArabic) "❌ فشل تحميل الصورة" else "❌ Image load error")
                    }
                    return@executeSafely
                }

                val maxDimension = 512
                if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                    val ratio = Math.min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                    val width = Math.round(ratio * bitmap.width)
                    val height = Math.round(ratio * bitmap.height)
                    bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                }

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val imageBytes = baos.toByteArray()

                val avatarFile = File(ctx.filesDir, "user_avatar.jpg")
                val fos = FileOutputStream(avatarFile)
                fos.write(imageBytes)
                fos.close()

                val prefs = ctx.getSharedPreferences("user_avatar", Context.MODE_PRIVATE)
                prefs.edit().putString("avatar_path", avatarFile.absolutePath).apply()

                val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)
                val payload = JSONObject().apply {
                    put("avatar_base64", base64Image)
                }

                val response = apiClient?.uploadUserAvatar(payload)

                mainHandler.post {
                    if (isDestroyed.get() || !isAdded) return@post
                    showLoading(false)

                    if (response != null && response.isSuccess) {
                        val serverUrl = response.data?.optString("avatar_url", "") ?: ""
                        if (serverUrl.isNotEmpty()) prefs.edit().putString("avatar_url", serverUrl).apply()
                        showToast(if (isArabic) "✅ تم تحديث الصورة" else "✅ Photo updated")
                    } else {
                        showToast(if (isArabic) "💾 حفظ محلي فقط" else "💾 Local save only")
                    }
                    loadUserAvatar()
                }
            } catch (e: Exception) {
                logNonFatal("uploadProfileImage", e)
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabic) "❌ خطأ في رفع الصورة" else "❌ Upload error")
                    }
                }
            }
        }
    }

    private fun fetchProfileFromServer() {
        if (isDestroyed.get() || !isAdded) return

        executeSafely {
            try {
                val response = apiClient?.orgProfile

                if (response != null && response.isSuccess && response.data != null) {
                    val user = response.data?.optJSONObject("user")
                    val org = response.data?.optJSONObject("organization")

                    if (org != null) orgProfileManager?.saveFromServer(org)

                    if (user != null) {
                        val displayName = user.optString("display_name", "")
                        val phone = user.optString("phone", "")
                        if (!displayName.isNullOrEmpty()) sessionManager?.displayName = displayName
                        if (!phone.isNullOrEmpty()) sessionManager?.phone = phone
                    }

                    mainHandler.post {
                        if (!isDestroyed.get() && isAdded) updateUIWithData(user, org)
                    }
                }
            } catch (e: Exception) {
                logNonFatal("fetchProfileFromServer.thread", e)
            }
        }
    }

    private fun updateUIWithData(user: JSONObject?, org: JSONObject?) {
        if (user != null) {
            tvProfileName?.text = user.optString("display_name", "")
            val username = user.optString("username", "")
            if (!username.isNullOrEmpty()) tvUsername?.text = "@$username"
            tvAccountNum?.text = user.optString("phone", "")
            val branchName = user.optString("branch_name", "")
            if (!branchName.isNullOrEmpty()) tvBranchName?.text = "🏢 $branchName"
        }

        if (org != null) {
            tvOrgNameAr?.text = org.optString("name_ar", "")
            tvOrgNameEn?.text = org.optString("name_en", "")
            tvPhone?.text = org.optString("phone", "")
            tvEmail?.text = org.optString("email", "")
            val tax = org.optString("tax_number", "")
            if (!tax.isNullOrEmpty()) {
                tvTaxNum?.text = (if (isArabic) "الرقم الضريبي: " else "Tax Number: ") + tax
            }
        }
    }

    private fun showEditOrgDialog() {
        val ctx = context ?: return
        if (isDestroyed.get()) return
        if (permissionManager?.isAdmin() != true) {
            showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_mira_profile, null)
        val etNameAr = dialogView.findViewById<EditText>(R.id.etEditNameAr)
        val etNameEn = dialogView.findViewById<EditText>(R.id.etEditNameEn)
        val etPhone = dialogView.findViewById<EditText>(R.id.etEditPhone)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEditEmail)
        val etTax = dialogView.findViewById<EditText>(R.id.etEditTax)
        val etSlogan = dialogView.findViewById<EditText>(R.id.etEditSlogan)
        val etAddress = dialogView.findViewById<EditText>(R.id.etEditAddress)
        val etWebsite = dialogView.findViewById<EditText>(R.id.etEditWebsite)

        tvOrgNameAr?.text?.let { etNameAr?.setText(it) }
        tvOrgNameEn?.text?.let { etNameEn?.setText(it) }
        tvPhone?.text?.let { etPhone?.setText(it) }
        tvEmail?.text?.let { etEmail?.setText(it) }
        etSlogan?.setText(orgProfileManager?.orgSlogan ?: "")
        etAddress?.setText(orgProfileManager?.orgAddress ?: "")
        etWebsite?.setText(orgProfileManager?.orgWebsite ?: "")

        tvTaxNum?.text?.toString()?.let { taxText ->
            val tax = taxText.replace(if (isArabic) "الرقم الضريبي: " else "Tax Number: ", "").trim()
            etTax?.setText(tax)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isArabic) "✏️ تعديل بيانات المنظمة" else "✏️ Edit Organization")
            .setView(dialogView)
            .setPositiveButton(if (isArabic) "حفظ" else "Save") { _, _ ->
                val nameAr = etNameAr?.text?.toString()?.trim() ?: ""
                val nameEn = etNameEn?.text?.toString()?.trim() ?: ""
                val phone = etPhone?.text?.toString()?.trim() ?: ""
                val email = etEmail?.text?.toString()?.trim() ?: ""
                val tax = etTax?.text?.toString()?.trim() ?: ""
                val slogan = etSlogan?.text?.toString()?.trim() ?: ""
                val address = etAddress?.text?.toString()?.trim() ?: ""
                val website = etWebsite?.text?.toString()?.trim() ?: ""

                saveOrgProfileToServer(nameAr, nameEn, phone, email, tax, slogan, address, website)
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun showEditUserDialog() {
        val ctx = context ?: return
        if (isDestroyed.get()) return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_user_profile, null)
        val etDisplayName = dialogView.findViewById<EditText>(R.id.etEditDisplayName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etEditUserPhone)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEditUserEmail)
        val etUsername = dialogView.findViewById<EditText>(R.id.etEditUsername)

        tvProfileName?.text?.let { etDisplayName?.setText(it) }
        tvAccountNum?.text?.let { etPhone?.setText(it) }
        tvUsername?.text?.toString()?.replace("@", "")?.let { etUsername?.setText(it) }
        etEmail?.setText(sessionManager?.email ?: "")

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isArabic) "👤 تعديل الملف الشخصي" else "👤 Edit Profile")
            .setView(dialogView)
            .setPositiveButton(if (isArabic) "حفظ" else "Save") { _, _ ->
                val displayName = etDisplayName?.text?.toString()?.trim() ?: ""
                val phone = etPhone?.text?.toString()?.trim() ?: ""
                val email = etEmail?.text?.toString()?.trim() ?: ""
                val username = etUsername?.text?.toString()?.trim() ?: ""

                saveUserProfileToServer(displayName, phone, email, username)
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val ctx = context ?: return
        if (isDestroyed.get()) return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_change_password, null)
        val etCurrentPassword = dialogView.findViewById<EditText>(R.id.etCurrentPassword)
        val etNewPassword = dialogView.findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = dialogView.findViewById<EditText>(R.id.etConfirmPassword)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isArabic) "🔒 تغيير كلمة المرور" else "🔒 Change Password")
            .setView(dialogView)
            .setPositiveButton(if (isArabic) "تغيير" else "Change") { _, _ ->
                val currentPassword = etCurrentPassword?.text?.toString() ?: ""
                val newPassword = etNewPassword?.text?.toString() ?: ""
                val confirmPassword = etConfirmPassword?.text?.toString() ?: ""

                if (currentPassword.isEmpty() || newPassword.isEmpty()) {
                    showToast(if (isArabic) "⚠️ جميع الحقول مطلوبة" else "⚠️ All fields required")
                    return@setPositiveButton
                }
                if (newPassword != confirmPassword) {
                    showToast(if (isArabic) "⚠️ كلمتا المرور غير متطابقتين" else "⚠️ Passwords do not match")
                    return@setPositiveButton
                }
                if (newPassword.length < 6) {
                    showToast(if (isArabic) "⚠️ كلمة المرور قصيرة (6 أحرف على الأقل)" else "⚠️ Password too short (min 6)")
                    return@setPositiveButton
                }
                changePassword(currentPassword, newPassword)
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
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
                        showToast(if (isArabic) "✅ تم تغيير كلمة المرور" else "✅ Password changed")
                    } else {
                        val error = response?.error ?: "Unknown error"
                        showToast((if (isArabic) "❌ فشل: " else "❌ Failed: ") + error)
                    }
                }
            } catch (e: Exception) {
                logNonFatal("changePassword.thread", e)
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabic) "❌ خطأ" else "❌ Error")
                    }
                }
            }
        }
    }

    private fun showLogoutDialog() {
        val ctx = context ?: return
        if (isDestroyed.get()) return

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isArabic) "🚪 تسجيل الخروج" else "🚪 Logout")
            .setMessage(if (isArabic) "هل أنت متأكد من تسجيل الخروج؟" else "Are you sure you want to logout?")
            .setPositiveButton(if (isArabic) "تسجيل الخروج" else "Logout") { _, _ -> performLogout() }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun performLogout() {
        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.onPreLogout()
            }

            val token = sessionManager?.authToken
            if (!token.isNullOrEmpty()) {
                executeSafely {
                    try {
                        apiClient?.logout(token)
                    } catch (e: Exception) {
                        logNonFatal("performLogout.api", e)
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
            logNonFatal("performLogout", e)
        }
    }

    private fun saveOrgProfileToServer(
        nameAr: String, nameEn: String, phone: String,
        email: String, tax: String, slogan: String,
        address: String, website: String
    ) {
        if (permissionManager?.isAdmin() != true) {
            showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
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
                        tvOrgNameAr?.text = nameAr
                        tvOrgNameEn?.text = nameEn
                        tvPhone?.text = phone
                        tvEmail?.text = email
                        tvTaxNum?.text = (if (isArabic) "الرقم الضريبي: " else "Tax Number: ") + tax

                        orgProfileManager?.saveFullProfileExtended(
                            nameAr, nameEn, phone, email, tax, slogan, address, website
                        )

                        showToast(if (isArabic) "✅ تم حفظ بيانات المنظمة" else "✅ Organization saved")
                    } else {
                        val error = response?.error ?: "Unknown error"
                        showToast((if (isArabic) "❌ فشل: " else "❌ Failed: ") + error)
                    }
                }
            } catch (e: Exception) {
                logNonFatal("saveOrgProfileToServer.thread", e)
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabic) "❌ خطأ في الحفظ" else "❌ Save error")
                    }
                }
            }
        }
    }

    private fun saveUserProfileToServer(displayName: String, phone: String, email: String, username: String) {
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
                        tvProfileName?.text = displayName
                        tvAccountNum?.text = phone
                        if (!username.isNullOrEmpty()) {
                            tvUsername?.text = "@$username"
                        }

                        if (!displayName.isNullOrEmpty()) sessionManager?.displayName = displayName
                        if (!phone.isNullOrEmpty()) sessionManager?.phone = phone
                        if (!email.isNullOrEmpty()) sessionManager?.email = email

                        showToast(if (isArabic) "✅ تم تحديث الملف الشخصي" else "✅ Profile updated")
                    } else {
                        val error = response?.error ?: "Unknown error"
                        showToast((if (isArabic) "❌ فشل: " else "❌ Failed: ") + error)
                    }
                }
            } catch (e: Exception) {
                logNonFatal("saveUserProfileToServer.thread", e)
                mainHandler.post {
                    if (!isDestroyed.get() && isAdded) {
                        showLoading(false)
                        showToast(if (isArabic) "❌ خطأ في الحفظ" else "❌ Save error")
                    }
                }
            }
        }
    }

    private fun saveBiometricPreference(key: String, value: Boolean) {
        executeSafely {
            try {
                val payload = JSONObject().apply {
                    put(key, value)
                }
                apiClient?.saveSettings(payload)
            } catch (e: Exception) {
                logNonFatal("saveBiometricPreference", e)
            }
        }
    }

    private fun executeSafely(task: Runnable) {
        if (isDestroyed.get()) return
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            logNonFatal("executeSafely.rejected", e)
            Thread(task).start()
        }
    }

    private fun showLoading(show: Boolean) {
        try {
            val act = activity
            if (act is UHFMainActivity && !isDestroyed.get()) {
                if (show) act.showLoading()
                else act.hideLoading()
            }
        } catch (e: Exception) {
            logNonFatal("showLoading", e)
        }
    }

    private fun showToast(message: String) {
        try {
            if (isAdded && !isDestroyed.get() && context != null) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            logNonFatal("showToast", e)
        }
    }

    private fun logNonFatal(location: String, throwable: Throwable) {
        Log.e(TAG, "Location: $location | Error: ${throwable.message}", throwable)
    }

    override fun onResume() {
        super.onResume()
        loadLocalProfileData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isDestroyed.set(true)
        mainHandler.removeCallbacksAndMessages(null)

        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.hideLoading()
            }
        } catch (e: Exception) {
            Log.e(TAG, "hideLoading error: ${e.message}")
        }
    }
}
