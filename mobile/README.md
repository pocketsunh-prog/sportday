# SportDay Mobile (Android)

Native Android app built with Kotlin and Jetpack Compose that accesses the SportDay backend API.

## Architecture

- **Language**: Kotlin 2.1, Java 25
- **UI**: Jetpack Compose with Material 3
- **Networking**: Retrofit + Gson
- **Local Storage**: DataStore Preferences (JWT token storage)
- **Navigation**: Jetpack Navigation Compose
- **Compile SDK**: 34 | **Min SDK**: 24 | **Target SDK**: 34

## Setup

1. Open the `mobile/` folder in Android Studio (Iguana or newer)
2. Let Gradle sync and download dependencies
3. Start the backend with `docker-compose up -d` and `mvn spring-boot:run` from `../backend/`
4. Run the app on an Android emulator

## API Connection

The app connects to the backend at `http://10.0.2.2:8080/` (Android emulator → host localhost). Make sure the backend is running before launching the app.

## Screens

| Screen | Description |
|--------|-------------|
| Login | Authenticate with username/password |
| Register | Create new account |
| Events | Browse and filter events |
| Event Detail | View details, enroll/cancel enrollment |
| My Enrollments | View personal enrollment history |
| Results | View leaderboard per event |
| Profile | View/edit profile, logout |

## Features

- JWT authentication with token persistence
- Event browsing with enabled/disabled filter
- Event enrollment and cancellation
- Event results leaderboard with ranking
- User profile management
- Role-based access (USER, MANAGER, ADMIN)

## Requirements

- Android Studio Ladybug (2024.3) or newer
- Android SDK 36
- JDK 25
- Running backend at port 8080

### Build Release APK from Command Line

```powershell
# Set JDK 17 (required)
$env:JAVA_HOME = "C:\path\to\jdk-17"

### Set `JAVA_HOME` to your JDK 17 install:
   - **Windows (PowerShell):**
     ```powershell
     $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
     ```
   - **Windows (cmd):**
     ```bat
     set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
     ```
   - **macOS / Linux:**
     ```bash
     export JAVA_HOME=$(/usr/libexec/java_home -v 17)
     ```
# Build
.\gradlew assembleRelease
```
**Install on device:**
```bash
adb install app/build/outputs/apk/release/app-release.apk
```
