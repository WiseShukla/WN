package com.project.wificollector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private var wifiManager: WifiManager? = null
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private var etLocationName: EditText? = null
    private var etX: EditText? = null
    private var etY: EditText? = null
    private var btnScan: Button? = null
    private var btnResetSteps: Button? = null
    private var btnViewCSV: Button? = null
    private var btnExportCSV: Button? = null
    private var tvStatus: TextView? = null
    private var tvCount: TextView? = null
    private var tvWifiList: TextView? = null
    private var tvIMUStatus: TextView? = null
    private var tvSensorType: TextView? = null

    private var csvFile: File? = null
    private var imuCsvFile: File? = null

    private var currentHeading = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)

    private var stepCount = 0
    private var initialStepCounterValue = -1L
    private var sensorType = "Initializing..."
    private var useAccelerometerFallback = false

    private var lastStepTime = 0L
    private var wasAboveThreshold = false

    private var isScanning = false
    private var scanCount = 0
    private var targetScans = 10
    private var successfulScans = 0
    private var currentLocation = ""
    private var currentX = ""
    private var currentY = ""
    private var stepsSinceLastScan = 0

    private val handler = Handler(Looper.getMainLooper())
    private var lastDisplayedSteps = -1
    private var lastDisplayedHeading = -1

    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            val currentSteps = stepCount
            val currentHead = currentHeading.toInt()

            if (currentSteps != lastDisplayedSteps || currentHead != lastDisplayedHeading) {
                lastDisplayedSteps = currentSteps
                lastDisplayedHeading = currentHead
                forceUpdateDisplay()
            }

            handler.postDelayed(this, 100)
        }
    }

    private var wifiScanReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            csvFile = File(getExternalFilesDir(null), "wifi_fingerprints.csv")
            imuCsvFile = File(getExternalFilesDir(null), "imu_data.csv")

            initializeCSVFiles()
            createUI()

            wifiScanReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        if (isScanning) processScanResults(success)
                    }
                }
            }

            checkAndRequestPermissions()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()

        try {
            wifiScanReceiver?.let { receiver ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        receiver,
                        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    registerReceiver(
                        receiver,
                        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    )
                }
            }

            startAllSensors()
            handler.post(uiUpdateRunnable)
        } catch (e: Exception) {
            Toast.makeText(this, "Resume error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            wifiScanReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {}

        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {}

        handler.removeCallbacks(uiUpdateRunnable)
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (needed.isNotEmpty()) {
            tvStatus?.text = "Requesting permissions..."
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            onPermissionsReady()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show()
            }

            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        startAllSensors()
        checkLocationServices()
        handler.post(uiUpdateRunnable)
    }

    private fun startAllSensors() {
        try {
            sensorManager?.unregisterListener(this)

            var stepSensorStarted = false

            val stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (stepDetector != null) {
                val registered = sensorManager?.registerListener(
                    this,
                    stepDetector,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0
                ) ?: false
                if (registered) {
                    sensorType = "Step Detector"
                    stepSensorStarted = true
                    useAccelerometerFallback = false
                }
            }

            if (!stepSensorStarted) {
                val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (stepCounter != null) {
                    val registered = sensorManager?.registerListener(
                        this,
                        stepCounter,
                        SensorManager.SENSOR_DELAY_FASTEST,
                        0
                    ) ?: false
                    if (registered) {
                        sensorType = "Step Counter"
                        stepSensorStarted = true
                        useAccelerometerFallback = false
                    }
                }
            }

            if (!stepSensorStarted) {
                sensorType = "Accelerometer"
                useAccelerometerFallback = true
            }

            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, 0)
            }

            val mag = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (mag != null) {
                sensorManager?.registerListener(this, mag, SensorManager.SENSOR_DELAY_FASTEST, 0)
            }

            tvSensorType?.text = "Sensor: $sensorType"
            tvStatus?.text = "Ready"

        } catch (e: Exception) {
            Toast.makeText(this, "Sensor error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    stepCount++
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values[0].toLong()
                    if (initialStepCounterValue == -1L) {
                        initialStepCounterValue = totalSteps - stepCount
                    }
                    stepCount = (totalSteps - initialStepCounterValue).toInt()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.clone()
                    if (useAccelerometerFallback) {
                        detectStepFromAccelerometer(event.values)
                    }
                    updateHeading()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    geomagnetic = event.values.clone()
                    updateHeading()
                }
            }
        } catch (e: Exception) {}
    }

    private fun detectStepFromAccelerometer(values: FloatArray) {
        val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        val currentTime = System.currentTimeMillis()

        val threshold = 11.0f
        val isAbove = magnitude > threshold

        if (isAbove && !wasAboveThreshold && (currentTime - lastStepTime) > 350) {
            stepCount++
            lastStepTime = currentTime
        }

        wasAboveThreshold = isAbove
    }

    private fun updateHeading() {
        if (gravity.isNotEmpty() && geomagnetic.isNotEmpty()) {
            val R = FloatArray(9)
            val I = FloatArray(9)

            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                currentHeading = Math.toDegrees(orientation[0].toDouble())
                if (currentHeading < 0) currentHeading += 360
            }
        }
    }

    private fun forceUpdateDisplay() {
        val dir = when {
            currentHeading < 45 || currentHeading >= 315 -> "N"
            currentHeading < 135 -> "E"
            currentHeading < 225 -> "S"
            else -> "W"
        }
        tvIMUStatus?.text = "Steps: $stepCount | ${currentHeading.toInt()}° ($dir)"
    }

    private fun resetSteps() {
        stepCount = 0
        initialStepCounterValue = -1L
        lastDisplayedSteps = -1
        forceUpdateDisplay()
        Toast.makeText(this, "Steps reset", Toast.LENGTH_SHORT).show()
    }

    private fun checkLocationServices(): Boolean {
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
        val isNetworkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false

        if (!isGpsEnabled && !isNetworkEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Location Required")
                .setMessage("WiFi scanning needs Location ON")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        return true
    }

    private fun initializeCSVFiles() {
        try {
            csvFile?.let { file ->
                if (!file.exists()) {
                    FileWriter(file, false).use { writer ->
                        writer.write("Timestamp,Location,X,Y,BSSID,SSID,RSSI,Frequency\n")
                    }
                }
            }

            imuCsvFile?.let { file ->
                if (!file.exists()) {
                    FileWriter(file, false).use { writer ->
                        writer.write("Timestamp,Location,X,Y,Steps,Heading\n")
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun createUI() {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        // Title
        val title = TextView(this)
        title.text = "WiFi Data Collector"
        title.textSize = 24f
        title.gravity = Gravity.CENTER
        layout.addView(title)

        addSpace(layout, 20)

        // Sensor info
        tvSensorType = TextView(this)
        tvSensorType?.text = "Sensor: Initializing..."
        tvSensorType?.textSize = 12f
        layout.addView(tvSensorType)

        addSpace(layout, 16)

        // IMU Status
        tvIMUStatus = TextView(this)
        tvIMUStatus?.text = "Steps: 0 | 0° (N)"
        tvIMUStatus?.textSize = 20f
        tvIMUStatus?.gravity = Gravity.CENTER
        tvIMUStatus?.setPadding(16, 24, 16, 24)
        tvIMUStatus?.setBackgroundColor(0xFFFF9800.toInt())
        tvIMUStatus?.setTextColor(0xFFFFFFFF.toInt())
        layout.addView(tvIMUStatus)

        addSpace(layout, 12)

        // Reset button
        btnResetSteps = Button(this)
        btnResetSteps?.text = "RESET STEPS"
        btnResetSteps?.setOnClickListener { resetSteps() }
        layout.addView(btnResetSteps)

        addSpace(layout, 20)

        // Location input
        val lblLocation = TextView(this)
        lblLocation.text = "Location Name:"
        layout.addView(lblLocation)

        etLocationName = EditText(this)
        etLocationName?.hint = "e.g., elevator_exit"
        layout.addView(etLocationName)

        addSpace(layout, 8)

        // X input
        val lblX = TextView(this)
        lblX.text = "X Coordinate:"
        layout.addView(lblX)

        etX = EditText(this)
        etX?.hint = "0"
        etX?.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        layout.addView(etX)

        addSpace(layout, 8)

        // Y input
        val lblY = TextView(this)
        lblY.text = "Y Coordinate:"
        layout.addView(lblY)

        etY = EditText(this)
        etY?.hint = "0"
        etY?.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        layout.addView(etY)

        addSpace(layout, 16)

        // WiFi list
        val lblWifi = TextView(this)
        lblWifi.text = "WiFi Networks:"
        layout.addView(lblWifi)

        tvWifiList = TextView(this)
        tvWifiList?.text = "Tap SCAN to detect..."
        tvWifiList?.maxLines = 4
        tvWifiList?.setPadding(12, 12, 12, 12)
        tvWifiList?.setBackgroundColor(0xFFEEEEEE.toInt())
        layout.addView(tvWifiList)

        addSpace(layout, 12)

        // Status
        tvStatus = TextView(this)
        tvStatus?.text = "Initializing..."
        tvStatus?.textSize = 14f
        layout.addView(tvStatus)

        tvCount = TextView(this)
        tvCount?.text = "Total samples: 0"
        layout.addView(tvCount)

        addSpace(layout, 16)

        // Scan button
        btnScan = Button(this)
        btnScan?.text = "SCAN HERE (10X)"
        btnScan?.textSize = 18f
        btnScan?.setOnClickListener {
            val location = etLocationName?.text?.toString()?.trim() ?: ""
            val x = etX?.text?.toString()?.trim() ?: ""
            val y = etY?.text?.toString()?.trim() ?: ""

            if (location.isEmpty() || x.isEmpty() || y.isEmpty()) {
                Toast.makeText(this, "Fill all fields!", Toast.LENGTH_SHORT).show()
            } else {
                collectData(location, x, y)
            }
        }
        layout.addView(btnScan)

        addSpace(layout, 12)

        // View button
        btnViewCSV = Button(this)
        btnViewCSV?.text = "VIEW INFO"
        btnViewCSV?.setOnClickListener { viewCSVInfo() }
        layout.addView(btnViewCSV)

        addSpace(layout, 8)

        // Share button
        btnExportCSV = Button(this)
        btnExportCSV?.text = "SHARE CSV"
        btnExportCSV?.setOnClickListener { shareCSVFiles() }
        layout.addView(btnExportCSV)

        addSpace(layout, 8)

        // Clear button
        val btnClear = Button(this)
        btnClear.text = "CLEAR ALL DATA"
        btnClear.setOnClickListener { clearAllData() }
        layout.addView(btnClear)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun addSpace(layout: LinearLayout, height: Int) {
        val space = View(this)
        space.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (height * resources.displayMetrics.density).toInt()
        )
        layout.addView(space)
    }

    private fun collectData(location: String, x: String, y: String) {
        if (wifiManager?.isWifiEnabled != true) {
            Toast.makeText(this, "Turn ON WiFi!", Toast.LENGTH_LONG).show()
            return
        }

        if (!checkLocationServices()) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions()
            return
        }

        stepsSinceLastScan = stepCount
        resetSteps()

        currentLocation = location
        currentX = x
        currentY = y

        scanCount = 0
        successfulScans = 0
        targetScans = 10
        isScanning = true

        btnScan?.isEnabled = false
        tvStatus?.text = "Scanning..."

        saveIMUData()
        startNextScan()
    }

    private fun startNextScan() {
        if (!isScanning || scanCount >= targetScans) {
            finishScanning()
            return
        }

        try {
            wifiManager?.startScan()
            handler.postDelayed({
                if (isScanning) processScanResults(false)
            }, 2000)
        } catch (e: Exception) {
            finishScanning()
        }
    }

    private fun processScanResults(freshResults: Boolean) {
        if (!isScanning) return

        try {
            val results = wifiManager?.scanResults ?: emptyList()

            if (results.isNotEmpty()) {
                successfulScans++

                val preview = results.take(3).joinToString("\n") {
                    "${if (it.SSID.isNullOrEmpty()) "Hidden" else it.SSID}: ${it.level}dBm"
                }
                tvWifiList?.text = "Found ${results.size}:\n$preview"

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                csvFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        results.forEach { ap ->
                            val ssid = if (ap.SSID.isNullOrEmpty()) "Hidden" else ap.SSID.replace(",", ";")
                            writer.write("$timestamp,$currentLocation,$currentX,$currentY,${ap.BSSID},$ssid,${ap.level},${ap.frequency}\n")
                        }
                    }
                }
            }

            scanCount++
            tvStatus?.text = "Scan $scanCount/$targetScans"

            handler.postDelayed({ startNextScan() }, 500)

        } catch (e: Exception) {
            scanCount++
            handler.postDelayed({ startNextScan() }, 500)
        }
    }

    private fun saveIMUData() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            imuCsvFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write("$timestamp,$currentLocation,$currentX,$currentY,$stepsSinceLastScan,${"%.1f".format(currentHeading)}\n")
                }
            }
        } catch (e: Exception) {}
    }

    private fun finishScanning() {
        isScanning = false
        btnScan?.isEnabled = true

        if (successfulScans > 0) {
            tvStatus?.text = "Done! Steps: $stepsSinceLastScan"
            val total = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            tvCount?.text = "Total samples: $total"
            Toast.makeText(this, "Success!", Toast.LENGTH_SHORT).show()
        } else {
            tvStatus?.text = "No WiFi found"
        }
    }

    private fun viewCSVInfo() {
        val wifiRows = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
        val imuRows = imuCsvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Info")
            .setMessage("WiFi: $wifiRows\nIMU: $imuRows\nSensor: $sensorType")
            .setPositiveButton("OK", null)
            .show()

        tvCount?.text = "Total samples: $wifiRows"
    }

    private fun shareCSVFiles() {
        val uris = ArrayList<Uri>()

        csvFile?.let {
            if (it.exists()) uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", it))
        }
        imuCsvFile?.let {
            if (it.exists()) uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", it))
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No data!", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "text/csv"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun clearAllData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All?")
            .setPositiveButton("Delete") { _, _ ->
                csvFile?.delete()
                imuCsvFile?.delete()
                initializeCSVFiles()
                tvCount?.text = "Total samples: 0"
                Toast.makeText(this, "Cleared!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {}
    }
}
