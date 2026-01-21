# CloudChat Android App

A chat-interface file manager for S3 and WebDAV.

## Features implemented:
-   **Multi-backend**: Supports S3 (compatible with AWS, Minio, etc.) and WebDAV.
-   **User Isolation**: Automaticaly organizes files under `root/username/`.
-   **Sharing**: Accept files and text shared from other Android apps.
-   **Settings**: Save server and user details locally.

## How to Build the APK:

1.  **Environment**: Ensure you have **Android Studio** (Hedgehog or newer recommended) and **JDK 17** installed.
2.  **Open Project**: Launch Android Studio and select `Open` -> Browse to `c:\project\mychat`.
3.  **Gradle Sync**: Wait for Android Studio to sync dependencies and download the Gradle wrapper.
4.  **Build**: 
    -   Go to `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
    -   The generated APK will be located at: 
        `app/build/outputs/apk/debug/app-debug.apk`

## To-Do:
-   Actual file upload logic using `ContentResolver` to read the shared URIs.
-   Full PROPFIND implementation for WebDAV file listing.
-   Progress indicators for large file uploads.
