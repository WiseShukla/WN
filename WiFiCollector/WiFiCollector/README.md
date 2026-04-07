# WiFi Fingerprint Collector App

Simple Android app to collect WiFi signal data for indoor localization project.

## 📱 SUPER EASY BUILD INSTRUCTIONS (5 MINUTES!)

### Step 1: Download Android Studio
- Go to: https://developer.android.com/studio
- Download and install (it's free)
- Open Android Studio

### Step 2: Import This Project
1. In Android Studio, click: **File → Open**
2. Navigate to this `WiFiCollector` folder
3. Click **OK**
4. Wait 2-3 minutes for Gradle sync (automatic)

### Step 3: Build the APK
1. Click: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait 1-2 minutes
3. When done, click "locate" in the popup
4. You'll find: `app-debug.apk`

### Step 4: Install on Your Phone
1. Copy `app-debug.apk` to your phone
2. Open the file on your phone
3. Allow "Install from unknown sources" if asked
4. Install!

## OR: Run Directly from Android Studio

1. Connect your phone via USB
2. Enable USB Debugging on phone:
   - Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back → Developer Options → Enable USB Debugging
3. In Android Studio, click the green ▶️ **Run** button
4. Select your phone
5. App installs automatically!

## 📊 How to Use the App

1. Open the app
2. Enter:
   - Location Name (e.g., "elevator_exit")
   - X Coordinate (e.g., "0")
   - Y Coordinate (e.g., "0")
3. Click **"📍 SCAN HERE (50x)"**
4. Wait ~10 seconds
5. Move to next location and repeat

## 📁 Output File

- File location: `Downloads/wifi_fingerprints.csv`
- Format: Timestamp, Location, X, Y, BSSID, SSID, RSSI, Frequency
- Connect phone to computer to copy the file

## 🔧 Troubleshooting

**Problem: Gradle sync fails**
- Solution: Wait for Android Studio to download dependencies (internet required)

**Problem: Can't find APK**
- Solution: Look in `WiFiCollector/app/build/outputs/apk/debug/app-debug.apk`

**Problem: App crashes on phone**
- Solution: Go to Settings → Apps → WiFi Collector → Permissions → Allow Location

## 📞 Support

If stuck, ask any teammate who has built an Android app before. 
The process is standard and takes only 5 minutes.

## ✅ What This App Does

- Scans WiFi networks 50 times at each location
- Records BSSID (unique WiFi ID), RSSI (signal strength), and coordinates
- Saves everything to CSV file automatically
- No manual data entry needed!
