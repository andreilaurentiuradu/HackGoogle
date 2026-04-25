package ro.pub.cs.systems.eim.googlehack.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.*
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.*
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

// TODO: Adaugă în AndroidManifest.xml: <uses-permission android:name="android.permission.VIBRATE" />
// TODO: Creează HapticManager.kt cu pattern-uri vibrate: ANCHOR, PRE_FOCUS_BREATH, FOCUS_EXIT, HYPERFOCUS_SOFT, MEDICATION

data class HealthMetric(
    val name: String,
    val value: String = "N/A"
)

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
                HealthScreen(
                    status = status,
                    wsStatus = wsStatus,
                    heartRate = heartRate,
                    activityState = activityState,
                    accelerometer = accelerometer,
                    gyroscope = gyroscope,
                    light = light
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

                    Log.d("WS_HEALTH", "Connected to $SERVER_URL")

                    runOnUiThread {
                        wsStatus = "WebSocket: conectat"
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WS_HEALTH", "Server response: $text")
                    runOnUiThread {
                        wsStatus = "Server: $text"
                        // TODO: Parsează JSON-ul și rutează după câmpul "state":
                        // "adhd_high_activity" → HapticManager.vibrate(ANCHOR)
                        // "pre_focus_ritual"   → citește breaths=3, inhale_ms=5000, exhale_ms=5000
                        //                        bucla pentru fiecare respirație:
                        //                          afișează "Inspiră ████████████"
                        //                          vibrate(inhale_ms)          ← vibrație continuă 5s
                        //                          afișează "Expiră  ████████████"
                        //                          vibrate(exhale_ms)          ← vibrație continuă 5s
                        //                        după cele 3 respirații (30s total) afișează "Gata? ✓"
                        //                        la tap ✓ → trimite {"action":"focus_ready"}
                        // "focus_started"      → pornește timer-ul pe ecran
                        //                        TODO: activează Do Not Disturb pe durata sesiunii:
                        //                          val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        //                          if (nm.isNotificationPolicyAccessGranted)
                        //                              nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                        //                        TODO: dezactivează DND la "focus_complete" sau "focus_end"
                        //                              nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        //                        TODO: adaugă în AndroidManifest.xml:
                        //                              <uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
                        // "focus_exit"         → HapticManager.vibrate(FOCUS_EXIT) + afișează exits_count din JSON
                        // "focus_complete"     → afișează ecran raport cu:
                        //                        duration_minutes, quality_percent, exits_count,
                        //                        acc_stability_percent, hr_variability
                        // "hyperfocus_alert"   → HapticManager.vibrate(HYPERFOCUS_SOFT) + Toast cu message
                        // "medication_reminder"→ HapticManager.vibrate(MEDICATION) +
                        //                        Dialog "Medicație? ✓/✗" →
                        //                        dacă ✓: trimite {"action":"medication_taken","time":...}
                        //                        dacă ✗: trimite {"action":"medication_skipped","time":...}
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isWsConnected = false
                    this@MainActivity.webSocket = null

                    Log.e("WS_HEALTH", "WebSocket error: ${t.message}", t)

                    runOnUiThread {
                        wsStatus = "WebSocket: eroare (${t.message})"
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

    private fun startSendingLoop() {
        if (sendingJob != null) return

        sendingJob = lifecycleScope.launch {
            while (true) {
                sendHealthData()
                delay(1000)
            }
        }
    }

    private fun stopSendingLoop() {
        sendingJob?.cancel()
        sendingJob = null
    }

    // TODO: Adaugă fun sendAction(action: String, extra: Map<String,Any> = emptyMap())
    //       care trimite {"action": action, ...extra} via webSocket?.send(gson.toJson(...))
    // TODO: Adaugă fun startFocusSession() → sendAction("focus_start")
    // TODO: Adaugă fun endFocusSession()   → sendAction("focus_end")

    private fun sendHealthData() {
        if (!isWsConnected || webSocket == null) {
            Log.d("WS_HEALTH", "Skip send: websocket not connected")
            return
        }

        val payload = mapOf(
            "heart_rate" to heartRate,
            "accelerometer" to accelerometer,
            "gyroscope" to gyroscope,
            "light" to light,
            "activity_state" to activityState,
            "battery" to getBatteryPayload(),
            "timestamp" to System.currentTimeMillis()
        )

        val json = gson.toJson(payload)
        val sent = webSocket?.send(json) ?: false

        Log.d("WS_HEALTH", "Sending: $json")

        runOnUiThread {
            wsStatus = if (sent) "WebSocket: date trimise" else "WebSocket: send failed"
        }
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
                    status = "Heart rate pasiv nesuportat"
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

@Composable
fun HealthScreen(
    status: String,
    wsStatus: String,
    heartRate: Double?,
    activityState: String?,
    accelerometer: List<Float>?,
    gyroscope: List<Float>?,
    light: Float?
) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ML Sensor Stream", style = MaterialTheme.typography.title3, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            // TODO: Adaugă buton "Start Focus" care apelează startFocusSession()
            //       și "Stop Focus" când sesiunea e activă → endFocusSession()
            // TODO: Adaugă ecran FocusSessionScreen cu timer + raport calitate la final
            // TODO: Adaugă ecran MedicationScreen pentru setarea orelor reminder
            //       → POST /medication/schedule cu lista de ore
            // TODO: Adaugă ecran ReportScreen → GET /report/daily afișat la cerere

            MetricLine("status", status)
            MetricLine("ws", wsStatus)
            MetricLine("heart_rate", heartRate?.toString() ?: "N/A")
            MetricLine("activity", activityState ?: "N/A")
            MetricLine("light", light?.toString() ?: "N/A")
            MetricLine("accelerometer", accelerometer?.joinToString() ?: "N/A")
            MetricLine("gyroscope", gyroscope?.joinToString() ?: "N/A")
        }
    }
}

@Composable
fun MetricLine(name: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(text = name, style = MaterialTheme.typography.caption2, color = Color.White)
        Text(text = value, style = MaterialTheme.typography.caption1, color = Color.Green)
    }
}