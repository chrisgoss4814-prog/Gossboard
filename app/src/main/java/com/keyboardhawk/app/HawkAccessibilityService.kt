package com.keyboardhawk.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HawkAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HawkAccessibilityService? = null
        const val PREFS_NAME = "hawk_prefs"
        const val PREF_GROQ_KEY = "groq_api_key"
        const val TAG = "HawkAI"
        val debugLog = StringBuilder()
        const val MODEL_LOCAL = "local"
        const val MODEL_FAST = "llama-3.1-8b-instant"
        const val MODEL_SMART = "llama-3.3-70b-versatile"
        var currentModel = MODEL_LOCAL
        const val MAX_STEPS = 40
        const val STUCK_THRESHOLD = 3
        const val MAX_RETRIES = 3
        const val ACTION_DELAY_MS = 1800L
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val GROQ_PLANNER_TOKENS = 500   // Groq tokens for planning/re-planning
        const val GROQ_LEARN_TOKENS   = 250   // Groq tokens for memory extraction
        const val LLAMA_MAX_NODES     = 80    // nodes sent to Llama per step
        const val PLANNER_MAX_NODES   = 160   // nodes sent to Groq planner (richer view)
        const val OVERLAY_TAG = "HawkOverlay"
    }

    private fun getApiKey(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_GROQ_KEY, "") ?: ""

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    var isRunning = false
    private var currentTaskId = 0
    private var cachedAiUrl = "https://hawk-proxyout.onrender.com"

    // ── HYBRID AI STATE ───────────────────────────────────────────
    // Groq = planner/interpreter (3 calls max per task)
    // Llama = executor (every step, unlimited local tokens)
    private var taskPlan = ""                      // Groq-generated step plan
    private var taskSessionId = ""                 // unique ID for Drive uploads
    private val memory by lazy { HawkMemory(this) }
    private val cloud = HawkCloudStorage()

    // ── FLOATING OVERLAY ──────────────────────────────────────────
    private var overlayView: View? = null
    private var overlayStatus: TextView? = null
    private var overlayStep: TextView? = null
    private var overlayStop: View? = null
    private lateinit var windowManager: WindowManager

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_view, null)
        overlayView = view
        overlayStatus = view.findViewById(R.id.ovStatus)
        overlayStep = view.findViewById(R.id.ovStep)
        overlayStop = view.findViewById(R.id.ovStop)
        overlayStop?.setOnClickListener { stopTask() }
        view.setTag(OVERLAY_TAG)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 8
        params.y = 80
        try { windowManager.addView(view, params) } catch (_: Exception) {}
    }

    fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        overlayStatus = null
        overlayStep = null
        overlayStop = null
    }

    private fun updateOverlay(status: String, step: Int = 0, max: Int = 0) {
        handler.post {
            overlayStatus?.text = status
            overlayStep?.text = if (step > 0) "$step/$max" else ""
            overlayView?.visibility = View.VISIBLE
        }
    }

    // ── DEBUG ─────────────────────────────────────────────────────

    fun getAiUrl(): String = cachedAiUrl
    fun updateAiUrl(url: String) { cachedAiUrl = url; debug("SERVICE", "AI URL → $url") }

    // Level-aware debug — routes to both the raw log and the IME's color-coded debug panel.
    // Stage prefixes that trigger elevated levels:
    //   -ERR → ERROR, STUCK/RETRY → WARN, FATAL → FATAL, everything else → INFO
    fun debug(stage: String, message: String) {
        val level = when {
            stage.endsWith("-ERR") || stage == "ERR" -> "ERROR"
            stage == "STUCK" || stage == "RETRY"     -> "WARN"
            stage == "FATAL"                          -> "FATAL"
            else                                      -> "INFO"
        }
        val time = dateFormat.format(Date())
        val entry = "[$time][$level][$stage] $message\n"
        Log.d(TAG, entry.trim())
        debugLog.append(entry)
        if (debugLog.length > 20000) {
            val lines = debugLog.toString().split("\n")
            debugLog.clear()
            debugLog.append(lines.takeLast(100).joinToString("\n") + "\n")
        }
        // Route to IME with level context
        handler.post {
            val ime = KeyboardHawkIME.instance
            when (level) {
                "ERROR" -> ime?.debugError(stage, message)
                "WARN"  -> ime?.debugWarn(stage, message)
                "FATAL" -> ime?.debugFatal(stage, message)
                else    -> ime?.debugInfo(stage, message)
            }
            ime?.updateLog("[$stage] $message")
        }
    }

    // Convenience typed overloads — route directly with correct level tag so the
    // level-detection logic in debug() picks the right IME method.
    fun debugWarn(stage: String, msg: String)  = debug("STUCK", "[$stage] $msg")  // STUCK tag → WARN
    fun debugError(stage: String, msg: String) = debug("$stage-ERR", msg)         // -ERR tag → ERROR
    fun debugFatal(stage: String, msg: String) = debug("FATAL", "[$stage] $msg")  // FATAL tag → FATAL

    override fun onServiceConnected() {
        instance = this
        debug("SERVICE", "Hawk AI Connected ✓")
        handler.post { KeyboardHawkIME.instance?.updateStatus("Hawk AI Ready") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        instance = null
    }

    // ── STOP ──────────────────────────────────────────────────────

    fun stopTask() {
        isRunning = false
        currentTaskId++
        debug("STOP", "Task stopped by user")
        handler.post {
            KeyboardHawkIME.instance?.updateStatus("Stopped")
            updateOverlay("■ Stopped")
            handler.postDelayed({ removeOverlay() }, 2000)
        }
    }

    // ── DATA CLASSES ──────────────────────────────────────────────

    data class HawkNode(
        val id: String,
        val text: String,
        val type: String,
        val clickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val bounds: Rect,
        val children: MutableList<HawkNode> = mutableListOf()
    )

    // ── SCREEN READING ────────────────────────────────────────────
    // Builds a pruned node map — only keeps nodes useful to the AI.
    // Ignores empty containers, invisible nodes, and tiny elements.
    fun buildNodeMap(): List<HawkNode> {
        val roots = mutableListOf<HawkNode>()
        var counter = 0

        fun isUseful(n: AccessibilityNodeInfo): Boolean {
            if (!n.isVisibleToUser) return false
            val bounds = Rect(); n.getBoundsInScreen(bounds)
            if (bounds.width() < 4 || bounds.height() < 4) return false
            val text = n.text?.toString()?.trim() ?: ""
            val desc = n.contentDescription?.toString()?.trim() ?: ""
            return n.isClickable || n.isScrollable || n.isEditable ||
                    text.isNotEmpty() || desc.isNotEmpty()
        }

        fun buildNode(n: AccessibilityNodeInfo, depth: Int): HawkNode? {
            if (depth > 10) return null
            if (!isUseful(n) && n.childCount == 0) return null
            counter++
            val text = (n.text?.toString()?.trim() ?: "")
                .ifEmpty { n.contentDescription?.toString()?.trim() ?: "" }
                .ifEmpty { n.className?.toString()?.substringAfterLast(".") ?: "view" }
            val type = when {
                n.isEditable -> "input"
                n.isClickable -> "btn"
                n.isScrollable -> "scroll"
                else -> "text"
            }
            val bounds = Rect(); n.getBoundsInScreen(bounds)
            val node = HawkNode(
                id = "n$counter",
                text = text.take(50),
                type = type,
                clickable = n.isClickable,
                scrollable = n.isScrollable,
                editable = n.isEditable,
                bounds = bounds
            )
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                buildNode(child, depth + 1)?.let { node.children.add(it) }
                child.recycle()
            }
            return node
        }

        val root = rootInActiveWindow ?: return roots
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            buildNode(child, 0)?.let { roots.add(it) }
            child.recycle()
        }
        root.recycle()
        debug("SCREEN", "Node map: $counter nodes")
        return roots
    }

    // Compresses node tree to compact JSON — interactive nodes first, trimmed.
    // maxNodes: 80 for Llama (execution), 160 for Groq (planning), 500 for Drive backup
    fun nodeMapToJson(nodes: List<HawkNode>, maxNodes: Int = LLAMA_MAX_NODES): String {
        var count = 0

        fun nodeToJson(n: HawkNode): JSONObject? {
            if (count >= maxNodes) return null
            count++
            val obj = JSONObject()
            obj.put("id", n.id)
            obj.put("t", n.type)
            if (n.text.isNotEmpty()) obj.put("txt", n.text)
            obj.put("x", n.bounds.centerX())
            obj.put("y", n.bounds.centerY())
            if (n.editable) obj.put("inp", true)
            if (n.scrollable) obj.put("scr", true)
            if (n.children.isNotEmpty()) {
                val kids = JSONArray()
                for (child in n.children) {
                    nodeToJson(child)?.let { kids.put(it) }
                    if (count >= maxNodes) break
                }
                if (kids.length() > 0) obj.put("c", kids)
            }
            return obj
        }

        val arr = JSONArray()
        // Prioritize interactive nodes by sorting — buttons/inputs first
        val sorted = nodes.sortedByDescending { it.clickable || it.editable || it.scrollable }
        for (n in sorted) {
            nodeToJson(n)?.let { arr.put(it) }
            if (count >= maxNodes) break
        }
        return arr.toString()
    }

    fun findNodeById(nodes: List<HawkNode>, id: String): HawkNode? {
        for (node in nodes) {
            if (node.id == id) return node
            findNodeById(node.children, id)?.let { return it }
        }
        return null
    }

    // ── ACTIONS ───────────────────────────────────────────────────

    fun simulateTap(x: Float, y: Float) {
        debug("ACT", "TAP ($x,$y)")
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build(), null, null
        )
    }

    fun simulateSwipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long = 400) {
        debug("ACT", "SWIPE ($sx,$sy)→($ex,$ey)")
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null
        )
    }

    fun scrollForward(): Boolean {
        fun tryScroll(n: AccessibilityNodeInfo): Boolean {
            if (n.isScrollable) {
                n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                debug("ACT", "SCROLL FORWARD on node")
                return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (tryScroll(c)) { c.recycle(); return true }
                c.recycle()
            }
            return false
        }
        val root = rootInActiveWindow ?: return false
        val done = tryScroll(root)
        root.recycle()
        if (!done) {
            val h = resources.displayMetrics.heightPixels
            val w = resources.displayMetrics.widthPixels / 2f
            simulateSwipe(w, h * 0.75f, w, h * 0.25f)
        }
        return done
    }

    fun scrollBackward(): Boolean {
        fun tryScroll(n: AccessibilityNodeInfo): Boolean {
            if (n.isScrollable) {
                n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                debug("ACT", "SCROLL BACKWARD on node")
                return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (tryScroll(c)) { c.recycle(); return true }
                c.recycle()
            }
            return false
        }
        val root = rootInActiveWindow ?: return false
        val done = tryScroll(root)
        root.recycle()
        if (!done) {
            val h = resources.displayMetrics.heightPixels
            val w = resources.displayMetrics.widthPixels / 2f
            simulateSwipe(w, h * 0.25f, w, h * 0.75f)
        }
        return done
    }

    // Types text into the nearest editable node to the given coordinates.
    // Falls back to clipboard-paste if ACTION_SET_TEXT fails.
    private fun typeAtCoords(x: Float, y: Float, text: String) {
        simulateTap(x, y)
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            var bestNode: AccessibilityNodeInfo? = null
            var bestDist = Float.MAX_VALUE

            fun findNearest(n: AccessibilityNodeInfo) {
                if (n.isEditable) {
                    val b = Rect(); n.getBoundsInScreen(b)
                    val dx = b.centerX() - x; val dy = b.centerY() - y
                    val dist = dx * dx + dy * dy
                    if (dist < bestDist) { bestDist = dist; bestNode = n }
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    findNearest(c); c.recycle()
                }
            }
            findNearest(root)
            root.recycle()

            bestNode?.let { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                debug("ACT", if (ok) "TYPE OK: $text" else "TYPE FAILED, trying paste")
                if (!ok) {
                    // Clipboard paste fallback
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("hawk", text))
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    debug("ACT", "PASTE fallback")
                }
            } ?: debug("ACT", "TYPE: no editable found near ($x,$y)")
        }, 400)
    }

    // Clicks a node by its bounds — works even for non-clickable nodes by trying parent
    private fun clickNode(node: HawkNode): Boolean {
        val cx = node.bounds.centerX().toFloat()
        val cy = node.bounds.centerY().toFloat()
        // Try via AccessibilityNodeInfo if the live tree has a matching element
        val root = rootInActiveWindow
        if (root != null) {
            fun tryClick(n: AccessibilityNodeInfo): Boolean {
                val b = Rect(); n.getBoundsInScreen(b)
                if (b.centerX() == node.bounds.centerX() && b.centerY() == node.bounds.centerY()) {
                    if (n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    val p = n.parent
                    if (p != null && p.isClickable) {
                        p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        p.recycle()
                        return true
                    }
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    if (tryClick(c)) { c.recycle(); return true }
                    c.recycle()
                }
                return false
            }
            val clicked = tryClick(root)
            root.recycle()
            if (clicked) { debug("ACT", "CLICK node OK"); return true }
        }
        // Fallback: gesture tap at coords
        simulateTap(cx, cy)
        return true
    }

    // Launches an app by package name
    private fun launchApp(packageName: String) {
        debug("ACT", "LAUNCH app: $packageName")
        val intent = packageManager.getLaunchIntentForPackage(packageName.trim())
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
        } else {
            debug("ACT", "App not found: $packageName — pressing home instead")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ── AI AGENT LOOP (Hybrid: Groq plans • Llama executes) ──────────
    //
    //  Architecture per task:
    //    1. Groq  → builds a step-plan from the initial screen + memories  (1 call)
    //    2. Llama → executes every action step with the plan as context    (N calls)
    //    3. Groq  → re-plans if stuck ≥ STUCK_THRESHOLD times             (max 1 per stuck)
    //    4. Groq  → extracts learnings on done → saved to local + cloud   (1 call)
    //
    //  LOCAL-only mode: skips all Groq calls, Llama runs without a plan.

    fun runTask(task: String) {
        if (isRunning) stopTask()
        isRunning = true
        val taskId   = ++currentTaskId
        taskSessionId = System.currentTimeMillis().toString(16)
        var step       = 0
        var stuckCount = 0
        var lastHash   = 0
        var retries    = 0
        val recentActions = mutableListOf<String>()
        val useGroq = currentModel != MODEL_LOCAL
        taskPlan = ""

        debug("TASK", "▶ $task [${if (useGroq) currentModel else "Llama-only"}]")
        handler.post { showOverlay(); updateOverlay("Starting…") }

        // ── Inner step loop ──────────────────────────────────────────
        fun doStep() {
            if (!isRunning || currentTaskId != taskId) return
            if (step >= MAX_STEPS) {
                debug("TASK", "Max steps ($MAX_STEPS) reached")
                isRunning = false
                handler.post {
                    updateOverlay("✓ Done (max steps)")
                    handler.postDelayed({ removeOverlay() }, 3000)
                }
                return
            }

            step++
            val status = "Step $step/$MAX_STEPS"
            handler.post {
                KeyboardHawkIME.instance?.updateStatus("$status — reading screen")
                updateOverlay("Reading screen…", step, MAX_STEPS)
            }

            val nodeMap    = buildNodeMap()
            val screenJson = nodeMapToJson(nodeMap, LLAMA_MAX_NODES)
            val hash       = screenJson.hashCode()

            if (hash == lastHash) stuckCount++ else { stuckCount = 0; lastHash = hash }

            // STUCK: Groq re-plans (or scroll if LOCAL mode)
            if (stuckCount >= STUCK_THRESHOLD) {
                debug("STUCK", "Screen unchanged ${stuckCount}× at step $step")
                if (useGroq) {
                    handler.post { updateOverlay("Groq re-planning…", step, MAX_STEPS) }
                    val plannerScreen = nodeMapToJson(nodeMap, PLANNER_MAX_NODES)
                    callGroq(buildReplanPrompt(task, taskPlan, recentActions, plannerScreen),
                             GROQ_PLANNER_TOKENS) { newPlan ->
                        if (!newPlan.isNullOrBlank()) {
                            taskPlan = newPlan
                            debug("REPLAN", newPlan.take(100))
                        }
                        stuckCount = 0
                        handler.postDelayed({ doStep() }, ACTION_DELAY_MS)
                    }
                } else {
                    scrollForward(); stuckCount = 0
                    handler.postDelayed({ doStep() }, ACTION_DELAY_MS)
                }
                return
            }

            // Upload full node tree to Drive for unlimited context archive (non-blocking)
            if (HawkCloudConfig.isConfigured) {
                val fullTree = nodeMapToJson(nodeMap, 500)
                cloud.uploadNodeTree(taskSessionId, step, fullTree) { fid ->
                    if (fid != null) debug("CLOUD", "Drive step $step → $fid")
                }
            }

            val execLabel = if (currentModel != MODEL_LOCAL) "Groq ▶" else "Llama ▶"
            handler.post {
                KeyboardHawkIME.instance?.updateStatus("$status — $execLabel")
                updateOverlay("$execLabel", step, MAX_STEPS)
            }

            // EXECUTE: Llama picks the next action
            callLlamaForAction(
                buildExecutorPrompt(task, taskPlan, recentActions,
                                    memory.buildPromptBlock(task)),
                screenJson, step
            ) { action ->
                if (!isRunning || currentTaskId != taskId) return@callLlamaForAction
                val actionType = action.optString("action", "wait")

                if (actionType == "wait" && retries < MAX_RETRIES && step <= 2) {
                    retries++
                    debug("RETRY", "Wait on step $step, retry $retries/$MAX_RETRIES")
                    handler.postDelayed({ doStep() }, 800)
                    return@callLlamaForAction
                }
                retries = 0

                val summary = describeAction(action)
                debug("EXECUTE", summary)
                recentActions.add(summary)
                if (recentActions.size > 8) recentActions.removeAt(0)

                handler.post {
                    KeyboardHawkIME.instance?.updateStatus(summary)
                    updateOverlay(summary, step, MAX_STEPS)
                }
                executeAction(action, nodeMap)

                if (actionType == "done") {
                    val msg = action.optString("message", "Task complete")
                    debug("DONE", msg)
                    isRunning = false
                    handler.post {
                        KeyboardHawkIME.instance?.updateStatus("✓ $msg")
                        updateOverlay("✓ $msg")
                        handler.postDelayed({ removeOverlay() }, 4000)
                    }
                    // Groq extracts learnings → local + cloud memory
                    if (useGroq) {
                        val logLine = "Task: $task | Steps: $step | $msg | " +
                                      recentActions.joinToString(" → ")
                        callGroq(buildLearningsPrompt(task, recentActions, msg),
                                 GROQ_LEARN_TOKENS) { raw ->
                            raw?.split("\n")
                                ?.map { it.trim().removePrefix("-").trim() }
                                ?.filter { it.length > 8 }
                                ?.take(5)
                                ?.forEach { learned ->
                                    val mem = memory.save(learned, "auto")
                                    cloud.saveMemory(mem.id, learned, mem.timestamp, "auto") {}
                                    debug("MEM", "Learned: $learned")
                                }
                            cloud.logTask(logLine) {}
                        }
                    }
                } else {
                    handler.postDelayed({ doStep() }, ACTION_DELAY_MS)
                }
            }
        }

        // PHASE 1: Groq generates execution plan (skipped in LOCAL mode)
        if (useGroq) {
            val memBlock      = memory.buildPromptBlock(task)
            val plannerScreen = nodeMapToJson(buildNodeMap(), PLANNER_MAX_NODES)
            handler.post { updateOverlay("Groq planning…") }
            callGroq(buildPlannerPrompt(task, plannerScreen, memBlock),
                     GROQ_PLANNER_TOKENS) { plan ->
                taskPlan = plan ?: ""
                debug("PLAN", taskPlan.take(120))
                handler.postDelayed({ doStep() }, 700)
            }
        } else {
            handler.postDelayed({ doStep() }, 700)
        }
    }

    private fun describeAction(action: JSONObject): String {
        return when (action.optString("action")) {
            "click"   -> "Click ${action.optString("id")}"
            "tap"     -> "Tap (${action.optInt("x")}, ${action.optInt("y")})"
            "type"    -> "Type \"${action.optString("text").take(20)}\""
            "scroll"  -> "Scroll ${action.optString("direction")}"
            "swipe"   -> "Swipe gesture"
            "back"    -> "Press Back"
            "home"    -> "Press Home"
            "openApp" -> "Open ${action.optString("package")}"
            "done"    -> "Done: ${action.optString("message")}"
            else      -> "Wait"
        }
    }

    // ── PROMPT BUILDERS ───────────────────────────────────────────
    //
    //  buildPlannerPrompt  → Groq at task start   (gets 160 nodes, memories)
    //  buildReplanPrompt   → Groq when stuck       (gets fresh 160-node view)
    //  buildExecutorPrompt → Llama every step      (gets 80 nodes, plan reference)
    //  buildLearningsPrompt→ Groq on done          (extracts bullet memories)

    private fun buildPlannerPrompt(task: String, screenJson: String, memBlock: String): String =
        "You are Hawk's strategic planner. Read the initial screen and user memories, " +
        "then produce a numbered execution plan.\n" +
        "TASK: $task\n$memBlock\n" +
        "INITIAL SCREEN (top $PLANNER_MAX_NODES nodes):\n${screenJson.take(2800)}\n\n" +
        "Reply with a concise numbered plan (4–8 steps). Each step = one concrete UI action.\n" +
        "Note any app that needs to be opened first. Plain text — NO JSON."

    private fun buildReplanPrompt(
        task: String, oldPlan: String,
        recentActions: List<String>, screenJson: String
    ): String =
        "You are Hawk's replanner. The agent is stuck (same screen repeated).\n" +
        "TASK: $task\nOLD PLAN:\n$oldPlan\n" +
        "LAST ACTIONS: ${recentActions.joinToString(" → ")}\n" +
        "CURRENT SCREEN: ${screenJson.take(1800)}\n\n" +
        "The old plan is not working. Create a different numbered plan (3–6 steps).\n" +
        "Try a different approach: scroll, press back, use search, or navigate differently.\n" +
        "Plain text — NO JSON."

    private fun buildExecutorPrompt(
        task: String, plan: String,
        recentActions: List<String>, memBlock: String
    ): String {
        val planSection = if (plan.isNotBlank()) "\nEXECUTION PLAN:\n$plan\n" else ""
        val history = if (recentActions.isNotEmpty())
            "\nLast actions: ${recentActions.joinToString(" → ")}" else ""
        val diagSelf = if (task.lowercase().let {
            it.contains("diagnos") || it.contains("debug") || it.contains("broken") ||
            it.contains("not working") || it.contains("why")
        }) """
SELF-KNOWLEDGE:
  Model routing: LOCAL→Render proxy→ngrok→Colab Llama | FAST/SMART→Groq
  Node map: ≤$LLAMA_MAX_NODES nodes (Llama) | ≤$PLANNER_MAX_NODES nodes (Groq planner)
  Full tree archived to Google Drive each step (if cloud configured)
  Memories: local SharedPrefs + Google Sheets (unlimited via Apps Script)
  Failure modes: 429=rate-limit|401=bad key|AI unreachable=Colab/ngrok down
  Current: model=$currentModel url=$cachedAiUrl maxSteps=$MAX_STEPS
""" else ""
        return "You are Hawk, an autonomous Android UI executor.$planSection$history$memBlock$diagSelf\n" +
            "GOAL: $task\n\n" +
            "UI tree JSON fields: id, t(type), txt, x, y, inp(editable), scr(scrollable).\n" +
            "Types: btn, input, scroll, text.\n\n" +
            "RULES:\n" +
            "- NEVER click nodes whose txt contains 'Hawk','HAWK','CMD','CHAT','Step ','Llama ▶' — your own UI.\n" +
            "- Follow the execution plan step by step.\n" +
            "- Prefer {action:click,id:\"n12\"} over tap with coordinates.\n" +
            "- To type: use {action:type,x:…,y:…,text:\"…\"} at the field's centre.\n" +
            "- When goal is achieved: {action:done,message:\"reason\"}.\n\n" +
            "Reply with EXACTLY ONE raw JSON object — no markdown, no explanation.\n\n" +
            "Valid actions:\n" +
            "{\"action\":\"click\",\"id\":\"n12\"}\n" +
            "{\"action\":\"tap\",\"x\":180,\"y\":400}\n" +
            "{\"action\":\"type\",\"x\":180,\"y\":300,\"text\":\"hello world\"}\n" +
            "{\"action\":\"scroll\",\"direction\":\"forward\"}\n" +
            "{\"action\":\"scroll\",\"direction\":\"backward\"}\n" +
            "{\"action\":\"swipe\",\"sx\":180,\"sy\":700,\"ex\":180,\"ey\":200}\n" +
            "{\"action\":\"back\"}\n" +
            "{\"action\":\"home\"}\n" +
            "{\"action\":\"openApp\",\"package\":\"com.android.chrome\"}\n" +
            "{\"action\":\"done\",\"message\":\"reason\"}"
    }

    private fun buildLearningsPrompt(
        task: String, recentActions: List<String>, result: String
    ): String =
        "Task completed: \"$task\"\nResult: $result\n" +
        "Actions taken: ${recentActions.joinToString(" → ")}\n\n" +
        "Extract 1–4 short memory bullets about what worked or what to remember for next time.\n" +
        "Each bullet on its own line starting with '- '.\n" +
        "Only include non-obvious things. Plain text — NO JSON."

    // ── HTTP LAYER ────────────────────────────────────────────────
    //
    //  callAI          → generic OpenAI-compatible call (Groq or Llama endpoint)
    //  callGroq        → planning / re-planning / learnings (text response)
    //  callLlamaForAction → action execution (JSON response → JSONObject)

    private fun callAI(
        url: String,
        model: String,
        useGroqAuth: Boolean,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        temperature: Double = 0.05,
        callback: (String?) -> Unit
    ) {
        val messages = JSONArray().apply {
            put(JSONObject().also { it.put("role", "system"); it.put("content", systemPrompt) })
            put(JSONObject().also { it.put("role", "user");   it.put("content", userContent) })
        }
        val body = JSONObject().apply {
            put("model", model); put("messages", messages)
            put("max_tokens", maxTokens); put("temperature", temperature)
        }
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .also { if (useGroqAuth) it.addHeader("Authorization", "Bearer ${getApiKey()}") }
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val who = if (useGroqAuth) "Groq" else "Llama"
                debug("AI-ERR", "$who network: ${e.message}")
                handler.post { updateOverlay("$who unreachable") }
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    debug("AI-ERR", "HTTP ${response.code}: ${raw.take(80)}")
                    callback(null); return
                }
                try {
                    val content = JSONObject(raw).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message")
                        .getString("content").trim()
                    callback(content)
                } catch (e: Exception) {
                    debug("AI-ERR", "Parse: ${e.message} | ${raw.take(60)}")
                    callback(null)
                }
            }
        })
    }

    // Groq call — returns plain text (plan, re-plan, or learnings)
    private fun callGroq(systemPrompt: String, maxTokens: Int, callback: (String?) -> Unit) {
        val model = if (currentModel == MODEL_FAST) MODEL_FAST else MODEL_SMART
        callAI(GROQ_URL, model, true, systemPrompt, "Proceed.", maxTokens, 0.3, callback)
    }

    // Action executor — uses Groq when model is FAST/SMART, Llama when LOCAL
    private fun callLlamaForAction(
        systemPrompt: String,
        screenJson: String,
        step: Int,
        callback: (JSONObject) -> Unit
    ) {
        val userContent = "Step $step. Screen:\n${screenJson.take(3500)}"
        val useGroqExec = currentModel != MODEL_LOCAL
        val execUrl     = if (useGroqExec) GROQ_URL else "${getAiUrl()}/v1/chat/completions"
        val execModel   = if (useGroqExec)
                              if (currentModel == MODEL_FAST) MODEL_FAST else MODEL_SMART
                          else "llama-3.1-8b-instruct"
        val tokens      = if (useGroqExec) 150 else 80
        callAI(execUrl, execModel, useGroqExec, systemPrompt, userContent, tokens, 0.05) { content ->
            val action = if (content != null) {
                debug(if (useGroqExec) "GROQ-EX" else "LLAMA", content.take(120))
                parseAction(content)
            } else {
                JSONObject().put("action", "wait")
            }
            handler.post { callback(action) }
        }
    }

    // Robust JSON extractor — handles markdown, extra text, nested content
    private fun parseAction(content: String): JSONObject {
        // 1. Direct parse
        try { return JSONObject(content) } catch (_: Exception) {}

        // 2. Strip markdown code fences: ```json ... ``` or ``` ... ```
        val fenced = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```")
            .find(content)?.groupValues?.getOrNull(1)
        if (fenced != null) try { return JSONObject(fenced) } catch (_: Exception) {}

        // 3. Find first { ... last } in the string
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try { return JSONObject(content.substring(start, end + 1)) } catch (_: Exception) {}
        }

        // 4. Try to interpret plain language instructions
        val lower = content.lowercase()
        return when {
            lower.contains("scroll down") || lower.contains("scroll forward") ->
                JSONObject().put("action", "scroll").put("direction", "forward")
            lower.contains("scroll up") || lower.contains("scroll backward") ->
                JSONObject().put("action", "scroll").put("direction", "backward")
            lower.contains("press back") || lower.contains("go back") ->
                JSONObject().put("action", "back")
            lower.contains("press home") || lower.contains("go home") ->
                JSONObject().put("action", "home")
            lower.contains("task") && lower.contains("complet") ->
                JSONObject().put("action", "done").put("message", "Task complete")
            else -> {
                debug("AI-PARSE", "Could not parse: ${content.take(60)}")
                JSONObject().put("action", "wait")
            }
        }
    }

    // ── ACTION EXECUTOR ───────────────────────────────────────────

    private fun executeAction(action: JSONObject, nodeMap: List<HawkNode>) {
        when (val type = action.optString("action", "wait")) {
            "click" -> {
                val id = action.optString("id")
                val node = findNodeById(nodeMap, id)
                if (node != null) clickNode(node)
                else debug("EXECUTE", "Node $id not found in map")
            }
            "tap" -> simulateTap(
                action.optDouble("x", 180.0).toFloat(),
                action.optDouble("y", 400.0).toFloat()
            )
            "type" -> typeAtCoords(
                action.optDouble("x", 180.0).toFloat(),
                action.optDouble("y", 400.0).toFloat(),
                action.optString("text")
            )
            "scroll" -> {
                if (action.optString("direction") == "backward") scrollBackward()
                else scrollForward()
            }
            "swipe" -> simulateSwipe(
                action.optDouble("sx", 180.0).toFloat(),
                action.optDouble("sy", 700.0).toFloat(),
                action.optDouble("ex", 180.0).toFloat(),
                action.optDouble("ey", 200.0).toFloat()
            )
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "openApp" -> launchApp(action.optString("package"))
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "wait", "done" -> Unit
            else -> debug("EXECUTE", "Unknown action: $type")
        }
    }
}
