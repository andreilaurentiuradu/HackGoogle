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
import android.os.BatteryManager
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), SensorEventListener {

    private val HR_PERMISSION = "android.permission.health.READ_HEART_RATE"
    private val SERVER_URL = "ws://10.200.22.124:8000/ws/health"

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
                    onStartFocus = { startFocusSession() },
                    onStopFocus = { endFocusSession() },
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

        if (hasPermissions()) {
            startPassiveMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        stopSendingLoop()
        stopPassiveMonitoring()
        stopAndroidSensors()
        disconnectWebSocket()
    }

    override fun onDestroy() {
        stopSendingLoop()
        stopPassiveMonitoring()
        stopAndroidSensors()
        disconnectWebSocket()
        wsClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

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

            wsStatus = "Server state: $state"

            when (state) {
                "adhd_high_activity", "adhd_fidgeting" -> {
                    HapticManager.vibrate(this, HapticPattern.ANCHOR)
                    status = json.get("message")?.asString ?: "Fidgeting detectat"
                }

                "pre_focus_ritual" -> {
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
                            HapticManager.vibrateDuration(this@MainActivity, inhaleMs)
                            delay(inhaleMs)

                            breathingPhase = "Exhale"
                            status = "Expiră"
                            HapticManager.vibrateDuration(this@MainActivity, exhaleMs)
                            delay(exhaleMs)
                        }

                        breathingPhase = "Ready"
                        status = "Focus ready"

                        delay(800)

                        isBreathingActive = false
                        sendAction("focus_ready")
                    }
                }

                "focus_started" -> {
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
                    isBreathingActive = false
                    isFocusActive = false
                    stopFocusTimer()
                    disableDnd()
                    focusReport = json
                    status = "Focus finalizat"
                }

                "hyperfocus_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    val message = json.get("message")?.asString ?: "Pauză recomandată"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    status = message
                }

                "medication_reminder" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    showMedicationDialog = true
                    status = "Reminder medicație"
                }

                "epilepsy_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.MEDICATION)
                    status = json.get("message")?.asString ?: "Alertă lumină"
                }

                "anxiety_alert" -> {
                    HapticManager.vibrate(this, HapticPattern.HYPERFOCUS_SOFT)
                    status = json.get("message")?.asString ?: "Puls ridicat"
                }

                else -> {
                    status = json.get("message")?.asString ?: "Totul este în regulă"
                }
            }

        } catch (e: Exception) {
            Log.e("WS_HEALTH", "Invalid server JSON: $text", e)
            wsStatus = "Server JSON invalid"
        }
    }

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

    private fun startFocusSession() {
        sendAction("focus_start")
    }

    private fun endFocusSession() {
        sendAction("focus_end")
        isBreathingActive = false
        isFocusActive = false
        stopFocusTimer()
        disableDnd()
    }

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
        if (hasPermissions()) {
            status = "Permisiuni acceptate"
            startPassiveMonitoring()
            return
        }

        status = "Cer permisiuni..."

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.BODY_SENSORS,
                HR_PERMISSION
            ),
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
    }

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