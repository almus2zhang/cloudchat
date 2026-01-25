# CloudChat Android App

[English](#english) | [中文](#中文)

---

## English

CloudChat is a unique Android application that provides a **chat-style interface** for managing your files on cloud storage (S3 and WebDAV). Instead of a traditional folder-tree view, it presents your files as "messages" in a conversation, making mobile file management familiar and intuitive.

### Detailed Features:

-   **Dual Backend Connectivity**: 
    -   **S3 Support**: Connect to any S3-compatible service (AWS S3, Minio, DigitalOcean Spaces, etc.).
    -   **WebDAV Support**: Seamless integration with WebDAV servers for personal cloud storage (Nextcloud, OwnCloud, etc.).
-   **Chat-Inspiration UI**:
    -   Files are displayed as message bubbles in a chronological flow.
    -   Support for modern UI elements like **Zoomable Images** and a dedicated **Video Player** for media hosted on the cloud.
-   **Intelligent File Organization**:
    -   **Multi-Account Support**: Manage multiple cloud configurations and switch between them easily.
    -   **Automatic Pathing**: Files are automatically organized under `root/username/` to maintain a clean and isolated environment for different users on the same server.
-   **Seamless Android Integration**:
    -   **Share Sheet Target**: Directly upload files or text snippets to your cloud by sharing from other apps (e.g., Photos, Browser, File Managers).
    -   **Thumbnail Previewing**: Generates and shows thumbnails for media files to quickly identify content.
-   **Robust Security & Privacy**:
    -   **Confidential Configuration**: Core server credentials are XOR-obfuscated within the binary to prevent casual discovery via decompilation.
    -   **Optimized Release**: Release builds are processed with ProGuard/R8 to minify code and obfuscate logic for enhanced security.
    -   **Local Storage**: Account settings are stored securely using Android DataStore (Preferences).

### How to Build the APK:

1.  **Environment**: Ensure you have **Android Studio** (Hedgehog or newer recommended) and **JDK 17** installed.
2.  **Configuration & Secrets**:
    *   The project uses XOR-obfuscated secrets to protect WebDAV credentials.
    *   Copy `app/src/main/java/com/cloudchat/utils/ConfigHelper.kt.example` to `app/src/main/java/com/cloudchat/utils/ConfigHelper.kt`.
    *   (Optional) Use `generate_secrets.py` to generate your own XORed byte arrays for the URL, user, and password. 
    *   Update `ConfigHelper.kt` with your values.
3.  **Open Project**: Launch Android Studio and select `Open` -> Browse to project directory.
4.  **Gradle Sync**: Wait for Android Studio to sync dependencies and download the Gradle wrapper.
5.  **Build**: 
    -   Go to `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
    -   The generated APK will be located at: `app/build/outputs/apk/debug/app-debug.apk`

---

## 中文

CloudChat 是一款独特的 Android 应用，它为存储在云端（S3 和 WebDAV）的文件提供了一个**聊天式交互界面**。与传统的文件夹树状视图不同，它将文件以对话中的“消息”形式展现，使移动端的文件管理变得像聊天一样熟悉且直观。

### 详细功能描述：

-   **双存储后端支持**：
    -   **S3 支持**：可连接任何兼容 S3 的服务（如 AWS S3, Minio, DigitalOcean Spaces 等）。
    -   **WebDAV 支持**：无缝集成 WebDAV 服务器，适用于个人云存储（如 Nextcloud, OwnCloud 等）。
-   **聊天式 UI 设计**：
    -   文件以时间线的形式显示在消息气泡中。
    -   内置现代化的 UI 组件，支持**图片缩放查看**和专用的**视频播放器**，可直接播放云端媒体。
-   **智能文件组织**：
    -   **多账号管理**：支持保存多个云端配置并可随时切换。
    -   **自动化路径管理**：文件自动整理在 `root/username/` 路径下，确保同一服务器上不同用户的环境清晰且隔离。
-   **无缝 Android 系统集成**：
    -   **系统分享支持**：通过 Android 原生分享菜单，可直接将其他应用（如相册、浏览器、文件管理器）中的文件或文本上传至云端。
    -   **缩略图预览**：自动生成并显示媒体文件的缩略图，方便快速识别内容。
-   **健全的安全与隐私防护**：
    -   **加密配置文件**：核心服务器凭据在二进制文件中经过 XOR 混淆处理，防止通过简单的反编译获取敏感信息。
    -   **发布优化**：Release 版本通过 ProGuard/R8 开启了代码压缩和混淆，进一步增强了应用的安全性。
    -   **本地安全存储**：账号设置通过 Android DataStore (Preferences) 进行本地加密存储。

### 如何编译 APK：

1.  **环境准备**：确保安装了 **Android Studio** (建议 Hedgehog 或更高版本) 和 **JDK 17**。
2.  **配置与密钥设置**：
    *   项目使用 XOR 混淆保护 WebDAV 凭据。
    *   将 `app/src/main/java/com/cloudchat/utils/ConfigHelper.kt.example` 复制为 `app/src/main/java/com/cloudchat/utils/ConfigHelper.kt`。
    *   （可选）使用 `generate_secrets.py` 生成你自己的 URL、用户名和密码的 XOR 加密字节数组。
    *   在 `ConfigHelper.kt` 中更新相关值。
3.  **打开项目**：启动 Android Studio，选择 `Open` 并浏览到项目目录。
4.  **Gradle 同步**：等待 Android Studio 同步依赖项并下载 Gradle Wrapper。
5.  **编译构建**： 
    -   点击菜单栏 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
    -   生成的 APK 文件位于：`app/build/outputs/apk/debug/app-debug.apk`
