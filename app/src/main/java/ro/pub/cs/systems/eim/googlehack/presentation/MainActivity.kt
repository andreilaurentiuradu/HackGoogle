package ro.pub.cs.systems.eim.googlehack.presentation

import android.Manifest
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
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.UserActivityInfo
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.google.gson.Gson
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

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

    private lateinit var sensorManager: SensorManager
    private val registeredAndroidSensors = mutableListOf<Sensor>()

    private var rawSensors by mutableStateOf<Map<String, List<Float>>>(emptyMap())

    private val passiveClient by lazy {
        HealthServices.getClient(this).passiveMonitoringClient
    }

    private var status by mutableStateOf("Pornesc...")
    private var wsStatus by mutableStateOf("WebSocket: neconectat")

    private var metrics by mutableStateOf(
        mapOf(
            "Heart rate" to HealthMetric("Heart rate"),
            "Steps daily" to HealthMetric("Steps daily"),
            "Calories daily" to HealthMetric("Calories daily"),
            "Distance daily" to HealthMetric("Distance daily"),
            "Floors daily" to HealthMetric("Floors daily"),
            "Activity state" to HealthMetric("Activity state")
        )
    )

    private var isPassiveRegistered = false

    private val passiveDataTypes = setOf(
        DataType.HEART_RATE_BPM,
        DataType.STEPS_DAILY,
        DataType.CALORIES_DAILY,
        DataType.DISTANCE_DAILY,
        DataType.FLOORS_DAILY
    )

    private val passiveCallback = object : PassiveListenerCallback {

        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            Log.d("PASSIVE_HEALTH", "New data: $dataPoints")

            updateFromDataPoints("Heart rate", dataPoints, DataType.HEART_RATE_BPM, "bpm")
            updateFromDataPoints("Steps daily", dataPoints, DataType.STEPS_DAILY, "steps")
            updateFromDataPoints("Calories daily", dataPoints, DataType.CALORIES_DAILY, "kcal")
            updateFromDataPoints("Distance daily", dataPoints, DataType.DISTANCE_DAILY, "m")
            updateFromDataPoints("Floors daily", dataPoints, DataType.FLOORS_DAILY, "floors")

            sendHealthData()
        }

        override fun onUserActivityInfoReceived(info: UserActivityInfo) {
            Log.d("PASSIVE_HEALTH", "Activity info: $info")

            runOnUiThread {
                updateMetric(
                    "Activity state",
                    info.userActivityState.toString(),
                    shouldSend = true
                )
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
                    metrics = metrics.values.toList(),
                    rawSensors = rawSensors
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

        if (hasPermissions()) {
            startPassiveMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()

        stopPassiveMonitoring()
        stopAndroidSensors()
        disconnectWebSocket()
    }

    override fun onDestroy() {
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
                    Log.d("WS_HEALTH", "Connected to $SERVER_URL")

                    isWsConnected = true
                    this@MainActivity.webSocket = webSocket

                    runOnUiThread {
                        wsStatus = "WebSocket: conectat"
                    }

                    sendHealthData()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WS_HEALTH", "Server response: $text")

                    runOnUiThread {
                        wsStatus = "Server: $text"
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WS_HEALTH", "WebSocket error: ${t.message}", t)

                    isWsConnected = false
                    this@MainActivity.webSocket = null

                    runOnUiThread {
                        wsStatus = "WebSocket: eroare (${t.message})"
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WS_HEALTH", "Closed: code=$code reason=$reason")

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

    private fun sendHealthData() {
        if (!isWsConnected || webSocket == null) {
            Log.e("WS_HEALTH", "Cannot send: websocket not connected")

            runOnUiThread {
                wsStatus = "WebSocket: neconectat"
            }
            return
        }

        val payload = mapOf(
            "health_services" to mapOf(
                "heart_rate" to cleanMetric(metrics["Heart rate"]?.value),
                "steps_daily" to cleanMetric(metrics["Steps daily"]?.value),
                "calories_daily" to cleanMetric(metrics["Calories daily"]?.value),
                "distance_daily" to cleanMetric(metrics["Distance daily"]?.value),
                "floors_daily" to cleanMetric(metrics["Floors daily"]?.value),
                "activity_state" to metrics["Activity state"]?.value
            ),

            "raw_sensors" to rawSensors,

            "battery" to getBatteryPayload(),

            "device" to mapOf(
                "source" to "pixel_watch",
                "timestamp" to System.currentTimeMillis()
            )
        )

        val json = gson.toJson(payload)

        Log.d("WS_HEALTH", "Sending: $json")

        val sent = webSocket?.send(json) ?: false

        runOnUiThread {
            wsStatus = if (sent) {
                "WebSocket: date trimise"
            } else {
                "WebSocket: send failed"
            }
        }
    }

    private fun cleanMetric(value: String?): Double? {
        if (value == null || value == "N/A") return null

        return value
            .split(" ")
            .firstOrNull()
            ?.toDoubleOrNull()
    }

    private fun getBatteryPayload(): Map<String, Any?> {
        val batteryIntent = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

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
            "is_charging" to isCharging,
            "raw_level" to level,
            "raw_scale" to scale,
            "status" to status,
            "plugged" to plugged
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

        Log.d("PASSIVE_HEALTH", "heartGranted=$heartGranted activityGranted=$activityGranted")

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
            Log.d("PASSIVE_HEALTH", "permissions=${permissions.toList()}")
            Log.d("PASSIVE_HEALTH", "grantResults=${grantResults.toList()}")

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
                status = "Verific Passive Capabilities..."

                val capabilities = passiveClient.getCapabilitiesAsync().await()
                val supported = capabilities.supportedDataTypesPassiveMonitoring

                Log.d("PASSIVE_HEALTH", "Supported passive types: $supported")

                val supportedTypes = passiveDataTypes.filter { it in supported }.toSet()

                Log.d("PASSIVE_HEALTH", "Registering passive types: $supportedTypes")

                if (supportedTypes.isEmpty()) {
                    status = "Niciun DataType pasiv suportat"
                    return@launch
                }

                val config = PassiveListenerConfig.builder()
                    .setDataTypes(supportedTypes)
                    .setShouldUserActivityInfoBeRequested(true)
                    .build()

                passiveClient.setPassiveListenerCallback(config, passiveCallback)

                isPassiveRegistered = true
                status = "Passive Monitoring activ"

            } catch (e: Exception) {
                status = "Eroare Passive"
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
                status = "Passive Monitoring oprit"
                Log.d("PASSIVE_HEALTH", "Passive callback cleared")
            } catch (e: Exception) {
                Log.e("PASSIVE_HEALTH", "Clear passive error", e)
            }
        }
    }

    private fun updateFromDataPoints(
        metricName: String,
        container: DataPointContainer,
        dataType: DeltaDataType<*, *>,
        unit: String
    ) {
        val points = container.getData(dataType)
        val latest = points.lastOrNull() ?: return

        val value = when (latest) {
            is SampleDataPoint<*> -> latest.value.toString()
            is IntervalDataPoint<*> -> latest.value.toString()
            else -> latest.toString()
        }

        runOnUiThread {
            updateMetric(metricName, "$value $unit", shouldSend = false)
        }
    }

    private fun updateMetric(
        name: String,
        value: String,
        shouldSend: Boolean = false
    ) {
        metrics = metrics.toMutableMap().apply {
            this[name] = HealthMetric(name, value)
        }

        if (shouldSend) {
            sendHealthData()
        }
    }

    private fun startAndroidSensors() {
        registeredAndroidSensors.clear()

        val sensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_HEART_BEAT
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

                Log.d(
                    "RAW_SENSORS",
                    "Registered: ${sensor.name}, type=${sensor.type}, vendor=${sensor.vendor}"
                )
            } else {
                Log.d("RAW_SENSORS", "Not available: type=$type")
            }
        }
    }

    private fun stopAndroidSensors() {
        sensorManager.unregisterListener(this)
        registeredAndroidSensors.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = sensorName(event.sensor.type)

        rawSensors = rawSensors.toMutableMap().apply {
            this[key] = event.values.toList()
        }

        Log.d("RAW_SENSORS", "$key = ${event.values.toList()}")

        sendHealthData()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("RAW_SENSORS", "Accuracy changed: ${sensor?.name}, accuracy=$accuracy")
    }

    private fun sensorName(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
            Sensor.TYPE_LIGHT -> "light"
            Sensor.TYPE_PRESSURE -> "pressure"
            Sensor.TYPE_PROXIMITY -> "proximity"
            Sensor.TYPE_GRAVITY -> "gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
            Sensor.TYPE_STEP_COUNTER -> "step_counter"
            Sensor.TYPE_STEP_DETECTOR -> "step_detector"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "relative_humidity"
            Sensor.TYPE_HEART_BEAT -> "heart_beat"
            else -> "sensor_$type"
        }
    }
}

@Composable
fun HealthScreen(
    status: String,
    wsStatus: String,
    metrics: List<HealthMetric>,
    rawSensors: Map<String, List<Float>>
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
            Text(
                text = "All Sensors",
                style = MaterialTheme.typography.title3,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.caption3,
                color = Color.Gray
            )

            Text(
                text = wsStatus,
                style = MaterialTheme.typography.caption3,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            metrics.forEach {
                MetricLine(it.name, it.value)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Raw sensors",
                style = MaterialTheme.typography.caption1,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            rawSensors.forEach { (name, values) ->
                MetricLine(
                    name = name,
                    value = values.joinToString(
                        prefix = "[",
                        postfix = "]",
                        separator = ", "
                    ) { "%.2f".format(it) }
                )

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun MetricLine(
    name: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.caption2,
            color = Color.White
        )

        Text(
            text = value,
            style = MaterialTheme.typography.caption1,
            color = Color.Green
        )
    }
}