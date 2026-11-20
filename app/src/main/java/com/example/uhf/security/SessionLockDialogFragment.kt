package com.example.uhf.security

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import kotlinx.coroutines.delay
import java.util.Locale

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
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
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
                MiraJewelryTheme {
                    val layoutDir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                        MiraSessionLockScreen(
                            isArabic = isArabic,
                            dialogWindow = dialog?.window,
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
//  واجهة Jetpack Compose — مرتبطة ديناميكياً بـ MiraTheme و MaterialTheme.typography
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MiraSessionLockScreen(
    isArabic: Boolean,
    dialogWindow: Window?,
    onUnlockClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val colors = MiraTheme.colors
    val typography = MaterialTheme.typography
    val dangerColor = Color(0xFFEF4444)
    val buttonContentColor = Color(0xFF0A0F1D)

    // مزامنة ألوان شريط الحالة وشريط التنقل مع الثيم النشط
    SideEffect {
        dialogWindow?.apply {
            statusBarColor = colors.backgroundTop.toArgb()
            navigationBarColor = colors.backgroundTop.toArgb()
        }
    }

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
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    // حركة نبض الرادار حول الدرع
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
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // البطاقة الرئيسية المتوافقة مع ألوان الثيم
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .border(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                colors.goldPrimary.copy(alpha = 0.65f),
                                colors.goldPrimary.copy(alpha = 0.16f),
                                colors.goldPrimary.copy(alpha = 0.50f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = colors.cardSurface,
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. شعار الدرع مع هالة الرادار المتحركة
                    Box(
                        modifier = Modifier.size(104.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(86.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .border(
                                    1.5.dp,
                                    colors.goldPrimary.copy(alpha = pulseAlpha),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            colors.goldPrimary.copy(alpha = 0.22f),
                                            colors.backgroundMid
                                        )
                                    )
                                )
                                .border(
                                    1.dp,
                                    colors.goldPrimary.copy(alpha = 0.45f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemeShieldLockCanvas(
                                modifier = Modifier.size(38.dp),
                                accentColor = colors.goldPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 2. شارة الحالة الأمنية
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.goldPrimary.copy(alpha = 0.12f))
                            .border(
                                0.8.dp,
                                colors.goldPrimary.copy(alpha = 0.35f),
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isArabic) "حماية الخزينة ونقطة البيع" else "VAULT & POS SECURITY",
                            style = typography.labelSmall,
                            color = colors.goldPrimary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. العنوان الرئيسي
                    Text(
                        text = if (isArabic) "تم قفل الجلسة مؤقتاً" else "Session Temporarily Locked",
                        style = typography.titleLarge,
                        color = colors.textPrimary,
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
                        style = typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 5. شريط مؤقت القفل الحي
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.backgroundTop.copy(alpha = 0.65f))
                            .border(
                                0.8.dp,
                                colors.textSecondary.copy(alpha = 0.15f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "مدة القفل الحالية" else "Locked Duration",
                            style = typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Text(
                            text = formattedDuration,
                            style = typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.goldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 6. زر الفتح بالبصمة
                    Button(
                        onClick = onUnlockClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = buttonContentColor
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        FingerprintCanvasIcon(
                            modifier = Modifier.size(22.dp),
                            tint = buttonContentColor
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isArabic) "فتح الجلسة بالبصمة" else "Unlock with Biometrics",
                            style = typography.titleMedium,
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
                        border = BorderStroke(0.8.dp, dangerColor.copy(alpha = 0.30f))
                    ) {
                        Text(
                            text = if (isArabic) "تسجيل الخروج من الحساب" else "Sign Out",
                            style = typography.labelLarge,
                            color = dangerColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * أيقونة الدرع والقفل مرسومة بـ Canvas بلون الثيم النشط
 */
@Composable
private fun ThemeShieldLockCanvas(
    modifier: Modifier = Modifier,
    accentColor: Color
) {
    val highlightColor = accentColor.copy(alpha = 0.85f)

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
            brush = Brush.linearGradient(listOf(highlightColor, accentColor)),
            style = Stroke(width = w * 0.075f, join = StrokeJoin.Round)
        )

        val bodyWidth = w * 0.34f
        val bodyHeight = h * 0.26f
        val bodyLeft = (w - bodyWidth) / 2f
        val bodyTop = h * 0.48f

        drawRoundRect(
            brush = Brush.linearGradient(listOf(highlightColor, accentColor)),
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
        )

        val shackleRadius = w * 0.11f
        drawArc(
            color = accentColor,
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
