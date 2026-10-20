package com.example.fbautoposter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Controls the *official* Facebook Android app through its accessibility tree.
 * Facebook UI is not a stable API; neither scanning nor successful submission can be guaranteed.
 */
class PostingAccessibilityService : AccessibilityService() {
    companion object { var instance: PostingAccessibilityService? = null; private set }
    private enum class Job { IDLE, SCAN, POST }
    private enum class Stage { NONE, SCANNING, FIND_GROUP, FIND_COMPOSER, PREPARING, FIND_EDITOR, FIND_SUBMIT, WAIT_CONFIRM }

    private val handler = Handler(Looper.getMainLooper())
    private var job = Job.IDLE
    private var stage = Stage.NONE
    private var scanScheduled = false
    private var stageStartedAt = 0L
    private val stepRunnable = Runnable { scanScheduled = false; advance() }

    private var groups: List<FbGroup> = emptyList()
    private var index = 0
    private var text = ""
    private var prepared = ""
    private var mode = "OFF"
    private var apiKey = ""
    private var delayMs = 60000L
    private var navigationScrolls = 0
    private var scanScrolls = 0
    private var scanNoChanges = 0
    private var scanSeenCount = 0
    private var confirmationText = ""
    private var submittedAt = 0L

    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayStatus: TextView? = null
    private val discoveredNames = linkedSetOf<String>()

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onInterrupt() { }
    override fun onDestroy() { stopPosting(); instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (job == Job.IDLE || event?.packageName?.toString() != "com.facebook.katana") return
        // Do not postpone the next step on every Facebook content-change event.
        schedule(650L)
    }

    fun startGroupScan() {
        stopPosting()
        job = Job.SCAN
        stage = Stage.SCANNING
        scanScrolls = 0
        scanNoChanges = 0
        discoveredNames.clear()
        scanSeenCount = GroupsStore.all(this).size
        showOverlay()
        status("Шукаю твої групи у Facebook")
        if (openFacebook("https://www.facebook.com/groups/joins/")) schedule(5000)
        else finish("Не вдалося запустити Facebook")
    }

    fun startPosting() {
        stopPosting()
        val prefs = getSharedPreferences("posting", MODE_PRIVATE)
        val allGroups = GroupsStore.all(this)
        val ids = try {
            val json = JSONArray(prefs.getString("selected_ids", "[]"))
            (0 until json.length()).map { json.getString(it) }.toSet()
        } catch (_: Exception) { emptySet<String>() }
        groups = allGroups.filter { it.id in ids }
        text = prefs.getString("message", "")?.trim().orEmpty()
        apiKey = prefs.getString("api", "")?.trim().orEmpty()
        mode = prefs.getString("mode", "OFF") ?: "OFF"
        delayMs = (prefs.getString("delay", "60")?.toLongOrNull() ?: 60L).coerceAtLeast(15L) * 1000L
        if (groups.isEmpty() || text.isBlank()) { status("Вибери групи та введи текст"); return }
        index = 0
        job = Job.POST
        showOverlay()
        log("START: обрано ${groups.size} груп")
        openNext()
    }

    fun stopPosting() {
        job = Job.IDLE
        stage = Stage.NONE
        handler.removeCallbacksAndMessages(null)
        scanScheduled = false
        overlay?.let { view -> try { windowManager?.removeView(view) } catch (_: Exception) { } }
        overlay = null
        overlayStatus = null
    }

    private fun finish(message: String) {
        log(message)
        job = Job.IDLE
        stage = Stage.NONE
        handler.removeCallbacks(stepRunnable)
        scanScheduled = false
        status(message)
        // Leave the status overlay visible until the user presses Stop.
    }

    private fun schedule(delay: Long) {
        if (job == Job.IDLE || scanScheduled) return
        scanScheduled = true
        handler.postDelayed(stepRunnable, delay)
    }

    private fun reschedule(delay: Long) {
        handler.removeCallbacks(stepRunnable)
        scanScheduled = false
        schedule(delay)
    }

    private fun changeStage(value: Stage, delay: Long = 1100L) {
        stage = value
        stageStartedAt = System.currentTimeMillis()
        reschedule(delay)
    }

