# Shizuku Performance Overlay (CPU, GPU & FPS)

An Android app utilizing **Shizuku** (`UserService`) to read real-time CPU load, GPU utilization, and `SurfaceFlinger` hardware FPS as a floating, draggable overlay without requiring root.

## How to Build the APK

### Method 1: GitHub Actions (No Android Studio Needed)
1. Push the contents of `ShizukuPerfOverlay` to a new GitHub repository.
2. Open the **Actions** tab in your GitHub repository.
3. Click the **Build Shizuku Overlay APK** workflow run and download `ShizukuPerfOverlay-debug-apk` under **Artifacts**.

### Method 2: Android Studio
1. Open the `ShizukuPerfOverlay` directory in Android Studio.
2. Wait for Gradle Sync to complete.
3. Go to **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
