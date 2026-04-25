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
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val HR_PERMISSION = "android.permission.health.READ_HEART_RATE"

    private val measureClient by lazy {
        HealthServices.getClient(this).measureClient
    }

    private var heartRate by mutableStateOf<Double?>(null)
    private var status by mutableStateOf("Pornesc...")

    private var isRegistered = false
    private var permissionsRequested = false

    private val measureCallback = object : MeasureCallback {

        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            Log.d("HR_REAL", "Availability: $availability")
            runOnUiThread {
                status = "Availability: $availability"
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val values = data.getData(DataType.HEART_RATE_BPM)
            val latest = values.lastOrNull()?.value

            Log.d("HR_REAL", "RAW HR DATA: $values")
            Log.d("HR_REAL", "LATEST HR: $latest")

            if (latest != null && latest > 0.0) {
                runOnUiThread {
                    heartRate = latest
                    status = "Puls real primit"
                }

                Log.d("HR_REAL", "PULS REAL: $latest BPM")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WearApp(
                    heartRate = heartRate,
                    status = status
                )
            }
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()

        if (hasHeartPermission()) {
            startHeartRate()
        } else if (!permissionsRequested) {
            requestPermissionsIfNeeded()
        }
    }

    override fun onPause() {
        stopHeartRate()
        super.onPause()
    }

    override fun onDestroy() {
        stopHeartRate()
        super.onDestroy()
    }

    private fun hasHeartPermission(): Boolean {
        val bodySensorsGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) ==
                    PackageManager.PERMISSION_GRANTED

        val readHeartRateGranted =
            ContextCompat.checkSelfPermission(this, HR_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED

        Log.d("HR_REAL", "BODY_SENSORS granted = $bodySensorsGranted")
        Log.d("HR_REAL", "READ_HEART_RATE granted = $readHeartRateGranted")

        return bodySensorsGranted || readHeartRateGranted
    }

    private fun requestPermissionsIfNeeded() {
        if (hasHeartPermission()) {
            status = "Permisiuni deja acceptate"
            startHeartRate()
            return
        }

        permissionsRequested = true
        status = "Cer permisiuni..."

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                HR_PERMISSION
            ),
            100
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            Log.d("HR_REAL", "Permissions result: ${permissions.toList()}")
            Log.d("HR_REAL", "Grant results: ${grantResults.toList()}")

            if (hasHeartPermission()) {
                status = "Permisiuni acceptate"
                startHeartRate()
            } else {
                status = "Permisiune refuzată"
                Log.e("HR_REAL", "Permisiune refuzată real")
            }
        }
    }

    private fun startHeartRate() {
        if (isRegistered) return

        lifecycleScope.launch {
            try {
                status = "Verific senzor puls..."

                val capabilities = measureClient.getCapabilitiesAsync().await()

                Log.d(
                    "HR_REAL",
                    "Supported measure types: ${capabilities.supportedDataTypesMeasure}"
                )

                if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesMeasure) {
                    status = "HEART_RATE_BPM nu e suportat"
                    Log.e("HR_REAL", "HEART_RATE_BPM nu e suportat")
                    return@launch
                }

                measureClient.registerMeasureCallback(
                    DataType.HEART_RATE_BPM,
                    measureCallback
                )

                isRegistered = true
                status = "Aștept puls real..."

                Log.d("HR_REAL", "MeasureCallback registered")

            } catch (e: Exception) {
                status = "Eroare Health Services"
                Log.e("HR_REAL", "Eroare Health Services", e)
            }
        }
    }

    private fun stopHeartRate() {
        if (!isRegistered) return

        lifecycleScope.launch {
            try {
                measureClient.unregisterMeasureCallbackAsync(
                    DataType.HEART_RATE_BPM,
                    measureCallback
                ).await()

                isRegistered = false
                status = "Măsurare oprită"

                Log.d("HR_REAL", "MeasureCallback unregistered")

            } catch (e: Exception) {
                Log.e("HR_REAL", "Unregister error", e)
            }
        }
    }
}

@Composable
fun WearApp(
    heartRate: Double?,
    status: String
) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "❤️",
                    style = MaterialTheme.typography.display1
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = heartRate?.toInt()?.toString() ?: "--",
                    style = MaterialTheme.typography.display2,
                    color = Color.Red
                )

                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.caption1,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = status,
                    style = MaterialTheme.typography.caption3,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}