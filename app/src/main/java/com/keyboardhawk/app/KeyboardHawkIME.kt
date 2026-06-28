package com.keyboardhawk.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ══════════════════════════════════════════════════════════════════
//  Keyboard Hawk IME  —  Full-featured futuristic keyboard + AI
//  Modes: COMMAND (run AI agent) | CHAT (type normally)
//  Features: QWERTY, caps lock, clipboard manager, debug panel,
//            toolbar, self-diagnosis, color-coded logs
// ══════════════════════════════════════════════════════════════════

class KeyboardHawkIME : InputMethodService() {

    companion object {
        var instance: KeyboardHawkIME? = null
    }

    // ── Enums ─────────────────────────────────────────────────────
    enum class Mode { COMMAND, CHAT }
    enum class Panel { NONE, LOG, CLIPBOARD, DEBUG, MEMORY }

    // ── State ─────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var currentMode = Mode.COMMAND
    private var activePanel = Panel.NONE
    private var isCapsOn = false
    private var isSymbols = false
    private var isToolbarExpanded = false

    // Clipboard history — up to 12 entries
    private val clipHistory = mutableListOf<String>()
    private var clipManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // Log lines with color spans
    private val logLines = mutableListOf<String>()            // plain agent log
    private val debugEntries = mutableListOf<DebugEntry>()   // rich debug

    data class DebugEntry(
        val time: String,
        val level: String,   // INFO / WARN / ERROR / FATAL
        val stage: String,
        val message: String
    )

    // ── Views ──────────────────────────────────────────────────────
    private var rootView: View? = null
    private var tvStatus: TextView? = null
    private var tvModeBadge: TextView? = null
    private var vHawkDot: View? = null
    private var btnModelToggle: TextView? = null
    private var btnToolbar: TextView? = null

    private var toolbar: LinearLayout? = null
    private var panelLog: ScrollView? = null
    private var panelClip: LinearLayout? = null
    private var panelDebug: ScrollView? = null
    private var panelMemory: LinearLayout? = null
    private var clipList: LinearLayout? = null
    private var memList: LinearLayout? = null
    private var tvLog: TextView? = null
    private var tvDebugLog: TextView? = null
    private var cmdBar: LinearLayout? = null
    private var etCommand: EditText? = null
    private val memory by lazy { HawkMemory(this) }
    private val cloud = HawkCloudStorage()

    // All 26 letter keys + special keys
    private val letterKeys = mutableMapOf<String, TextView>()
    private var kCaps: TextView? = null
    private var kDel: TextView? = null
    private var kSpace: TextView? = null
    private var kEnter: TextView? = null
    private var kMemo: TextView? = null
    private var kComma: TextView? = null
    private var kPeriod: TextView? = null

    // Letter → view id map
    private val letterIds = mapOf(
        "q" to R.id.kQ, "w" to R.id.kW, "e" to R.id.kE, "r" to R.id.kR, "t" to R.id.kT,
        "y" to R.id.kY, "u" to R.id.kU, "i" to R.id.kI, "o" to R.id.kO, "p" to R.id.kP,
        "a" to R.id.kA, "s" to R.id.kS, "d" to R.id.kD, "f" to R.id.kF, "g" to R.id.kG,
        "h" to R.id.kH, "j" to R.id.kJ, "k" to R.id.kK, "l" to R.id.kL,
        "z" to R.id.kZ, "x" to R.id.kX, "c" to R.id.kC, "v" to R.id.kV,
        "b" to R.id.kB, "n" to R.id.kN, "m" to R.id.kM
    )

    private val symbolRow1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val symbolRow2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", "'")
    private val symbolRow3 = listOf("/", "\\", "|", "<", ">", "?", "~", "`")

