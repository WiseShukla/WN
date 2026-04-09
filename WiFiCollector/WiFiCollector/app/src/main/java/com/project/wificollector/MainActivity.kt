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
    
    private var csvFile: File? = null
    private var imuCsvFile: File? = null
    
    // Step counter variables
    private var stepSensor: Sensor? = null
    private var stepDetector: Sensor? = null
    private var useStepDetector = false
    private var initialStepCount = -1
    private var lastSavedStepCount = 0
    private var currentTotalSteps = 0
    private var stepDetectorCount = 0  // For TYPE_STEP_DETECTOR
    
    // Fallback: Accelerometer-based step detection
    private var useAccelerometer = false
    private var accelStepCount = 0
    private var lastStepTime = 0L
    private var lastAccelMagnitude = 0f
    
    // Heading variables
    private var currentHeading = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    
    // WiFi scanning variables
    private var isScanning = false
    private var scanCount = 0
    private var targetScans = 10
    private var successfulScans = 0
    private var currentLocation = ""
    private var currentX = ""
    private var currentY = ""
    private var stepsSinceLastScan = 0
    
    private val handler = Handler(Looper.getMainLooper())
    
    // WiFi scan receiver
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (isScanning) {
                    processScanResults(success)
                }
            }
        }
    }
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

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
            
            // Request permissions first, sensors start after permission granted
            requestAllPermissions()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register WiFi scan receiver with Android 12+ compatibility
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
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
    
    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            onAllPermissionsGranted()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
                onAllPermissionsGranted()
            } else {
                Toast.makeText(this, "Some permissions denied. App may not work properly.", Toast.LENGTH_LONG).show()
                startSensors()
            }
        }
    }
    
    private fun onAllPermissionsGranted() {
        startSensors()
        checkLocationServices()
    }
    
    private fun checkLocationServices(): Boolean {
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Location Services Required")
                .setMessage("WiFi scanning requires Location Services to be ON.\n\nPlease enable Location in Settings.")
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
                        writer.flush()
                    }
                }
            }
            
            imuCsvFile?.let { file ->
                if (!file.exists()) {
                    FileWriter(file, false).use { writer ->
                        writer.write("Timestamp,Location,X,Y,Steps,Heading\n")
                        writer.flush()
                    }
                }
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "CSV init error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createUI() {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        
        TextView(this).apply {
            text = "WiFi Data Collector"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        TextView(this).apply {
            text = "Location Name:"
            layout.addView(this)
        }
        
        etLocationName = EditText(this).apply {
            hint = "e.g., elevator_exit"
            setPadding(20, 20, 20, 20)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        TextView(this).apply {
            text = "X Coordinate:"
            layout.addView(this)
        }
        
        etX = EditText(this).apply {
            hint = "0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(20, 20, 20, 20)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        TextView(this).apply {
            text = "Y Coordinate:"
            layout.addView(this)
        }
        
        etY = EditText(this).apply {
            hint = "0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(20, 20, 20, 20)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        TextView(this).apply {
            text = "IMU Sensors:"
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        tvIMUStatus = TextView(this).apply {
            text = "Steps=0, Heading=0° (North)"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#FF6B35"))
            layout.addView(this)
        }
        
        TextView(this).apply {
            text = "0°=North, 90°=East, 180°=South, 270°=West"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        // RESET STEPS BUTTON
        btnResetSteps = Button(this).apply {
            text = "🔄 RESET STEPS"
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 15, 20, 15)
            setOnClickListener {
                resetStepCount()
            }
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 15 })
        
        TextView(this).apply {
            text = "WiFi Networks:"
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        tvWifiList = TextView(this).apply {
            text = "Scan to see networks..."
            maxLines = 6
            setPadding(10, 10, 10, 10)
            setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        tvStatus = TextView(this).apply {
            text = "Ready to collect"
            textSize = 18f
            setTextColor(android.graphics.Color.BLUE)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        tvCount = TextView(this).apply {
            text = "Total samples: 0"
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        btnScan = Button(this).apply {
            text = "📡 SCAN HERE (10X)"
            textSize = 18f
            setPadding(30, 30, 30, 30)
            setOnClickListener {
                val location = etLocationName?.text?.toString()?.trim() ?: ""
                val x = etX?.text?.toString()?.trim() ?: ""
                val y = etY?.text?.toString()?.trim() ?: ""
                
                if (location.isEmpty() || x.isEmpty() || y.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please fill all fields!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    collectData(location, x, y)
                }
            }
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        btnViewCSV = Button(this).apply {
            text = "📊 VIEW CSV INFO"
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                viewCSVInfo()
            }
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        btnExportCSV = Button(this).apply {
            text = "📤 SHARE CSV FILES"
            setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                shareCSVFiles()
            }
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        // CLEAR ALL DATA BUTTON
        Button(this).apply {
            text = "🗑️ CLEAR ALL DATA"
            setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                clearAllData()
            }
            layout.addView(this)
        }
        
        scrollView.addView(layout)
        setContentView(scrollView)
    }
    
    private fun resetStepCount() {
        // Reset all step counters
        when {
            useStepDetector -> {
                stepDetectorCount = 0
            }
            useAccelerometer -> {
                accelStepCount = 0
            }
            else -> {
                // For TYPE_STEP_COUNTER, we reset the baseline
                lastSavedStepCount = currentTotalSteps
            }
        }
        
        updateIMUDisplay(0)
        Toast.makeText(this, "✓ Steps reset to 0", Toast.LENGTH_SHORT).show()
    }
    
    private fun clearAllData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Data?")
            .setMessage("This will delete all WiFi and IMU data collected. Are you sure?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    csvFile?.let { file ->
                        if (file.exists()) file.delete()
                    }
                    imuCsvFile?.let { file ->
                        if (file.exists()) file.delete()
                    }
                    initializeCSVFiles()
                    tvCount?.text = "Total samples: 0"
                    Toast.makeText(this, "✓ All data cleared!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun viewCSVInfo() {
        try {
            val wifiRows = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            val imuRows = imuCsvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            
            val stepSensorStatus = when {
                useStepDetector -> "Step Detector ✓ (Real-time)"
                stepSensor != null -> "Step Counter ✓ (Batched)"
                useAccelerometer -> "Accelerometer (Fallback)"
                else -> "Not available ❌"
            }
            val wifiStatus = if (wifiManager.isWifiEnabled) "ON ✓" else "OFF ❌"
            val locationStatus = if (checkLocationServicesQuiet()) "ON ✓" else "OFF ❌"
            
            val message = """
                WiFi Data: $wifiRows rows
                IMU Data: $imuRows rows
                
                WiFi: $wifiStatus
                Location Services: $locationStatus
                Step Detection: $stepSensorStatus
                
                Files: ${csvFile?.parent}
            """.trimIndent()
            
            AlertDialog.Builder(this)
                .setTitle("Status Info")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
            
            tvCount?.text = "Total samples: $wifiRows"
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun checkLocationServicesQuiet(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    
    private fun shareCSVFiles() {
        try {
            if (csvFile?.exists() != true && imuCsvFile?.exists() != true) {
                Toast.makeText(this, "No data collected yet!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uris = ArrayList<Uri>()
            
            csvFile?.let {
                if (it.exists()) {
                    val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
                    uris.add(uri)
                }
            }
            
            imuCsvFile?.let {
                if (it.exists()) {
                    val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
                    uris.add(uri)
                }
            }
            
            if (uris.isEmpty()) {
                Toast.makeText(this, "No files to share!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share CSV Files"))
            
        } catch (e: Exception) {
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun collectData(location: String, x: String, y: String) {
        // CHECK WIFI STATUS
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "⚠️ WiFi is OFF! Please turn ON WiFi first.", Toast.LENGTH_LONG).show()
            return
        }
        
        // CHECK LOCATION SERVICES
        if (!checkLocationServices()) {
            return
        }
        
        // CHECK LOCATION PERMISSION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "⚠️ Location permission required for WiFi scanning!", Toast.LENGTH_LONG).show()
            requestAllPermissions()
            return
        }
        
        // Get current steps and reset for next segment
        stepsSinceLastScan = getCurrentSteps()
        resetStepCountInternal()
        
        // Store current values
        currentLocation = location
        currentX = x
        currentY = y
        
        // Reset scan counters
        scanCount = 0
        successfulScans = 0
        targetScans = 10
        isScanning = true
        
        btnScan?.isEnabled = false
        tvStatus?.text = "Starting scan..."
        tvWifiList?.text = "Requesting scan..."
        
        // Save IMU data once at start
        saveIMUData()
        
        // Start first scan
        startNextScan()
    }
    
    private fun getCurrentSteps(): Int {
        return when {
            useStepDetector -> stepDetectorCount
            useAccelerometer -> accelStepCount
            else -> {
                if (initialStepCount == -1) 0 
                else currentTotalSteps - lastSavedStepCount
            }
        }
    }
    
    private fun resetStepCountInternal() {
        when {
            useStepDetector -> stepDetectorCount = 0
            useAccelerometer -> accelStepCount = 0
            else -> lastSavedStepCount = currentTotalSteps
        }
    }
    
    private fun startNextScan() {
        if (!isScanning || scanCount >= targetScans) {
            finishScanning()
            return
        }
        
        try {
            val scanStarted = wifiManager.startScan()
            
            if (!scanStarted) {
                tvWifiList?.text = "⚠️ Scan throttled by Android. Using cached results..."
                handler.postDelayed({
                    processScanResults(false)
                }, 500)
            }
            
            // Timeout in case broadcast never arrives
            handler.postDelayed({
                if (isScanning && scanCount < targetScans) {
                    processScanResults(false)
                }
            }, 3000)
            
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
                    val ssid = if (it.SSID.isNullOrEmpty()) "Hidden" else it.SSID
                    "$ssid: ${it.level}dBm"
                }
                tvWifiList?.text = "Found ${results.size} networks:\n$preview"
                
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                
                csvFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        results.forEach { ap ->
                            val ssid = if (ap.SSID.isNullOrEmpty()) "Hidden" else ap.SSID.replace(",", ";")
                            writer.write("$timestamp,$currentLocation,$currentX,$currentY,${ap.BSSID},$ssid,${ap.level},${ap.frequency}\n")
                        }
                        writer.flush()
                    }
                }
            } else {
                tvWifiList?.text = "⚠️ No networks found!"
            }
            
            scanCount++
            tvStatus?.text = "Scanning... $scanCount/$targetScans (${if (freshResults) "fresh" else "cached"})"
            
            val delay = if (freshResults) 2000L else 500L
            handler.postDelayed({
                startNextScan()
            }, delay)
            
        } catch (e: Exception) {
            tvStatus?.text = "Error: ${e.message}"
            scanCount++
            handler.postDelayed({
                startNextScan()
            }, 1000)
        }
    }
    
    private fun saveIMUData() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            imuCsvFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write("$timestamp,$currentLocation,$currentX,$currentY,$stepsSinceLastScan,${"%.1f".format(currentHeading)}\n")
                    writer.flush()
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
            tvStatus?.text = "✓ Saved $successfulScans scans! Steps=$stepsSinceLastScan"
            val total = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            tvCount?.text = "Total samples: $total"
            Toast.makeText(this, "Success! Collected $successfulScans scans", Toast.LENGTH_SHORT).show()
        } else {
            tvStatus?.text = "❌ No WiFi data collected!"
            Toast.makeText(this, "ERROR: Check WiFi & Location are ON", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startSensors() {
        try {
            // Priority 1: TYPE_STEP_DETECTOR (real-time, instant feedback)
            stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            
            if (stepDetector != null) {
                val registered = sensorManager.registerListener(
                    this, 
                    stepDetector, 
                    SensorManager.SENSOR_DELAY_FASTEST
                )
                if (registered) {
                    useStepDetector = true
                    useAccelerometer = false
                    Toast.makeText(this, "✓ Step Detector active (real-time)!", Toast.LENGTH_SHORT).show()
                } else {
                    tryStepCounter()
                }
            } else {
                tryStepCounter()
            }
            
            // Accelerometer for heading
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            }
            
            // Magnetometer for heading
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (magnetometer != null) {
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Sensor error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun tryStepCounter() {
        // Priority 2: TYPE_STEP_COUNTER (batched, may have delay)
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        
        if (stepSensor != null) {
            val registered = sensorManager.registerListener(
                this, 
                stepSensor, 
                SensorManager.SENSOR_DELAY_FASTEST
            )
            if (registered) {
                useStepDetector = false
                useAccelerometer = false
                Toast.makeText(this, "✓ Step Counter active (may have slight delay)", Toast.LENGTH_SHORT).show()
            } else {
                setupAccelerometerFallback()
            }
        } else {
            setupAccelerometerFallback()
        }
    }
    
    private fun setupAccelerometerFallback() {
        useStepDetector = false
        useAccelerometer = true
        Toast.makeText(this, "⚠️ Using accelerometer for steps (less accurate)", Toast.LENGTH_LONG).show()
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    // Each event = 1 step detected instantly!
                    stepDetectorCount++
                    updateIMUDisplay(stepDetectorCount)
                }
                
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values[0].toInt()
                    
                    if (initialStepCount == -1) {
                        initialStepCount = totalSteps
                        lastSavedStepCount = totalSteps
                    }
                    
                    currentTotalSteps = totalSteps
                    val steps = currentTotalSteps - lastSavedStepCount
                    updateIMUDisplay(steps)
                }
                
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.clone()
                    
                    if (useAccelerometer) {
                        detectStepFromAccel(event.values)
                    }
                    
                    updateHeading()
                }
                
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    geomagnetic = event.values.clone()
                    updateHeading()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun detectStepFromAccel(values: FloatArray) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastStepTime < 300) {
            return
        }
        
        val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        val delta = kotlin.math.abs(magnitude - lastAccelMagnitude)
        
        if (delta > 3.5f && magnitude > 8.0f && magnitude < 15.0f) {
            accelStepCount++
            lastStepTime = currentTime
            updateIMUDisplay(accelStepCount)
        }
        
        lastAccelMagnitude = magnitude
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
    
    private fun updateIMUDisplay(steps: Int) {
        handler.post {
            val direction = when {
                currentHeading < 45 || currentHeading >= 315 -> "N"
                currentHeading < 135 -> "E"
                currentHeading < 225 -> "S"
                else -> "W"
            }
            tvIMUStatus?.text = "Steps=$steps | Heading=${currentHeading.toInt()}° ($direction)"
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
