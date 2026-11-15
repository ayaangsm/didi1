# AcceptSnap

Android background screen-watcher app that detects **"accept"** in both on-screen text and images across any open app, and automatically saves timestamped screenshots into daily date folders (`Pictures/AcceptSnap/YYYY-MM-DD/timestamp.png`).

## Option 1: Build the .apk in the cloud (no Android Studio needed)

1. Create a new GitHub repository and upload all files from this ZIP.
2. Open the **Actions** tab in GitHub and run **Build Android APK** (or push to `main`).
3. Download the compiled `app-debug.apk` artifact and install it on your Android phone.

## Option 2: Build locally with Android Studio or Gradle

1. Open this folder in **Android Studio** (Jellyfish or newer), or run:
   ```bash
   gradle assembleDebug
   ```
2. The compiled APK will be created at:
   `app/build/outputs/apk/debug/app-debug.apk`

## First-Time Android Setup

1. Open **AcceptSnap** and tap **Start Background Watcher** (accept Screen Capture permission).
2. Tap **Open Accessibility Settings** and enable **AcceptSnap Screen Text Watcher** for instant UI text detection across other apps.
3. Tap **Disable Battery Optimization** so Android keeps the service running in the background while you use other apps.
4. Screenshots are automatically saved to `Pictures/AcceptSnap/yyyy-MM-dd/yyyyMMdd_HHmmss_SSS.png`.
