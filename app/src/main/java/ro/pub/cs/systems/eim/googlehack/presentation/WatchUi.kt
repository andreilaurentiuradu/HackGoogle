package ro.pub.cs.systems.eim.googlehack.presentation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

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

    onStartFocus: () -> Unit,
    onStopFocus: () -> Unit,
    onMedicationTaken: () -> Unit,
    onMedicationSkipped: () -> Unit
) {
    var screen by remember { mutableStateOf("home") }

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                isCheckInActive -> {
                    CheckInScreen(
                        question = checkInQuestion ?: "Spune-mi trei lucruri pe care le vezi.",
                        negative = checkInNegative,
                        heartRate = heartRate,
                        isListening = isListening,
                        lastTranscript = lastTranscript,
                        onStartVoiceInput = onStartVoiceInput,
                        onSkip = onCheckInNegative
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

                screen == "focus" -> {
                    FocusIntroScreen(
                        heartRate = heartRate,
                        onStartFocus = onStartFocus,
                        onBack = { screen = "home" }
                    )
                }

                screen == "calm" -> {
                    CalmScreen(
                        onBack = { screen = "home" }
                    )
                }

                screen == "report" -> {
                    ReportScreen(
                        report = focusReport,
                        exits = exitsCount,
                        onBack = { screen = "home" }
                    )
                }

                else -> {
                    HomeScreen(
                        status = status,
                        wsStatus = wsStatus,
                        heartRate = heartRate,
                        onFocusClick = { screen = "focus" },
                        onCalmClick = { screen = "calm" },
                        onReportClick = { screen = "report" }
                    )
                }
            }

            if (showMedicationDialog) {
                Alert(
                    title = { Text("Medicație?") },
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
        }
    }
}

@Composable
fun CheckInScreen(
    question: String,
    negative: String,
    heartRate: Double?,
    isListening: Boolean,
    lastTranscript: String?,
    onStartVoiceInput: () -> Unit,
    onSkip: () -> Unit
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
                .padding(horizontal = 12.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = if (isListening) "Ascult..." else "Grounding",
                color = Color.White,
                style = MaterialTheme.typography.caption1
            )

            Box(
                modifier = Modifier
                    .size(74.dp)
                    .scale(if (isListening) pulse else 1f)
                    .clip(CircleShape)
                    .background(Color(0xFF2D1B69)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isListening) "🎙️" else "🌙",
                        color = Color.White,
                        style = MaterialTheme.typography.title2
                    )

                    Text(
                        text = "${heartRate?.toInt() ?: "--"}",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }

            Text(
                text = question.take(62),
                color = Color.White,
                style = MaterialTheme.typography.caption1
            )

            if (!lastTranscript.isNullOrBlank()) {
                Text(
                    text = "Ai spus: ${lastTranscript.take(34)}",
                    color = Color(0xFFD8B4FE),
                    style = MaterialTheme.typography.caption2
                )
            } else {
                Text(
                    text = if (isListening) "Vorbește acum..." else "Apasă și răspunde vocal",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.caption2
                )
            }

            BigVoiceButton(
                text = if (isListening) "Ascult..." else "Vorbește",
                onClick = onStartVoiceInput
            )

            Text(
                text = negative.take(8),
                color = Color.Gray,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.safeClick { onSkip() }
            )
        }
    }
}

@Composable
fun BigVoiceButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 124.dp, height = 42.dp)
            .clip(CircleShape)
            .background(Color(0xFF7C3AED))
            .safeClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.caption1
        )
    }
}

