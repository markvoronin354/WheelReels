# WheelReels 🚗📱

**WheelReels** is an Android utility application that allows drivers to navigate short-form video apps (Instagram Reels, TikTok, Facebook Reels, YouTube Shorts, Snapchat) using the **Next** and **Previous** track buttons on their car's steering wheel via Bluetooth. Technically this is the corret way of doing it...hands free driving. have fun and stay safe!

---

## ✨ Features

- 🎵 **Steering Wheel Controls**: Intercepts `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`, and Bluetooth AVRCP media session commands.
- ⚡ **Shevery / Shizuku & Root Support**: Uses system ADB commands (`input swipe`) via Shizuku/Shevery or Root (`su`) to bypass OEM accessibility blocks and dispatch gestures.
- 🎯 **Target App Selection**: Choose specific apps (Instagram, TikTok, Facebook, YouTube, Snapchat) or enable **Global Swiping**.
- 🟢 **Head Unit Optimization**: Holds MediaSession AudioFocus and outputs media metadata so car head units enable physical steering wheel buttons.

---

## 🚀 Getting Started

### Prerequisites
- Android 7.0 (API level 24) or higher.
- Bluetooth-enabled car head unit or steering wheel media controls.
- (Optional) [Shizuku](https://shizuku.rikka.app/) or Shevery for system-level touch injection.

### Setup Instructions
1. Clone the repository and build using Android Studio or Gradle:
   ```bash
   git clone https://github.com/your-username/reels-while-driving.git
   cd reels-while-driving
   ./gradlew assembleDebug
   ```
2. Install the APK on your Android device.
3. Open **WheelReels**:
   - Authorize **Shizuku / Shevery** for ADB touch injection.
   - Turn ON the **Steering Wheel Controls** master switch.
4. Connect your phone to your car's Bluetooth audio.
5. Open Instagram Reels, TikTok, or Shorts and press the Next / Prev buttons on your steering wheel!

---

## 🛠️ Architecture

- **`MediaButtonService`**: Foreground service holding `MediaSession` & `AudioFocus` to capture Bluetooth AVRCP commands from car head units and monitor target apps via `UsageStatsManager`.
- **`ShizukuManager`**: Executes privileged ADB shell commands (`input swipe`) when authorized via Shizuku/Shevery or Root (`su`).
- **`PreferencesRepository`**: Stores app settings and package selections using `SharedPreferences`.

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
