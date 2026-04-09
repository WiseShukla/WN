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
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
    
    private lateinit var etLocationName: EditText
    private lateinit var etX: EditText
    private lateinit var etY: EditText
    private lateinit var btnScan: Button
    private lateinit var btnViewCSV: Button
    private lateinit var btnExportCSV: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvWifiList: TextView
    private lateinit var tvIMUStatus: TextView
    
    private val csvFile by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "wifi_fingerprints.csv"
        )
    }
    
    private val imuCsvFile by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "imu_data.csv"
        )
    }
    
    private var stepCount = 0
    private var lastAccel = 0f
    private var currentAccel = 0f
    private var currentHeading = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        requestAllPermissions()
        initializeCSVFiles()
        createUI()
        startIMUTracking()
    }
    
    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
    
    private fun initializeCSVFiles() {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            if (!csvFile.exists()) {
                FileWriter(csvFile, false).use { writer ->
                    writer.write("Timestamp,Location,X,Y,BSSID,SSID,RSSI,Frequency\n")
                    writer.flush()
                }
            }
            
            if (!imuCsvFile.exists()) {
                FileWriter(imuCsvFile, false).use { writer ->
                    writer.write("Timestamp,Location,X,Y,Steps,Heading\n")
                    writer.flush()
                }
            }
            
            runOnUiThread {
                Toast.makeText(this, "CSV files ready in Downloads", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Error creating files: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        
        TextView(this).apply {
            text = "WiFi Data Collector"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        TextView(this).apply {
            text = "Location Name:"
            textSize = 16f
            layout.addView(this)
        }
        
        etLocationName = EditText(this).apply {
            hint = "e.g., elevator_exit"
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etLocationName)
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        TextView(this).apply {
            text = "X Coordinate (meters):"
            textSize = 16f
            layout.addView(this)
        }
        
        etX = EditText(this).apply {
            hint = "e.g., 0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etX)
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        TextView(this).apply {
            text = "Y Coordinate (meters):"
            textSize = 16f
            layout.addView(this)
        }
        
        etY = EditText(this).apply {
            hint = "e.g., 0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etY)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        tvIMUStatus = TextView(this).apply {
            text = "IMU: Steps=0, Heading=0 degrees"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#FF6B35"))
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        TextView(this).apply {
            text = "WiFi Networks Detected:"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        tvWifiList = TextView(this).apply {
            text = "Scan to see networks..."
            textSize = 14f
            setTextColor(android.graphics.Color.DKGRAY)
            maxLines = 8
            setPadding(10, 10, 10, 10)
            setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        tvStatus = TextView(this).apply {
            text = "Ready to collect"
            textSize = 20f
            setTextColor(android.graphics.Color.BLUE)
        }
        layout.addView(tvStatus)
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        tvCount = TextView(this).apply {
            text = "Total samples: ${countTotalSamples()}"
            textSize = 16f
        }
        layout.addView(tvCount)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        btnScan = Button(this).apply {
            text = "SCAN HERE (50X)"
            textSize = 20f
            setPadding(40, 40, 40, 40)
            
            setOnClickListener {
                val location = etLocationName.text.toString().trim()
                val x = etX.text.toString().trim()
                val y = etY.text.toString().trim()
                
                if (location.isEmpty() || x.isEmpty() || y.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please fill all fields!",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                
                collectData(location, x, y)
            }
        }
        layout.addView(btnScan)
        
        layout.addView(Space(this).apply { minimumHeight = 10 })
        
        btnViewCSV = Button(this).apply {
            text = "VIEW CSV LOCATION"
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setOnClickListener {
                viewAndSaveCSV()
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
        
        val scrollView = ScrollView(this)
        scrollView.addView(layout)
        setContentView(scrollView)
    }
    
    private fun countTotalSamples(): Int {
        return try {
            if (csvFile.exists()) {
                csvFile.readLines().size - 1
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun viewAndSaveCSV() {
        try {
            if (!csvFile.exists()) {
                initializeCSVFiles()
            }
            
            val wifiExists = csvFile.exists()
            val imuExists = imuCsvFile.exists()
            
            val wifiSize = if (wifiExists) csvFile.length() / 1024 else 0
            val imuSize = if (imuExists) imuCsvFile.length() / 1024 else 0
            
            val message = """
                CSV Files Saved!
                
                WiFi Data:
                ${csvFile.absolutePath}
                Size: ${wifiSize}KB
                Rows: ${countTotalSamples()}
                
                IMU Data:
                ${imuCsvFile.absolutePath}
                Size: ${imuSize}KB
                
                Files are in Downloads folder!
            """.trimIndent()
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            tvCount.text = "Total samples: ${countTotalSamples()}"
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun shareCSVFiles() {
        try {
            if (!csvFile.exists() || !imuCsvFile.exists()) {
                Toast.makeText(this, "No data collected yet!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                
                val uris = ArrayList<Uri>()
                
                if (csvFile.exists()) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        csvFile
                    )
                    uris.add(uri)
                }
                
                if (imuCsvFile.exists()) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        imuCsvFile
                    )
                    uris.add(uri)
                }
                
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share CSV Files"))
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing files: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun collectData(location: String, x: String, y: String) {
        btnScan.isEnabled = false
        tvStatus.text = "Scanning WiFi..."
        tvWifiList.text = "Scanning..."
        
        Thread {
            var scansCompleted = 0
            
            repeat(50) { i ->
                wifiManager.startScan()
                Thread.sleep(200)
                
                val results = wifiManager.scanResults
                
                if (i == 0) {
                    runOnUiThread {
                        val wifiText = results.take(5).joinToString("\n") { 
                            "${it.SSID} (${it.BSSID.takeLast(8)}): ${it.level} dBm"
                        }
                        tvWifiList.text = "Found ${results.size} networks:\n$wifiText\n..."
                    }
                }
                
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
                
                try {
                    FileWriter(csvFile, true).use { writer ->
                        results.forEach { ap ->
                            writer.write(
                                "$timestamp,$location,$x,$y,${ap.BSSID},${ap.SSID},${ap.level},${ap.frequency}\n"
                            )
                        }
                        writer.flush()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WiFiCollector", "Error writing WiFi data", e)
                }
                
                saveIMUData(timestamp, location, x, y)
                
                scansCompleted++
                
                runOnUiThread {
                    tvStatus.text = "Scanning... $scansCompleted/50"
                }
            }
            
            runOnUiThread {
                tvStatus.text = "Saved 50 scans at $location"
                btnScan.isEnabled = true
                
                tvCount.text = "Total samples: ${countTotalSamples()}"
                
                Toast.makeText(
                    this@MainActivity,
                    "Data saved to Downloads!\nWiFi: wifi_fingerprints.csv\nIMU: imu_data.csv",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
    
    private fun saveIMUData(timestamp: String, location: String, x: String, y: String) {
        try {
            FileWriter(imuCsvFile, true).use { writer ->
                writer.write(
                    "$timestamp,$location,$x,$y,$stepCount,${"%.1f".format(currentHeading)}\n"
                )
                writer.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("WiFiCollector", "Error writing IMU data", e)
        }
    }
    
    private fun startIMUTracking() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = event.values.clone()
                detectStep(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic = event.values.clone()
                updateHeading()
            }
        }
    }
    
    private fun detectStep(values: FloatArray) {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        
        lastAccel = currentAccel
        currentAccel = sqrt(x * x + y * y + z * z)
        
        val delta = currentAccel - lastAccel
        
        if (delta > 6.0f) {
            stepCount++
            updateIMUDisplay()
        }
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
                
                updateIMUDisplay()
            }
        }
    }
    
    private fun updateIMUDisplay() {
        runOnUiThread {
            tvIMUStatus?.text = "IMU: Steps=$stepCount, Heading=${currentHeading.toInt()} degrees"
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}