    // ══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    override fun onCreateInputView(): View {
        instance = this
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = view
        bindViews(view)
        setupListeners(view)
        setupClipboard()
        syncState()
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Auto-suggest mode based on field type
        if (currentMode == Mode.CHAT) {
            handler.post { updateStatus("CHAT · ready") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipListener?.let { clipManager?.removeOnPrimaryClipChangedListener(it) }
        instance = null
    }

    // ══════════════════════════════════════════════════════════════
    //  VIEW BINDING
    // ══════════════════════════════════════════════════════════════

    private fun bindViews(v: View) {
        tvStatus = v.findViewById(R.id.tvStatus)
        tvModeBadge = v.findViewById(R.id.tvModeBadge)
        vHawkDot = v.findViewById(R.id.vHawkDot)
        btnModelToggle = v.findViewById(R.id.btnModelToggle)
        btnToolbar = v.findViewById(R.id.btnToolbar)

        toolbar = v.findViewById(R.id.toolbar)
        panelLog = v.findViewById(R.id.panelLog)
        panelClip = v.findViewById(R.id.panelClip)
        panelDebug = v.findViewById(R.id.panelDebug)
        clipList = v.findViewById(R.id.clipList)
        tvLog = v.findViewById(R.id.tvLog)
        tvDebugLog = v.findViewById(R.id.tvDebugLog)
        cmdBar = v.findViewById(R.id.cmdBar)
        etCommand = v.findViewById(R.id.etCommand)

        kCaps = v.findViewById(R.id.kCaps)
        kDel = v.findViewById(R.id.kDel)
        kSpace = v.findViewById(R.id.kSpace)
        kEnter = v.findViewById(R.id.kEnter)
        kMemo = v.findViewById(R.id.kMemo)
        kComma = v.findViewById(R.id.kComma)
        kPeriod = v.findViewById(R.id.kPeriod)

        // Bind all letter keys
        for ((letter, resId) in letterIds) {
            val tv = v.findViewById<TextView>(resId)
            letterKeys[letter] = tv
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EVENT LISTENERS
    // ══════════════════════════════════════════════════════════════

    private fun setupListeners(v: View) {
        // Mode badge toggle
        tvModeBadge?.setOnClickListener { toggleMode() }

        // Model selector
        btnModelToggle?.setOnClickListener { cycleModel() }

        // Toolbar expand
        btnToolbar?.setOnClickListener { toggleToolbar() }

        // Toolbar items
        v.findViewById<View>(R.id.toolClip).setOnClickListener { togglePanel(Panel.CLIPBOARD) }
        v.findViewById<View>(R.id.toolDebug).setOnClickListener { togglePanel(Panel.DEBUG) }
        v.findViewById<View>(R.id.toolLog).setOnClickListener { togglePanel(Panel.LOG) }
        v.findViewById<View>(R.id.toolDiagnose).setOnClickListener { runDiagnosis() }
        v.findViewById<View>(R.id.toolClear).setOnClickListener { clearAll() }
        v.findViewById<View>(R.id.toolStop).setOnClickListener { stopTask() }
        v.findViewById<View>(R.id.toolMemory).setOnClickListener { togglePanel(Panel.MEMORY) }

        // Memory panel controls
        panelMemory = v.findViewById(R.id.panelMemory)
        memList     = v.findViewById(R.id.memList)
        v.findViewById<TextView>(R.id.tvMemClear).setOnClickListener { clearAllMemories() }
        v.findViewById<View>(R.id.btnMemSave).setOnClickListener { saveNewMemory() }

        // Debug panel buttons
        v.findViewById<TextView>(R.id.tvDebugCopy).setOnClickListener { copyDebugLog() }
        v.findViewById<TextView>(R.id.tvDebugClear).setOnClickListener { clearDebugLog() }

        // CMD bar
        v.findViewById<View>(R.id.btnRun).setOnClickListener { runCommand() }
        etCommand?.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
            if (isSend || isEnter) { runCommand(); true } else false
        }

        // Letter keys
        for ((letter, tv) in letterKeys) {
            tv.setOnClickListener { typeChar(letter) }
        }

        // Special keys
        kCaps?.setOnClickListener { toggleCaps() }
        kDel?.setOnClickListener { pressDelete() }
        kDel?.setOnLongClickListener { deleteWord(); true }
        kSpace?.setOnClickListener { typeChar(" ") }
        kComma?.setOnClickListener { typeChar(",") }
        kPeriod?.setOnClickListener { typeChar(".") }
        kMemo?.setOnClickListener { togglePanel(Panel.MEMORY) }
        kMemo?.setOnLongClickListener { promptAddMemory(); true }
        kEnter?.setOnClickListener { pressEnter() }
        kEnter?.setOnLongClickListener { runCommand(); true }
    }

    // ══════════════════════════════════════════════════════════════
    //  CLIPBOARD MANAGER
    // ══════════════════════════════════════════════════════════════

    private fun setupClipboard() {
        clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipManager?.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(applicationContext).toString().trim()
                if (text.isNotEmpty() && (clipHistory.isEmpty() || clipHistory.first() != text)) {
                    clipHistory.add(0, text)
                    if (clipHistory.size > 12) clipHistory.removeAt(clipHistory.size - 1)
                    if (activePanel == Panel.CLIPBOARD) refreshClipPanel()
                    debugInfo("CLIP", "Captured: ${text.take(30)}")
                }
            }
        }
        clipManager?.addOnPrimaryClipChangedListener(clipListener!!)
    }

