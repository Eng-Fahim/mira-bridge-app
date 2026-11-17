package com.example.uhf.activity

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.uhf.BuildConfig
import com.example.uhf.R
import com.example.uhf.license.LicenseManager
import com.example.uhf.license.MiraBlobFetcher
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

class ActivationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ActivationActivity"
        private val LICENSE_KEY_PATTERN: Pattern =
            Pattern.compile("^MIRA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
    }

    private lateinit var sessionManager: SessionManager
    private var isArabic: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // حماية الشاشة والأمان ضد التقاط الصور والتسجيل
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        sessionManager = SessionManager(applicationContext)
        isArabic = "ar" == resources.configuration.locales[0].language

        val initialServerUrl = loadValidSavedServerUrl()

        setContent {
            MiraJewelryTheme {
                ActivationScreen(
                    isArabic = isArabic,
                    initialServerUrl = initialServerUrl,
                    onLookupServer = { key -> performServerLookup(key) },
                    onSaveServerUrl = { url -> sessionManager.setServerUrl(url) },
                    onActivateLicense = { serverUrl, key, onSuccess, onError ->
                        performLicenseActivation(serverUrl, key, onSuccess, onError)
                    },
                    onContinueToLogin = { goToLogin() }
                )
            }
        }
    }

    private fun loadValidSavedServerUrl(): String {
        val savedUrl = sessionManager.serverUrl
        val isValid = !savedUrl.isNullOrEmpty() &&
                !savedUrl.contains("id.ibreg.org") &&
                !savedUrl.contains("wp-json") &&
                !savedUrl.endsWith("/api/v1") &&
                !savedUrl.endsWith("/api/v1/")
        return if (isValid) savedUrl else ""
    }

    private fun getCentralUrl(): String {
        val v = BuildConfig.MIRA_LICENSE_BASE_URL
        return if (!v.isNullOrEmpty()) v else "https://jewelry.ibreg.org/api/v1"
    }

    private suspend fun performServerLookup(licenseKey: String): MiraBlobFetcher.LookupResult {
        return withContext(Dispatchers.IO) {
            val centralUrl = getCentralUrl()
            Log.d(TAG, "🔍 Lookup: central=$centralUrl, key=$licenseKey")
            MiraBlobFetcher.lookupServerUrl(applicationContext, centralUrl, licenseKey)
        }
    }

    private fun performLicenseActivation(
        serverUrl: String,
        licenseKey: String,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        LicenseManager.activate(
            applicationContext,
            serverUrl,
            licenseKey,
            object : LicenseManager.Callback {
                override fun onSuccess(response: JSONObject) {
                    runOnUiThread {
                        Log.d(TAG, "✅ Activation successful on $serverUrl")
                        onSuccess(response)
                    }
                }

                override fun onError(code: String, message: String, httpCode: Int) {
                    runOnUiThread {
                        Log.e(TAG, "❌ Activation failed: $code | $message")
                        onError(message)
                    }
                }
            }
        )
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

private data class ActivationSuccessInfo(
    val facilityName: String,
    val validUntil: String,
    val serverUrl: String
)

private val EmeraldSuccess = Color(0xFF10B981)
private val CyanAccent     = Color(0xFF38BDF8)
private val ErrorRed       = Color(0xFFEF4444)

@Composable
private fun ActivationScreen(
    isArabic: Boolean,
    initialServerUrl: String,
    onLookupServer: suspend (String) -> MiraBlobFetcher.LookupResult,
    onSaveServerUrl: (String) -> Unit,
    onActivateLicense: (
        serverUrl: String,
        licenseKey: String,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onContinueToLogin: () -> Unit
) {
    val colors = MiraTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var keyFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var detectedServerUrl by remember { mutableStateOf(initialServerUrl) }
    var detectedCustomerName by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fieldErrorText by remember { mutableStateOf<String?>(null) }
    var showRetry by remember { mutableStateOf(false) }
    var successDialogData by remember { mutableStateOf<ActivationSuccessInfo?>(null) }

    val cleanKey = keyFieldValue.text.trim().uppercase(Locale.ROOT)
    val isPatternValid = remember(cleanKey) {
        Pattern.matches("^MIRA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$", cleanKey)
    }

    fun formatLicenseKey(raw: String): String {
        val alphanumeric = raw.uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .take(16)
        return alphanumeric.chunked(4).joinToString("-")
    }

    fun updateKeyInput(newRawText: String) {
        val formatted = formatLicenseKey(newRawText)
        keyFieldValue = TextFieldValue(
            text = formatted,
            selection = TextRange(formatted.length)
        )
        fieldErrorText = null
        errorMessage = null
        showRetry = false
    }

    fun startActivationProcess() {
        focusManager.clearFocus()

        if (cleanKey.isEmpty()) {
            fieldErrorText = if (isArabic) "أدخل رقم الترخيص" else "Enter license key"
            return
        }

        if (!isPatternValid) {
            fieldErrorText = if (isArabic) {
                "الصيغة المطلوبة: MIRA-XXXX-XXXX-XXXX"
            } else {
                "Required format: MIRA-XXXX-XXXX-XXXX"
            }
            return
        }

        isLoading = true
        errorMessage = null
        fieldErrorText = null
        showRetry = false
        statusText = if (isArabic) {
            "جارٍ التحقق من الترخيص والبحث عن الخادم..."
        } else {
            "Verifying license & discovering server..."
        }

        coroutineScope.launch {
            try {
                val lookup = onLookupServer(cleanKey)

                if (!lookup.success) {
                    isLoading = false
                    showRetry = true
                    errorMessage = lookup.error ?: if (isArabic) {
                        "فشل التحقق من الترخيص"
                    } else {
                        "License lookup failed"
                    }
                    return@launch
                }

                if (!lookup.serverReady) {
                    isLoading = false
                    showRetry = true
                    errorMessage = if (isArabic) {
                        "خادم المنشأة غير جاهز للتفعيل.\n\nقد يكون:\n• تحت الصيانة\n• لم يُثبَّت بعد\n\nتواصل مع ميرا تكنولوجي."
                    } else {
                        "Customer server not ready.\n\nIt might be:\n• Under maintenance\n• Not installed yet\n\nContact MIRA Technology."
                    }
                    return@launch
                }

                val targetServer = lookup.serverUrl ?: ""
                onSaveServerUrl(targetServer)
                detectedServerUrl = targetServer
                detectedCustomerName = lookup.customerName ?: ""

                val customerDisplay = if (detectedCustomerName.isNotEmpty()) {
                    detectedCustomerName
                } else {
                    if (isArabic) "منشأتك" else "Your Facility"
                }

                statusText = if (isArabic) {
                    "تم التعرف على: $customerDisplay\nجارٍ توثيق وتفعيل الجهاز..."
                } else {
                    "Identified: $customerDisplay\nActivating device..."
                }

                onActivateLicense(
                    targetServer,
                    cleanKey,
                    { response ->
                        isLoading = false
                        var facilityName = detectedCustomerName
                        var validUntil = ""
                        try {
                            val facility = response.optJSONObject("facility")
                            if (facility != null) {
                                facilityName = facility.optString(
                                    "name_ar",
                                    facility.optString("name", facilityName)
                                )
                            }
                            val license = response.optJSONObject("license")
                            if (license != null) {
                                validUntil = license.optString("valid_until", "")
                            }
                        } catch (_: Exception) {
                        }

                        successDialogData = ActivationSuccessInfo(
                            facilityName = facilityName,
                            validUntil = validUntil,
                            serverUrl = targetServer
                        )
                    },
                    { message ->
                        isLoading = false
                        showRetry = true
                        errorMessage = message
                        fieldErrorText = if (isArabic) {
                            "فشل التفعيل — تأكد من المفتاح وحاول مرة أخرى"
                        } else {
                            "Activation failed — check key and try again"
                        }
                    }
                )
            } catch (e: Exception) {
                isLoading = false
                showRetry = true
                errorMessage = e.localizedMessage ?: if (isArabic) {
                    "حدث خطأ غير متوقع في الاتصال"
                } else {
                    "Unexpected connection error"
                }
            }
        }
    }

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
            // إضاءة محيطية ذهبية ناعمة في الأعلى
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.goldPrimary.copy(alpha = colors.glowAlpha),
                            CyanAccent.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = Offset(center.x, 150.dp.toPx()),
                        radius = size.minDimension * 0.7f
                    ),
                    radius = size.minDimension * 0.7f,
                    center = Offset(center.x, 150.dp.toPx())
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // 1. شعار المنظومة
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(colors.cardSurface.copy(alpha = 0.55f))
                        .border(1.dp, colors.cardBorder, CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_mira_id_logo),
                        contentDescription = "MIRA ID Logo",
                        modifier = Modifier.size(96.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "MIRA ID",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    color = colors.goldPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isArabic) "تفعيل الترخيص المؤسسي" else "Enterprise License Activation",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isArabic) {
                        "أدخل مفتاح الترخيص لربط الجهاز بخادم المنشأة السحابي وتوثيق الهوية"
                    } else {
                        "Enter your license key to link this device to your cloud facility server"
                    },
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 2. بطاقة التفعيل الرئيسية
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // شريط حالة اكتشاف الخادم التلقائي
                        ServerDiscoveryBanner(
                            isArabic = isArabic,
                            serverUrl = detectedServerUrl,
                            customerName = detectedCustomerName
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // عنوان حقل المفتاح + زر اللصق
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isArabic) "مفتاح الترخيص" else "License Key",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )

                            // زر لصق سريع من الحافظة
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isLoading) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val pasted = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!pasted.isNullOrBlank()) {
                                            updateKeyInput(pasted)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = "Paste",
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isArabic) "لصق المفتاح" else "Paste Key",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.goldPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // حقل إدخال مفتاح الترخيص (LTR دائماً لصيغة MIRA-XXXX-XXXX-XXXX)
                        OutlinedTextField(
                            value = keyFieldValue,
                            onValueChange = { if (!isLoading) updateKeyInput(it.text) },
                            enabled = !isLoading,
                            singleLine = true,
                            isError = fieldErrorText != null,
                            textStyle = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.4.sp,
                                textAlign = TextAlign.Center,
                                textDirection = TextDirection.Ltr,
                                color = colors.textPrimary
                            ),
                            placeholder = {
                                Text(
                                    text = "MIRA-XXXX-XXXX-XXXX",
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.4.sp,
                                        textAlign = TextAlign.Center,
                                        textDirection = TextDirection.Ltr,
                                        color = colors.textSecondary.copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = if (isPatternValid) EmeraldSuccess else colors.goldPrimary
                                )
                            },
                            trailingIcon = {
                                when {
                                    isPatternValid -> {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Valid Format",
                                            tint = EmeraldSuccess
                                        )
                                    }
                                    keyFieldValue.text.isNotEmpty() && !isLoading -> {
                                        IconButton(onClick = { updateKeyInput("") }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear",
                                                tint = colors.textSecondary
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                autoCorrect = false,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (cleanKey.isNotEmpty()) startActivationProcess() }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isPatternValid) EmeraldSuccess else colors.goldPrimary,
                                unfocusedBorderColor = colors.cardBorder,
                                errorBorderColor = ErrorRed,
                                focusedContainerColor = colors.backgroundTop.copy(alpha = 0.45f),
                                unfocusedContainerColor = colors.backgroundTop.copy(alpha = 0.30f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // رسالة خطأ صيغة الحقل
                        AnimatedVisibility(visible = fieldErrorText != null) {
                            Text(
                                text = fieldErrorText ?: "",
                                color = ErrorRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 3. حالة التحميل (Loading Container)
                        AnimatedVisibility(
                            visible = isLoading,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.08f))
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = colors.goldPrimary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = statusText,
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 19.sp
                                )
                            }
                        }

                        // 4. صندوق عرض الأخطاء + زر إعادة المحاولة
                        AnimatedVisibility(
                            visible = errorMessage != null && !isLoading,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ErrorRed.copy(alpha = 0.10f))
                                    .border(1.dp, ErrorRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = ErrorRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = errorMessage ?: "",
                                        color = if (colors.isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        if (!isLoading && (errorMessage != null)) {
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // 5. زر التفعيل أو زر إعادة المحاولة
                        if (!isLoading) {
                            val buttonEnabled = cleanKey.isNotEmpty()
                            val buttonBrush = if (buttonEnabled) {
                                Brush.horizontalGradient(
                                    listOf(colors.goldDeep, colors.goldPrimary, colors.goldHighlight)
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        colors.trackBackground,
                                        colors.trackBackground
                                    )
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(buttonBrush)
                                    .clickable(enabled = buttonEnabled) {
                                        startActivationProcess()
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (showRetry) Icons.Rounded.Refresh else Icons.Rounded.Shield,
                                        contentDescription = null,
                                        tint = if (buttonEnabled) Color(0xFF060B19) else colors.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            showRetry && isArabic -> "إعادة المحاولة"
                                            showRetry -> "Retry Activation"
                                            isArabic -> "تفعيل المنظومة الآن"
                                            else -> "Activate System Now"
                                        },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (buttonEnabled) Color(0xFF060B19) else colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(24.dp))

                // 6. تذييل الأمان والتشفير
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) {
                            "محمي بتشفير Ed25519 الرقمي • ميرا تكنولوجي"
                        } else {
                            "Ed25519 Cryptographic Protection • MIRA Technology"
                        },
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }

    // 7. نافذة نجاح التفعيل الفاخرة
    successDialogData?.let { info ->
        ActivationSuccessDialog(
            isArabic = isArabic,
            info = info,
            onContinue = onContinueToLogin
        )
    }
}

@Composable
private fun ServerDiscoveryBanner(
    isArabic: Boolean,
    serverUrl: String,
    customerName: String
) {
    val colors = MiraTheme.colors
    val hasServer = serverUrl.isNotEmpty()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (hasServer) EmeraldSuccess.copy(alpha = 0.10f)
                else colors.backgroundTop.copy(alpha = 0.45f)
            )
            .border(
                width = 1.dp,
                color = if (hasServer) EmeraldSuccess.copy(alpha = 0.35f) else colors.cardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Dns,
            contentDescription = null,
            tint = if (hasServer) EmeraldSuccess else colors.goldPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    customerName.isNotEmpty() -> customerName
                    hasServer && isArabic -> "خادم المنشأة متصل"
                    hasServer -> "Facility Server Linked"
                    isArabic -> "اكتشاف تلقائي لخادم المنشأة"
                    else -> "Auto-Discover Facility Server"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (hasServer) {
                    serverUrl
                } else if (isArabic) {
                    "سيتم تحديد رابط الخادم تلقائياً فور إدخال المفتاح"
                } else {
                    "Server endpoint will be resolved automatically"
                },
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActivationSuccessDialog(
    isArabic: Boolean,
    info: ActivationSuccessInfo,
    onContinue: () -> Unit
) {
    val colors = MiraTheme.colors

    Dialog(
        onDismissRequest = { /* غير قابل للإغلاق إلا بالمتابعة */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.5.dp, colors.goldPrimary),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(EmeraldSuccess.copy(alpha = 0.15f))
                        .border(1.5.dp, EmeraldSuccess, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isArabic) "تم تفعيل المنظومة بنجاح" else "System Activated Successfully",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // بطاقة تفاصيل الترخيص
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.backgroundTop.copy(alpha = 0.50f))
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    if (info.facilityName.isNotEmpty()) {
                        DetailRow(
                            label = if (isArabic) "المنشأة:" else "Facility:",
                            value = info.facilityName
                        )
                    }
                    if (info.validUntil.isNotEmpty()) {
                        DetailRow(
                            label = if (isArabic) "صالح حتى:" else "Valid Until:",
                            value = info.validUntil
                        )
                    }
                    if (info.serverUrl.isNotEmpty()) {
                        DetailRow(
                            label = if (isArabic) "الخادم:" else "Server:",
                            value = info.serverUrl
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(colors.goldDeep, colors.goldPrimary, colors.goldHighlight)
                            )
                        )
                        .clickable { onContinue() }
                ) {
                    Text(
                        text = if (isArabic) "متابعة لتسجيل الدخول" else "Continue to Login",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF060B19)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = MiraTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = colors.goldPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
