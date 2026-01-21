# Implementation Plan - CloudChat Android App

A chat-style interface that serves as a file uploader/manager for S3 and WebDAV storage.

## Features
- [ ] **Dual Backend Support**: S3 (AWS/Minio) and WebDAV.
- [ ] **User Isolation**: Data stored under `server_root/username/`.
- [ ] **Chat-style UI**: Messages for text, bubbles for files/media.
- [ ] **Multi-account**: Easy switching between usernames.
- [ ] **Share Integration**: Accept files/text shared from other Android apps.

## Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Networking**: OkHttp, AWS SDK for S3
- **Local Storage**: DataStore (for settings)

## Phase 1: Project Setup
- Create project structure (Gradle, Manifest, Basic Activity).
- Design the data models for `Message` and `ServerConfig`.

## Phase 2: Interface & Settings
- Implement `SettingsScreen` for server and username configuration.
- Implement the main `ChatScreen` layout.

## Phase 3: Storage Integration
- [x] Implement `StorageProvider` interface with `testConnection`.
- [x] Implement `S3StorageProvider` and `WebDavStorageProvider`.
- [ ] Implement actual file upload/download background tasks (WorkManager).

## Phase 4: Sharing & Advanced Features
- [x] Handle `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents.
- [x] Implement Image/Video previews in Chat.
- [x] Implement Full-screen Zoomable Image viewer and Video player (ExoPlayer).
- [ ] Implement file upload/download Progress.
- [x] Implement navigation between Main and Settings.

## Phase 5: Build & Packaging
- Guide on generating the APK using Gradle.
