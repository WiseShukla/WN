# ⚡ SUPER QUICK APK BUILD (No Android Studio GUI!)

If you prefer command line or just want the APK fast:

## Option 1: Using Android Studio (Recommended)

1. Open Android Studio
2. File → Open → Select WiFiCollector folder
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. Done! APK is in: `app/build/outputs/apk/debug/app-debug.apk`

## Option 2: Command Line (For Advanced Users)

```bash
# On Windows:
cd WiFiCollector
gradlew.bat assembleDebug

# On Mac/Linux:
cd WiFiCollector
./gradlew assembleDebug
```

APK will be in: `app/build/outputs/apk/debug/app-debug.apk`

## Option 3: Online Build Service

1. Upload this folder to: https://appetize.io/build
2. Select "Build APK"
3. Download result

## Option 4: Ask a Friend

Give this folder to anyone who has Android Studio installed.
They can build it in 2 minutes and send you the APK.

## 📦 What You Get

File: `app-debug.apk` (about 2-3 MB)

This is YOUR app, ready to install on any Android phone!

## 🔐 Security Note

This APK is unsigned (debug build). It's perfectly safe for your project.
For production apps, you'd need to sign it, but not for this project.
