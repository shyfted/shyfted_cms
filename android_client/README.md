# Shyfted Client Android

Android-native Shyfted device client.

This is the Android equivalent starting point for `device_clients/device.py`. The current release is a branded Shyfted device shell that loads the configured CMS URL in a full-screen WebView. The source also includes small endpoint/device-spec classes so heartbeat, config polling, media download, caching, and renderer-specific behavior can be added without reshaping the app later.

## Requirements

- Android Studio with Android SDK installed
- JDK 17, normally bundled with current Android Studio
- Android SDK platform 35, or update `compileSdk` in `app/build.gradle` to an installed SDK
- Android 11 device with ADB over Wi-Fi enabled

No Google Play Services, Chrome, or external browser dependency is used.

## Phase 0 Device Configuration

First-boot defaults:

```properties
device.name=Petey
device.id=petey_001
cms.url=https://cms.shyfted.com.au
```

Runtime configuration is loaded in this order:

1. Built-in first-boot defaults
2. App preferences, reserved for a future settings/MDM surface
3. External app-specific properties file
4. ADB launch extras

The external configuration file path on device is:

```bash
/sdcard/Android/data/au.com.shyfted.client/files/shyfted-client.properties
```

Create or replace it over ADB:

```bash
adb shell 'mkdir -p /sdcard/Android/data/au.com.shyfted.client/files'
adb shell 'cat > /sdcard/Android/data/au.com.shyfted.client/files/shyfted-client.properties <<EOF
device.name=Petey
device.id=petey_001
cms.url=https://cms.shyfted.com.au
EOF'
```

One-off launch overrides are also supported:

```bash
adb shell am start \
  -n au.com.shyfted.client/.MainActivity \
  --es device.name Petey \
  --es device.id petey_001 \
  --es cms.url https://cms.shyfted.com.au
```

## Build In Android Studio

1. Open `android_client` in Android Studio.
2. Let Gradle sync complete.
3. Select `app` as the run configuration.
4. Build with `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

The debug APK will be created at:

```bash
android_client/app/build/outputs/apk/debug/app-debug.apk
```

## Build From Command Line

From this directory, use a local Gradle installation:

```bash
gradle :app:assembleDebug
```

If you prefer a Gradle wrapper, generate it from Android Studio or from a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

## Install Over ADB

Connect to the Android 11 RK3566 device over Wi-Fi:

```bash
adb connect DEVICE_IP:5555
adb devices
```

Install or replace the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Launch Over ADB

```bash
adb shell am start -n au.com.shyfted.client/.MainActivity
```

To view logs:

```bash
adb logcat | grep -i shyfted
```

## Current v0.1 Behavior

- Package name: `au.com.shyfted.client`
- App name: `Shyfted Client`
- Shyfted avatar launcher icon
- Shyfted branded splash/loading and offline screens
- External configuration for device name, device ID, and CMS URL
- Full-screen native Android WebView
- Loads the configured CMS URL on launch
- JavaScript enabled
- DOM storage enabled
- Screen kept awake
- Built-in Shyfted offline page with a reconnect button

## CMS Device Contract Reviewed

The Raspberry Pi client in `../device_clients/device.py` currently:

- Uses `SHYFTED_CMS_URL`, defaulting to `https://cms.shyfted.com.au`
- Uses `SHYFTED_DEVICE_ID`, defaulting to `device_001`
- Posts device metadata to `/device/<device_id>/heartbeat`
- Polls `/device/<device_id>/config`
- Downloads rendered screen media from CMS-provided URLs
- Tracks `content_id` to avoid re-rendering unchanged LCD/e-ink content
- Delegates output to LCD and e-ink render paths

Future Android work should add those behaviors around `CmsEndpoints` and `DeviceSpec`, then introduce storage, polling, and renderer classes as needed.
