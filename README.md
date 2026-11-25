# BatteryWatch (Strażnik Baterii)

🇬🇧 English · 🇵🇱 [Polski](README.pl.md)

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/FoxSoftware-code/BatteryWatch)](../../releases/latest)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

An Android app that shows **what is really draining your battery** and helps you fix it.
It reads the same data the system uses (`dumpsys batterystats`, `dumpsys cpuinfo`),
turns it into a readable diagnosis and suggests concrete fixes. Every fix can be undone.

> **No internet permission.** All measurements and history stay on your phone. No ads, no trackers.

> The app's interface is currently in **Polish**. An English translation is planned. Contributions are welcome.

## Features

| Screen | What it shows |
|---|---|
| **Live** | Charge level, current draw (mA / W), temperature, drain rate in %/h and the processes using the most CPU. Refreshes only while the screen is open. |
| **Charging** | A monitor that runs only while plugged in and starts automatically when you connect a charger. Every 10 s it records level, current, voltage, power, temperature and screen state. For each session it computes the rate in %/h, time to 80% and 100%, mAh added and estimated battery capacity, peak and average power, and time above 40 °C. It detects thermal throttling and charging pauses. Includes charts, 30-day history and alerts for charge thresholds (80/85/90/100%) and overheating. |
| **Diagnosis** | Analysis of `batterystats` since the last full charge: drain with the screen on and off, apps, wakelocks, wakeups and network. It ends with a list of findings sorted by severity, each with a one-tap action. |
| **History** | A sample every 15 minutes (WorkManager, no permanent background service) and separate drain rates for screen off and screen on. |
| **Actions** | Auto-brightness, screen timeout, background Wi-Fi/Bluetooth scanning, shortcuts to gestures and AOD, and restricting apps in the background. Every change asks for confirmation and is logged, so you can undo it. |

## Requirements

- Android 12 (API 31) or newer. Developed and tested on a **OnePlus CPH2609 running Android 16**. On other phones some data may be missing or formatted differently. Please [report it](../../issues).
- A computer with [ADB](https://developer.android.com/tools/adb), and USB or wireless debugging enabled on the phone.
- Optional: [Shizuku](https://shizuku.rikka.app/) for actions that need shell privileges.

## Installation

### Download

Get the signed APK from [**Releases**](../../releases/latest), install it, then grant the permissions (see below).

### Build from source

```bash
# JDK 17+ (e.g. the one bundled with Android Studio) and the Android SDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Grant permissions

```bash
./setup_permissions.sh
```

Battery data on Android is protected by system permissions that a normal app cannot request.
[`setup_permissions.sh`](setup_permissions.sh) grants them once over ADB:

| Permission | Why |
|---|---|
| `DUMP`, `BATTERY_STATS`, `PACKAGE_USAGE_STATS` | read `dumpsys batterystats` and `cpuinfo` |
| `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS` | actions: brightness, screen timeout, Wi-Fi/BT scanning |
| `POST_NOTIFICATIONS` | charging monitor notifications |
| battery optimization exemption | start the charging monitor from the background when a charger is connected |

The permissions survive reboots. You only need to grant them again after uninstalling the app.

> **OnePlus / OPPO / realme:** before running the script, enable
> **"Disable permission monitoring"** in Developer options. Without it the system rejects the ADB commands.
> You can turn the switch back off afterwards.

### Shizuku (optional)

When [Shizuku](https://shizuku.rikka.app/) is running, **"Restrict in background"** and **"Force stop"** run directly
(`appops RUN_ANY_IN_BACKGROUND`, `standby-bucket restricted`, `am force-stop`). Without Shizuku the app
opens that app's settings page and you make the change yourself.

## Project structure

```
app/src/main/java/pl/foxsoftware/batterywatch/
├── data/       device readers, dumpsys parsers, history and charging session databases
├── domain/     diagnosis rules (FindingsEngine), charging analysis, applying and undoing actions
├── charging/   charging monitor service and its automatic start
└── ui/         Jetpack Compose screens and the ViewModel
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The parser and diagnosis rules are tested against a real, **anonymized** `batterystats` dump
from a OnePlus CPH2609 ([`app/src/test/resources`](app/src/test/resources)). Email addresses and
third-party app names were replaced with `example.com` and `com.example.appNN`.

To add a dump from your own phone (`adb shell dumpsys batterystats > dump.txt`), remove email
addresses and the list of installed apps before sharing it.

## Contributing

Contributions are welcome, especially:

- **Testing on other phones** (Samsung, Pixel, Xiaomi…) and reporting what's missing
- **English translation** of the interface
- New diagnosis rules in [`FindingsEngine`](app/src/main/java/pl/foxsoftware/batterywatch/domain/FindingsEngine.kt)

Look for issues labeled [`good first issue`](../../issues?q=is%3Aopen+label%3A%22good+first+issue%22),
or ask in [Discussions](../../discussions). When reporting a bug, include your phone model, Android
version and what doesn't show up. Don't paste a full `batterystats` dump, because it contains personal data.

## Security

- The app doesn't connect to the internet and doesn't send any data.
- Every settings change asks for confirmation and stores the previous value, so you can undo it from the app.
- The ADB permissions are powerful (`WRITE_SECURE_SETTINGS`, `DUMP`). Only install APKs built from this source or downloaded from this repository's Releases.

## License

[MIT](LICENSE)
