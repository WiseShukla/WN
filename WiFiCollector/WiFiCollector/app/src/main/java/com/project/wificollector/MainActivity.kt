package com.project.wificollector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private val csvFile by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "wifi_fingerprints.csv"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        }
        
        setupUI()
    }
    
    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        
        // Title
        TextView(this).apply {
            text = "📡 WiFi Data Collector"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layout.addView(this)
        }
        
        layout.addView(Space(this).apply { minimumHeight = 40 })
        
        // Location input
        TextView(this).apply {
            text = "Location Name:"
            textSize = 18f
            layout.addView(this)
        }
        
        val etLocation = EditText(this).apply {
            hint = "e.g., elevator_exit"
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etLocation)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        // X coordinate
        TextView(this).apply {
            text = "X Coordinate (meters):"
            textSize = 18f
            layout.addView(this)
        }
        
        val etX = EditText(this).apply {
            hint = "e.g., 0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etX)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        // Y coordinate
        TextView(this).apply {
            text = "Y Coordinate (meters):"
            textSize = 18f
            layout.addView(this)
        }
        
        val etY = EditText(this).apply {
            hint = "e.g., 0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 18f
            setPadding(20, 20, 20, 20)
        }
        layout.addView(etY)
        
        layout.addView(Space(this).apply { minimumHeight = 40 })
        
        // Status
        val tvStatus = TextView(this).apply {
            text = "Ready to collect"
            textSize = 20f
            setTextColor(android.graphics.Color.BLUE)
        }
        layout.addView(tvStatus)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        // Sample count
        val tvCount = TextView(this).apply {
            text = "Total samples: 0"
            textSize = 16f
        }
        layout.addView(tvCount)
        
        layout.addView(Space(this).apply { minimumHeight = 40 })
        
        // Scan button
        val btnScan = Button(this).apply {
            text = "📍 SCAN HERE (50x)"
            textSize = 20f
            setPadding(40, 40, 40, 40)
            
            setOnClickListener {
                val location = etLocation.text.toString().trim()
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
                
                isEnabled = false
                tvStatus.text = "Scanning..."
                
                Thread {
                    var scansCompleted = 0
                    
                    repeat(50) { i ->
                        wifiManager.startScan()
                        Thread.sleep(200)
                        
                        val results = wifiManager.scanResults
                        
                        val timestamp = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                        
                        synchronized(csvFile) {
                            if (!csvFile.exists()) {
                                csvFile.writeText("Timestamp,Location,X,Y,BSSID,SSID,RSSI,Frequency\n")
                            }
                            
                            results.forEach { ap ->
                                csvFile.appendText(
                                    "$timestamp,$location,$x,$y,${ap.BSSID},${ap.SSID},${ap.level},${ap.frequency}\n"
                                )
                            }
                        }
                        
                        scansCompleted++
                        
                        runOnUiThread {
                            tvStatus.text = "Scanning... $scansCompleted/50"
                        }
                    }
                    
                    runOnUiThread {
                        tvStatus.text = "✅ Saved 50 scans at $location"
                        isEnabled = true
                        
                        val totalSamples = csvFile.readLines().size - 1
                        tvCount.text = "Total samples: $totalSamples"
                        
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Data saved to Downloads/wifi_fingerprints.csv",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }
        layout.addView(btnScan)
        
        layout.addView(Space(this).apply { minimumHeight = 20 })
        
        // View location button
        Button(this).apply {
            text = "📂 View CSV Location"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Saved to:\n${csvFile.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
            layout.addView(this)
        }
        
        val scrollView = ScrollView(this)
        scrollView.addView(layout)
        setContentView(scrollView)
    }
}
