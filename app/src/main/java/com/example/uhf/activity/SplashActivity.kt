package com.example.uhf.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.license.LicenseManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraServerConfig
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale
import java.util.concurrent.Executors

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var isArabicState by mutableStateOf(true)
    private var statusMessageState by mutableStateOf("")
    private var orgDisplayNameState by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        isArabicState = Locale.getDefault().language == "ar"
        statusMessageState = if (isArabicState) {
            "جاري التحقق من الترخيص والاتصال الآمن..."
        } else {
            "Verifying license & secure connection..."
        }

        val orgProfile = MiraOrgProfileManager.getInstance(this)
        val savedOrgName = if (isArabicState) orgProfile.orgNameAr else orgProfile.orgNameEn
        if (!savedOrgName.isNullOrBlank()) {
            orgDisplayNameState = savedOrgName
        }

        setContent {
            val direction = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MiraJewelryTheme {
                    SplashScreenContent(
                        isArabic = isArabicState,
                        orgName = orgDisplayNameState,
                        statusMessage = statusMessageState
                    )
                }
            }
        }

        verifyLicenseAndRoute()
    }

    private fun verifyLicenseAndRoute() {
        executor.execute {
            val startTime = System.currentTimeMillis()
            val isActivated = isLicenseActivated()

            val elapsed = System.currentTimeMillis() - startTime
            val minSplashDuration = 1400L
            val remainingDelay = (minSplashDuration - elapsed).coerceAtLeast(0L)

            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed

                statusMessageState = if (isActivated) {
                    if (isArabicState) "تم التحقق من الترخيص بنجاح" else "License verified successfully"
                } else {
                    if (isArabicState) "مطلوب تفعيل ترخيص المنشأة" else "Enterprise license activation required"
                }

                val targetClass = if (isActivated) {
                    LoginActivity::class.java
                } else {
                    ActivationActivity::class.java
                }

                val intent = Intent(this, targetClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }, remainingDelay)
        }
    }

    private fun isLicenseActivated(): Boolean {
        return try {
            val licenseManager = LicenseManager.getInstance(this)
            val sessionManager = SessionManager(this)
            val serverConfig = MiraServerConfig.getInstance(this)

            val serverUrl = serverConfig.serverUrl.ifBlank { sessionManager.serverUrl ?: "" }.trim()
            val hasValidServer = serverUrl.startsWith("https://", ignoreCase = true) &&
                !serverUrl.contains("ibreg.org", ignoreCase = true)

            licenseManager.isActivated && hasValidServer
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}

@Composable
private fun SplashScreenContent(
    isArabic: Boolean,
    orgName: String,
    statusMessage: String
) {
    val colors = MiraTheme.colors

    val entranceAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "emblemPulse")
    val glowScale by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

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
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(entranceAnim.value)
                    .scale(0.92f + (0.08f * entranceAnim.value))
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.goldPrimary.copy(alpha = 0.14f),
                    border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .size(104.dp)
                        .scale(glowScale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Diamond,
                            contentDescription = "MIRA ID",
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(50.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "MIRA ID™",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.goldPrimary,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (orgName.isNotBlank()) {
                        orgName
                    } else if (isArabic) {
                        "منظومة إدارة الذهب والمجوهرات الذكية"
                    } else {
                        "Smart Jewelry & RFID Enterprise Suite"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(34.dp))

                LinearProgressIndicator(
                    color = colors.goldPrimary,
                    trackColor = colors.trackBackground,
                    modifier = Modifier
                        .width(190.dp)
                        .height(4.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = colors.goldPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isArabic) {
                        "ميرا تكنولوجي للحلول الذكية • حماية مشفّرة"
                    } else {
                        "MIRA Technology • Hardware-Encrypted Security"
                    },
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}
