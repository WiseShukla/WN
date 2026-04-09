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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var wifiManager: WifiManager
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

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

    // Heading
    private var currentHeading = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)

    // Step counting
    private var stepCount = 0
    private var initialStepCounterValue = -1L
    private var sensorType = "Initializing..."
    private var useAccelerometerFallback = false

    // Accelerometer fallback
    private var lastStepTime = 0L
    private var wasAboveThreshold = false

    // WiFi scanning
    private var isScanning = false
    private var scanCount = 0
    private var targetScans = 10
    private var successfulScans = 0
    private var currentLocation = ""
    private var currentX = ""
    private var currentY = ""
    private var stepsSinceLastScan = 0

    // Handler for UI updates
    private val handler = Handler(Looper.getMainLooper())
    private var lastDisplayedSteps = -1
    private var lastDisplayedHeading = -1

    // UI Update Runnable - forces refresh every 100ms
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

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (isScanning) processScanResults(success)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        csvFile = File(getExternalFilesDir(null), "wifi_fingerprints.csv")
        imuCsvFile = File(getExternalFilesDir(null), "imu_data.csv")

        initializeCSVFiles()
        createUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                wifiScanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                wifiScanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
        }

        startAllSensors()
        handler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {}

        sensorManager.unregisterListener(this)
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
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            onPermissionsReady()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "✓ All permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Some permissions denied", Toast.LENGTH_LONG).show()
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
        sensorManager.unregisterListener(this)

        var stepSensorStarted = false

        // PRIORITY 1: TYPE_STEP_DETECTOR (instant, 1 event per step)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            val registered = sensorManager.registerListener(
                this,
                stepDetector,
                SensorManager.SENSOR_DELAY_FASTEST,
                0
            )
            if (registered) {
                sensorType = "Step Detector ✓"
                stepSensorStarted = true
                useAccelerometerFallback = false
            }
        }

        // PRIORITY 2: TYPE_STEP_COUNTER
        if (!stepSensorStarted) {
            val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepCounter != null) {
                val registered = sensorManager.registerListener(
                    this,
                    stepCounter,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0
                )
                if (registered) {
                    sensorType = "Step Counter ✓"
                    stepSensorStarted = true
                    useAccelerometerFallback = false
                }
            }
        }

        // PRIORITY 3: Accelerometer fallback
        if (!stepSensorStarted) {
            sensorType = "Accelerometer ⚠️"
            useAccelerometerFallback = true
        }

        // Accelerometer (for heading + fallback steps)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            sensorManager.registerListener(
                this,
                accel,
                SensorManager.SENSOR_DELAY_FASTEST,
                0
            )
        }

        // Magnetometer for heading
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (mag != null) {
            sensorManager.registerListener(
                this,
                mag,
                SensorManager.SENSOR_DELAY_FASTEST,
                0
            )
        }

        tvSensorType?.text = "Sensor: $sensorType"
        tvStatus?.text = "✓ Ready"
    }

    override fun onSensorChanged(event: SensorEvent) {
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
        tvIMUStatus?.text = "👟 Steps: $stepCount  |  🧭 ${currentHeading.toInt()}° ($dir)"
    }

    private fun resetSteps() {
        stepCount = 0
        initialStepCounterValue = -1L
        lastDisplayedSteps = -1
        forceUpdateDisplay()
        Toast.makeText(this, "✓ Steps reset to 0", Toast.LENGTH_SHORT).show()
    }

    private fun checkLocationServices(): Boolean {
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Location Required")
                .setMessage("WiFi scanning requires Location to be ON")
                .setPositiveButton("Open Settings") { _, _ ->
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
        } catch (e: Exception) {
            Toast.makeText(this, "CSV init error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createUI() {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        // Title
        val title = TextView(this)
        title.text = "WiFi Data Collector"
        title.textSize = 26f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(android.graphics.Color.parseColor("#1565C0"))
        layout.addView(title)

        val space1 = Space(this)
        space1.minimumHeight = 15
        layout.addView(space1)

        // Sensor type indicator
        tvSensorType = TextView(this)
        tvSensorType?.text = "Sensor: Initializing..."
        tvSensorType?.textSize = 14f
        tvSensorType?.setTextColor(android.graphics.Color.DKGRAY)
        layout.addView(tvSensorType)

        val space2 = Space(this)
        space2.minimumHeight = 20
        layout.addView(space2)

        // IMU DISPLAY (BIG)
        tvIMUStatus = TextView(this)
        tvIMUStatus?.text = "👟 Steps: 0  |  🧭 0° (N)"
        tvIMUStatus?.textSize = 24f
        tvIMUStatus?.setTypeface(null, android.graphics.Typeface.BOLD)
        tvIMUStatus?.setTextColor(android.graphics.Color.WHITE)
        tvIMUStatus?.setBackgroundColor(android.graphics.Color.parseColor("#E65100"))
        tvIMUStatus?.setPadding(30, 30, 30, 30)
        tvIMUStatus?.gravity = android.view.Gravity.CENTER
        layout.addView(tvIMUStatus)

        val space3 = Space(this)
        space3.minimumHeight = 10
        layout.addView(space3)

        // Reset steps button
        btnResetSteps = Button(this)
        btnResetSteps?.text = "🔄 RESET STEPS TO ZERO"
        btnResetSteps?.textSize = 16f
        btnResetSteps?.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
        btnResetSteps?.setTextColor(android.graphics.Color.WHITE)
        btnResetSteps?.setOnClickListener { resetSteps() }
        layout.addView(btnResetSteps)

        val space4 = Space(this)
        space4.minimumHeight = 25
        layout.addView(space4)

        // Location name
        val labelLocation = TextView(this)
        labelLocation.text = "Location Name:"
        labelLocation.textSize = 16f
        layout.addView(labelLocation)

        etLocationName = EditText(this)
        etLocationName?.hint = "e.g., elevator_exit"
        etLocationName?.setPadding(20, 20, 20, 20)
        etLocationName?.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        layout.addView(etLocationName)

        val space5 = Space(this)
        space5.minimumHeight = 10
        layout.addView(space5)

        // X coordinate
        val labelX = TextView(this)
        labelX.text = "X Coordinate:"
        labelX.textSize = 16f
        layout.addView(labelX)

        etX = EditText(this)
        etX?.hint = "0"
        etX?.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        etX?.setPadding(20, 20, 20, 20)
        etX?.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        layout.addView(etX)

        val space6 = Space(this)
        space6.minimumHeight = 10
        layout.addView(space6)

        // Y coordinate
        val labelY = TextView(this)
        labelY.text = "Y Coordinate:"
        labelY.textSize = 16f
        layout.addView(labelY)

        etY = EditText(this)
        etY?.hint = "0"
        etY?.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        etY?.setPadding(20, 20, 20, 20)
        etY?.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        layout.addView(etY)

        val space7 = Space(this)
        space7.minimumHeight = 20
        layout.addView(space7)

        // WiFi networks display
        val labelWifi = TextView(this)
        labelWifi.text = "WiFi Networks:"
        labelWifi.textSize = 16f
        labelWifi.setTypeface(null, android.graphics.Typeface.BOLD)
        layout.addView(labelWifi)

        tvWifiList = TextView(this)
        tvWifiList?.text = "Tap SCAN to detect networks..."
        tvWifiList?.maxLines = 5
        tvWifiList?.setPadding(15, 15, 15, 15)
        tvWifiList?.setBackgroundColor(android.graphics.Color.parseColor("#ECEFF1"))
        layout.addView(tvWifiList)

        val space8 = Space(this)
        space8.minimumHeight = 15
        layout.addView(space8)

        // Status
        tvStatus = TextView(this)
        tvStatus?.text = "Initializing..."
        tvStatus?.textSize = 16f
        tvStatus?.setTextColor(android.graphics.Color.parseColor("#1565C0"))
        layout.addView(tvStatus)

        tvCount = TextView(this)
        tvCount?.text = "Total samples: 0"
        tvCount?.textSize = 14f
        tvCount?.setTextColor(android.graphics.Color.GRAY)
        layout.addView(tvCount)

        val space9 = Space(this)
        space9.minimumHeight = 20
        layout.addView(space9)

        // SCAN BUTTON
        btnScan = Button(this)
        btnScan?.text = "📡 SCAN HERE (10X)"
        btnScan?.textSize = 20f
        btnScan?.setPadding(30, 40, 30, 40)
        btnScan?.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
        btnScan?.setTextColor(android.graphics.Color.WHITE)
        btnScan?.setOnClickListener {
            val location = etLocationName?.text?.toString()?.trim() ?: ""
            val x = etX?.text?.toString()?.trim() ?: ""
            val y = etY?.text?.toString()?.trim() ?: ""

            if (location.isEmpty() || x.isEmpty() || y.isEmpty()) {
                Toast.makeText(this@MainActivity, "⚠️ Fill all fields!", Toast.LENGTH_SHORT).show()
            } else {
                collectData(location, x, y)
            }
        }
        layout.addView(btnScan)

        val space10 = Space(this)
        space10.minimumHeight = 15
        layout.addView(space10)

        // View info button
        btnViewCSV = Button(this)
        btnViewCSV?.text = "📊 VIEW INFO"
        btnViewCSV?.setBackgroundColor(android.graphics.Color.parseColor("#43A047"))
        btnViewCSV?.setTextColor(android.graphics.Color.WHITE)
        btnViewCSV?.setOnClickListener { viewCSVInfo() }
        layout.addView(btnViewCSV)

        val space11 = Space(this)
        space11.minimumHeight = 10
        layout.addView(space11)

        // Share button
        btnExportCSV = Button(this)
        btnExportCSV?.text = "📤 SHARE CSV FILES"
        btnExportCSV?.setBackgroundColor(android.graphics.Color.parseColor("#1E88E5"))
        btnExportCSV?.setTextColor(android.graphics.Color.WHITE)
        btnExportCSV?.setOnClickListener { shareCSVFiles() }
        layout.addView(btnExportCSV)

        val space12 = Space(this)
        space12.minimumHeight = 10
        layout.addView(space12)

        // Clear data button
        val btnClear = Button(this)
        btnClear.text = "🗑️ CLEAR ALL DATA"
        btnClear.setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
        btnClear.setTextColor(android.graphics.Color.WHITE)
        btnClear.setOnClickListener { clearAllData() }
        layout.addView(btnClear)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun collectData(location: String, x: String, y: String) {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "⚠️ Turn ON WiFi!", Toast.LENGTH_LONG).show()
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
        tvStatus?.text = "Starting scan..."

        saveIMUData()
        startNextScan()
    }

    private fun startNextScan() {
        if (!isScanning || scanCount >= targetScans) {
            finishScanning()
            return
        }

        try {
            wifiManager.startScan()
            handler.postDelayed({
                if (isScanning) processScanResults(false)
            }, 2000)
        } catch (e: Exception) {
            tvStatus?.text = "Scan error: ${e.message}"
            finishScanning()
        }
    }

    private fun processScanResults(freshResults: Boolean) {
        if (!isScanning) return

        try {
            val results = wifiManager.scanResults

            if (results.isNotEmpty()) {
                successfulScans++

                val preview = results.take(3).joinToString("\n") {
                    "${if (it.SSID.isNullOrEmpty()) "Hidden" else it.SSID}: ${it.level}dBm"
                }
                tvWifiList?.text = "Found ${results.size} networks:\n$preview"

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                csvFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        results.forEach { ap ->
                            val ssid = if (ap.SSID.isNullOrEmpty()) "Hidden" else ap.SSID.replace(",", ";")
                            writer.write("$timestamp,$currentLocation,$currentX,$currentY,${ap.BSSID},$ssid,${ap.level},${ap.frequency}\n")
                        }
                    }
                }
            } else {
                tvWifiList?.text = "⚠️ No networks found"
            }

            scanCount++
            tvStatus?.text = "Scanning... $scanCount/$targetScans"

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
        } catch (e: Exception) {
            Toast.makeText(this, "IMU save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishScanning() {
        isScanning = false
        btnScan?.isEnabled = true

        if (successfulScans > 0) {
            tvStatus?.text = "✓ Done! Saved $successfulScans scans, Steps: $stepsSinceLastScan"
            val total = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            tvCount?.text = "Total samples: $total"
            Toast.makeText(this, "✓ Success!", Toast.LENGTH_SHORT).show()
        } else {
            tvStatus?.text = "❌ No WiFi data collected"
            Toast.makeText(this, "Check WiFi & Location are ON", Toast.LENGTH_LONG).show()
        }
    }

    private fun viewCSVInfo() {
        val wifiRows = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
        val imuRows = imuCsvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Data Info")
            .setMessage("WiFi samples: $wifiRows\nIMU samples: $imuRows\n\nStep sensor: $sensorType\n\nPath: ${csvFile?.parent}")
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
            Toast.makeText(this, "No data to share!", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "text/csv"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share CSV Files"))
    }

    private fun clearAllData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Data?")
            .setMessage("This will delete all collected WiFi and IMU data.")
            .setPositiveButton("Delete") { _, _ ->
                csvFile?.delete()
                imuCsvFile?.delete()
                initializeCSVFiles()
                tvCount?.text = "Total samples: 0"
                Toast.makeText(this, "✓ All data cleared!", Toast.LENGTH_SHORT).show()
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
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {}
    }
}
