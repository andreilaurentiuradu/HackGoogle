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
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), SensorEventListener {

    private val HR_PERMISSION = "android.permission.health.READ_HEART_RATE"

    // Schimbă IP-ul dacă serverul rulează pe alt device/IP.
    private val SERVER_URL = "ws://10.200.22.124:8000/ws/health"

    private val EMERGENCY_PHONE = "+40728151136"
    private val EMERGENCY_TTS_REPEATS = 3

    private val EPILEPSY_ALERT_WINDOW_MS = 30_000L
    private val EMERGENCY_DELAY_MS = 10_000L

    private val STRESS_POLL_MS = 10_000L
    private val STRESS_COOLDOWN_MS = 90_000L
    private val STRESS_THRESHOLD = 0.55f

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
    private var areAndroidSensorsStarted = false

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

    private var alertTitle by mutableStateOf<String?>(null)
    private var alertBody by mutableStateOf("")
    private var alertDismissJob: Job? = null

    private var selectedProfile by mutableStateOf<String?>(null)

    private var isBreathingActive by mutableStateOf(false)
    private var breathingPhase by mutableStateOf("Inhale")
    private var breathingStep by mutableStateOf(1)
    private var breathingTotal by mutableStateOf(3)

    // ──────────────────────────────────────────────
    // Anxiety / grounding vocal
    // ──────────────────────────────────────────────

    private var isCheckInActive by mutableStateOf(false)
    private var checkInQuestionId by mutableStateOf<String?>(null)
    private var checkInQuestion by mutableStateOf<String?>(null)
    private var checkInPositive by mutableStateOf("Da")
    private var checkInNegative by mutableStateOf("Skip")

    private var isListening by mutableStateOf(false)
    private var lastTranscript by mutableStateOf<String?>(null)
    private var speechRecognizer: SpeechRecognizer? = null

    // ──────────────────────────────────────────────
    // Emergency / location
    // ──────────────────────────────────────────────

    private var emergencyTriggered = false
    private var consecutiveEpilepsyAlerts = 0
    private var lastEpilepsyAlertAt = 0L
    private var emergencyJob: Job? = null

    private var lastLocation: Location? = null
    private lateinit var locationManager: LocationManager

    private val locationListener = LocationListener { location ->
        lastLocation = location
        Log.d(
            "LOCATION",
            "Location updated: ${location.latitude}, ${location.longitude}"
        )
    }

    // ──────────────────────────────────────────────
    // Local stress model
    // ──────────────────────────────────────────────

    private var stressJob: Job? = null
    private var lastStressAlertAt = 0L

    // ──────────────────────────────────────────────
    // TTS + emergency call audio
    // ──────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsMessageSpoken = false
    private var ttsRepeatCount = 0

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var audioManager: AudioManager
    private var previousSpeakerphoneOn = false

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (
                state == TelephonyManager.CALL_STATE_OFFHOOK &&
                emergencyTriggered &&
                !ttsMessageSpoken
            ) {
                Log.d("EMERGENCY", "Call is OFFHOOK. Starting emergency TTS.")

                ttsMessageSpoken = true
                enableSpeakerphone()

                lifecycleScope.launch {
                    delay(1_500L)
                    speakEmergencyMessage()
                }
            } else if (state == TelephonyManager.CALL_STATE_IDLE && ttsMessageSpoken) {
                Log.d("EMERGENCY", "Call ended. Restoring speakerphone.")

                restoreSpeakerphone()
                ttsMessageSpoken = false
                ttsRepeatCount = 0
            }
        }
    }

    // ──────────────────────────────────────────────
    // Health Services
    // ──────────────────────────────────────────────

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

        Log.d("LIFECYCLE", "onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initTts()
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

                    alertTitle = alertTitle,
                    alertBody = alertBody,
                    selectedProfile = selectedProfile,
                    onAlertDismiss = {
                        alertTitle = null
                    },
                    onProfileSelect = { profile ->
                        if (profile.isEmpty()) {
                            selectedProfile = null
                        } else {
                            selectedProfile = profile
                            sendAction("set_profile", mapOf("profile" to profile))
                        }
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

        Log.d("LIFECYCLE", "onResume")

        connectWebSocket()
        startAndroidSensors()
        startSendingLoop()
        startLocationUpdates()
        registerPhoneStateListener()

        if (hasPermissions()) {
            startPassiveMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()

        Log.d("LIFECYCLE", "onPause called, keeping WebSocket alive for demo")

        /*
         * IMPORTANT:
         * Pe Wear OS, onPause() se cheamă foarte des:
         * - când se stinge ecranul
         * - când intră în ambient mode
         * - când apare un overlay
         *
         * Dacă închidem WebSocket-ul aici, serverul vede mereu:
         * connection closed -> connection open.
         *
         * Pentru demo, păstrăm conexiunea și senzorii activi.
         */
    }

    override fun onDestroy() {
        Log.d("LIFECYCLE", "onDestroy")

        stopSendingLoop()
        stopStressLoop()
        stopPassiveMonitoring()
        stopAndroidSensors()
        stopLocationUpdates()
        disconnectWebSocket()
        unregisterPhoneStateListener()

        emergencyJob?.cancel()
        emergencyJob = null

        alertDismissJob?.cancel()
        alertDismissJob = null

        focusTimerJob?.cancel()
        focusTimerJob = null

        speechRecognizer?.destroy()
        speechRecognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        if (ttsMessageSpoken) {
            restoreSpeakerphone()
        }

        wsClient.dispatcher.executorService.shutdown()

        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // TTS general + emergency TTS
    // ──────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val roResult = tts?.setLanguage(Locale("ro", "RO"))

                ttsReady = roResult != TextToSpeech.LANG_MISSING_DATA &&
                        roResult != TextToSpeech.LANG_NOT_SUPPORTED

                if (!ttsReady) {
                    tts?.setLanguage(Locale.getDefault())
                    ttsReady = true
                }

                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.05f)

                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onError(utteranceId: String?) {
                        Log.e("TTS", "TTS error for utterance: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        if (
                            utteranceId == "emergency" &&
                            ttsRepeatCount < EMERGENCY_TTS_REPEATS - 1
                        ) {
                            ttsRepeatCount++

                            lifecycleScope.launch {
                                delay(800)
                                speakEmergencyMessage()
                            }
                        }
                    }
                })

                Log.d("TTS", "TTS ready")
            } else {
                ttsReady = false
                Log.e("TTS", "TextToSpeech init failed")
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "mind_watch_tts_${System.currentTimeMillis()}"
        )
    }

    private suspend fun speakEmergencyMessage() {
        if (!ttsReady) {
            Log.w("EMERGENCY", "TTS nu e inițializat")
            return
        }

        val lat = lastLocation?.latitude ?: 0.0
        val lon = lastLocation?.longitude ?: 0.0
        val locationText = resolveAddress(lat, lon)

        val message = "Ajutor. Am o criză epileptică la locația $locationText"

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "emergency"
        )

        Log.d(
            "EMERGENCY",
            "TTS vorbit (${ttsRepeatCount + 1}/$EMERGENCY_TTS_REPEATS): $message"
        )
    }

    private suspend fun resolveAddress(lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) {
            return "necunoscută. Vă rog veniți urgent."
        }

        return withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    return@withContext "${"%.5f".format(lat)}, ${"%.5f".format(lon)}."
                }

                @Suppress("DEPRECATION")
                val addresses = Geocoder(this@MainActivity, Locale("ro", "RO"))
                    .getFromLocation(lat, lon, 1)

                val address = addresses?.firstOrNull()

                if (address != null) {
                    val parts = listOfNotNull(
                        address.thoroughfare,
                        address.subThoroughfare,
                        address.locality,
                        address.adminArea
                    )

                    val text = if (parts.isNotEmpty()) {
                        parts.joinToString(", ")
                    } else {
                        "${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
                    }

                    "$text."
                } else {
                    "${"%.5f".format(lat)}, ${"%.5f".format(lon)}."
                }
            } catch (e: Exception) {
                Log.w("EMERGENCY", "Geocoding eșuat: ${e.message}")
                "${"%.5f".format(lat)}, ${"%.5f".format(lon)}."
            }
        }
    }

    private fun enableSpeakerphone() {
        try {
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true

            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)

            Log.d("EMERGENCY", "Speakerphone enabled")
        } catch (e: Exception) {
            Log.w("EMERGENCY", "Nu am putut activa speakerphone: ${e.message}")
        }
    }

    private fun restoreSpeakerphone() {
        try {
            audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_NORMAL

            Log.d("EMERGENCY", "Speakerphone restored")
        } catch (_: Exception) {
        }
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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

                    Log.d("WS_HEALTH", "WebSocket opened")

                    runOnUiThread {
                        wsStatus = "WebSocket: conectat"

                        selectedProfile?.let { profile ->
                            sendAction("set_profile", mapOf("profile" to profile))
                        }
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

                    Log.d("WS_HEALTH", "WebSocket closed: $code $reason")

                    runOnUiThread {
                        wsStatus = "WebSocket: închis"
                    }
                }
            }
        )
    }

    private fun disconnectWebSocket() {
        isWsConnected = false
        webSocket?.close(1000, "Activity destroyed")
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
                "profile_set" -> {
                    val profile = json.get("profile")?.asString
                    selectedProfile = profile
                    status = "Profil activ: ${profile ?: "-"}"

                    if (profile != "epilepsy") {
                        resetEpilepsyEmergencyState()
                    }
                }

                "adhd_high_activity", "adhd_fidgeting" -> {
                    HapticManager.vibrate(this, HapticPattern.ANCHOR)
                    status = message

                    getNotificationObject(json)?.let { notif ->
                        showWatchNotification(
                            title = notif.get("title")?.asString ?: "Alertă",
                            body = notif.get("body")?.asString ?: ""
                        )
                    }
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

                    getNotificationObject(json)?.let { notif ->
                        showWatchNotification(
                            title = notif.get("title")?.asString ?: "Focus întrerupt",
                            body = notif.get("body")?.asString ?: ""
                        )
                    }
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
                }

                "hyperfocus_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    status = message

                    showWatchNotification(
                        title = "Hyperfocus detectat",
                        body = message
                    )
                }

                "medication_reminder" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    showMedicationDialog = true
                    status = "Reminder medicație"
                }

                "epilepsy_preictal" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    status = message

                    showWatchNotification(
                        title = "Stare de risc",
                        body = message
                    )
                }

                "epilepsy_warning" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    status = message

                    showWatchNotification(
                        title = "Avertisment",
                        body = message
                    )
                }

                "epilepsy_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)

                    val freq = json.get("freq")?.asDouble ?: 0.0
                    status = json.get("message")?.asString ?: "ALERTĂ EPILEPSIE ${freq} Hz"

                    showWatchNotification(
                        title = "Alertă siguranță",
                        body = status
                    )

                    handleEpilepsyAlert(freq)
                }

                "anxiety_alert", "stress_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    status = message

                    getNotificationObject(json)?.let { notif ->
                        showWatchNotification(
                            title = notif.get("title")?.asString ?: "Stres detectat",
                            body = notif.get("body")?.asString ?: "Respiră adânc. Ia o pauză."
                        )
                    }

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
                    val now = System.currentTimeMillis()

                    if (now - lastEpilepsyAlertAt > EPILEPSY_ALERT_WINDOW_MS) {
                        consecutiveEpilepsyAlerts = 0
                        emergencyTriggered = false
                        ttsMessageSpoken = false
                    }

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

    private fun handleEpilepsyAlert(freq: Double) {
        val now = System.currentTimeMillis()

        consecutiveEpilepsyAlerts = if (
            lastEpilepsyAlertAt > 0 &&
            now - lastEpilepsyAlertAt <= EPILEPSY_ALERT_WINDOW_MS
        ) {
            consecutiveEpilepsyAlerts + 1
        } else {
            1
        }

        lastEpilepsyAlertAt = now

        Log.d(
            "EMERGENCY",
            "Epilepsy alert count=$consecutiveEpilepsyAlerts, freq=$freq, emergencyTriggered=$emergencyTriggered"
        )

        if (consecutiveEpilepsyAlerts >= 2) {
            Log.d("EMERGENCY", "2 epilepsy alerts inside window. Emergency will start in ${EMERGENCY_DELAY_MS / 1000}s.")
            triggerEmergency(freq)
        }
    }

    private fun resetEpilepsyEmergencyState() {
        consecutiveEpilepsyAlerts = 0
        lastEpilepsyAlertAt = 0L
        emergencyTriggered = false
        ttsMessageSpoken = false
        ttsRepeatCount = 0

        emergencyJob?.cancel()
        emergencyJob = null

        Log.d("EMERGENCY", "Epilepsy emergency state reset")
    }

    private fun getNotificationObject(json: JsonObject): JsonObject? {
        return try {
            if (json.has("notification") && json.get("notification").isJsonObject) {
                json.getAsJsonObject("notification")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showWatchNotification(title: String, body: String) {
        alertTitle = title
        alertBody = body

        alertDismissJob?.cancel()
        alertDismissJob = lifecycleScope.launch {
            delay(4000)
            alertTitle = null
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("LOCATION", "ACCESS_FINE_LOCATION permission not granted")
            return
        }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    10_000L,
                    10f,
                    locationListener
                )

                locationManager.getLastKnownLocation(provider)?.let {
                    lastLocation = it
                    Log.d(
                        "LOCATION",
                        "Last known location from $provider: ${it.latitude}, ${it.longitude}"
                    )
                }
            } catch (e: Exception) {
                Log.w("LOCATION", "Could not request location from $provider: ${e.message}")
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
        if (emergencyTriggered) {
            Log.d("EMERGENCY", "Emergency already triggered, skipping duplicate.")
            return
        }

        emergencyTriggered = true
        ttsMessageSpoken = false
        ttsRepeatCount = 0

        Log.d("EMERGENCY", "Emergency trigger started. Waiting ${EMERGENCY_DELAY_MS / 1000}s before SMS/call.")

        emergencyJob?.cancel()
        emergencyJob = lifecycleScope.launch {
            delay(EMERGENCY_DELAY_MS)

            val lat = lastLocation?.latitude ?: 0.0
            val lon = lastLocation?.longitude ?: 0.0

            Log.d("EMERGENCY", "Sending SMS and starting call now. lat=$lat lon=$lon freq=$freq")

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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("EMERGENCY", "CALL_PHONE permission not granted")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)

            Log.d("EMERGENCY", "Emergency call intent started")
        } catch (e: Exception) {
            Log.e("EMERGENCY", "Call failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("EMERGENCY", "READ_PHONE_STATE permission not granted")
            return
        }

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneStateListener() {
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
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
        feedStressPredictor()

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

    private fun feedStressPredictor() {
        val acc = accelerometer ?: return
        if (acc.size < 3) return

        val hr = heartRate?.toFloat() ?: 0f

        // Dacă reactivezi predictorul local Android, îl chemi aici.
        // stressPredictor.update(acc[0], acc[1], acc[2], hr)
    }

    private fun stopStressLoop() {
        stressJob?.cancel()
        stressJob = null
    }

    private fun maybeShowStressAlert(score: Float) {
        val now = System.currentTimeMillis()

        if (now - lastStressAlertAt < STRESS_COOLDOWN_MS) return

        lastStressAlertAt = now

        HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)

        status = "Stres detectat (${"%.0f".format(score * 100)}%)"

        showWatchNotification(
            title = "Stres detectat",
            body = "Respiră adânc. Ia o pauză de un minut."
        )
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

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
        }

        if (
            ContextCompat.checkSelfPermission(this, HR_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(HR_PERMISSION)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isEmpty()) {
            status = "Permisiuni acceptate"
            startPassiveMonitoring()
            registerPhoneStateListener()
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
                registerPhoneStateListener()
                startLocationUpdates()
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
        if (areAndroidSensorsStarted) return

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

        areAndroidSensorsStarted = true
    }

    private fun stopAndroidSensors() {
        if (!areAndroidSensorsStarted) return

        sensorManager.unregisterListener(this)
        registeredAndroidSensors.clear()
        areAndroidSensorsStarted = false
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