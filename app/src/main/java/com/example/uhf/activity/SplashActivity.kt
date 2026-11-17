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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.tools.SessionManager
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val SPLASH_DURATION_MS = 2800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MiraLuxurySplashScreen(
                durationMs = SPLASH_DURATION_MS,
                onTimeout = { navigateToNextScreen() }
            )
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

    // ═══════════════════════════════════════════════════════════════════════
    //  ⚡ نفس منطق الفحص الصارم تماماً دون أي تغيير لضمان استقرار النظام
    // ═══════════════════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════════════════════
//  🎨 لوحة الألوان الملكية لهوية MIRA ID
// ═══════════════════════════════════════════════════════════════════════════
private val DeepNavyTop    = Color(0xFF050A18)
private val DeepNavyBottom = Color(0xFF0D1936)
private val GoldLight      = Color(0xFFFFE59E)
private val GoldPrimary    = Color(0xFFD4AF37)
private val GoldDark       = Color(0xFF996515)
private val EmeraldStatus  = Color(0xFF10B981)
private val CyanAccent     = Color(0xFF38BDF8)

@Composable
private fun MiraLuxurySplashScreen(
    durationMs: Long,
    onTimeout: () -> Unit
) {
    // تشغيل المؤقت والانتقال التلقائي
    LaunchedEffect(Unit) {
        delay(durationMs)
        onTimeout()
    }

    // حركات الدخول السينمائية
    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { startAnimation = true }

    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.65f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )

    val progressFraction by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = (durationMs - 250).toInt(), easing = LinearEasing),
        label = "progressFraction"
    )

    // حركات مستمرة: موجات RFID + دوران الحلقة + لمعة الذهب
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
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )

    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "goldShimmer"
    )

    val goldMetallicBrush = Brush.linearGradient(
        colors = listOf(GoldDark, GoldPrimary, GoldLight, GoldPrimary, GoldDark),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 260f, 120f)
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DeepNavyTop
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(DeepNavyTop, DeepNavyBottom, DeepNavyTop)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 1. الهالة الضوئية الذهبية في الخلفية
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GoldPrimary.copy(alpha = 0.16f),
                            CyanAccent.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.55f
                    ),
                    radius = size.minDimension * 0.55f,
                    center = center
                )
            }

            // 2. المحتوى المركزي (الشعار + الاسم + الوصف)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .alpha(contentAlpha)
            ) {
                // أيقونة الشعار مع موجات RFID والحلقة الدوارة
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(180.dp)
                        .scale(logoScale)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val maxRadius = size.minDimension / 2f

                        // موجة RFID خارجية متلاشية
                        val waveRadius = maxRadius * (0.55f + 0.45f * rfidWaveProgress)
                        val waveAlpha = (1f - rfidWaveProgress) * 0.45f
                        drawCircle(
                            color = GoldPrimary.copy(alpha = waveAlpha),
                            radius = waveRadius,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // حلقة هندسية دوارة متقطعة
                        rotate(ringRotation) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        Color.Transparent,
                                        GoldPrimary.copy(alpha = 0.75f),
                                        GoldLight,
                                        Color.Transparent
                                    )
                                ),
                                startAngle = 0f,
                                sweepAngle = 240f,
                                useCenter = false,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                                topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width * 0.72f,
                                    size.height * 0.72f
                                )
                            )
                        }
                    }

                    // الإطار الدائري الداخلي للشعار
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF14244B), Color(0xFF081024))
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    listOf(GoldLight, GoldPrimary, GoldDark)
                                ),
                                shape = CircleShape
                            )
                    ) {
                        // رسم ماسة ذهبية هندسية (ويمكنك استبدالها بـ Image(painterResource(R.drawable.your_logo)) لاحقاً)
                        GoldenDiamondEmblem(modifier = Modifier.size(52.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // اسم المنظومة بتأثير اللمعة الذهبية
                Text(
                    text = "MIRA ID",
                    style = TextStyle(
                        brush = goldMetallicBrush,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 5.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "SMART JEWELRY & UHF RFID",
                    color = GoldLight.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // خط فاصل ذهبي متدرج
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(1.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, GoldPrimary, Color.Transparent)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(18.dp))

                // العبارة التعريفية بالعربية
                Text(
                    text = "المنظومة الذكية لإدارة وتتبع الذهب والمجوهرات",
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(36.dp))

                // شريط التقدم الذكي
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(GoldDark, GoldPrimary, GoldLight)
                                )
                            )
                    )
                }
            }

            // 3. تذييل الإصدار وحالة الأمان في الأسفل
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
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
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}

@Composable
private fun GoldenDiamondEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val diamondOutline = Path().apply {
            moveTo(w * 0.25f, h * 0.15f)
            lineTo(w * 0.75f, h * 0.15f)
            lineTo(w * 0.95f, h * 0.40f)
            lineTo(w * 0.50f, h * 0.90f)
            lineTo(w * 0.05f, h * 0.40f)
            close()
        }

        drawPath(
            path = diamondOutline,
            brush = Brush.verticalGradient(
                colors = listOf(GoldLight, GoldPrimary, GoldDark)
            ),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )

        // خطوط الأوجه الداخلية للماسة
        val goldStroke = GoldPrimary.copy(alpha = 0.85f)
        val strokeWidth = 1.5.dp.toPx()

        drawLine(goldStroke, Offset(w * 0.05f, h * 0.40f), Offset(w * 0.95f, h * 0.40f), strokeWidth)
        drawLine(goldStroke, Offset(w * 0.25f, h * 0.15f), Offset(w * 0.35f, h * 0.40f), strokeWidth)
        drawLine(goldStroke, Offset(w * 0.75f, h * 0.15f), Offset(w * 0.65f, h * 0.40f), strokeWidth)
        drawLine(goldStroke, Offset(w * 0.35f, h * 0.40f), Offset(w * 0.50f, h * 0.90f), strokeWidth)
        drawLine(goldStroke, Offset(w * 0.65f, h * 0.40f), Offset(w * 0.50f, h * 0.90f), strokeWidth)
    }
}
