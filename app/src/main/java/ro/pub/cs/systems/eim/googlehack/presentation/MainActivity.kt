package ro.pub.cs.systems.eim.googlehack.presentation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.UserActivityInfo
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), SensorEventListener {

    private val HR_PERMISSION = "android.permission.health.READ_HEART_RATE"

    // Schimbă IP-ul/portul dacă ai pornit serverul pe alt port.
    private val SERVER_URL = "ws://10.200.22.124:8000/ws/health"

    private val EMERGENCY_PHONE = "+40728151136"

    private val gson = Gson()

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isWsConnected = false
    private var sendingJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private val registeredAndroidSensors = mutableListOf<Sensor>()

    private var heartRate by mutableStateOf<Double?>(null)
    private var activityState by mutableStateOf<String?>(null)

    private var accelerometer by mutableStateOf<List<Float>?>(null)
    private var gyroscope by mutableStateOf<List<Float>?>(null)
    private var light by mutableStateOf<Float?>(null)

    private var status by mutableStateOf("Pornesc...")
    private var wsStatus by mutableStateOf("WebSocket: neconectat")

    private var isFocusActive by mutableStateOf(false)
    private var focusSeconds by mutableStateOf(0)
    private var focusTimerJob: Job? = null

    private var focusReport by mutableStateOf<JsonObject?>(null)
    private var showMedicationDialog by mutableStateOf(false)
    private var exitsCount by mutableStateOf<Int?>(null)

    private var isBreathingActive by mutableStateOf(false)
    private var breathingPhase by mutableStateOf("Inhale")
    private var breathingStep by mutableStateOf(1)
    private var breathingTotal by mutableStateOf(3)

    // ──────────────────────────────────────────────
    // Anxiety / grounding check-in state
    // ──────────────────────────────────────────────

    private var isCheckInActive by mutableStateOf(false)
    private var checkInQuestionId by mutableStateOf<String?>(null)
    private var checkInQuestion by mutableStateOf<String?>(null)
    private var checkInPositive by mutableStateOf("Da")
    private var checkInNegative by mutableStateOf("Skip")

    private var isListening by mutableStateOf(false)
    private var lastTranscript by mutableStateOf<String?>(null)
    private var speechRecognizer: SpeechRecognizer? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // ──────────────────────────────────────────────
    // Emergency / location
    // ──────────────────────────────────────────────

    private var emergencyTriggered = false
    private var consecutiveEpilepsyAlerts = 0
    private var lastLocation: Location? = null
    private lateinit var locationManager: LocationManager
    private val locationListener = LocationListener { location ->
        lastLocation = location
    }

    private val passiveClient by lazy {
        HealthServices.getClient(this).passiveMonitoringClient
    }

    private var isPassiveRegistered = false

    private val passiveDataTypes = setOf(
        DataType.HEART_RATE_BPM
    )

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            updateHeartRate(dataPoints)
        }

        override fun onUserActivityInfoReceived(info: UserActivityInfo) {
            Log.d("PASSIVE_HEALTH", "Activity info: $info")
            runOnUiThread {
                activityState = info.userActivityState.toString()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        initTextToSpeech()
        initSpeechRecognizer()

        setContent {
            MaterialTheme {
                WatchApp(
                    status = status,
                    wsStatus = wsStatus,
                    heartRate = heartRate,

                    isFocusActive = isFocusActive,
                    focusSeconds = focusSeconds,
                    focusReport = focusReport,
                    exitsCount = exitsCount,

                    showMedicationDialog = showMedicationDialog,

                    isBreathingActive = isBreathingActive,
                    breathingPhase = breathingPhase,
                    breathingStep = breathingStep,
                    breathingTotal = breathingTotal,

                    isCheckInActive = isCheckInActive,
                    checkInQuestion = checkInQuestion,
                    checkInPositive = checkInPositive,
                    checkInNegative = checkInNegative,
                    isListening = isListening,
                    lastTranscript = lastTranscript,
                    onCheckInPositive = {
                        answerCheckIn(true)
                    },
                    onCheckInNegative = {
                        answerCheckIn(false)
                    },
                    onStartVoiceInput = {
                        startVoiceInput()
                    },

                    onStartFocus = {
                        startFocusSession()
                    },
                    onStopFocus = {
                        endFocusSession()
                    },
                    onMedicationTaken = {
                        showMedicationDialog = false
                        sendAction(
                            "medication_taken",
                            mapOf("time" to System.currentTimeMillis())
                        )
                    },
                    onMedicationSkipped = {
                        showMedicationDialog = false
                        sendAction(
                            "medication_skipped",
                            mapOf("time" to System.currentTimeMillis())
                        )
                    }
                )
            }
        }

        connectWebSocket()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()

        connectWebSocket()
        startAndroidSensors()
        startSendingLoop()
        startLocationUpdates()

        if (hasPermissions()) {
            startPassiveMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()

        stopSendingLoop()
        stopPassiveMonitoring()
        stopAndroidSensors()
        stopLocationUpdates()
        disconnectWebSocket()
    }

    override fun onDestroy() {
        stopSendingLoop()
        stopPassiveMonitoring()
        stopAndroidSensors()
        stopLocationUpdates()
        disconnectWebSocket()

        speechRecognizer?.destroy()
        speechRecognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        wsClient.dispatcher.executorService.shutdown()

        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Text To Speech
    // ──────────────────────────────────────────────

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val roResult = tts?.setLanguage(Locale("ro", "RO"))

                if (roResult == TextToSpeech.LANG_MISSING_DATA ||
                    roResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.language = Locale.ENGLISH
                }

                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.05f)
                isTtsReady = true
            } else {
                isTtsReady = false
                Log.e("TTS", "TextToSpeech init failed")
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady || text.isBlank()) return

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "mind_watch_tts_${System.currentTimeMillis()}"
        )
    }

    // ──────────────────────────────────────────────
    // Speech To Text
    // ──────────────────────────────────────────────

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w("SPEECH", "Speech recognition not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                status = "Ascult..."
            }

            override fun onBeginningOfSpeech() {
                isListening = true
                status = "Te ascult..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                status = "Procesez răspunsul..."
            }

            override fun onError(error: Int) {
                isListening = false

                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Eroare audio"
                    SpeechRecognizer.ERROR_CLIENT -> "Eroare client"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisiune microfon lipsă"
                    SpeechRecognizer.ERROR_NETWORK -> "Eroare rețea"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout rețea"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Nu am auzit clar"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Microfon ocupat"
                    SpeechRecognizer.ERROR_SERVER -> "Eroare server speech"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nu am auzit nimic"
                    else -> "Nu am auzit clar"
                }

                status = message
                Log.e("SPEECH", "Speech error: $error | $message")
                speak("Nu am auzit clar. Încearcă din nou.")
            }

            override fun onResults(results: Bundle?) {
                isListening = false

                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val transcript = matches?.firstOrNull()

                if (transcript.isNullOrBlank()) {
                    status = "Nu am prins răspunsul"
                    speak("Nu am prins răspunsul. Mai încearcă o dată.")
                    return
                }

                lastTranscript = transcript
                status = "Ai spus: $transcript"

                sendAction(
                    "anxiety_voice_answer",
                    mapOf(
                        "question_id" to (checkInQuestionId ?: "grounding"),
                        "question" to (checkInQuestion ?: ""),
                        "transcript" to transcript,
                        "heart_rate" to (heartRate ?: 0.0),
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                201
            )
            return
        }

        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            putExtra(RecognizerIntent.EXTRA_PROMPT, checkInQuestion ?: "Răspunde acum")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            lastTranscript = null
            isListening = true
            status = "Ascult..."
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            status = "Microfon indisponibil"
            Log.e("SPEECH", "Could not start listening", e)
        }
    }

    // ──────────────────────────────────────────────
    // WebSocket
    // ──────────────────────────────────────────────

    private fun connectWebSocket() {
        if (webSocket != null || isWsConnected) return

        wsStatus = "WebSocket: conectare..."

        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        webSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isWsConnected = true
                    this@MainActivity.webSocket = webSocket

                    runOnUiThread {
                        wsStatus = "WebSocket: conectat"
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WS_HEALTH", "Server response: $text")

                    runOnUiThread {
                        handleServerMessage(text)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    isWsConnected = false
                    this@MainActivity.webSocket = null

                    Log.e("WS_HEALTH", "WebSocket error: ${t.message}", t)

                    runOnUiThread {
                        wsStatus = "WebSocket: eroare"
                        status = "Server indisponibil"
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isWsConnected = false
                    this@MainActivity.webSocket = null

                    runOnUiThread {
                        wsStatus = "WebSocket: închis"
                    }
                }
            }
        )
    }

    private fun disconnectWebSocket() {
        isWsConnected = false
        webSocket?.close(1000, "Activity stopped")
        webSocket = null
        wsStatus = "WebSocket: închis"
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject

            if (json.has("status") && !json.has("state")) {
                wsStatus = "Server: ${json.get("status").asString}"
                return
            }

            val state = json.get("state")?.asString ?: return
            val message = json.get("message")?.asString ?: "Totul este în regulă"

            wsStatus = "Server state: $state"

            when (state) {
                "adhd_high_activity", "adhd_fidgeting" -> {
                    HapticManager.vibrate(this, HapticPattern.ANCHOR)
                    status = message
                }

                "pre_focus_ritual" -> {
                    isCheckInActive = false
                    lastTranscript = null

                    val breaths = json.get("breaths")?.asInt ?: 3
                    val inhaleMs = json.get("inhale_ms")?.asLong ?: 5000L
                    val exhaleMs = json.get("exhale_ms")?.asLong ?: 5000L

                    isBreathingActive = true
                    breathingTotal = breaths
                    breathingStep = 1
                    breathingPhase = "Inhale"
                    focusReport = null
                    status = "Pregătire focus"

                    lifecycleScope.launch {
                        repeat(breaths) { index ->
                            breathingStep = index + 1

                            breathingPhase = "Inhale"
                            status = "Inspiră"
                            speak("Inspiră")
                            HapticManager.vibrateDuration(this@MainActivity, inhaleMs)
                            delay(inhaleMs)

                            breathingPhase = "Exhale"
                            status = "Expiră"
                            speak("Expiră")
                            HapticManager.vibrateDuration(this@MainActivity, exhaleMs)
                            delay(exhaleMs)
                        }

                        breathingPhase = "Ready"
                        status = "Focus ready"
                        speak("Ești pregătit. Începem focus.")

                        delay(800)

                        isBreathingActive = false
                        sendAction("focus_ready")
                    }
                }

                "focus_started" -> {
                    isCheckInActive = false
                    lastTranscript = null

                    isBreathingActive = false
                    isFocusActive = true
                    focusReport = null
                    status = "Focus pornit"
                    enableDnd()
                    startFocusTimer()
                }

                "focus_exit" -> {
                    HapticManager.vibrate(this, HapticPattern.FOCUS_EXIT)
                    exitsCount = json.get("exits_count")?.asInt
                    status = "Ai ieșit din focus"
                }

                "focus_complete", "focus_end" -> {
                    isCheckInActive = false
                    lastTranscript = null

                    isBreathingActive = false
                    isFocusActive = false
                    stopFocusTimer()
                    disableDnd()
                    focusReport = json
                    status = "Focus finalizat"
                    speak("Focus finalizat.")
                }

                "hyperfocus_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    status = message
                    speak(message)
                }

                "medication_reminder" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    showMedicationDialog = true
                    status = "Reminder medicație"
                    speak("Este timpul pentru medicație.")
                }

                "epilepsy_preictal" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    status = message
                    speak("Am detectat o stare de risc. Rămâi într-un loc sigur.")
                }

                "epilepsy_warning" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    status = message
                    speak("Atenție. Posibil stimul luminos periculos.")
                }

                "epilepsy_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)

                    val freq = json.get("freq")?.asDouble ?: 0.0
                    status = json.get("message")?.asString ?: "ALERTĂ EPILEPSIE ${freq} Hz"

                    speak("Alertă de siguranță. Dacă poți, așază-te jos.")

                    consecutiveEpilepsyAlerts++

                    if (consecutiveEpilepsyAlerts >= 2) {
                        triggerEmergency(freq)
                    }
                }

                "anxiety_alert", "stress_alert" -> {
                    status = message
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)

                    sendAction(
                        "anxiety_checkin_start",
                        mapOf(
                            "heart_rate" to (heartRate ?: 0.0),
                            "source_state" to state
                        )
                    )
                }

                "anxiety_voice_question" -> {
                    isCheckInActive = true
                    isBreathingActive = false

                    checkInQuestionId = json.get("question_id")?.asString ?: "grounding"
                    checkInQuestion = json.get("question")?.asString
                        ?: "Spune-mi trei lucruri pe care le vezi în jur."

                    checkInPositive = json.get("positive")?.asString ?: "Da"
                    checkInNegative = json.get("negative")?.asString ?: "Skip"

                    lastTranscript = null
                    status = "Grounding vocal"

                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    speak(checkInQuestion ?: "")
                }

                "anxiety_checkin_question" -> {
                    isCheckInActive = true
                    isBreathingActive = false

                    checkInQuestionId = json.get("question_id")?.asString ?: "safe"
                    checkInQuestion = json.get("question")?.asString
                        ?: "Te simți în siguranță acum?"

                    checkInPositive = json.get("positive")?.asString ?: "Da"
                    checkInNegative = json.get("negative")?.asString ?: "Nu"

                    lastTranscript = null
                    status = "Check-in anxietate"

                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    speak(checkInQuestion ?: "")
                }

                "anxiety_checkin_done" -> {
                    isCheckInActive = false
                    isBreathingActive = false
                    isListening = false
                    lastTranscript = null

                    status = message
                    speak(message)
                }

                "start_breathing" -> {
                    isCheckInActive = false
                    isListening = false
                    lastTranscript = null

                    status = message
                    speak("Hai să facem un exercițiu de respirație.")
                    sendAction("focus_start")
                }

                "notify_contact" -> {
                    isCheckInActive = false
                    isListening = false
                    lastTranscript = null

                    val lat = lastLocation?.latitude ?: 0.0
                    val lon = lastLocation?.longitude ?: 0.0

                    sendEmergencySms(
                        EMERGENCY_PHONE,
                        0.0,
                        lat,
                        lon
                    )

                    status = "Contact notificat"
                    speak("Am trimis un mesaj către contactul tău.")
                }

                "normal" -> {
                    consecutiveEpilepsyAlerts = 0
                    emergencyTriggered = false
                    status = message
                }

                else -> {
                    status = message
                }
            }

        } catch (e: Exception) {
            Log.e("WS_HEALTH", "Invalid server JSON: $text", e)
            wsStatus = "Server JSON invalid"
        }
    }

    // ──────────────────────────────────────────────
    // Actions
    // ──────────────────────────────────────────────

    private fun sendAction(action: String, extra: Map<String, Any> = emptyMap()) {
        if (!isWsConnected || webSocket == null) {
            Log.d("WS_ACTION", "Skip action: websocket not connected")
            status = "Ceas neconectat la server"
            return
        }

        val payload = mutableMapOf<String, Any>(
            "action" to action,
            "timestamp" to System.currentTimeMillis()
        )

        payload.putAll(extra)

        val json = gson.toJson(payload)
        val sent = webSocket?.send(json) ?: false

        wsStatus = if (sent) {
            "Action trimisă: $action"
        } else {
            "Action failed: $action"
        }
    }

    private fun answerCheckIn(isPositive: Boolean) {
        val questionId = checkInQuestionId ?: "grounding"
        val answerText = if (isPositive) {
            checkInPositive
        } else {
            checkInNegative
        }

        sendAction(
            "anxiety_answer",
            mapOf(
                "question_id" to questionId,
                "question" to (checkInQuestion ?: ""),
                "answer" to answerText,
                "positive" to isPositive,
                "heart_rate" to (heartRate ?: 0.0),
                "timestamp" to System.currentTimeMillis()
            )
        )

        status = "Răspuns trimis"
    }

    private fun startFocusSession() {
        isCheckInActive = false
        lastTranscript = null
        sendAction("focus_start")
    }

    private fun endFocusSession() {
        isCheckInActive = false
        lastTranscript = null

        sendAction("focus_end")
        isBreathingActive = false
        isFocusActive = false
        stopFocusTimer()
        disableDnd()
    }

    // ──────────────────────────────────────────────
    // Focus timer
    // ──────────────────────────────────────────────

    private fun startFocusTimer() {
        focusTimerJob?.cancel()
        focusSeconds = 0

        focusTimerJob = lifecycleScope.launch {
            while (isActive && isFocusActive) {
                delay(1000)
                focusSeconds++
            }
        }
    }

    private fun stopFocusTimer() {
        focusTimerJob?.cancel()
        focusTimerJob = null
    }

    // ──────────────────────────────────────────────
    // DND
    // ──────────────────────────────────────────────

    private fun enableDnd() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_NONE
            )
        }
    }

    private fun disableDnd() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }

    // ──────────────────────────────────────────────
    // Location + emergency
    // ──────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                locationManager.requestLocationUpdates(provider, 30_000L, 10f, locationListener)
                locationManager.getLastKnownLocation(provider)?.let {
                    lastLocation = it
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    private fun triggerEmergency(freq: Double) {
        if (emergencyTriggered) return
        emergencyTriggered = true

        lifecycleScope.launch {
            delay(30_000L)

            val lat = lastLocation?.latitude ?: 0.0
            val lon = lastLocation?.longitude ?: 0.0

            sendEmergencySms(
                EMERGENCY_PHONE,
                freq,
                lat,
                lon
            )

            makeEmergencyCall(EMERGENCY_PHONE)
        }
    }

    private fun sendEmergencySms(phone: String, freq: Double, lat: Double, lon: Double) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("EMERGENCY", "SMS permission not granted")
            return
        }

        val mapsUrl = "maps.google.com/?q=%.6f,%.6f".format(lat, lon)

        val message = if (freq > 0.0) {
            "ALERTA: CRIZA EPILEPTICA | %.1f Hz. GPS: %.2f,%.2f. %s"
                .format(freq, lat, lon, mapsUrl)
        } else {
            "ALERTA: anxietate/stres ridicat. GPS: %.2f,%.2f. %s"
                .format(lat, lon, mapsUrl)
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            smsManager?.sendTextMessage(phone, null, message, null, null)
            Log.d("EMERGENCY", "SMS sent: $message")
        } catch (e: Exception) {
            Log.e("EMERGENCY", "SMS failed", e)
        }
    }

    private fun makeEmergencyCall(phone: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("EMERGENCY", "CALL_PHONE permission not granted")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("EMERGENCY", "Call failed", e)
        }
    }

    // ──────────────────────────────────────────────
    // Sending sensor data
    // ──────────────────────────────────────────────

    private fun startSendingLoop() {
        if (sendingJob != null) return

        sendingJob = lifecycleScope.launch {
            while (isActive) {
                sendHealthData()
                delay(1000)
            }
        }
    }

    private fun stopSendingLoop() {
        sendingJob?.cancel()
        sendingJob = null
    }

    private fun sendHealthData() {
        if (!isWsConnected || webSocket == null) return

        val payload = mapOf(
            "health_services" to mapOf(
                "heart_rate" to heartRate,
                "activity_state" to activityState
            ),
            "raw_sensors" to mapOf(
                "accelerometer" to accelerometer,
                "gyroscope" to gyroscope,
                "linear_acceleration" to accelerometer,
                "light" to listOf(light ?: 0f)
            ),
            "battery" to getBatteryPayload(),
            "timestamp" to System.currentTimeMillis()
        )

        val json = gson.toJson(payload)
        webSocket?.send(json)
    }

    private fun getBatteryPayload(): Map<String, Any?> {
        val batteryIntent = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val percent = if (level >= 0 && scale > 0) {
            level * 100.0 / scale
        } else {
            null
        }

        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

        return mapOf(
            "level_percent" to percent,
            "is_charging" to isCharging
        )
    }

    // ──────────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        val heartGranted =
            ContextCompat.checkSelfPermission(this, HR_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) ==
                    PackageManager.PERMISSION_GRANTED

        val activityGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED

        return heartGranted && activityGranted
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
        }

        if (ContextCompat.checkSelfPermission(this, HR_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(HR_PERMISSION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isEmpty()) {
            status = "Permisiuni acceptate"
            startPassiveMonitoring()
            return
        }

        status = "Cer permisiuni..."

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            200
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 200) {
            if (hasPermissions()) {
                status = "Permisiuni acceptate"
                startPassiveMonitoring()
            } else {
                status = "Permisiuni lipsă"
            }
        }

        if (requestCode == 201) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (granted) {
                startVoiceInput()
            } else {
                status = "Microfon refuzat"
                speak("Am nevoie de microfon pentru răspuns vocal.")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Health Services
    // ──────────────────────────────────────────────

    private fun startPassiveMonitoring() {
        if (isPassiveRegistered) return

        lifecycleScope.launch {
            try {
                status = "Verific Health Services..."

                val capabilities = passiveClient.getCapabilitiesAsync().await()
                val supported = capabilities.supportedDataTypesPassiveMonitoring
                val supportedTypes = passiveDataTypes.filter { it in supported }.toSet()

                if (supportedTypes.isEmpty()) {
                    status = "Heart rate nesuportat"
                    return@launch
                }

                val config = PassiveListenerConfig.builder()
                    .setDataTypes(supportedTypes)
                    .setShouldUserActivityInfoBeRequested(true)
                    .build()

                passiveClient.setPassiveListenerCallback(config, passiveCallback)

                isPassiveRegistered = true
                status = "Monitoring activ"

            } catch (e: Exception) {
                status = "Eroare Health Services"
                Log.e("PASSIVE_HEALTH", "Passive error", e)
            }
        }
    }

    private fun stopPassiveMonitoring() {
        if (!isPassiveRegistered) return

        lifecycleScope.launch {
            try {
                passiveClient.clearPassiveListenerCallbackAsync().await()
                isPassiveRegistered = false
                status = "Monitoring oprit"
            } catch (e: Exception) {
                Log.e("PASSIVE_HEALTH", "Clear passive error", e)
            }
        }
    }

    private fun updateHeartRate(container: DataPointContainer) {
        val points = container.getData(DataType.HEART_RATE_BPM)
        val latest = points.lastOrNull() ?: return

        val value = when (latest) {
            is SampleDataPoint<*> -> latest.value.toString().toDoubleOrNull()
            is IntervalDataPoint<*> -> latest.value.toString().toDoubleOrNull()
            else -> null
        }

        if (value != null) {
            runOnUiThread {
                heartRate = value
            }
        }
    }

    // ──────────────────────────────────────────────
    // Android raw sensors
    // ──────────────────────────────────────────────

    private fun startAndroidSensors() {
        registeredAndroidSensors.clear()

        val sensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT
        )

        sensorTypes.forEach { type ->
            val sensor = sensorManager.getDefaultSensor(type)

            if (sensor != null) {
                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )

                registeredAndroidSensors.add(sensor)
                Log.d("RAW_SENSORS", "Registered: ${sensor.name}")
            } else {
                Log.d("RAW_SENSORS", "Not available type=$type")
            }
        }
    }

    private fun stopAndroidSensors() {
        sensorManager.unregisterListener(this)
        registeredAndroidSensors.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometer = event.values.toList()
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroscope = event.values.toList()
            }

            Sensor.TYPE_LIGHT -> {
                light = event.values.firstOrNull()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}