    private fun refreshClipPanel() {
        val list = clipList ?: return
        list.removeAllViews()
        if (clipHistory.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No clipboard history yet"
                setTextColor(Color.parseColor("#334455"))
                textSize = 8f
                setPadding(8, 4, 8, 4)
            }
            list.addView(empty)
            return
        }
        for ((idx, entry) in clipHistory.withIndex()) {
            val tv = TextView(this).apply {
                text = "#${idx + 1}  ${entry.take(44)}${if (entry.length > 44) "…" else ""}"
                setTextColor(Color.parseColor("#5599BB"))
                textSize = 8f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(10, 5, 10, 5)
                setBackgroundResource(R.drawable.clip_item_bg)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                p.setMargins(0, 2, 0, 2)
                layoutParams = p
                isClickable = true
                isFocusable = true
                setOnClickListener { pasteClipEntry(entry) }
            }
            list.addView(tv)
        }
    }

    private fun pasteClipEntry(text: String) {
        if (currentMode == Mode.CHAT) {
            currentInputConnection?.commitText(text, 1)
        } else {
            etCommand?.apply {
                val pos = selectionStart.coerceAtLeast(0)
                val sb = StringBuilder(getText())
                sb.insert(pos, text)
                setText(sb)
                setSelection((pos + text.length).coerceAtMost(sb.length))
            }
        }
        debugInfo("CLIP", "Pasted: ${text.take(30)}")
    }

    // ══════════════════════════════════════════════════════════════
    //  MODE & TOOLBAR
    // ══════════════════════════════════════════════════════════════

    private fun toggleMode() {
        currentMode = if (currentMode == Mode.COMMAND) Mode.CHAT else Mode.COMMAND
        applyMode()
        debugInfo("MODE", "Switched to $currentMode")
    }

    private fun applyMode() {
        when (currentMode) {
            Mode.COMMAND -> {
                tvModeBadge?.text = "CMD"
                tvModeBadge?.setTextColor(Color.parseColor("#AA44FF"))
                tvModeBadge?.setBackgroundResource(R.drawable.mode_cmd_bg)
                cmdBar?.visibility = View.VISIBLE
                updateStatus("CMD MODE — type task, press ▶")
            }
            Mode.CHAT -> {
                tvModeBadge?.text = "CHAT"
                tvModeBadge?.setTextColor(Color.parseColor("#0088FF"))
                tvModeBadge?.setBackgroundResource(R.drawable.mode_chat_bg)
                cmdBar?.visibility = View.GONE
                updateStatus("CHAT MODE — typing normally")
            }
        }
    }

    private fun toggleToolbar() {
        isToolbarExpanded = !isToolbarExpanded
        toolbar?.visibility = if (isToolbarExpanded) View.VISIBLE else View.GONE
        btnToolbar?.text = if (isToolbarExpanded) "▲" else "⌂"
        btnToolbar?.setTextColor(
            Color.parseColor(if (isToolbarExpanded) "#00AAFF" else "#445577")
        )
        if (!isToolbarExpanded) togglePanel(Panel.NONE)
    }

    private fun togglePanel(panel: Panel) {
        activePanel = if (activePanel == panel) Panel.NONE else panel
        panelLog?.visibility    = View.GONE
        panelClip?.visibility   = View.GONE
        panelDebug?.visibility  = View.GONE
        panelMemory?.visibility = View.GONE
        when (activePanel) {
            Panel.LOG       -> { panelLog?.visibility    = View.VISIBLE; scrollLogToBottom() }
            Panel.CLIPBOARD -> { panelClip?.visibility   = View.VISIBLE; refreshClipPanel() }
            Panel.DEBUG     -> { panelDebug?.visibility  = View.VISIBLE; scrollDebugToBottom() }
            Panel.MEMORY    -> { panelMemory?.visibility = View.VISIBLE; refreshMemoryPanel() }
            Panel.NONE      -> {}
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TYPING
    // ══════════════════════════════════════════════════════════════

    private fun typeChar(raw: String) {
        val ch = if (!isSymbols && raw.length == 1 && raw[0].isLetter()) {
            if (isCapsOn) raw.uppercase() else raw.lowercase()
        } else raw

        when (currentMode) {
            Mode.CHAT -> {
                currentInputConnection?.commitText(ch, 1)
                // Auto-disable caps after first letter (shift behavior)
                if (isCapsOn && raw.length == 1 && raw[0].isLetter()) {
                    // Keep caps on — user explicitly toggled; don't auto-off
                }
            }
            Mode.COMMAND -> {
                val et = etCommand ?: return
                val pos = et.selectionStart.coerceAtLeast(0)
                val sb = StringBuilder(et.text)
                sb.insert(pos, ch)
                et.setText(sb)
                et.setSelection((pos + ch.length).coerceAtMost(sb.length))
            }
        }
    }

    private fun pressDelete() {
        when (currentMode) {
            Mode.CHAT -> currentInputConnection?.deleteSurroundingText(1, 0)
            Mode.COMMAND -> {
                val et = etCommand ?: return
                val pos = et.selectionStart
                if (pos > 0) {
                    val sb = StringBuilder(et.text)
                    sb.deleteCharAt(pos - 1)
                    et.setText(sb)
                    et.setSelection((pos - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun deleteWord() {
        when (currentMode) {
            Mode.CHAT -> {
                val ic = currentInputConnection ?: return
                val text = ic.getTextBeforeCursor(50, 0) ?: return
                val trimmed = text.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                val deleteCount = if (lastSpace < 0) trimmed.length + (text.length - trimmed.length)
                else text.length - lastSpace - 1
                ic.deleteSurroundingText(deleteCount.coerceAtLeast(1), 0)
            }
            Mode.COMMAND -> {
                val et = etCommand ?: return
                val pos = et.selectionStart
                val text = et.text.substring(0, pos)
                val trimmed = text.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                val start = if (lastSpace < 0) 0 else lastSpace + 1
                val sb = StringBuilder(et.text)
                sb.delete(start, pos)
                et.setText(sb)
                et.setSelection(start)
            }
        }
    }

    private fun pressEnter() {
        when (currentMode) {
            Mode.CHAT -> {
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                )
            }
            Mode.COMMAND -> runCommand()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CAPS LOCK
    // ══════════════════════════════════════════════════════════════

    private fun toggleCaps() {
        isCapsOn = !isCapsOn
        applyCaps()
    }

    private fun applyCaps() {
        val textColor = if (isCapsOn) Color.parseColor("#00CCFF") else Color.parseColor("#8899AA")
        kCaps?.setTextColor(textColor)
        kCaps?.setBackgroundResource(
            if (isCapsOn) R.drawable.key_caps_active else R.drawable.key_special
        )
        // Update all letter key labels
        for ((letter, tv) in letterKeys) {
            tv.text = if (isCapsOn) letter.uppercase() else letter.lowercase()
            tv.setTextColor(
                if (isCapsOn) Color.parseColor("#DDEEFF") else Color.parseColor("#7799CC")
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SYMBOL MODE (stub — expand as needed)
    // ══════════════════════════════════════════════════════════════

    private fun toggleSymbols() {
        isSymbols = !isSymbols
        kMemo?.text = if (isSymbols) "ABC" else "MEM"
        kMemo?.setTextColor(
            Color.parseColor(if (isSymbols) "#00CCFF" else "#44BBDD")
        )
        if (isSymbols) {
            // Map top 10 letter keys to symbol row 1
            val letters = listOf("q","w","e","r","t","y","u","i","o","p")
            letters.forEachIndexed { i, l ->
                letterKeys[l]?.text = symbolRow1.getOrElse(i) { l }
                letterKeys[l]?.setTextColor(Color.parseColor("#AABBCC"))
            }
            // Map middle row
            val mid = listOf("a","s","d","f","g","h","j","k","l")
            mid.forEachIndexed { i, l ->
                letterKeys[l]?.text = symbolRow2.getOrElse(i) { l }
                letterKeys[l]?.setTextColor(Color.parseColor("#AABBCC"))
            }
            // Map bottom
            val bot = listOf("z","x","c","v","b","n","m")
            bot.forEachIndexed { i, l ->
                letterKeys[l]?.text = symbolRow3.getOrElse(i) { l }
                letterKeys[l]?.setTextColor(Color.parseColor("#AABBCC"))
            }
        } else {
            // Restore letters
            for ((letter, tv) in letterKeys) {
                tv.text = if (isCapsOn) letter.uppercase() else letter.lowercase()
                tv.setTextColor(
                    if (isCapsOn) Color.parseColor("#DDEEFF") else Color.parseColor("#7799CC")
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK CONTROL
    // ══════════════════════════════════════════════════════════════

    private fun runCommand() {
        val svc = HawkAccessibilityService.instance
        if (svc == null) {
            updateStatus("⚠ Accessibility Service not connected")
            debugError("CMD", "Service is null — user must enable Accessibility Service")
            return
        }
        val task = etCommand?.text?.toString()?.trim() ?: ""
        if (task.isEmpty()) {
            updateStatus("Type a task command first")
            return
        }
        if (svc.isRunning) {
            updateStatus("⚡ Already running — use STOP to cancel")
            return
        }
        logLines.clear()
        addLog("▶ Task: $task")
        updateStatus("Launching agent…")
        svc.runTask(task)
    }

    private fun stopTask() {
        val svc = HawkAccessibilityService.instance
        if (svc != null) {
            svc.stopTask()
            debugInfo("CMD", "Stop requested by user")
        } else {
            updateStatus("Service not connected")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MEMORY PANEL
    // ══════════════════════════════════════════════════════════════

    private fun refreshMemoryPanel() {
        val list = memList ?: return
        list.removeAllViews()
        val entries = memory.getAll()
        if (entries.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "No memories yet — add one above or run a task"
                setTextColor(Color.parseColor("#223344"))
                textSize = 8f
                setPadding(6, 4, 6, 4)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            list.addView(tv)
            return
        }
        for (mem in entries) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(4, 2, 4, 2)
            }
            val label = android.widget.TextView(this).apply {
                text = "[${mem.source.take(4).uppercase()}] ${mem.content}"
                setTextColor(Color.parseColor(if (mem.source == "user") "#5599CC" else "#44AA77"))
                textSize = 7.5f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(2, 0, 4, 0)
            }
            val date = android.widget.TextView(this).apply {
                text = memory.formatDate(mem)
                setTextColor(Color.parseColor("#1A3344"))
                textSize = 6f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            // Tap to paste into command bar
            row.setOnClickListener {
                rootView?.findViewById<android.widget.EditText>(R.id.etCommand)?.let { et ->
                    val cur = et.text?.toString() ?: ""
                    et.setText(if (cur.isBlank()) mem.content else "$cur ${mem.content}")
                    et.setSelection(et.text.length)
                }
                updateStatus("Memory pasted to command bar")
            }
            // Long-press to delete (also from cloud)
            row.setOnLongClickListener {
                memory.delete(mem.id)
                cloud.deleteMemory(mem.id) {}
                debugInfo("MEM", "Deleted: ${mem.content.take(30)}")
                refreshMemoryPanel()
                true
            }
            row.addView(label)
            row.addView(date)
            list.addView(row)
        }
    }

    private fun saveNewMemory() {
        val et = rootView?.findViewById<android.widget.EditText>(R.id.etNewMem) ?: return
        val text = et.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        val mem = memory.save(text, "user")
        cloud.saveMemory(mem.id, text, mem.timestamp, "user") {}
        et.setText("")
        debugInfo("MEM", "Saved: $text")
        refreshMemoryPanel()
    }

    private fun clearAllMemories() {
        memory.clear()
        debugWarn("MEM", "All local memories cleared")
        refreshMemoryPanel()
    }

    // Long-press on MEM key — quick-add the current command bar text as a memory
    private fun promptAddMemory() {
        val cmdText = rootView?.findViewById<android.widget.EditText>(R.id.etCommand)
            ?.text?.toString()?.trim() ?: ""
        if (cmdText.isNotBlank()) {
            val mem = memory.save(cmdText, "user")
            cloud.saveMemory(mem.id, cmdText, mem.timestamp, "user") {}
            updateStatus("Saved to memory: ${cmdText.take(30)}")
            debugInfo("MEM", "Quick-saved: $cmdText")
        } else {
            togglePanel(Panel.MEMORY)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SELF-DIAGNOSIS
    // ══════════════════════════════════════════════════════════════

    private fun runDiagnosis() {
        val svc = HawkAccessibilityService.instance
        if (svc == null) {
            debugError("DIAG", "HawkAccessibilityService not connected — ensure accessibility is enabled in Settings")
            updateStatus("⚠ Service offline")
            return
        }
        debugInfo("DIAG", "Running self-diagnosis…")

        val lines = mutableListOf<String>()
        lines += "=== HAWK DIAGNOSTIC REPORT ${dateFormat.format(Date())} ==="
        lines += "Mode: $currentMode | Model: ${HawkAccessibilityService.currentModel}"
        lines += "Caps: $isCapsOn | Symbols: $isSymbols"
        lines += "Service: ${if (svc != null) "CONNECTED" else "OFFLINE ⚠"}"
        lines += "Agent running: ${svc?.isRunning}"
        lines += "AI URL: ${svc?.getAiUrl()}"
        lines += "Clip history: ${clipHistory.size} entries"
        lines += "Log lines: ${logLines.size} | Debug entries: ${debugEntries.size}"
        lines += ""
        lines += "=== RECENT ERRORS ==="
        val errors = debugEntries.filter { it.level in listOf("ERROR", "FATAL") }.takeLast(5)
        if (errors.isEmpty()) lines += "(no errors)" else errors.forEach { lines += "[${it.stage}] ${it.message}" }
        lines += ""
        lines += "=== ARCH SUMMARY ==="
        lines += "AccessibilityService reads UI tree → prunes to 80 nodes"
        lines += "Sends JSON to AI (LOCAL/FAST/SMART) → parses action"
        lines += "Executes: click/tap/type/scroll/swipe/back/home/openApp"
        lines += "Floating overlay shows progress (TYPE_ACCESSIBILITY_OVERLAY)"
        lines += "IME types via InputConnection in CHAT mode"
        lines += "Max steps: ${HawkAccessibilityService.MAX_STEPS}, delay: ${HawkAccessibilityService.ACTION_DELAY_MS}ms"
        lines += "Stuck threshold: ${HawkAccessibilityService.STUCK_THRESHOLD} same-screen detections"
        lines += "==="

        val report = lines.joinToString("\n")
        // Copy to clipboard
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hawk_diag", report))

        // Show in debug panel
        report.split("\n").forEach { debugInfo("DIAG", it) }
        togglePanel(Panel.DEBUG)
        if (!isToolbarExpanded) toggleToolbar()
        updateStatus("Diagnostic complete — copied to clipboard")
    }

    // ══════════════════════════════════════════════════════════════
    //  MODEL CYCLING
    // ══════════════════════════════════════════════════════════════

    private fun cycleModel() {
        HawkAccessibilityService.currentModel = when (HawkAccessibilityService.currentModel) {
            HawkAccessibilityService.MODEL_LOCAL -> HawkAccessibilityService.MODEL_FAST
            HawkAccessibilityService.MODEL_FAST -> HawkAccessibilityService.MODEL_SMART
            else -> HawkAccessibilityService.MODEL_LOCAL
        }
        updateModelButton()
        debugInfo("MODEL", "Switched to ${HawkAccessibilityService.currentModel}")
    }

    private fun updateModelButton() {
        val (label, color) = when (HawkAccessibilityService.currentModel) {
            HawkAccessibilityService.MODEL_FAST -> "FAST" to "#00CCFF"
            HawkAccessibilityService.MODEL_SMART -> "SMART" to "#FF8800"
            else -> "LOCAL" to "#00AAFF"
        }
        btnModelToggle?.text = label
        btnModelToggle?.setTextColor(Color.parseColor(color))
    }

    // ══════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════

    private fun clearAll() {
        logLines.clear()
        tvLog?.text = "Log cleared."
        updateStatus("Logs cleared")
        debugInfo("SYS", "Log cleared by user")
    }

    private fun copyDebugLog() {
        val text = debugEntries.joinToString("\n") {
            "[${it.time}][${it.level}][${it.stage}] ${it.message}"
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hawk_debug", text))
        updateStatus("Debug log copied to clipboard")
    }

    private fun clearDebugLog() {
        debugEntries.clear()
        tvDebugLog?.text = "Debug cleared."
    }

    private fun scrollLogToBottom() {
        panelLog?.post { panelLog?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun scrollDebugToBottom() {
        panelDebug?.post { panelDebug?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun syncState() {
        applyMode()
        applyCaps()
        updateModelButton()
        val svc = HawkAccessibilityService.instance
        if (svc?.isRunning == true) updateStatus("Agent running…")
    }

    // ══════════════════════════════════════════════════════════════
    //  PUBLIC UPDATE METHODS (called by HawkAccessibilityService)
    // ══════════════════════════════════════════════════════════════

    fun updateStatus(msg: String) {
        handler.post { tvStatus?.text = msg }
    }

    fun addLog(msg: String) {
        handler.post {
            val ts = dateFormat.format(Date())
            logLines.add("[$ts] $msg")
            if (logLines.size > 120) logLines.removeAt(0)
            tvLog?.text = logLines.takeLast(30).joinToString("\n")
            scrollLogToBottom()
        }
    }

    // Alias so HawkAccessibilityService can call updateLog()
    fun updateLog(msg: String) = addLog(msg)

    fun debugInfo(stage: String, msg: String) = addDebugEntry("INFO", stage, msg)
    fun debugWarn(stage: String, msg: String) = addDebugEntry("WARN", stage, msg)
    fun debugError(stage: String, msg: String) = addDebugEntry("ERROR", stage, msg)
    fun debugFatal(stage: String, msg: String) = addDebugEntry("FATAL", stage, msg)

    private fun addDebugEntry(level: String, stage: String, msg: String) {
        handler.post {
            val ts = dateFormat.format(Date())
            debugEntries.add(DebugEntry(ts, level, stage, msg))
            if (debugEntries.size > 200) debugEntries.removeAt(0)
            if (activePanel == Panel.DEBUG) refreshDebugView()
        }
    }

    private fun refreshDebugView() {
        val ssb = SpannableStringBuilder()
        for (entry in debugEntries.takeLast(60)) {
            val color = when (entry.level) {
                "WARN"  -> Color.parseColor("#FFAA00")
                "ERROR" -> Color.parseColor("#FF4444")
                "FATAL" -> Color.parseColor("#FF0066")
                else    -> Color.parseColor("#336655")
            }
            val stageColor = Color.parseColor("#224466")
            val timeColor  = Color.parseColor("#1A3050")

            val timePart = "[${entry.time}]"
            val stagePart = "[${entry.level}][${entry.stage}] "
            val msgPart = entry.message + "\n"

            val start0 = ssb.length
            ssb.append(timePart)
            ssb.setSpan(ForegroundColorSpan(timeColor), start0, ssb.length, 0)

            val start1 = ssb.length
            ssb.append(stagePart)
            ssb.setSpan(ForegroundColorSpan(color), start1, ssb.length, 0)

            val start2 = ssb.length
            ssb.append(msgPart)
            ssb.setSpan(ForegroundColorSpan(
                if (entry.level == "INFO") Color.parseColor("#3A5A7A") else color
            ), start2, ssb.length, 0)
        }
        tvDebugLog?.text = ssb
        scrollDebugToBottom()
    }
}
