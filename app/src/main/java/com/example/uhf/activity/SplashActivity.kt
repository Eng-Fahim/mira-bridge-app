package com.example.uhf.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.R
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val SPLASH_DURATION_MS = 2800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // يضبط لون أيقونات شريط الحالة العلوي تلقائياً حسب الوضع الفاتح أو الداكن
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MiraJewelryTheme {
                MiraLuxurySplashScreen(
                    durationMs = SPLASH_DURATION_MS,
                    onTimeout = { navigateToNextScreen() }
                )
            }
        }
    }

    private fun navigateToNextScreen() {
        if (isFinishing || isDestroyed) return

        val target: Class<*> = if (isLicenseActivated()) {
            Log.d(TAG, "✅ License active — → LoginActivity")
            LoginActivity::class.java
        } else {
            Log.d(TAG, "⚠️ License NOT active — → ActivationActivity")
            ActivationActivity::class.java
        }

        val intent = Intent(this, target)
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun isLicenseActivated(): Boolean {
        return try {
            val sm = SessionManager(applicationContext)

            var token: String? = null
            try {
                val method = sm.javaClass.getMethod("getActivationToken")
                val result = method.invoke(sm)
                if (result is String) token = result
            } catch (e: Exception) {
                Log.w(TAG, "getActivationToken not available: ${e.message}")
            }

            if (token.isNullOrEmpty()) {
                Log.d(TAG, "❌ No activation token → NOT activated")
                return false
            }

            val serverUrl = sm.serverUrl
            if (serverUrl.isNullOrEmpty()) {
                Log.d(TAG, "❌ No serverUrl → NOT activated")
                return false
            }
            if (serverUrl.contains("id.ibreg.org") || serverUrl.contains("wp-json")) {
                Log.d(TAG, "❌ serverUrl is legacy WordPress → NOT activated")
                return false
            }
            if (serverUrl.endsWith("/api/v1") || serverUrl.endsWith("/api/v1/")) {
                Log.d(TAG, "❌ serverUrl is center URL (/api/v1), not customer URL → NOT activated")
                return false
            }

            Log.d(TAG, "✅ Activated: token=${token.length} chars, server=$serverUrl")
            true
        } catch (e: Exception) {
            Log.e(TAG, "License check failed: ${e.message}", e)
            false
        }
    }
}

private val EmeraldStatus = Color(0xFF10B981)
private val CyanAccent    = Color(0xFF38BDF8)

@Composable
private fun MiraLuxurySplashScreen(
    durationMs: Long,
    onTimeout: () -> Unit
) {
    val colors = MiraTheme.colors

    LaunchedEffect(Unit) {
        delay(durationMs)
        onTimeout()
    }

    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { startAnimation = true }

    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.72f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )

    val progressFraction by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = (durationMs - 200).toInt(), easing = LinearEasing),
        label = "progressFraction"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "luxuryLoops")

    val rfidWaveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rfidWave"
    )

    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )

    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -320f,
        targetValue = 680f,
        animationSpec = infiniteRepeatable(
            animation = tween(1850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "goldShimmer"
    )

    val goldMetallicBrush = Brush.linearGradient(
        colors = listOf(
            colors.goldDeep,
            colors.goldPrimary,
            colors.goldHighlight,
            colors.goldPrimary,
            colors.goldDeep
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 280f, 130f)
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
                        colors = listOf(
                            colors.backgroundTop,
                            colors.backgroundMid,
                            colors.backgroundTop
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 1. الإضاءة المحيطية المتكيفة مع الثيم
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.goldPrimary.copy(alpha = colors.glowAlpha),
                            CyanAccent.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(center.x, center.y - 60.dp.toPx()),
                        radius = size.minDimension * 0.65f
                    ),
                    radius = size.minDimension * 0.65f,
                    center = Offset(center.x, center.y - 60.dp.toPx())
                )
            }

            // 2. المحتوى الرئيسي
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .alpha(contentAlpha)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(260.dp)
                        .scale(logoScale)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val maxRadius = size.minDimension / 2f

                        val waveRadius = maxRadius * (0.72f + 0.28f * rfidWaveProgress)
                        val waveAlpha = (1f - rfidWaveProgress) * 0.50f
                        drawCircle(
                            color = colors.goldPrimary.copy(alpha = waveAlpha),
                            radius = waveRadius,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        rotate(ringRotation) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        Color.Transparent,
                                        colors.goldPrimary.copy(alpha = 0.85f),
                                        colors.goldHighlight,
                                        CyanAccent.copy(alpha = 0.7f),
                                        Color.Transparent
                                    )
                                ),
                                startAngle = 0f,
                                sweepAngle = 260f,
                                useCenter = false,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                                topLeft = Offset(size.width * 0.06f, size.height * 0.06f),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width * 0.88f,
                                    size.height * 0.88f
                                )
                            )
                        }
                    }

                    Image(
                        painter = painterResource(id = R.drawable.ic_mira_id_logo),
                        contentDescription = "MIRA ID Logo",
                        modifier = Modifier.size(215.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "MIRA ID",
                    style = TextStyle(
                        brush = goldMetallicBrush,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 6.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "SMART JEWELRY RFID TAG",
                    color = colors.subtitleGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(1.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, colors.goldPrimary, Color.Transparent)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "المنظومة الذكية لإدارة وتتبع الذهب والمجوهرات",
                    color = colors.textPrimary.copy(alpha = 0.90f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.trackBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        colors.goldDeep,
                                        colors.goldPrimary,
                                        colors.goldHighlight,
                                        CyanAccent
                                    )
                                )
                            )
                    )
                }
            }

            // 3. شريط التذييل السفلي
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp)
                    .alpha(contentAlpha)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(EmeraldStatus)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MIRA TECHNOLOGY • ENTERPRISE EDITION",
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp
                )
            }
        }
    }
}
