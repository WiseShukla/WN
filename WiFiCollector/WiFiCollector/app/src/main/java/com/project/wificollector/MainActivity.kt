package com.project.wificollector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var wifiManager: WifiManager
    private lateinit var sensorManager: SensorManager
    
    private var etLocationName: EditText? = null
    private var etX: EditText? = null
    private var etY: EditText? = null
    private var btnScan: Button? = null
    private var btnViewCSV: Button? = null
    private var btnExportCSV: Button? = null
    private var tvStatus: TextView? = null
    private var tvCount: TextView? = null
    private var tvWifiList: TextView? = null
    private var tvIMUStatus: TextView? = null
    
    private var csvFile: File? = null
    private var imuCsvFile: File? = null
    
    // STEP COUNTER (Hardware Pedometer)
    private var stepSensor: Sensor? = null
    private var initialStepCount = -1  // Steps at app start
    private var lastSavedStepCount = 0  // Steps at last scan
    private var currentTotalSteps = 0   // Current total steps from sensor
    
    // HEADING (Compass)
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null
    private var currentHeading = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            
            csvFile = File(getExternalFilesDir(null), "wifi_fingerprints.csv")
            imuCsvFile = File(getExternalFilesDir(null), "imu_data.csv")
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1
                )
            }
            
            // Request Activity Recognition permission for step counter (Android 10+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                        2
                    )
                }
            }
            
            initializeCSVFiles()
            createUI()
            startSensors()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
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
            textSize = 16f
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
            text = "SCAN HERE (50X)"
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
            text = "VIEW CSV INFO"
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setOnClickListener {
                viewCSVInfo()
            }
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        btnExportCSV = Button(this).apply {
            text = "SHARE CSV FILES"
            setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
            setOnClickListener {
                shareCSVFiles()
            }
            layout.addView(this)
        }
        
        scrollView.addView(layout)
        setContentView(scrollView)
    }
    
    private fun viewCSVInfo() {
        try {
            val wifiRows = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            val imuRows = imuCsvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
            
            val stepSensorAvailable = stepSensor != null
            
            val message = """
                WiFi Data: $wifiRows rows
                IMU Data: $imuRows rows
                
                Hardware Pedometer: ${if (stepSensorAvailable) "Available" else "Not Available"}
                
                Use SHARE button to export!
            """.trimIndent()
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            tvCount?.text = "Total samples: $wifiRows"
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareCSVFiles() {
        try {
            if (csvFile?.exists() != true || imuCsvFile?.exists() != true) {
                Toast.makeText(this, "No data collected yet!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uris = ArrayList<Uri>()
            
            csvFile?.let {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
                uris.add(uri)
            }
            
            imuCsvFile?.let {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
                uris.add(uri)
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
        // Calculate steps since last scan
        val stepsSinceLastScan = if (initialStepCount == -1) {
            0  // First scan, no steps yet
        } else {
            currentTotalSteps - lastSavedStepCount
        }
        
        // Update last saved step count for next scan
        lastSavedStepCount = currentTotalSteps
        
        btnScan?.isEnabled = false
        tvStatus?.text = "Scanning..."
        tvWifiList?.text = "Scanning WiFi..."
        
        Thread {
            try {
                repeat(50) { i ->
                    wifiManager.startScan()
                    Thread.sleep(200)
                    
                    val results = wifiManager.scanResults
                    
                    if (i == 0) {
                        runOnUiThread {
                            val preview = results.take(3).joinToString("\n") { 
                                "${it.SSID}: ${it.level}dBm"
                            }
                            tvWifiList?.text = "Found ${results.size}:\n$preview"
                        }
                    }
                    
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    
                    // Save WiFi data
                    csvFile?.let { file ->
                        FileWriter(file, true).use { writer ->
                            results.forEach { ap ->
                                writer.write("$timestamp,$location,$x,$y,${ap.BSSID},${ap.SSID},${ap.level},${ap.frequency}\n")
                            }
                            writer.flush()
                        }
                    }
                    
                    // Save IMU data (steps since last scan + current heading)
                    imuCsvFile?.let { file ->
                        FileWriter(file, true).use { writer ->
                            writer.write("$timestamp,$location,$x,$y,$stepsSinceLastScan,${"%.1f".format(currentHeading)}\n")
                            writer.flush()
                        }
                    }
                    
                    runOnUiThread {
                        tvStatus?.text = "Scanning... ${i + 1}/50"
                    }
                }
                
                runOnUiThread {
                    tvStatus?.text = "Saved! Steps=$stepsSinceLastScan"
                    btnScan?.isEnabled = true
                    val total = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
                    tvCount?.text = "Total samples: $total"
                    Toast.makeText(this, "Walked $stepsSinceLastScan steps to this location!", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus?.text = "Error: ${e.message}"
                    btnScan?.isEnabled = true
                    Toast.makeText(this, "Scan error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    private fun startSensors() {
        try {
            // ============================================
            // HARDWARE PEDOMETER (TYPE_STEP_COUNTER)
            // Much more accurate than accelerometer!
            // ============================================
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            
            if (stepSensor != null) {
                sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                Toast.makeText(this, "Hardware pedometer active!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No hardware pedometer. Steps may be inaccurate.", Toast.LENGTH_LONG).show()
            }
            
            // ============================================
            // MAGNETOMETER + ACCELEROMETER (For Heading)
            // ============================================
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            
        } catch (e: Exception) {
            Toast.makeText(this, "Sensor error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                // ============================================
                // STEP COUNTER (Hardware Pedometer)
                // Returns total steps since device reboot
                // ============================================
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values[0].toInt()
                    
                    // First time getting step data
                    if (initialStepCount == -1) {
                        initialStepCount = totalSteps
                        lastSavedStepCount = totalSteps
                    }
                    
                    currentTotalSteps = totalSteps
                    
                    // Calculate steps since last scan
                    val stepsSinceLastScan = currentTotalSteps - lastSavedStepCount
                    
                    updateIMUDisplay(stepsSinceLastScan)
                }
                
                // ============================================
                // ACCELEROMETER (For heading calculation)
                // ============================================
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.clone()
                    updateHeading()
                }
                
                // ============================================
                // MAGNETOMETER (For heading calculation)
                // ============================================
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    geomagnetic = event.values.clone()
                    updateHeading()
                }
            }
        } catch (e: Exception) {
            // Ignore sensor errors
        }
    }
    
    private fun updateHeading() {
        if (gravity.isNotEmpty() && geomagnetic.isNotEmpty()) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                
                // Convert radians to degrees (0-360)
                currentHeading = Math.toDegrees(orientation[0].toDouble())
                if (currentHeading < 0) currentHeading += 360
                
                // Update display
                val stepsSinceLastScan = if (initialStepCount == -1) 0 else currentTotalSteps - lastSavedStepCount
                updateIMUDisplay(stepsSinceLastScan)
            }
        }
    }
    
    private fun updateIMUDisplay(steps: Int) {
        runOnUiThread {
            val direction = when {
                currentHeading < 45 || currentHeading >= 315 -> "North"
                currentHeading < 135 -> "East (Right)"
                currentHeading < 225 -> "South"
                else -> "West (Left)"
            }
            tvIMUStatus?.text = "Steps=$steps, Heading=${currentHeading.toInt()}° ($direction)"
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
