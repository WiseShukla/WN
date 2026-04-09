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
import kotlin.math.sqrt

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
    
    // IMU tracking - AUTOMATICALLY HANDLES A/B WINGS!
    private var stepCount = 0
    private var lastAccel = 0f
    private var currentAccel = 0f
    private var currentHeading = 0.0  // ← This distinguishes LEFT (270°) vs RIGHT (90°)
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
            
            initializeCSVFiles()
            createUI()
            startIMUTracking()
            
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
                        // ← HEADING COLUMN CAPTURES DIRECTION (A wing vs B wing)
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
        
        // IMU Status - Shows HEADING (direction you're facing)
        // This automatically shows if you're facing LEFT (270°) or RIGHT (90°)
        tvIMUStatus = TextView(this).apply {
            text = "IMU: Steps=0, Heading=0"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#FF6B35"))
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
            
            val message = """
                WiFi Data: $wifiRows rows
                IMU Data: $imuRows rows
                
                Location:
                ${csvFile?.absolutePath}
                
                Heading data automatically captures:
                - LEFT turn to A wing (~270 degrees)
                - RIGHT turn to B wing (~90 degrees)
                
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
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun collectData(location: String, x: String, y: String) {
        // ============================================
        // CRITICAL: RESET STEPS (NOT HEADING!)
        // - Steps reset to measure distance between locations
        // - Heading KEEPS tracking so you know direction
        //   (LEFT=270° for A wing, RIGHT=90° for B wing)
        // ============================================
        stepCount = 0
        updateIMUDisplay()
        
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
                    
                    // ============================================
                    // SAVE IMU DATA WITH HEADING
                    // This line captures BOTH:
                    // - stepCount: distance walked
                    // - currentHeading: direction faced
                    //   (automatically distinguishes A/B wings!)
                    // ============================================
                    imuCsvFile?.let { file ->
                        FileWriter(file, true).use { writer ->
                            writer.write("$timestamp,$location,$x,$y,$stepCount,${"%.1f".format(currentHeading)}\n")
                            //                                    ↑ Steps    ↑ Heading (0-360°)
                            //                                               270° = LEFT (A wing)
                            //                                               90° = RIGHT (B wing)
                            writer.flush()
                        }
                    }
                    
                    runOnUiThread {
                        tvStatus?.text = "Scanning... ${i + 1}/50"
                    }
                }
                
                runOnUiThread {
                    tvStatus?.text = "Saved! Walk to next location"
                    btnScan?.isEnabled = true
                    val total = csvFile?.let { if (it.exists()) it.readLines().size - 1 else 0 } ?: 0
                    tvCount?.text = "Total samples: $total"
                    Toast.makeText(this, "Steps reset. Heading still tracking!", Toast.LENGTH_SHORT).show()
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
    
    private fun startIMUTracking() {
        try {
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {
            // IMU optional
        }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.clone()
                    detectStep(event.values)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    geomagnetic = event.values.clone()
                    updateHeading()  // ← Continuously updates heading (A/B wing detection)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun detectStep(values: FloatArray) {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        
        lastAccel = currentAccel
        currentAccel = sqrt(x * x + y * y + z * z)
        
        if (currentAccel - lastAccel > 6.0f) {
            stepCount++
            updateIMUDisplay()
        }
    }
    
    // ============================================
    // UPDATE HEADING - THIS IS HOW A/B WINGS WORK!
    // Magnetometer continuously tracks which direction
    // you're facing:
    // - 0° = North (straight from elevator)
    // - 90° = East (RIGHT turn to B wing)
    // - 270° = West (LEFT turn to A wing)
    // ============================================
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
                
                updateIMUDisplay()
            }
        }
    }
    
    private fun updateIMUDisplay() {
        runOnUiThread {
            tvIMUStatus?.text = "IMU: Steps=$stepCount, Heading=${currentHeading.toInt()}"
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
