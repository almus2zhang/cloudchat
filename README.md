# CloudChat (Android 端)

[English](#english) | [中文](#中文)

---

## 中文

**CloudChat** 是一款采用全新“聊天式”交互设计的云存储管理与笔记协作客户端。它摒弃了传统文件管理器繁琐的树状文件夹结构，将您的云端文件、随手笔记、多媒体资源以类似微信/Telegram 聊天流的形式呈现，让文件存储、检索与管理变得像聊天一样自然高效。

本仓库为 **Android 原生 App (APK)** 版本代码。

### 🌟 核心功能特性

#### 1. 💬 聊天式云端存储交互
- **时间线消息流**：所有上传的文件、照片、视频、语音及随记均以时间线气泡展现，清晰直观。
- **混合文本存储（Hybrid Text Offloading）**：
  - 短文本直接保存在索引元数据中，加载秒开。
  - 长文本（≥500字）自动转存为 `.txt` 文件上传至云存储，加载时自动静默解包读取，兼顾传输效率与数据完整性。
- **长文本智能折叠**：超过 10 行的长文本自动折叠并提供优雅的展开/收起按钮与渐变过度效果。

#### 2. 🔌 双引擎云存储支持 (WebDAV + S3)
- **WebDAV 支持**：无缝对接 Nextcloud, ownCloud, 坚果云, Alist 及各类网盘 WebDAV 接口。
- **S3 协议支持**：支持 AWS S3, MinIO, DigitalOcean Spaces, 阿里云 OSS / 腾讯云 COS（S3 兼容模式）。
- **多账号快速切换**：可保存多个云端存储节点与账号，一键随时切换。
- **隔离用户路径**：自动按 `root/username/` 规范整理文件，保证同一服务器下多用户隔离。

#### 3. 🎬 丰富多媒体预览与播放
- **媒体播放器**：内置原生视频/音频播放组件，支持进度拖动与时间显示。
- **高精缩略图**：自动为上传的图片和视频生成云端/本地缩略图，浏览速度提升 10 倍。
- **图片全屏放大查看**：支持手势缩放、平移与原图加载。

#### 4. 📱 Android 原生深度集成
- **系统级分享菜单（Share Sheet）**：在相册、浏览器、文件管理器等任意 App 点击“分享”，即可直接将图片、文本或文件保存至 CloudChat 云端。
- **本地高能缓存**：采用 Android DataStore 与本地磁盘双重缓存机制，离线亦可快速检索历史记录。
- **安全与混淆防护**：核心凭据混淆处理，Release 版本全量经过 ProGuard / R8 压缩与混淆，防反编译防泄漏。

---

### 📦 三端生态与互通说明

CloudChat 采用统一的数据结构标准，全平台数据 100% 无缝互通：

| 平台端 | 架构技术 | 特色优势 |
| :--- | :--- | :--- |
| **Android (APK)** | Kotlin + Jetpack Compose + Coroutines | 原生系统分享集成、手势流畅、后台稳定同步 |
| **Web 网页端** | React + Vite + TailwindCSS | 无需安装、跨浏览器随处访问、内置日记导出 |
| **Desktop 桌面端** | Rust + Tauri 2.0 | 内存占用极低（<50MB）、秒速启动、本地文件拖拽 |

---

### 🛠️ 编译构建指南

1. **环境准备**：
   - Android Studio (Hedgehog 2023.1.1 或更高版本)
   - JDK 17
2. **配置密钥**：
   - 复制 `app/src/main/java/com/cloudchat/utils/ConfigHelper.kt.example` 为 `ConfigHelper.kt`。
   - （可选）运行 `python generate_secrets.py` 生成加密混淆凭据。
3. **编译 APK**：
   - 执行 Gradle 构建：`./gradlew assembleDebug` 或在 Android Studio 中点击 `Build -> Build APKs`。
   - 生成的 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

---

## English

**CloudChat** is a unique cloud storage and personal knowledge manager featuring a **chat-style interface**. Instead of traditional nested folders, CloudChat displays your files, notes, images, and videos as intuitive message bubbles in a seamless conversational stream.

This repository contains the source code for the **Android Native App (APK)**.

### 🌟 Key Features

- **Chat-Inspired UI**: Files and notes presented as chronological message bubbles.
- **Hybrid Text Offloading**: Short notes saved in metadata for instant access; long notes (≥500 chars) automatically offloaded as `.txt` cloud files.
- **Dual Backend**: Full support for both **WebDAV** (Nextcloud, Alist, etc.) and **S3-compatible API** (AWS S3, MinIO, etc.).
- **Smart Text Folding**: Long messages exceeding 10 lines are automatically truncated with expandable toggles.
- **Native Android Sharing**: Direct target for the Android Share Sheet—save images, text, and files from any application instantly.
- **Media Player & Viewer**: Built-in player for audio/video streaming and gesture-zoomable image viewer.
- **ProGuard / R8 Security**: Confidential configs obfuscated with XOR and optimized with ProGuard for release builds.
