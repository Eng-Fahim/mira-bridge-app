package com.example.uhf.security

import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.uhf.tools.SessionManager
import kotlinx.coroutines.delay

class SessionLockDialogFragment : DialogFragment() {

    private var sessionManager: SessionManager? = null
    private var callback: SessionLockManager.LockCallback? = null
    private var isArabic: Boolean = true
    private var unlockedSuccessfully = false

    fun attachDependencies(
        sessionManager: SessionManager,
        callback: SessionLockManager.LockCallback
    ) {
        this.sessionManager = sessionManager
        this.callback = callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isArabic = arguments?.getBoolean(ARG_IS_ARABIC, true) ?: true
        isCancelable = false
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = ComponentDialog(requireContext(), theme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        // منع الرجوع بزر النظام أو السحب الجانبي
        dialog.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // تجاهل زر الرجوع تماماً أثناء قفل الجلسة
            }
        })

        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // منع لقطات الشاشة وكشف المحتوى في قائمة التطبيقات المفتوحة
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.parseColor("#050914")
            navigationBarColor = Color.parseColor("#050914")
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val layoutDir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    MiraSessionLockScreen(
                        isArabic = isArabic,
                        onUnlockClick = { triggerBiometricPrompt() },
                        onLogoutClick = {
                            dismissAllowingStateLoss()
                            callback?.onLogoutRequested()
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // إطلاق البصمة تلقائياً بعد 350ms لتجربة سريعة للكاشير
        view.postDelayed({
            if (isAdded && !unlockedSuccessfully) {
                triggerBiometricPrompt()
            }
        }, 350L)
    }

    private fun triggerBiometricPrompt() {
        val ctx = context ?: return
        val bm = BiometricManager.from(ctx)

        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                ctx,
                if (isArabic) "البصمة غير مفعّلة على هذا الجهاز — يرجى تسجيل الدخول"
                else "Biometrics unavailable — please log in again",
                Toast.LENGTH_LONG
            ).show()
            dismissAllowingStateLoss()
            callback?.onLogoutRequested()
            return
        }

        val executor = ContextCompat.getMainExecutor(ctx)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.d(TAG, "✅ Biometric authentication succeeded")
                    unlockedSuccessfully = true
                    sessionManager?.unlockSession()
                    Toast.makeText(
                        ctx,
                        if (isArabic) "تم فتح الجلسة بنجاح" else "Session unlocked",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismissAllowingStateLoss()
                    callback?.onSessionUnlocked()
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        ctx,
                        if (isArabic) "لم يتم التعرف على البصمة، حاول مجدداً"
                        else "Fingerprint not recognized, try again",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // نبقى في شاشة القفل الآمنة دون إغلاقها!
                    Log.w(TAG, "Biometric prompt closed/error ($errorCode): $errString")
                }
            }
        )

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (isArabic) "التحقق من الهوية | MIRA ID" else "Identity Verification | MIRA ID")
            .setSubtitle(
                if (isArabic) "استخدم البصمة لفك قفل نقطة البيع والخزينة"
                else "Verify your identity to unlock POS & Vault"
            )
            .setAllowedAuthenticators(authenticators)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            promptBuilder.setNegativeButtonText(if (isArabic) "إلغاء" else "Cancel")
        }

        prompt.authenticate(promptBuilder.build())
    }

    companion object {
        private const val TAG = "SessionLockDialog"
        private const val ARG_IS_ARABIC = "arg_is_arabic"

        fun newInstance(isArabic: Boolean): SessionLockDialogFragment {
            return SessionLockDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_ARABIC, isArabic)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  واجهة Jetpack Compose — هوية MIRA ID الكحلية والذهبية
// ═══════════════════════════════════════════════════════════════════════════════

private val NavyDeepest = Color(0xFF050914)
private val NavyDark    = Color(0xFF0B132B)
private val NavyCard    = Color(0xE6111C3A)
private val GoldPrimary = Color(0xFFD4AF37)
private val GoldBright  = Color(0xFFF3D066)
private val TextMuted   = Color(0xFF94A3B8)
private val DangerSoft  = Color(0xFFF87171)

@Composable
private fun MiraSessionLockScreen(
    isArabic: Boolean,
    onUnlockClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    // مؤقت حي لحساب مدة القفل بالثواني
    var lockedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            lockedSeconds++
        }
    }

    val formattedDuration = remember(lockedSeconds) {
        val mins = lockedSeconds / 60
        val secs = lockedSeconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    // حركة نبض الرادار حول الدرع الذهبي
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NavyDeepest, NavyDark, NavyDeepest)
                )
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        // البطاقة الزجاجية الرئيسية
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            GoldBright.copy(alpha = 0.65f),
                            GoldPrimary.copy(alpha = 0.15f),
                            GoldBright.copy(alpha = 0.50f)
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                ),
            shape = RoundedCornerShape(28.dp),
            color = NavyCard,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. شعار الدرع الذهبي مع هالة الرادار المتحركة
                Box(
                    modifier = Modifier.size(104.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .border(1.5.dp, GoldPrimary.copy(alpha = pulseAlpha), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        GoldPrimary.copy(alpha = 0.22f),
                                        NavyDark
                                    )
                                )
                            )
                            .border(1.dp, GoldPrimary.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        GoldShieldLockCanvas(modifier = Modifier.size(38.dp))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. شارة الحالة الأمنية
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GoldPrimary.copy(alpha = 0.12f))
                        .border(0.8.dp, GoldPrimary.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isArabic) "حماية الخزينة ونقطة البيع" else "VAULT & POS SECURITY",
                        color = GoldBright,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. العنوان الرئيسي
                Text(
                    text = if (isArabic) "تم قفل الجلسة مؤقتاً" else "Session Temporarily Locked",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 4. الرسالة التوضيحية
                Text(
                    text = if (isArabic)
                        "لحماية بيانات المبيعات والمخزون، تم تفعيل القفل التلقائي بعد فترة من عدم النشاط. يرجى تأكيد هويتك للمتابعة."
                    else
                        "To protect sales and vault records, your session was automatically locked due to inactivity. Verify your identity to resume.",
                    color = TextMuted,
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 5. شريط مؤقت القفل الحي
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(NavyDeepest.copy(alpha = 0.65f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isArabic) "مدة القفل الحالية" else "Locked Duration",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = formattedDuration,
                        color = GoldBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 6. زر الفتح بالبصمة الذهبي
                Button(
                    onClick = onUnlockClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldPrimary,
                        contentColor = NavyDeepest
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    FingerprintCanvasIcon(
                        modifier = Modifier.size(22.dp),
                        tint = NavyDeepest
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "فتح الجلسة بالبصمة" else "Unlock with Biometrics",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 7. زر تسجيل الخروج
                TextButton(
                    onClick = onLogoutClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, DangerSoft.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = if (isArabic) "تسجيل الخروج من الحساب" else "Sign Out",
                        color = DangerSoft,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * أيقونة الدرع والقفل الذهبية مرسومة بـ Canvas بدقة متناهية بدون تشويه
 */
@Composable
private fun GoldShieldLockCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val shieldPath = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.88f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.50f)
            cubicTo(
                w * 0.88f, h * 0.74f,
                w * 0.70f, h * 0.89f,
                w * 0.50f, h * 0.95f
            )
            cubicTo(
                w * 0.30f, h * 0.89f,
                w * 0.12f, h * 0.74f,
                w * 0.12f, h * 0.50f
            )
            lineTo(w * 0.12f, h * 0.22f)
            close()
        }

        drawPath(
            path = shieldPath,
            brush = Brush.linearGradient(listOf(GoldBright, GoldPrimary)),
            style = Stroke(width = w * 0.075f, join = StrokeJoin.Round)
        )

        // جسم القفل الداخلي
        val bodyWidth = w * 0.34f
        val bodyHeight = h * 0.26f
        val bodyLeft = (w - bodyWidth) / 2f
        val bodyTop = h * 0.48f

        drawRoundRect(
            brush = Brush.linearGradient(listOf(GoldBright, GoldPrimary)),
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
        )

        // قوس القفل العلوي
        val shackleRadius = w * 0.11f
        drawArc(
            color = GoldBright,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.5f - shackleRadius, bodyTop - shackleRadius * 1.15f),
            size = Size(shackleRadius * 2f, shackleRadius * 2.2f),
            style = Stroke(width = w * 0.065f, cap = StrokeCap.Round)
        )
    }
}

/**
 * أيقونة بصمة الإصبع للزر الرئيسي
 */
@Composable
private fun FingerprintCanvasIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)

        drawArc(
            color = tint,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.12f),
            size = Size(w * 0.72f, h * 0.72f),
            style = stroke
        )
        drawArc(
            color = tint,
            startAngle = 190f,
            sweepAngle = 165f,
            useCenter = false,
            topLeft = Offset(w * 0.27f, h * 0.26f),
            size = Size(w * 0.46f, h * 0.56f),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.42f),
            end = Offset(w * 0.50f, h * 0.82f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round
        )
    }
}
