# Wi-Fi Folder Share 📲💻

**Wi-Fi Folder Share** is a modern, high-performance Android application built with **Kotlin** and **Jetpack Compose**. It turns your Android device into a local wireless file and text sharing hub, enabling seamless file management and real-time text/clipboard transfer across any device (PC, Mac, Linux, iPhone, or another Android phone) on the same local Wi-Fi network.

---

## 🚀 Key Features & Capabilities

### 🌐 1. Dual Protocol File Sharing Engine
* **Embedded HTTP Web Server (Default Port `8080`)**:
  * Browse, stream, download, and upload files directly through any web browser (Chrome, Safari, Firefox, Edge).
  * No third-party apps or software installation required on client devices.
  * Mobile-responsive web UI for easy file navigation.
* **FTP Server (Default Port `2121`)**:
  * Full FTP server support compatible with desktop file managers (Windows File Explorer, macOS Finder) and dedicated clients (FileZilla, WinSCP).
  * High-speed batch transfer for large files, videos, and multi-folder structures.

### 📁 2. Storage Access Framework (SAF) & Folder Selector
* **Flexible Directory Selection**: Pick any folder on internal storage or external SD card.
* **Folder Overview**: View total file count and aggregate storage size of the selected directory.
* **In-App Folder Explorer**: View shared folder contents in an interactive modal dialog without leaving the app.

### 📋 3. Real-Time Text Portal & Clipboard Synchronization
* **Cross-Device Text Transfer**: Send short messages, URLs, phone numbers, or code snippets instantly between phone and web clients.
* **Clipboard Integration**: One-tap action to fetch current device clipboard contents or copy incoming text directly to your Android clipboard.

### 📱 4. One-Tap Quick Connectivity
* **Wi-Fi Detection**: Automatically detects local IP address (`192.168.x.x`) and current Wi-Fi SSID.
* **QR Code Generator**: Generates clean QR codes for both HTTP and FTP addresses for quick scanning on mobile devices.
* **Address Sharing**: Quick action button to share server connection URLs via messaging apps, email, or system share sheets.

### 🔒 5. Granular Security & Server Settings
* **Authentication Control**: Enable optional username and password protection to restrict access.
* **Read-Only / Read-Write Modes**: Lock shared folders to read-only mode to prevent external client modifications or uploads.
* **Custom Port Configuration**: Change default HTTP (`8080`) and FTP (`2121`) ports to suit network preferences.
* **Power Management**: "Keep Screen On" mode prevents device sleep during long transfers.

### 📊 6. Real-Time Diagnostics & Background Service
* **Foreground Service Integration**: Persistent status bar notification keeps servers active reliably even when the app is minimized or the screen is off.
* **Live Activity Logs**: Monitor active client connections, file access, uploads, and errors in a real-time log terminal card.
* **Active Client Tracking**: Displays live counter of connected clients.

---

## 🛠️ Architecture & Tech Stack

* **UI Framework**: 100% Jetpack Compose with Material Design 3 (M3).
* **Language**: Kotlin with Coroutines and Flow for asynchronous operations.
* **Architecture Pattern**: MVVM (Model-View-ViewModel) with `StateFlow`.
* **Build Tooling**: Gradle Kotlin DSL (`build.gradle.kts`), compatible with Gradle 8.x / 9.x and JDK 17.
* **CI/CD**: GitHub Actions workflow included (`.github/workflows/android.yml`) for automated APK compilation (`gradle assembleDebug`).

---

## 📦 How to Build & Run

### Prerequisites
* **Java Development Kit (JDK)**: JDK 17 or higher installed.
* **Gradle**: System-installed Gradle (or run via GitHub Actions / IDE).

### Command Line Build
To build the debug APK directly from your terminal:
```bash
gradle assembleDebug
```
The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔄 Automated GitHub Actions CI/CD

This repository includes a ready-to-use GitHub Actions workflow (`.github/workflows/android.yml`). Whenever code is pushed to `main` or `master`:
1. Checks out repository code.
2. Sets up Temurin JDK 17.
3. Configures system Gradle.
4. Builds the debug APK with `gradle assembleDebug`.
5. Uploads `app-debug.apk` as a downloadable workflow artifact.

---

## 📖 Quick Usage Guide

1. **Launch App**: Open **Wi-Fi Folder Share** on your Android device connected to Wi-Fi.
2. **Select Folder**: Tap **Select Folder** and choose the directory you want to share.
3. **Start Server**: Tap the **Power Switch** to launch the Web & FTP servers.
4. **Connect Devices**:
   * Open the provided **Web Address** (e.g., `http://192.168.1.100:8080`) in any browser.
   * Or open the **FTP Address** (e.g., `ftp://192.168.1.100:2121`) in FileZilla or File Explorer.
   * Alternatively, scan the generated **QR Code** with another phone.
