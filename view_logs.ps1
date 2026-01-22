# 查看 CloudChat 应用日志
# 确保手机已通过 USB 连接并启用了 USB 调试

Write-Host "开始监听 CloudChat 日志..." -ForegroundColor Green
Write-Host "按 Ctrl+C 停止" -ForegroundColor Yellow
Write-Host ""

# 清空旧日志
adb logcat -c

# 过滤显示 CloudChat 相关日志
adb logcat | Select-String -Pattern "ChatBubble|ChatRepository|MediaViewer|MainScreen" --Context 0, 0
