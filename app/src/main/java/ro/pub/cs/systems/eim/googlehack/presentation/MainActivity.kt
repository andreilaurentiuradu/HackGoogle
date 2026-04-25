package ro.pub.cs.systems.eim.googlehack.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import androidx.wear.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.IntervalDataPoint

data class HealthMetric(
    val name: String,
    val value: String = "N/A"
)

class MainActivity : ComponentActivity() {

    private val HR_PERMISSION = "android.permission.health.READ_HEART_RATE"

    private val passiveClient by lazy {
        HealthServices.getClient(this).passiveMonitoringClient
    }

    private var status by mutableStateOf("Pornesc Passive Monitoring...")
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

    private val dataTypes = setOf(
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
        }

        override fun onUserActivityInfoReceived(info: UserActivityInfo) {
            Log.d("PASSIVE_HEALTH", "Activity info: $info")

            runOnUiThread {
                updateMetric(
                    "Activity state",
                    info.userActivityState.toString()
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                PassiveHealthScreen(
                    status = status,
                    metrics = metrics.values.toList()
                )
            }
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()

        if (hasPermissions()) {
            startPassiveMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPassiveMonitoring()
    }

    override fun onDestroy() {
        stopPassiveMonitoring()
        super.onDestroy()
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

                val supportedTypes = dataTypes.filter { it in supported }.toSet()

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
            is androidx.health.services.client.data.SampleDataPoint<*> -> {
                latest.value.toString()
            }

            is androidx.health.services.client.data.IntervalDataPoint<*> -> {
                latest.value.toString()
            }

            else -> {
                latest.toString()
            }
        }

        runOnUiThread {
            updateMetric(metricName, "$value $unit")
        }
    }

    private fun updateMetric(name: String, value: String) {
        metrics = metrics.toMutableMap().apply {
            this[name] = HealthMetric(name, value)
        }
    }
}

@Composable
fun PassiveHealthScreen(
    status: String,
    metrics: List<HealthMetric>
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
                text = "Passive Health",
                style = MaterialTheme.typography.title3,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.caption3,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            metrics.forEach {
                MetricLine(it)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun MetricLine(metric: HealthMetric) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = metric.name,
            style = MaterialTheme.typography.caption2,
            color = Color.White
        )

        Text(
            text = metric.value,
            style = MaterialTheme.typography.caption1,
            color = Color.Green
        )
    }
}