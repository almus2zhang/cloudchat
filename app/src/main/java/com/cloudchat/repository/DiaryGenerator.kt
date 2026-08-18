package com.cloudchat.repository

import com.cloudchat.model.ChatMessage
import com.cloudchat.model.MessageType
import java.util.Locale

/**
 * 静态日记网页生成器（移植自 cloudchat-web 的 diaryGenerator.js）
 * 支持 wechat（朋友圈九宫格）和 journal（简约现代）两种模板，以及密码锁。
 */
object DiaryGenerator {

    fun escapeHtml(str: String?): String {
        if (str == null) return ""
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val k = 1024.0
        val sizes = arrayOf("B", "KB", "MB", "GB")
        val i = (Math.log(bytes.toDouble()) / Math.log(k)).toInt().coerceIn(0, sizes.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(k, i.toDouble()), sizes[i])
    }

    /** 根据图片数量返回动态宫格列数 class（微信朋友圈规则 + 超过9张仍继续 3 列） */
    fun gridClassFor(count: Int): String {
        return when (count) {
            1 -> "grid-c1"
            2 -> "grid-c2"
            3 -> "grid-c3"
            4 -> "grid-c4"   // 2x2
            else -> "grid-c3" // 5+ 张统一 3 列
        }
    }

    fun cleanFileName(name: String): String {
        return name.split("/").last().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    data class FormatDate(
        val full: String,
        val date: String,
        val time: String,
        val year: Int,
        val monthDay: String
    )

    fun formatDate(ts: Long): FormatDate {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        return FormatDate(
            full = "%04d-%02d-%02d %02d:%02d".format(y, m, d, h, min),
            date = "%04d-%02d-%02d".format(y, m, d),
            time = "%02d:%02d".format(h, min),
            year = y,
            monthDay = "%d月%d日".format(m, d)
        )
    }

    /** 同一 groupId 的消息聚合为组 */
    fun groupMessages(msgs: List<ChatMessage>): List<DiaryGroup> {
        val emitted = mutableSetOf<String>()
        val result = mutableListOf<DiaryGroup>()
        for (msg in msgs) {
            if (emitted.contains(msg.id)) continue
            val gid = msg.groupId?.trim().orEmpty()
            if (gid.isNotEmpty()) {
                val members = msgs.filter { it.groupId?.trim().orEmpty() == gid }
                members.forEach { emitted.add(it.id) }
                if (members.size > 1) {
                    result.add(DiaryGroup(id = gid, sender = members[0].sender, senderName = members[0].senderName,
                        timestamp = members[0].timestamp, isGroup = true, messages = members))
                } else {
                    result.add(DiaryGroup(id = msg.id, sender = msg.sender, senderName = msg.senderName,
                        timestamp = msg.timestamp, isGroup = false, messages = listOf(msg)))
                }
            } else {
                result.add(DiaryGroup(id = msg.id, sender = msg.sender, senderName = msg.senderName,
                    timestamp = msg.timestamp, isGroup = false, messages = listOf(msg)))
                emitted.add(msg.id)
            }
        }
        return result
    }

    data class DiaryGroup(
        val id: String,
        val sender: String,
        val senderName: String?,
        val timestamp: Long,
        val isGroup: Boolean,
        val messages: List<ChatMessage>
    )

    /** 媒体 URL 解析器接口：由调用方提供「消息 -> 相对 URL」的映射 */
    interface MediaUrlResolver {
        fun resolve(msg: ChatMessage): String
        fun resolveAvatar(msg: ChatMessage, default: String): String
    }

    /**
     * 生成日记 HTML
     * @param folderName 标题
     * @param author 作者
     * @param templateId "wechat" | "journal"
     * @param password 访问密码（空则不加密）
     * @param messages 消息列表
     * @param resolver 媒体/头像 URL 解析
     */
    fun generateHtml(
        folderName: String,
        author: String,
        templateId: String,
        password: String,
        messages: List<ChatMessage>,
        resolver: MediaUrlResolver,
        coverUrl: String? = null,
        folderTree: FolderNode? = null
    ): String {
        val isWeChat = templateId == "wechat"
        val sorted = messages.sortedWith(
            if (isWeChat) compareByDescending { it.timestamp } else compareBy { it.timestamp }
        )
        val titleStr = folderName.ifBlank { "我的日记" }
        val authorStr = author.ifBlank { "CloudChat User" }

        val passwordHash = if (password.isNotBlank()) sha256Hex(password.trim()) else ""
        val groups = groupMessages(sorted)

        val groupedCount = groups.size

        // 渲染单条子项
        fun renderSubItem(sub: ChatMessage): String {
            val mediaUrl = resolver.resolve(sub)
            val isLocation = sub.content.startsWith("[位置]")
            val addr = sub.locationAddress ?: sub.content.removePrefix("[位置]").trim()
            return when {
                isLocation -> {
                    "<div class=\"wechat-location-badge\" style=\"margin: 4px 0;\">" +
                        "<svg class=\"icon\" viewBox=\"0 0 24 24\"><path fill=\"currentColor\" d=\"M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z\"/></svg>" +
                        "<span>${escapeHtml(addr)}</span></div>"
                }
                sub.type == MessageType.IMAGE -> {
                    "<div class=\"wechat-media-box\" style=\"margin: 4px 0;\">" +
                        "<img src=\"$mediaUrl\" class=\"wechat-single-img\" loading=\"lazy\" onclick=\"openLightbox(this.src)\"/>" +
                        (if (!sub.caption.isNullOrBlank()) "<div class=\"wechat-caption-sub\"><div class=\"caption-item\">${escapeHtml(sub.caption)}</div></div>" else "") +
                        "</div>"
                }
                sub.type == MessageType.VIDEO -> {
                    "<div class=\"wechat-media-box\" style=\"margin: 4px 0;\">" +
                        "<video src=\"$mediaUrl\" controls preload=\"none\" class=\"wechat-video-player\"></video>" +
                        (if (!sub.caption.isNullOrBlank()) "<div class=\"wechat-caption-sub\"><div class=\"caption-item\">${escapeHtml(sub.caption)}</div></div>" else "") +
                        "</div>"
                }
                sub.type == MessageType.AUDIO -> {
                    "<div class=\"wechat-audio-box\" style=\"margin: 4px 0;\"><audio src=\"$mediaUrl\" controls preload=\"none\" class=\"wechat-audio-player\"></audio></div>"
                }
                sub.type == MessageType.FILE -> {
                    "<div class=\"wechat-file-box\" style=\"margin: 4px 0;\">" +
                        "<div class=\"file-info\"><a href=\"$mediaUrl\" target=\"_blank\" download=\"${escapeHtml(sub.content)}\" class=\"file-name\">${escapeHtml(sub.content)}</a>" +
                        "<span class=\"file-size\">${formatBytes(sub.fileSize)}</span></div></div>"
                }
                else -> {
                    "<div class=\"wechat-text-content\" style=\"margin: 4px 0;\">${escapeHtml(sub.content)}</div>"
                }
            }
        }

        // 微信朋友圈渲染
        fun renderWeChat(list: List<ChatMessage>): String {
            val g = groupMessages(list.sortedWith(if (isWeChat) compareByDescending { it.timestamp } else compareBy { it.timestamp }))
            return g.joinToString("\n") { item ->
                val date = formatDate(item.timestamp)
                val contentBlock: String
                if (item.isGroup) {
                    val subs = item.messages
                    val isAllMedia = subs.all { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
                    if (isAllMedia) {
                        val count = subs.size
                        val gridClass = gridClassFor(count)
                        val imgs = subs.joinToString("") { s ->
                            val u = resolver.resolve(s)
                            if (s.type == MessageType.VIDEO) "<video src=\"$u\" controls preload=\"none\" class=\"wechat-grid-img\"></video>"
                            else "<img src=\"$u\" class=\"wechat-grid-img\" loading=\"lazy\" onclick=\"openLightbox(this.src)\"/>"
                        }
                        val captions = subs.mapNotNull { it.caption ?: it.locationAddress }.filter { it.isNotBlank() }
                        contentBlock = "<div class=\"wechat-grid-container $gridClass\">$imgs</div>" +
                            (if (captions.isNotEmpty()) "<div class=\"wechat-caption-sub\">${captions.joinToString("") { "<div class=\"caption-item\">${escapeHtml(it)}</div>" }}</div>" else "")
                    } else {
                        contentBlock = "<div class=\"wechat-composite-group\" style=\"background:#ffffff; border:1px solid #e2e2e2; border-radius:12px; padding:12px; margin:4px 0;\">" +
                            subs.mapIndexed { idx, s ->
                                (if (idx > 0) "<div style=\"border-top:1px solid #eeeeee; margin:8px 0;\"></div>" else "") + renderSubItem(s)
                            }.joinToString("") + "</div>"
                    }
                } else {
                    contentBlock = renderSubItem(item.messages[0])
                }
                val avatar = resolver.resolveAvatar(item.messages[0], "")
                val nameChar = (item.senderName ?: item.sender ?: authorStr).take(1).uppercase()
                val fallbackSvg = "data:image/svg+xml;charset=utf-8," + java.net.URLEncoder.encode(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="#212c3d"/><text x="50%" y="55%" dominant-baseline="middle" text-anchor="middle" font-size="90" font-weight="bold" fill="#818cf8">${nameChar}</text></svg>""",
                    "UTF-8"
                )
                "<div class=\"wechat-item\">" +
                    "<img src=\"$avatar\" class=\"wechat-avatar\" alt=\"Avatar\" onerror=\"this.onerror=null;this.src='${fallbackSvg}';\"/>" +
                    "<div class=\"wechat-body\"><div class=\"wechat-nickname\">${escapeHtml(item.senderName ?: item.sender ?: authorStr)}</div>" +
                    contentBlock +
                    "<div class=\"wechat-footer\"><span class=\"wechat-time\">${date.full}</span></div>" +
                    "</div></div>"
            }
        }

        // 简约现代时间轴渲染
        fun renderJournal(list: List<ChatMessage>): String {
            val g = groupMessages(list.sortedWith(if (isWeChat) compareByDescending { it.timestamp } else compareBy { it.timestamp }))
            return g.joinToString("\n") { item ->
                val date = formatDate(item.timestamp)
                val msg = item.messages[0]
                val isLocation = msg.content.startsWith("[位置]")

                val cardMedia: String
                if (item.isGroup) {
                    // 宫格聚合：渲染所有图片/视频
                    val isAllMedia = item.messages.all { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
                    if (isAllMedia) {
                        val count = item.messages.size
                        val gridClass = gridClassFor(count)
                        val imgs = item.messages.joinToString("") { s ->
                            val u = resolver.resolve(s)
                            if (s.type == MessageType.VIDEO) "<video src=\"$u\" controls preload=\"none\" class=\"card-grid-img\"></video>"
                            else "<img src=\"$u\" class=\"card-grid-img\" loading=\"lazy\" onclick=\"openLightbox(this.src)\"/>"
                        }
                        val captions = item.messages.mapNotNull { it.caption ?: it.locationAddress }.filter { it.isNotBlank() }
                        cardMedia = "<div class=\"card-grid-container $gridClass\">$imgs</div>" +
                            (if (captions.isNotEmpty()) "<div class=\"card-caption\">${captions.joinToString(" ") { escapeHtml(it) }}</div>" else "")
                    } else {
                        // 混合内容组：逐条渲染
                        cardMedia = "<div class=\"card-group\">" + item.messages.joinToString("") { s ->
                            val u = resolver.resolve(s)
                            when (s.type) {
                                MessageType.IMAGE -> "<img src=\"$u\" class=\"card-img\" loading=\"lazy\" onclick=\"openLightbox(this.src)\"/>"
                                MessageType.VIDEO -> "<video src=\"$u\" controls preload=\"none\" class=\"card-video\"></video>"
                                MessageType.AUDIO -> "<audio src=\"$u\" controls preload=\"none\"></audio>"
                                else -> "<div class=\"card-text\">${escapeHtml(s.content)}</div>"
                            }
                        } + "</div>"
                    }
                } else {
                    val mediaUrl = resolver.resolve(msg)
                    cardMedia = when (msg.type) {
                        MessageType.IMAGE -> "<div class=\"card-image-wrap\"><img src=\"$mediaUrl\" class=\"card-img\" loading=\"lazy\" onclick=\"openLightbox(this.src)\"/></div>"
                        MessageType.VIDEO -> "<div class=\"card-video-wrap\"><video src=\"$mediaUrl\" controls preload=\"none\" class=\"card-video\"></video></div>"
                        MessageType.AUDIO -> "<div class=\"card-audio-wrap\"><audio src=\"$mediaUrl\" controls preload=\"none\"></audio></div>"
                        else -> ""
                    }
                }

                val textStr = if (isLocation) (msg.locationAddress ?: msg.content) else (if (msg.type == MessageType.TEXT) msg.content else (msg.caption ?: ""))
                "<div class=\"timeline-node\"><div class=\"timeline-dot\"></div>" +
                    "<div class=\"timeline-content-card\">" +
                    "<div class=\"card-header\"><span class=\"card-date\">${date.full}</span>" +
                    (if (isLocation) "<span class=\"location-badge\">📍 ${escapeHtml(textStr)}</span>" else "") +
                    "</div>$cardMedia" +
                    (if (!isLocation && textStr.isNotBlank() && !item.isGroup) "<div class=\"card-text\">${escapeHtml(textStr)}</div>" else "") +
                    "</div></div>"
            }
        }

        // 计算文件夹节点（含子文件夹）的消息总数
        fun countMessages(node: FolderNode): Int =
            node.messages.size + node.children.sumOf { child -> countMessages(child) }

        // 递归渲染单个文件夹节点：先渲染直接消息，再对子文件夹用 details/summary 折叠包裹
        fun renderFolderNode(node: FolderNode): String {
            val directHtml = if (isWeChat) renderWeChat(node.messages) else renderJournal(node.messages)
            val childrenHtml = node.children.joinToString("\n") { child ->
                "<details class=\"diary-folder\">" +
                    "<summary class=\"diary-folder-summary\">📁 ${escapeHtml(child.name)}" +
                    "<span class=\"diary-folder-count\">(${countMessages(child)})</span></summary>" +
                    "<div class=\"diary-folder-content\">${renderFolderNode(child)}</div>" +
                    "</details>"
            }
            return directHtml + childrenHtml
        }

        // 当有文件夹树时：顶层消息 + 子文件夹折叠；否则平铺渲染
        val feedHtml: String = if (folderTree != null) {
            val topHtml = if (isWeChat) renderWeChat(folderTree.messages) else renderJournal(folderTree.messages)
            val subFoldersHtml = folderTree.children.joinToString("\n") { child ->
                "<details class=\"diary-folder\"><summary class=\"diary-folder-summary\">📁 ${escapeHtml(child.name)}" +
                    "<span class=\"diary-folder-count\">(${countMessages(child)})</span></summary>" +
                    "<div class=\"diary-folder-content\">${renderFolderNode(child)}</div></details>"
            }
            topHtml + subFoldersHtml
        } else {
            if (isWeChat) renderWeChat(sorted) else renderJournal(sorted)
        }

        val coverStyle = if (coverUrl != null) {
            " style=\"background-image: url('$coverUrl'); background-size: cover; background-position: center;\""
        } else ""
        val body = if (isWeChat) {
            "<div class=\"wechat-container\"><div class=\"wechat-header-cover\"$coverStyle><div class=\"cover-bg\"></div>" +
                "<div class=\"user-profile\"><span class=\"user-name\">${escapeHtml(authorStr)}</span></div></div>" +
                "<div class=\"diary-title-banner\"><h2>📂 ${escapeHtml(titleStr)}</h2><p>共收录 ${sorted.size} 条记录 (${groupedCount} 组动态)</p></div>" +
                "<div class=\"wechat-feed\">$feedHtml</div></div>"
        } else {
            "<div class=\"diary-container\"><header class=\"main-header\"$coverStyle><div class=\"header-inner\">" +
                "<h1>📖 ${escapeHtml(titleStr)}</h1>" +
                "<p class=\"subtitle\">记录人：${escapeHtml(authorStr)} · 共 ${groupedCount} 条动态</p></div></header>" +
                "<main class=\"timeline-container\">$feedHtml</main></div>"
        }

        return buildString {
            append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
            append("<meta charset=\"UTF-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            append("<title>${escapeHtml(titleStr)} - 个人日记专栏</title>\n")
            append("<style>\n${templateCss(templateId)}\n</style>\n</head>\n")
            append("<body class=\"theme-$templateId${if (passwordHash.isNotEmpty()) " is-locked" else ""}\">\n")

            if (passwordHash.isNotEmpty()) {
                append("""
                <div id="lockScreenOverlay" class="lock-screen-overlay">
                  <div class="lock-card">
                    <div class="lock-icon">🔒</div>
                    <h2>私密日记本</h2>
                    <p class="lock-sub">此归档页面已启用访问加密保护，请输入密码解密查看</p>
                    <div class="lock-form">
                      <input type="password" id="diaryPassInput" class="lock-input" placeholder="输入访问密码..." onkeydown="if(event.key==='Enter') verifyPassword()"/>
                      <button onclick="verifyPassword()" class="lock-btn-submit">🔓 解锁查看</button>
                    </div>
                    <div class="lock-remember-row">
                      <input type="checkbox" id="rememberPassCheck" checked />
                      <label for="rememberPassCheck">记住密码 (免重复输入)</label>
                    </div>
                    <div id="lockErrorMsg" class="lock-error-msg"></div>
                  </div>
                </div>
                """.trimIndent())
            }

            append(body)

            append("""
            <div id="lightboxModal" class="lightbox-modal" onclick="closeLightbox()">
              <span class="lightbox-close">&times;</span>
              <img class="lightbox-content" id="lightboxImg">
            </div>
            <script>
            const EXPECTED_HASH = "$passwordHash";
            const STORAGE_KEY = "diary_pass_${java.net.URLEncoder.encode(titleStr, "UTF-8")}";
            async function sha256(str) {
              const buffer = new TextEncoder().encode(str);
              const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
              const hashArray = Array.from(new Uint8Array(hashBuffer));
              return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
            }
            async function verifyPassword() {
              const input = document.getElementById('diaryPassInput').value;
              const errorDiv = document.getElementById('lockErrorMsg');
              if (!input) { errorDiv.innerText = "请输入访问密码"; return; }
              const hash = await sha256(input.trim());
              if (hash === EXPECTED_HASH) {
                if (document.getElementById('rememberPassCheck').checked) localStorage.setItem(STORAGE_KEY, input.trim());
                unlockPage();
              } else {
                errorDiv.innerText = "❌ 密码错误，无法解密查看日记";
              }
            }
            function unlockPage() {
              const overlay = document.getElementById('lockScreenOverlay');
              if (!overlay) return;
              overlay.style.display = 'none';
              document.body.classList.remove('is-locked');
            }
            window.addEventListener('DOMContentLoaded', async () => {
              if (EXPECTED_HASH) {
                const saved = localStorage.getItem(STORAGE_KEY);
                if (saved) { const hash = await sha256(saved); if (hash === EXPECTED_HASH) unlockPage(); }
              }
            });
            function openLightbox(src) {
              document.getElementById('lightboxModal').style.display = "flex";
              document.getElementById('lightboxImg').src = src;
            }
            function closeLightbox() { document.getElementById('lightboxModal').style.display = "none"; }
            </script>
            </body></html>
            """.trimIndent())
        }
    }

    fun sha256Hex(str: String): String {
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(str.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return str
        }
    }

    private fun templateCss(templateId: String): String {
        val common = """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; background: #f2f2f6; color: #1c1c1e; line-height: 1.6; }
        body.is-locked { overflow: hidden; }
        img, video { max-width: 100%; border-radius: 8px; }
        .lock-screen-overlay { position: fixed; inset: 0; z-index: 10000; background: rgba(15,23,42,0.88); backdrop-filter: blur(20px); display: flex; align-items: center; justify-content: center; padding: 20px; }
        .lock-card { background: rgba(30,41,59,0.95); border: 1px solid rgba(255,255,255,0.12); border-radius: 20px; width: 100%; max-width: 380px; padding: 32px 24px; text-align: center; color: #fff; }
        .lock-icon { font-size: 40px; margin-bottom: 12px; }
        .lock-card h2 { font-size: 20px; font-weight: 700; color: #38bdf8; margin-bottom: 6px; }
        .lock-sub { font-size: 12px; color: #94a3b8; margin-bottom: 24px; }
        .lock-form { display: flex; flex-direction: column; gap: 12px; }
        .lock-input { width: 100%; background: #0f172a; border: 1px solid #334155; border-radius: 12px; padding: 12px 16px; color: #fff; font-size: 14px; outline: none; }
        .lock-btn-submit { width: 100%; background: linear-gradient(135deg,#38bdf8,#34d399); border: none; border-radius: 12px; padding: 12px; color: #000; font-weight: 700; cursor: pointer; }
        .lock-remember-row { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 14px; font-size: 12px; color: #94a3b8; }
        .lock-error-msg { font-size: 12px; color: #f87171; margin-top: 12px; min-height: 18px; font-weight: 600; }
        .lightbox-modal { display: none; position: fixed; z-index: 9999; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.9); align-items: center; justify-content: center; }
        .lightbox-content { max-width: 90%; max-height: 90%; border-radius: 4px; }
        .lightbox-close { position: absolute; top: 20px; right: 35px; color: #fff; font-size: 40px; font-weight: bold; cursor: pointer; }
        .diary-folder { margin: 10px 0; border: 1px solid #e2e2e2; border-radius: 10px; overflow: hidden; background: #fafafa; }
        .diary-folder-summary { cursor: pointer; padding: 10px 14px; font-weight: 700; font-size: 14px; color: #576b95; background: #f3f4f7; list-style: none; display: flex; align-items: center; gap: 6px; user-select: none; }
        .diary-folder-summary::-webkit-details-marker { display: none; }
        .diary-folder-summary::before { content: "▸"; transition: transform 0.2s; font-size: 12px; }
        .diary-folder[open] > .diary-folder-summary::before { transform: rotate(90deg); }
        .diary-folder-count { font-size: 11px; color: #999; font-weight: 400; margin-left: auto; }
        .diary-folder-content { padding: 8px 10px; }
        .diary-folder .diary-folder { margin: 6px 0; }
        """.trimIndent()

        return if (templateId == "wechat") {
            """
            $common
            .theme-wechat { background: #ededed; }
            .wechat-container { max-width: 600px; margin: 0 auto; background: #fff; min-height: 100vh; }
            .wechat-header-cover { position: relative; height: 200px; background: linear-gradient(135deg, #1aad19, #07c160); }
            .user-profile { position: absolute; right: 20px; bottom: 20px; }
            .user-name { color: #fff; font-weight: 700; font-size: 18px; text-shadow: 0 1px 3px rgba(0,0,0,0.6); }
            .diary-title-banner { padding: 20px; border-bottom: 1px solid #f0f0f0; }
            .diary-title-banner h2 { font-size: 20px; color: #111; }
            .diary-title-banner p { font-size: 12px; color: #888; margin-top: 4px; }
            .wechat-feed { padding: 20px 16px; }
            .wechat-item { display: flex; gap: 12px; padding-bottom: 24px; border-bottom: 1px solid #f0f0f0; margin-bottom: 20px; }
            .wechat-avatar { width: 42px; height: 42px; border-radius: 6px; flex-shrink: 0; background: #f0f0f0; }
            .wechat-body { flex: 1; min-width: 0; }
            .wechat-nickname { font-weight: 600; color: #576b95; font-size: 15px; margin-bottom: 6px; }
            .wechat-text-content { font-size: 15px; color: #111; word-break: break-word; white-space: pre-wrap; margin-bottom: 8px; line-height: 1.5; }
            .wechat-location-badge { display: inline-flex; align-items: center; gap: 4px; color: #576b95; font-size: 13px; background: #f3f4f7; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px; }
            .wechat-location-badge .icon { width: 14px; height: 14px; }
            .wechat-grid-container { display: grid; gap: 4px; margin-bottom: 8px; }
            .wechat-grid-container.grid-c1 { grid-template-columns: 1fr; max-width: 220px; }
            .wechat-grid-container.grid-c2 { grid-template-columns: repeat(2, 1fr); width: 220px; }
            .wechat-grid-container.grid-c3 { grid-template-columns: repeat(3, 1fr); width: 290px; }
            .wechat-grid-container.grid-c4 { grid-template-columns: repeat(2, 1fr); width: 220px; }
            .wechat-grid-img { width: 100%; aspect-ratio: 1 / 1; object-fit: cover; border-radius: 4px; cursor: pointer; }
            .wechat-single-img { max-width: 220px; max-height: 280px; object-fit: cover; border-radius: 4px; cursor: pointer; }
            .wechat-caption-sub { font-size: 15px; color: #111; margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
            .wechat-caption-sub .caption-item { margin-bottom: 4px; }
            .wechat-video-player { max-width: 280px; border-radius: 4px; }
            .wechat-audio-box { background: #f7f7f7; border-radius: 6px; padding: 6px; width: 100%; }
            .wechat-audio-player { width: 100%; height: 36px; }
            .wechat-file-box { display: flex; align-items: center; gap: 10px; background: #f7f7f7; padding: 10px; border-radius: 6px; max-width: 320px; }
            .wechat-file-box .file-name { font-size: 13px; font-weight: 500; color: #111; text-decoration: none; word-break: break-all; }
            .wechat-file-box .file-size { font-size: 11px; color: #888; display: block; }
            .wechat-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; font-size: 12px; color: #b2b2b2; }
            """.trimIndent()
        } else {
            """
            $common
            .theme-journal { background: #f8fafc; }
            .theme-journal .diary-container { max-width: 760px; margin: 0 auto; padding: 40px 20px; }
            .theme-journal .main-header { text-align: center; margin-bottom: 40px; padding-bottom: 20px; border-bottom: 2px solid #e2e8f0; }
            .theme-journal .main-header h1 { font-size: 28px; color: #0f172a; font-weight: 800; }
            .theme-journal .main-header .subtitle { font-size: 14px; color: #64748b; margin-top: 6px; }
            .theme-journal .timeline-container { position: relative; padding-left: 20px; border-left: 2px solid #cbd5e1; }
            .theme-journal .timeline-node { position: relative; margin-bottom: 32px; }
            .theme-journal .timeline-dot { position: absolute; left: -27px; top: 16px; width: 12px; height: 12px; border-radius: 50%; background: #3b82f6; border: 2px solid #fff; }
            .theme-journal .timeline-content-card { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.04); border: 1px solid #e2e8f0; }
            .theme-journal .card-header { display: flex; justify-content: space-between; margin-bottom: 12px; font-size: 13px; color: #64748b; }
            .theme-journal .card-img { max-height: 400px; width: 100%; object-fit: cover; border-radius: 8px; cursor: pointer; }
            .theme-journal .card-grid-container { display: grid; gap: 4px; margin-bottom: 8px; }
            .theme-journal .card-grid-container.grid-c1 { grid-template-columns: 1fr; max-width: 280px; }
            .theme-journal .card-grid-container.grid-c2 { grid-template-columns: repeat(2, 1fr); }
            .theme-journal .card-grid-container.grid-c3 { grid-template-columns: repeat(3, 1fr); }
            .theme-journal .card-grid-container.grid-c4 { grid-template-columns: repeat(2, 1fr); }
            .theme-journal .card-grid-img { width: 100%; aspect-ratio: 1 / 1; object-fit: cover; border-radius: 6px; cursor: pointer; }
            .theme-journal .card-caption { font-size: 14px; color: #334155; line-height: 1.6; margin-top: 6px; }
            .theme-journal .card-group { display: flex; flex-direction: column; gap: 8px; }
            .theme-journal .card-group .card-img { max-height: 300px; width: 100%; object-fit: cover; border-radius: 8px; cursor: pointer; }
            .theme-journal .card-text { font-size: 15px; color: #334155; line-height: 1.7; white-space: pre-wrap; margin-top: 10px; }
            .location-badge { background: #e1f5fe; color: #0288d1; padding: 4px 10px; border-radius: 20px; font-size: 12px; }
            """.trimIndent()
        }
    }
}
