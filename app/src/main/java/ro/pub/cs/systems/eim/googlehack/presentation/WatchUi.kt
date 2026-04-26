package ro.pub.cs.systems.eim.googlehack.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import ro.pub.cs.systems.eim.googlehack.presentation.theme.WatchColors

fun Modifier.safeClick(onClick: () -> Unit): Modifier {
    return this.pointerInput(Unit) {
        detectTapGestures(onTap = { onClick() })
    }
}

@Composable
fun WatchApp(
    status: String,
    wsStatus: String,
    heartRate: Double?,

    isFocusActive: Boolean,
    focusSeconds: Int,
    focusReport: JsonObject?,
    exitsCount: Int?,

    showMedicationDialog: Boolean,

    isBreathingActive: Boolean,
    breathingPhase: String,
    breathingStep: Int,
    breathingTotal: Int,

    isCheckInActive: Boolean,
    checkInQuestion: String?,
    checkInPositive: String,
    checkInNegative: String,
    isListening: Boolean,
    lastTranscript: String?,
    onCheckInPositive: () -> Unit,
    onCheckInNegative: () -> Unit,
    onStartVoiceInput: () -> Unit,

    alertTitle: String?,
    alertBody: String,
    selectedProfile: String?,
    onAlertDismiss: () -> Unit,
    onProfileSelect: (String) -> Unit,

    onStartFocus: () -> Unit,
    onStopFocus: () -> Unit,
    onMedicationTaken: () -> Unit,
    onMedicationSkipped: () -> Unit
) {
    var screen by remember { mutableStateOf("home") }

    LaunchedEffect(selectedProfile) {
        screen = "home"
    }

    Scaffold(
        timeText = {
            if (selectedProfile != null) {
                TimeText()
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WatchColors.Background)
        ) {
            when {
                selectedProfile == null -> {
                    ProfileSelectScreen(
                        onProfileSelect = onProfileSelect
                    )
                }

                isCheckInActive -> {
                    CheckInScreen(
                        question = checkInQuestion ?: "Spune-mi trei lucruri pe care le vezi.",
                        positive = checkInPositive,
                        negative = checkInNegative,
                        heartRate = heartRate,
                        isListening = isListening,
                        lastTranscript = lastTranscript,
                        onStartVoiceInput = onStartVoiceInput,
                        onPositive = onCheckInPositive,
                        onNegative = onCheckInNegative
                    )
                }

                isBreathingActive -> {
                    BreathingFocusScreen(
                        phase = breathingPhase,
                        step = breathingStep,
                        total = breathingTotal
                    )
                }

                isFocusActive -> {
                    FocusModeScreen(
                        focusSeconds = focusSeconds,
                        onStopFocus = onStopFocus
                    )
                }

                screen == "focus" && selectedProfile == "adhd" -> {
                    FocusIntroScreen(
                        heartRate = heartRate,
                        onStartFocus = onStartFocus,
                        onBack = { screen = "home" }
                    )
                }

                screen == "calm" && selectedProfile == "adhd" -> {
                    CalmScreen(
                        onBack = { screen = "home" }
                    )
                }

                screen == "report" && selectedProfile == "adhd" -> {
                    ReportScreen(
                        report = focusReport,
                        exits = exitsCount,
                        onBack = { screen = "home" }
                    )
                }

                selectedProfile == "epilepsy" -> {
                    EpilepsyHomeScreen(
                        status = status,
                        wsStatus = wsStatus,
                        heartRate = heartRate,
                        onChangeProfile = { onProfileSelect("") }
                    )
                }

                else -> {
                    AdhdHomeScreen(
                        status = status,
                        wsStatus = wsStatus,
                        heartRate = heartRate,
                        onFocusClick = { screen = "focus" },
                        onCalmClick = { screen = "calm" },
                        onReportClick = { screen = "report" },
                        onChangeProfile = { onProfileSelect("") }
                    )
                }
            }

            if (showMedicationDialog) {
                Alert(
                    title = {
                        Text(
                            text = "Medicație?",
                            color = WatchColors.TextPrimary
                        )
                    },
                    positiveButton = {
                        Button(onClick = onMedicationTaken) {
                            Text("✓")
                        }
                    },
                    negativeButton = {
                        Button(onClick = onMedicationSkipped) {
                            Text("✗")
                        }
                    }
                )
            }

            if (alertTitle != null) {
                AlertBanner(
                    title = alertTitle,
                    body = alertBody,
                    onDismiss = onAlertDismiss
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Voice grounding check-in
// ─────────────────────────────────────────────────────────────

@Composable
fun CheckInScreen(
    question: String,
    positive: String,
    negative: String,
    heartRate: Double?,
    isListening: Boolean,
    lastTranscript: String?,
    onStartVoiceInput: () -> Unit,
    onPositive: () -> Unit,
    onNegative: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "grounding")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val softPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "softPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120F24)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(softPulse)
                .clip(CircleShape)
                .background(Color(0xFF7C3AED).copy(alpha = 0.16f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = if (isListening) "Ascult..." else "Grounding",
                color = WatchColors.TextPrimary,
                style = MaterialTheme.typography.caption1
            )

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .scale(if (isListening) pulse else 1f)
                    .clip(CircleShape)
                    .background(Color(0xFF2D1B69)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isListening) "🎙️" else "🌙",
                        color = WatchColors.TextPrimary,
                        style = MaterialTheme.typography.title2
                    )

                    Text(
                        text = "${heartRate?.toInt() ?: "--"} BPM",
                        color = WatchColors.TextSecondary,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }

            Text(
                text = question.take(72),
                color = WatchColors.TextPrimary,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )

            if (!lastTranscript.isNullOrBlank()) {
                Text(
                    text = "Ai spus: ${lastTranscript.take(38)}",
                    color = Color(0xFFD8B4FE),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = if (isListening) "Vorbește acum..." else "Apasă și răspunde vocal",
                    color = WatchColors.TextSecondary,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            }

            BigVoiceButton(
                text = if (isListening) "Ascult..." else "Vorbește",
                onClick = onStartVoiceInput
            )

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    text = positive.take(8),
                    color = Color(0xFFD8B4FE),
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.safeClick { onPositive() }
                )

                Text(
                    text = negative.take(8),
                    color = WatchColors.TextTertiary,
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.safeClick { onNegative() }
                )
            }
        }
    }
}

@Composable
fun BigVoiceButton(
    text: String,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1.0f,
        animationSpec = tween(100),
        label = "voiceAlpha"
    )

    Box(
        modifier = Modifier
            .size(width = 128.dp, height = 42.dp)
            .clip(CircleShape)
            .background(Color(0xFF7C3AED).copy(alpha = alpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = WatchColors.TextPrimary,
            style = MaterialTheme.typography.caption1
        )
    }
}

// ─────────────────────────────────────────────────────────────
// ProfileSelectScreen
// ─────────────────────────────────────────────────────────────

@Composable
fun ProfileSelectScreen(onProfileSelect: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = "Mind Watch",
                color = WatchColors.Accent,
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Alege profilul tău",
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption1
            )

            Box(
                modifier = Modifier
                    .size(width = 154.dp, height = 52.dp)
                    .clip(CircleShape)
                    .background(WatchColors.FocusButton)
                    .safeClick { onProfileSelect("adhd") },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ADHD",
                        color = WatchColors.TextPrimary,
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Focus · Calm · Medicație",
                        color = WatchColors.Accent.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.caption2
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(width = 154.dp, height = 52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1A00))
                    .safeClick { onProfileSelect("epilepsy") },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Epilepsie",
                        color = WatchColors.TextPrimary,
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Monitorizare · Urgențe",
                        color = WatchColors.Warning.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.caption2
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// ADHD Home
// ─────────────────────────────────────────────────────────────

@Composable
fun AdhdHomeScreen(
    status: String,
    wsStatus: String,
    heartRate: Double?,
    onFocusClick: () -> Unit,
    onCalmClick: () -> Unit,
    onReportClick: () -> Unit,
    onChangeProfile: () -> Unit
) {
    val connected = wsStatus.contains("conectat") ||
            wsStatus.contains("Server") ||
            wsStatus.contains("Action trimisă")

    val hrPulse = rememberInfiniteTransition(label = "hrPulse")
    val pulseAlpha by hrPulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "hrAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        ConnectionPill(
            connected = connected,
            text = if (connected) "Live" else "Offline",
            color = WatchColors.Accent
        )

        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(132.dp)) {
                drawCircle(
                    color = WatchColors.AccentGlow,
                    radius = size.minDimension / 2f,
                    alpha = if (heartRate != null) pulseAlpha else 0f,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(WatchColors.SurfaceLow),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${heartRate?.toInt() ?: "--"}",
                        color = WatchColors.Accent,
                        style = MaterialTheme.typography.display1,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "BPM",
                        color = WatchColors.TextSecondary,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }
        }

        Text(
            text = status.take(28),
            color = WatchColors.TextTertiary,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigButton(
                text = "Focus",
                onClick = onFocusClick
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("Calm", onCalmClick)
                SmallButton("Report", onReportClick)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "schimbă profilul",
                color = WatchColors.TextTertiary,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.safeClick { onChangeProfile() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Epilepsy Home
// ─────────────────────────────────────────────────────────────

@Composable
fun EpilepsyHomeScreen(
    status: String,
    wsStatus: String,
    heartRate: Double?,
    onChangeProfile: () -> Unit
) {
    val connected = wsStatus.contains("conectat") ||
            wsStatus.contains("Server") ||
            wsStatus.contains("Action trimisă")

    val pulse = rememberInfiniteTransition(label = "epPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "epAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        ConnectionPill(
            connected = connected,
            text = if (connected) "Monitorizare" else "Offline",
            color = WatchColors.Warning
        )

        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(132.dp)) {
                drawCircle(
                    color = WatchColors.Warning.copy(alpha = 0.2f),
                    radius = size.minDimension / 2f,
                    alpha = if (heartRate != null) pulseAlpha else 0f,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(WatchColors.SurfaceLow),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${heartRate?.toInt() ?: "--"}",
                        color = WatchColors.Warning,
                        style = MaterialTheme.typography.display1,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "BPM",
                        color = WatchColors.TextSecondary,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }
        }

        Text(
            text = status.take(28),
            color = WatchColors.TextTertiary,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center
        )

        Text(
            text = "schimbă profilul",
            color = WatchColors.TextTertiary,
            style = MaterialTheme.typography.caption2,
            modifier = Modifier.safeClick { onChangeProfile() }
        )
    }
}

@Composable
fun ConnectionPill(
    connected: Boolean,
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (connected) color.copy(alpha = 0.18f) else Color(0x33EF4444))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(if (connected) color else WatchColors.Offline)
            }

            Text(
                text = text,
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Focus intro
// ─────────────────────────────────────────────────────────────

@Composable
fun FocusIntroScreen(
    heartRate: Double?,
    onStartFocus: () -> Unit,
    onBack: () -> Unit
) {
    val glow = rememberInfiniteTransition(label = "readyGlow")
    val glowAlpha by glow.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "readyAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = "Focus Mode",
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption1
            )

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(106.dp)) {
                    drawCircle(
                        color = WatchColors.AccentGlow,
                        radius = size.minDimension / 2f,
                        alpha = glowAlpha,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(WatchColors.SurfaceLow),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Ready?",
                            color = WatchColors.Accent,
                            style = MaterialTheme.typography.title3
                        )

                        Text(
                            text = "HR ${heartRate?.toInt() ?: "--"}",
                            color = WatchColors.TextSecondary,
                            style = MaterialTheme.typography.caption2
                        )
                    }
                }
            }

            BigButton(
                text = "Start",
                onClick = onStartFocus
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .safeClick { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Back",
                    color = WatchColors.TextTertiary,
                    style = MaterialTheme.typography.caption1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Breathing focus
// ─────────────────────────────────────────────────────────────

@Composable
fun BreathingFocusScreen(
    phase: String,
    step: Int,
    total: Int
) {
    val targetSize = when (phase) {
        "Inhale" -> 158.dp
        "Exhale" -> 100.dp
        else -> 130.dp
    }

    val targetColor = when (phase) {
        "Inhale" -> WatchColors.AccentDim
        "Exhale" -> WatchColors.CalmSurface
        else -> WatchColors.SurfaceLow
    }

    val circleSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "bSize"
    )

    val circleColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "bColor"
    )

    val glowAlpha by rememberInfiniteTransition(label = "bGlow")
        .animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "bGlowAlpha"
        )

    val phaseText = when (phase) {
        "Inhale" -> "inspiră"
        "Exhale" -> "expiră"
        else -> "pregătire"
    }

    val arcSweep = if (total > 0) {
        (step.toFloat() / total.toFloat()) * 360f
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(circleSize + 28.dp)) {
                    drawArc(
                        color = WatchColors.SurfaceHigh,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawArc(
                        color = WatchColors.Accent,
                        startAngle = -90f,
                        sweepAngle = arcSweep,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Canvas(modifier = Modifier.size(circleSize + 16.dp)) {
                    drawCircle(
                        color = WatchColors.AccentGlow,
                        radius = size.minDimension / 2f,
                        alpha = glowAlpha,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                Box(
                    modifier = Modifier
                        .size(circleSize)
                        .clip(CircleShape)
                        .background(circleColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = phaseText,
                        color = WatchColors.TextPrimary,
                        style = MaterialTheme.typography.title2,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "$step / $total",
                color = WatchColors.TextTertiary,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Focus mode
// ─────────────────────────────────────────────────────────────

@Composable
fun FocusModeScreen(
    focusSeconds: Int,
    onStopFocus: () -> Unit
) {
    val minutes = focusSeconds / 60
    val seconds = (focusSeconds % 60).toString().padStart(2, '0')
    val sweep = (focusSeconds % 3600).toFloat() / 3600f * 360f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onStopFocus()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(126.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = WatchColors.SurfaceHigh,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawArc(
                        color = WatchColors.Accent,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(WatchColors.SurfaceLow),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$minutes:$seconds",
                            color = WatchColors.Accent,
                            style = MaterialTheme.typography.title1,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "FOCUS",
                            color = WatchColors.TextSecondary,
                            style = MaterialTheme.typography.caption2,
                            letterSpacing = 3.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Double tap to stop",
                color = WatchColors.TextTertiary,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Calm
// ─────────────────────────────────────────────────────────────

@Composable
fun CalmScreen(onBack: () -> Unit) {
    var phase by remember { mutableStateOf("Inhale") }

    LaunchedEffect(Unit) {
        while (true) {
            phase = "Inhale"
            delay(5000)
            phase = "Exhale"
            delay(5000)
        }
    }

    val targetSize = if (phase == "Inhale") 148.dp else 104.dp
    val targetColor = if (phase == "Inhale") WatchColors.CalmSurface else WatchColors.SurfaceLow

    val circleSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "cSize"
    )

    val circleColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "cColor"
    )

    val glowTrans = rememberInfiniteTransition(label = "calmGlow")
    val glowAlpha by glowTrans.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "cGlowAlpha"
    )

    val subtitle = if (phase == "Inhale") "breathe in slowly" else "breathe out..."

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Calm",
                color = WatchColors.CalmPrimary,
                style = MaterialTheme.typography.title3
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(circleSize + 20.dp)) {
                    drawCircle(
                        color = WatchColors.CalmPrimary.copy(alpha = 0.3f),
                        radius = size.minDimension / 2f,
                        alpha = glowAlpha,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                Box(
                    modifier = Modifier
                        .size(circleSize)
                        .clip(CircleShape)
                        .background(circleColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = phase,
                        color = WatchColors.CalmPrimary,
                        style = MaterialTheme.typography.title2,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption1
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .safeClick { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Back",
                    color = WatchColors.TextTertiary,
                    style = MaterialTheme.typography.caption1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Report
// ─────────────────────────────────────────────────────────────

@Composable
fun ReportScreen(
    report: JsonObject?,
    exits: Int?,
    onBack: () -> Unit
) {
    val quality = report?.get("quality_percent")?.asString ?: "--"
    val duration = report?.get("duration_minutes")?.asString ?: "--"
    val exitsValue = report?.get("exits_count")?.asString ?: exits?.toString() ?: "0"
    val qualityFloat = quality.toFloatOrNull() ?: 0f
    val exitsInt = exitsValue.toIntOrNull() ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Report",
            color = WatchColors.TextSecondary,
            style = MaterialTheme.typography.caption1
        )

        Box(
            modifier = Modifier.size(136.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = WatchColors.SurfaceHigh,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    color = WatchColors.Accent,
                    startAngle = -90f,
                    sweepAngle = qualityFloat / 100f * 360f,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(WatchColors.SurfaceLow),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$quality%",
                        color = WatchColors.Accent,
                        style = MaterialTheme.typography.display1
                    )

                    Text(
                        text = "quality",
                        color = WatchColors.TextSecondary,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("min", duration)

            MiniStat(
                label = "exits",
                value = exitsValue,
                valueColor = if (exitsInt >= 3) WatchColors.Warning else WatchColors.Accent
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .safeClick { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Back",
                color = WatchColors.TextTertiary,
                style = MaterialTheme.typography.caption1
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// AlertBanner
// ─────────────────────────────────────────────────────────────

@Composable
fun AlertBanner(
    title: String,
    body: String,
    onDismiss: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "alertPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "alertAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
            }
            .background(WatchColors.DangerOverlay)
            .safeClick { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawCircle(color = WatchColors.Warning)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                color = WatchColors.TextPrimary,
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = body,
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "atinge pentru a închide",
                color = WatchColors.TextTertiary,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Primitive
// ─────────────────────────────────────────────────────────────

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    val btnAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1.0f,
        animationSpec = tween(100),
        label = "bigBtnAlpha"
    )

    Box(
        modifier = Modifier
            .size(width = 154.dp, height = 52.dp)
            .clip(CircleShape)
            .background(WatchColors.FocusButton.copy(alpha = btnAlpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = WatchColors.TextPrimary,
            style = MaterialTheme.typography.title3
        )
    }
}

@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    val btnAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1.0f,
        animationSpec = tween(100),
        label = "smallBtnAlpha"
    )

    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(WatchColors.SurfaceHigh.copy(alpha = btnAlpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = WatchColors.TextPrimary,
            style = MaterialTheme.typography.caption1
        )
    }
}

@Composable
fun MiniStat(
    label: String,
    value: String,
    valueColor: Color = WatchColors.Accent
) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(WatchColors.SurfaceMid),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.title3
            )

            Text(
                text = label,
                color = WatchColors.TextSecondary,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}