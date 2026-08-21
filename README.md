# CloudChat Android v1.0.0

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/almus2zhang/cloudchat)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)](https://github.com/almus2zhang/cloudchat)

> ⚠️ **重要说明**：本项目依托 **WebDAV 存储协议** 实现多端无缝数据同步与备份；S3 存储协议暂未测试。

---

## 💻 关联项目

- **Web 与 Windows 桌面客户端**：[CloudChat Web & Desktop 客户端仓库](https://github.com/almus2zhang/cloudchat-web)

---

## 🌟 核心功能特性

### 1. 🔗 系统原生分享集成接收 (System Share Integration)
- **全系统应用无缝接入**：已注册 Android 系统原生 `SEND` 与 `SEND_MULTIPLE` Intent 接收器。
- **一键分享到 CloudChat**：在相册、浏览器、文件管理器、社交软件中选择“分享到 CloudChat”，自动接收并直接发送文本、图片与多媒体文件。

### 2. 🔳 消息与图片合并 (Message & Image Combination)
- 支持选中多条文本与图片，一键拼接合并为长卡片消息，便于组织与浏览。

### 3. 📁 文件夹打包归档 (Folder Archiving & Management)
- 支持多选聊天记录打包归档为文件夹卡片，支持文件夹层级移入与解散还原。

### 4. 📖 HTML 静态日记生成 (Static HTML Diary Generation)
- 一键提取聊天记录与多媒体素材，生成排版精致的静态 HTML 网页日记，支持手机端本地预览与导出。

### 5. ⚡ 物理修改时间增量同步与强制刷新 (Incremental Sync & Force Refresh)
- **快速增量同步 (Quick Sync)**：结合 WebDAV `PROPFIND` 响应的物理 `Last-Modified` 修改时间戳，未产生新记录时零流量拦截。
- **普通同步与强制刷新 (Force Refresh)**：支持手动拉取完整历史，以及当发生冲突时提供“用本地记录强制覆盖服务器”能力。

---

## 🔧 WebDAV 服务端配置与排查指南

在通过反向代理（如 Nginx、Nginx Proxy Manager、宝塔面板、FRP 等）接入 WebDAV 服务器时，如果出现 `GET/HEAD` 返回 404 成功，但 `PROPFIND` 或 `MKCOL` 拦截报错 `Failed to fetch`，说明网关未放行跨域响应头或 WebDAV 特殊谓词方法。

### 反向代理配置（Nginx 示例）

必须在配置中**允许 WebDAV 特殊谓词**并**放行跨域响应头**：

```nginx
# 1. 允许 WebDAV 扩展 HTTP 方法
dav_methods PUT DELETE MKCOL COPY MOVE;

# 2. 设置允许跨域方法 (必须包含 PROPFIND 与 MKCOL)
add_header 'Access-Control-Allow-Methods' 'GET, POST, OPTIONS, PUT, DELETE, PROPFIND, MKCOL' always;

# 3. 设置允许跨域请求头 (必须包含 Authorization, Content-Type, Depth)
add_header 'Access-Control-Allow-Headers' 'Authorization, Content-Type, Depth, X-Requested-With' always;
```
```lucky
# 4. lucky找到反代高级设置
跨域支持 指定允许的跨域方法
GET, POST, OPTIONS, PUT, DELETE, PROPFIND, MKCOL
```
---

## 📱 构建与编译

本项目基于原生 **Kotlin + Jetpack Compose** 开发。

```bash
# 清理缓存并构建 Debug APK
./gradlew assembleDebug --no-build-cache
```

编译产物位置：`app/build/outputs/apk/debug/app-debug.apk`
