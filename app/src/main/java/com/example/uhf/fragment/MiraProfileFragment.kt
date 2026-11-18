package com.example.uhf.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

class MiraProfileFragment : Fragment() {

    private var sessionManager: SessionManager? = null
    private var apiClient: MiraApiClient? = null
    private var orgProfileManager: MiraOrgProfileManager? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null

    private var isArabicState by mutableStateOf(true)
    private var displayNameState by mutableStateOf("")
    private var usernameState by mutableStateOf("")
    private var roleState by mutableStateOf("")
    private var phoneState by mutableStateOf("")
    private var branchNameState by mutableStateOf("")
    private var orgNameArState by mutableStateOf("")
    private var orgPhoneState by mutableStateOf("")
    private var orgEmailState by mutableStateOf("")
    private var taxNumberState by mutableStateOf("")

    private var isBiometricLoginState by mutableStateOf(false)
    private var isBiometricConfirmState by mutableStateOf(false)

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

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
        val ctx = requireContext()
        sessionManager = SessionManager(ctx)
        apiClient = MiraApiClient.getInstance(ctx)
        orgProfileManager = MiraOrgProfileManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)

        isArabicState = Locale.getDefault().language == "ar"

        setupImagePicker()
        loadLocalData()
        fetchServerData()
    }

    private fun setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                result.data?.data?.let { uploadProfileImage(it) }
            }
        }
    }

    private fun loadLocalData() {
        val sm = sessionManager ?: return
        val opm = orgProfileManager ?: return
        displayNameState = sm.displayName ?: ""
        usernameState = if (!sm.username.isNullOrEmpty()) "@${sm.username}" else ""
        roleState = sm.role ?: (if (isArabicState) "موظف" else "Employee")
        phoneState = sm.phone ?: ""
        branchNameState = branchesManager?.activeBranchDisplayName ?: ""
        orgNameArState = opm.orgNameAr ?: ""
        orgPhoneState = opm.orgPhone ?: ""
        orgEmailState = opm.orgEmail ?: ""
        taxNumberState = opm.taxNumber ?: ""
        isBiometricLoginState = sm.isBiometricLoginEnabled
        isBiometricConfirmState = sm.isBiometricConfirmEnabled
    }

    private fun fetchServerData() {
        executor.execute {
            try {
                val response = apiClient?.orgProfile
                if (response != null && response.isSuccess && response.data != null) {
                    val user = response.data?.optJSONObject("user")
                    val org = response.data?.optJSONObject("organization")
                    if (org != null) orgProfileManager?.saveFromServer(org)
                    mainHandler.post { loadLocalData() }
                }
            } catch (_: Exception) {}
        }
    }

    private fun uploadProfileImage(imageUri: Uri) {
        executor.execute {
            try {
                val ctx = context ?: return@execute
                val inputStream: InputStream? = ctx.contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                    val imageBytes = baos.toByteArray()
                    val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)

                    val payload = JSONObject().put("avatar_base64", base64Image)
                    apiClient?.uploadUserAvatar(payload)

                    mainHandler.post {
                        Toast.makeText(ctx, if (isArabicState) "✅ تم تحديث الصورة" else "✅ Photo updated", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @Composable
    private fun MiraProfileScreen() {
        val colors = MiraTheme.colors

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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. بطاقة الملف الشخصي الأساسية
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // أيقونة أو صورة البروفايل مع زر التغيير
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(CircleShape)
                                        .background(colors.goldPrimary.copy(alpha = 0.15f))
                                        .clickable {
                                            try {
                                                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                                imagePickerLauncher.launch(intent)
                                            } catch (_: Exception) {}
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.CameraAlt,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = displayNameState.ifEmpty { "Mira User" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = usernameState,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = colors.goldPrimary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = roleState,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.goldPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(activity, LoginActivity::class.java).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                }
                                                sessionManager?.logout()
                                                startActivity(intent)
                                                activity?.finish()
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                    ) {
                                        Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (isArabicState) "تسجيل الخروج" else "Logout")
                                    }
                                }
                            }
                        }
                    }

                    // 2. بطاقة بيانات المنظمة
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Business, contentDescription = null, tint = colors.goldPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isArabicState) "بيانات المنظمة" else "Organization Details",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                                Divider(color = colors.textSecondary.copy(alpha = 0.15f))
                                ProfileInfoRow(label = if (isArabicState) "الاسم بالعربي" else "Arabic Name", value = orgNameArState)
                                ProfileInfoRow(label = if (isArabicState) "الهاتف" else "Phone", value = orgPhoneState)
                                ProfileInfoRow(label = if (isArabicState) "البريد" else "Email", value = orgEmailState)
                                ProfileInfoRow(label = if (isArabicState) "الرقم الضريبي" else "Tax Number", value = taxNumberState)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProfileInfoRow(label: String, value: String) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Text(text = value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.textPrimary)
        }
    }
}