    private fun status(message: String) {
        val progress = if (job == Job.POST) "${index + 1}/${groups.size} • ${groups.getOrNull(index)?.name.orEmpty()}" else "Сканування груп"
        overlayStatus?.text = "FB Auto Poster\n$progress\n$message"
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 10)
            setBackgroundColor(0xE51B283A.toInt())
        }
        overlayStatus = TextView(this).apply { textSize = 14f; setTextColor(-1); maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt() }
        panel.addView(overlayStatus)
        panel.addView(Button(this).apply {
            text = "СТОП"
            setOnClickListener { stopPosting() }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 10; y = 95 }
        try { windowManager?.addView(panel, params); overlay = panel } catch (_: Exception) { overlay = null }
    }

    private fun openFacebook(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.facebook.katana")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) { log("Facebook launch failed: ${e.message}"); false }
    }

    private fun openNext() {
        if (job != Job.POST) return
        if (index >= groups.size) { finish("Черга завершена. Перевір журнал результатів у Facebook."); return }
        val group = groups[index]
        navigationScrolls = 0
        prepared = ""
        confirmationText = ""
        status("Відкриваю групу")
        val direct = group.url.isNotBlank()
        if (!openFacebook(if (direct) group.url else "https://www.facebook.com/groups/joins/")) {
            finish("Не вдалося відкрити Facebook")
            return
        }
        changeStage(if (direct) Stage.FIND_COMPOSER else Stage.FIND_GROUP, 4500L)
    }

    private fun nextAfter(result: String) {
        log("${groups.getOrNull(index)?.name.orEmpty()}: $result")
        status(result + " • затримка ${delayMs / 1000} с")
        index++
        stage = Stage.NONE
        handler.removeCallbacks(stepRunnable)
        scanScheduled = false
        handler.postDelayed({ if (job == Job.POST) openNext() }, delayMs)
    }

    private fun advance() {
        when (job) {
            Job.SCAN -> scanStep()
            Job.POST -> postingStep()
            Job.IDLE -> Unit
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (all.size >= 1800 || depth > 35) return
            all.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return all
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString().orEmpty()).trim()

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = node
        repeat(5) {
            if (parent == null) return null
            if (parent!!.isClickable && parent!!.isEnabled && parent!!.isVisibleToUser) return parent
            parent = parent!!.parent
        }
        return null
    }

    private fun normalized(value: String): String = value.lowercase().trim().replace(Regex("[\\s…:!.,]+"), " ")

    private val composerLabels = listOf(
        "write something", "write a post", "create post", "create a post", "what's on your mind", "what do you want to share",
        "напишіть щось", "створити допис", "написати допис", "що у вас нового", "напишите что-нибудь",
        "создать публикацию", "что у вас нового", "napište něco", "napsat příspěvek",
        "co se vám honí hlavou", "vytvořit příspěvek"
    )

    private fun tapComposer(): Boolean {
        val all = nodes(rootInActiveWindow)
        for (node in all) {
            val text = normalized(labelOf(node))
            if (text.isNotBlank() && composerLabels.any { text.contains(it) }) {
                val clickable = clickableParent(node) ?: continue
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    private fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollers = nodes(root).filter { it.isScrollable && it.isVisibleToUser }
        for (node in scrollers.asReversed()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val path = Path().apply {
            moveTo(width * 0.63f, height * 0.83f)
            lineTo(width * 0.63f, height * 0.30f)
        }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 420L)).build(), null, null)
    }

    private val uiLabels = setOf(
        "home", "menu", "groups", "your groups", "my groups", "groups you've joined", "see all", "view all", "posts", "post", "join",
        "joined", "notifications", "search", "settings", "new posts", "explore", "discover", "write something",
        "create post", "feed", "more", "your shortcuts", "all groups", "groups you manage",
        "головна", "групи", "мої групи", "всі групи", "переглянути всі", "створити допис", "дописи", "пошук", "меню",
        "группы", "мои группы", "все группы", "новости", "публикации", "создать публикацию", "поиск",
        "skupiny", "moje skupiny", "všechny skupiny", "zobrazit vše", "příspěvky", "hledat", "domů", "další"
    )

    private fun scanCandidates(): List<Pair<String, String>> {
        val results = linkedMapOf<String, Pair<String, String>>()
        val screen = resources.displayMetrics
        for (node in nodes(rootInActiveWindow)) {
            if (!node.isVisibleToUser) continue
            val raw = node.text?.toString()?.trim() ?: continue
            val candidate = raw.lineSequence().firstOrNull().orEmpty().substringBefore(" · ").trim()
            val norm = normalized(candidate)
            if (candidate.length !in 3..110 || norm in uiLabels) continue
            if (candidate.startsWith("http") || candidate.contains("\n") || candidate.matches(Regex("[0-9., ]+"))) continue
            if (candidate.contains("подобається", true) || candidate.contains("comment", true)) continue
            val hit = clickableParent(node) ?: continue
            val bounds = Rect()
            hit.getBoundsInScreen(bounds)
            if (bounds.width() < screen.widthPixels / 4 || bounds.height() < 20 || bounds.height() > screen.heightPixels * 0.55) continue
            if (bounds.centerY() < screen.heightPixels * 0.09 || bounds.centerY() > screen.heightPixels * 0.95) continue
            val url = GroupsStore.canonicalUrl(node.contentDescription?.toString().orEmpty())
            results[norm] = candidate to url
        }
        return results.values.toList()
    }

    private fun scanStep() {
        if (job != Job.SCAN) return
        val root = rootInActiveWindow
        if (root == null) { status("Чекаю відкриття списку груп Facebook"); schedule(2000); return }
        val candidates = scanCandidates()
        candidates.forEach { discoveredNames.add(normalized(it.first)) }
        val newCount = GroupsStore.add(this, candidates)
        val total = GroupsStore.all(this).size
        if (newCount > 0) scanNoChanges = 0 else scanNoChanges++
        status("Знайдено нових: ${total - scanSeenCount}\nЕкранів: $scanScrolls • збережено: $total")
        if (scanNoChanges >= 7 || scanScrolls >= 100) {
            finish("Сканування закінчено: +${total - scanSeenCount} груп (перевірте список)")
            return
        }
        scanScrolls++
        if (!scrollForward()) {
            finish("Facebook не дозволив прокрутку. Зібрано нових: ${total - scanSeenCount}")
            return
        }
        schedule(1700)
    }

    private fun findGroupByName(name: String): Boolean {
        val target = normalized(name)
        val matches = nodes(rootInActiveWindow).filter { node ->
            val s = normalized(labelOf(node)).substringBefore(" · ")
            node.isVisibleToUser && (s == target || (s.startsWith(target) && s.length - target.length <= 35))
        }
        for (node in matches) {
            val button = clickableParent(node) ?: continue
            if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun postingStep() {
        if (job != Job.POST) return
        when (stage) {
            Stage.FIND_GROUP -> {
                status("Шукаю групу за назвою у Facebook")
                val name = groups[index].name
                if (findGroupByName(name)) changeStage(Stage.FIND_COMPOSER, 2600)
                else if (navigationScrolls++ < 70 && scrollForward()) schedule(1500)
                else nextAfter("НЕ ЗНАЙДЕНО групу — імпортуйте її URL")
            }
            Stage.FIND_COMPOSER -> {
                status("Шукаю поле «Створити допис»")
                if (tapComposer()) changeStage(Stage.PREPARING, 2000)
                else if (stageTimedOut(16000)) nextAfter("НЕ ЗНАЙДЕНО редактор — перевірте Facebook")
                else schedule(1250)
            }
            Stage.PREPARING -> {
                status("Готую текст ${if (mode == "OFF") "" else "через Groq $mode"}")
                stage = Stage.FIND_EDITOR
                stageStartedAt = System.currentTimeMillis()
                if (mode == "OFF" || apiKey.isBlank()) {
                    prepared = text
                    schedule(200)
                } else {
                    thread {
                        val rewritten = rewrite(text, mode, apiKey)
                        handler.post {
                            if (job == Job.POST && stage == Stage.FIND_EDITOR) {
                                prepared = rewritten
                                reschedule(150)
                            }
                        }
                    }
                }
            }
            Stage.FIND_EDITOR -> {
                if (prepared.isBlank()) {
                    if (stageTimedOut(52000)) nextAfter("Groq не відповів") else schedule(700)
                    return
                }
                status("Вставляю текст")
                val all = nodes(rootInActiveWindow)
                val editable = all.firstOrNull { it.isEditable && it.isVisibleToUser }
                if (editable == null) {
                    if (stageTimedOut(16000)) nextAfter("НЕ ЗНАЙДЕНО текстове поле") else schedule(850)
                    return
                }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prepared) }
                val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (success) changeStage(Stage.FIND_SUBMIT, 1600)
                else {
                    editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (stageTimedOut(18000)) nextAfter("Не вдалося вставити текст") else schedule(1000)
                }
            }
            Stage.FIND_SUBMIT -> {
                status("Шукаю точну кнопку «Опублікувати»")
                val button = findSubmitButton()
                if (button != null) {
                    val ok = button.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapByCoordinates(button)
                    if (ok) {
                        submittedAt = System.currentTimeMillis()
                        status("Натиснуто «Опублікувати». Очікую результат")
                        changeStage(Stage.WAIT_CONFIRM, 3200)
                    } else {
                        if (stageTimedOut(17000)) failWithButtonDiagnostics() else schedule(900)
                    }
                } else if (stageTimedOut(17000)) failWithButtonDiagnostics()
                else schedule(900)
            }
            Stage.WAIT_CONFIRM -> {
                val allText = nodes(rootInActiveWindow).map { normalized(labelOf(it)) }
                val confirmed = listOf("your post was shared", "post published", "допис опубліковано", "ваш допис", "публикация опубликована", "příspěvek byl zveřejněn")
                val pending = listOf("pending approval", "waiting for approval", "очікує схвалення", "ожидает одобрения", "čeká na schválení")
                if (allText.any { t -> confirmed.any { t.contains(it) } }) nextAfter("СХОЖЕ, що допис опубліковано")
                else if (allText.any { t -> pending.any { t.contains(it) } }) nextAfter("Очікує схвалення адміністратора")
                else if (System.currentTimeMillis() - submittedAt >= 9000) nextAfter("Кнопку натиснуто; результат НЕ ПІДТВЕРДЖЕНО")
                else schedule(1100)
            }
            else -> Unit
        }
    }

    private fun stageTimedOut(timeout: Long) = System.currentTimeMillis() - stageStartedAt > timeout

    private val submitLabels = setOf(
        "post", "post now", "publish", "publish now", "share post", "submit post",
        "опублікувати", "опублікувати зараз", "опублікувати допис", "публікувати", "опубликовать", "опубликовать запись",
        "публиковать", "опубликовать сейчас", "запостить", "zveřejnit", "zveřejnit teď", "zveřejnit příspěvek", "publikovat",
        "odeslat příspěvek"
    )

    private fun findSubmitButton(): AccessibilityNodeInfo? {
        val screen = resources.displayMetrics
        // Require a composer with inserted text, to avoid pressing a "Post" in the feed.
        val editableNodes = nodes(rootInActiveWindow).filter { it.isEditable && it.isVisibleToUser }
        val expected = prepared.replace(Regex("\\s+"), " ").trim()
        if (editableNodes.none { node ->
                val visible = node.text?.toString().orEmpty().replace(Regex("\\s+"), " ").trim()
                visible == expected || (expected.length > 20 && visible.length >= expected.length / 2 && visible.startsWith(expected.take(24)))
            }) return null
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        for (node in nodes(rootInActiveWindow)) {
            if (!node.isVisibleToUser || !node.isEnabled || node.isEditable) continue
            val actual = normalized(labelOf(node))
            if (actual !in submitLabels) continue
            val clickable = clickableParent(node) ?: continue
            if (!clickable.isEnabled) continue
            val rect = Rect()
            clickable.getBoundsInScreen(rect)
            if (rect.width() < 12 || rect.height() < 12) continue
            var score = 10
            if (node.isClickable) score += 5
            if (node.className?.toString()?.contains("Button", ignoreCase = true) == true) score += 5
            if (node.contentDescription?.isNotBlank() == true) score += 3
            if (rect.centerX() > screen.widthPixels * 0.55) score += 3
            if (rect.centerY() < screen.heightPixels * 0.50) score += 2
            candidates.add(score to clickable)
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun tapByCoordinates(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val screen = resources.displayMetrics
        if (rect.isEmpty || rect.centerX() !in 1 until screen.widthPixels || rect.centerY() !in 1 until screen.heightPixels) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L)).build(), null, null)
    }

    private fun failWithButtonDiagnostics() {
        val summary = buildString {
            append("Група: ${groups.getOrNull(index)?.name.orEmpty()}\n")
            append("Етап: FIND_SUBMIT\n")
            append("Доступні кнопки та підписи Facebook:\n")
            nodes(rootInActiveWindow).filter { it.isVisibleToUser && (it.isClickable || it.className?.contains("Button") == true) }
                .take(130).forEach { n ->
                    val title = labelOf(n)
                    if (title.isNotBlank()) append("- ").append(title.take(140)).append("\n")
                }
        }
        getSharedPreferences("posting", MODE_PRIVATE).edit().putString("button_debug", summary).apply()
        log("Кнопку публікації не знайдено. Відкрийте діагностику в застосунку.")
        finish("Кнопку публікації не знайдено. Зупинено; скопіюйте діагностику.")
    }

    private fun log(message: String) {
        val pref = getSharedPreferences("posting", MODE_PRIVATE)
        val current = pref.getString("posting_log", "").orEmpty()
        val newLog = (current + "\n" + System.currentTimeMillis() + " " + message).takeLast(12000)
        pref.edit().putString("posting_log", newLog).apply()
    }

    private fun rewrite(source: String, mode: String, key: String): String = try {
        val connection = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 18000
        connection.readTimeout = 40000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "application/json")
        val prompt = "Rewrite the following job advertisement naturally in its original language. Return only final plain text, no spintax or commentary. Preserve ALL phone numbers, salaries, locations, conditions and factual claims exactly. Strength: $mode."
        val body = JSONObject().put("model", "openai/gpt-oss-20b")
            .put("temperature", 0.7).put("max_completion_tokens", 1800)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", source)))
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .getString("content").trim().ifBlank { source }
        } finally { connection.disconnect() }
    } catch (e: Exception) { log("Groq failed: ${e.javaClass.simpleName}"); source }
}
