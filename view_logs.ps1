# 查看 CloudChat 应用崩溃日志（完整堆栈）
# 使用前提：手机已通过 USB 连接电脑，并开启 USB 调试

$adb = "C:\Users\ken\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "检查设备连接..." -ForegroundColor Green
& $adb devices
Write-Host ""

Write-Host "清空旧日志..." -ForegroundColor Yellow
& $adb logcat -c

Write-Host "开始监听崩溃日志（FATAL EXCEPTION / AndroidRuntime）..." -ForegroundColor Green
Write-Host "请在手机上打开 CloudChat 触发闪退，然后按 Ctrl+C 停止" -ForegroundColor Yellow
Write-Host ""

# 监听 AndroidRuntime 崩溃和 CloudChat 相关日志
& $adb logcat | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|ChatBubble|ChatRepository|MainScreen|cloudchat" -Context 0, 40