@Composable
fun HomeScreen(
    status: String,
    wsStatus: String,
    heartRate: Double?,
    onFocusClick: () -> Unit,
    onCalmClick: () -> Unit,
    onReportClick: () -> Unit
) {
    val connected = wsStatus == "WebSocket: conectat" ||
            wsStatus.contains("date trimise") ||
            wsStatus.contains("Server") ||
            wsStatus.contains("Action trimisă")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Mind Watch",
            color = Color.White,
            style = MaterialTheme.typography.title2
        )

        Box(
            modifier = Modifier
                .size(118.dp)
                .clip(CircleShape)
                .background(Color(0xFF062E1C)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${heartRate?.toInt() ?: "--"}",
                    color = Color(0xFF4CFF72),
                    style = MaterialTheme.typography.display1
                )

                Text(
                    text = "BPM",
                    color = Color.Gray,
                    style = MaterialTheme.typography.title3
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigButton(
                text = "Focus",
                onClick = onFocusClick
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Calm", onCalmClick)
                SmallButton("Report", onReportClick)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = status.take(26),
                color = Color.Gray,
                style = MaterialTheme.typography.caption2
            )

            Text(
                text = if (connected) "● connected" else "○ offline",
                color = if (connected) Color(0xFF4CFF72) else Color.Red,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

@Composable
fun FocusIntroScreen(
    heartRate: Double?,
    onStartFocus: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Focus Mode",
                color = Color.White,
                style = MaterialTheme.typography.title2
            )

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .size(122.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF101820)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Ready?",
                        color = Color(0xFF4CFF72),
                        style = MaterialTheme.typography.title2
                    )

                    Text(
                        text = "HR ${heartRate?.toInt() ?: "--"}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            BigButton(
                text = "Start",
                onClick = onStartFocus
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Back",
                color = Color.Gray,
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.safeClick { onBack() }
            )
        }
    }
}

@Composable
fun BreathingFocusScreen(
    phase: String,
    step: Int,
    total: Int
) {
    val circleSize = when (phase) {
        "Inhale" -> 152.dp
        "Exhale" -> 106.dp
        else -> 132.dp
    }

    val circleColor = when (phase) {
        "Inhale" -> Color(0xFF0B3D2E)
        "Exhale" -> Color(0xFF102A43)
        else -> Color(0xFF1B5E20)
    }

    val subtitle = when (phase) {
        "Inhale" -> "breathe in slowly"
        "Exhale" -> "let it go"
        else -> "starting focus"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(circleColor),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = phase,
                        color = Color.White,
                        style = MaterialTheme.typography.title2
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "$step / $total",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = subtitle,
                color = Color.Gray,
                style = MaterialTheme.typography.caption1
            )
        }
    }
}

@Composable
fun FocusModeScreen(
    focusSeconds: Int,
    onStopFocus: () -> Unit
) {
    val minutes = focusSeconds / 60
    val seconds = (focusSeconds % 60).toString().padStart(2, '0')

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF071E15)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$minutes:$seconds",
                        color = Color(0xFF4CFF72),
                        style = MaterialTheme.typography.display1
                    )

                    Text(
                        text = "FOCUS",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Double tap to stop",
                color = Color.Gray,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

@Composable
fun CalmScreen(
    onBack: () -> Unit
) {
    var phase by remember { mutableStateOf("Inhale") }

    LaunchedEffect(Unit) {
        while (true) {
            phase = "Inhale"
            delay(5000)
            phase = "Exhale"
            delay(5000)
        }
    }

    val circleSize = if (phase == "Inhale") 145.dp else 108.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Calm",
                color = Color.White,
                style = MaterialTheme.typography.title3
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(Color(0xFF111827)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = phase,
                    color = Color.Cyan,
                    style = MaterialTheme.typography.title2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Back",
                color = Color.Gray,
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.safeClick { onBack() }
            )
        }
    }
}

@Composable
fun ReportScreen(
    report: JsonObject?,
    exits: Int?,
    onBack: () -> Unit
) {
    val quality = report?.get("quality_percent")?.asString ?: "--"
    val duration = report?.get("duration_minutes")?.asString ?: "--"
    val exitsValue = report?.get("exits_count")?.asString ?: exits?.toString() ?: "0"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Report",
            color = Color.White,
            style = MaterialTheme.typography.title3
        )

        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(Color(0xFF101820)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$quality%",
                    color = Color(0xFF4CFF72),
                    style = MaterialTheme.typography.display1
                )

                Text(
                    text = "quality",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.caption2
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("min", duration)
            MiniStat("exits", exitsValue)
        }

        Text(
            text = "Back",
            color = Color.Gray,
            style = MaterialTheme.typography.caption1,
            modifier = Modifier.safeClick { onBack() }
        )
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 142.dp, height = 54.dp)
            .clip(CircleShape)
            .background(Color(0xFF1B5E20))
            .safeClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.title3
        )
    }
}

@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(Color(0xFF202124))
            .safeClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.caption1
        )
    }
}

@Composable
fun MiniStat(
    label: String,
    value: String
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(Color(0xFF202124)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                color = Color(0xFF4CFF72),
                style = MaterialTheme.typography.title3
            )

            Text(
                text = label,
                color = Color.LightGray,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}