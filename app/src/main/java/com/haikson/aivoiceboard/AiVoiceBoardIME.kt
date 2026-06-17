package com.haikson.aivoiceboard

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.style.ImageSpan
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

class AiVoiceBoardIME : InputMethodService() {

    // --- State ---
    private enum class State { IDLE, RECORDING, TRANSCRIBING }
    private var state = State.IDLE

    private lateinit var lastWavCache: File
    private var autoEnterOnStop = false

    // --- Coroutines ---
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var transcribeJob: Job? = null
    private var timerJob: Job? = null
    private var backspaceJob: Job? = null
    private var btnClearRef: android.widget.TextView? = null
    private var isOverClear = false
    private var recordStartMs = 0L


    // --- Components ---
    private lateinit var prefs: PrefsManager
    private lateinit var recorder: AudioRecorder
    private lateinit var whisper: WhisperClient
    private val updateChecker = UpdateChecker()
    private var updateCheckJob: Job? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private var openPopup: PopupWindow? = null

    // --- Views ---
    private var updateBadgeView: View? = null
    private var tvStatus: TextView? = null
    private var waveform: WaveformView? = null
    private var rowIdle: LinearLayout? = null
    private var rowRecording: LinearLayout? = null
    private var rowTranscribing: LinearLayout? = null

    // -------------------------------------------------------------------------

    // Never go fullscreen — prevents keyboard from covering input field in some apps
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        recorder = AudioRecorder()
        whisper = WhisperClient(prefs)
        lastWavCache = File(cacheDir, "last_recording.wav")
        registerDownloadReceiver()
    }

    override fun onCreateInputView(): View {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val view = LayoutInflater.from(ctx).inflate(R.layout.keyboard_view, null)

        tvStatus       = view.findViewById(R.id.tvStatus)
        waveform       = view.findViewById(R.id.waveform)
        rowIdle        = view.findViewById(R.id.rowIdle)
        rowRecording   = view.findViewById(R.id.rowRecording)
        rowTranscribing = view.findViewById(R.id.rowTranscribing)
        updateBadgeView = view.findViewById(R.id.updateBadge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateBadgeView?.isForceDarkAllowed = false
        }

        // Left: tap = switch IME, long press = overflow menu
        val btnSwitchIme = view.findViewById<ImageButton>(R.id.btnSwitchIme)
        btnSwitchIme.setOnClickListener { switchToPreviousIme() }
        btnSwitchIme.setOnLongClickListener { showOverflowMenu(it); true }

        // IDLE row
        btnClearRef = view.findViewById<android.widget.TextView>(R.id.btnClear)
        val btnMic = view.findViewById<ImageButton>(R.id.btnMic)
        btnMic.setOnClickListener { startRecording() }
        btnMic.setOnLongClickListener { showMicMenu(it); true }

        // ↵ — tap = newline; long-press = horizontal palette dropdown (punctuation / send / enter).
        val btnEnter = view.findViewById<ImageButton>(R.id.btnEnter)
        btnEnter.setOnClickListener { currentInputConnection?.commitText("\n", 1) }
        btnEnter.setOnLongClickListener { showEnterPalette(it); true }

        view.findViewById<ImageButton>(R.id.btnBackspace).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    deleteChar()
                    isOverClear = false
                    backspaceJob = scope.launch {
                        delay(400)
                        withContext(Dispatchers.Main) {
                            btnClearRef?.visibility = View.VISIBLE
                        }
                        while (true) {
                            if (!isOverClear) deleteChar()
                            delay(50)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> updateClearHover(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceJob?.cancel()
                    backspaceJob = null
                    val triggered = isOverClear
                    isOverClear = false
                    btnClearRef?.visibility = View.GONE
                    btnClearRef?.background = resources.getDrawable(R.drawable.bg_popup, null)
                    if (triggered) clearAll()
                }
            }
            true
        }

        // RECORDING row
        view.findViewById<ImageButton>(R.id.btnCancel).setOnClickListener { cancelRecording() }
        view.findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            autoEnterOnStop = false; stopAndTranscribe()
        }
        view.findViewById<ImageButton>(R.id.btnStopEnter).setOnClickListener {
            autoEnterOnStop = true; stopAndTranscribe()
        }

        // TRANSCRIBING row
        view.findViewById<ImageButton>(R.id.btnCancelTranscribe).setOnClickListener {
            cancelTranscription()
        }

        updateUi()
        updateBadge()
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        maybeAutoCheckUpdate()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Any open dropdown must not linger when the keyboard goes away.
        openPopup?.let { runCatching { it.dismiss() } }
        // Recording is tied to the live mic session — closing the keyboard stops it.
        // Transcription runs independently of the window, so let it finish: an accidental
        // close must not throw away recognition already in progress. The result is still
        // stored in lastText (and committed if the input field is still attached).
        if (state == State.RECORDING) cancelRecording()
    }

    override fun onDestroy() {
        backspaceJob?.cancel()
        scope.cancel()
        recorder.cancel()
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overflow menu (long press on ⌨)
    // -------------------------------------------------------------------------

    // Tracks the single open dropdown so it can be dismissed when the keyboard
    // hides or another dropdown opens.
    private fun trackPopup(popup: PopupWindow) {
        openPopup?.let { runCatching { it.dismiss() } }
        openPopup = popup
        popup.setOnDismissListener { if (openPopup === popup) openPopup = null }
    }

    private fun showOverflowMenu(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val v = LayoutInflater.from(ctx).inflate(R.layout.popup_overflow, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.isForceDarkAllowed = false   // keep menu colours as designed on dark-theme devices
        }
        val menuWidth = (248 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(v, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false   // allow drawing outside the small IME window
        trackPopup(popup)

        v.findViewById<View>(R.id.itemSettings).setOnClickListener { popup.dismiss(); openSettings() }

        val itemUpdate = v.findViewById<View>(R.id.itemUpdate)
        val icon = v.findViewById<ImageView>(R.id.iconUpdate)
        val spinner = v.findViewById<View>(R.id.progressUpdate)
        val text = v.findViewById<TextView>(R.id.textUpdate)
        val actionDivider = v.findViewById<View>(R.id.infoDivider)
        val infoBtn = v.findViewById<View>(R.id.btnInfo)
        val githubBtn = v.findViewById<View>(R.id.btnGithub)

        if (hasUpdate()) {
            icon.setImageResource(R.drawable.ic_update)
            text.text = "${getString(R.string.menu_update_now)} (${prefs.latestVersion})"
            actionDivider.visibility = View.VISIBLE
            infoBtn.visibility = View.VISIBLE
            githubBtn.visibility = View.GONE
            itemUpdate.setOnClickListener { popup.dismiss(); startUpdateDownload() }
            infoBtn.setOnClickListener { openInBrowser(popup, prefs.latestReleaseUrl) }
        } else {
            icon.setImageResource(R.drawable.ic_retry)
            text.text = getString(R.string.menu_check_update)
            actionDivider.visibility = View.VISIBLE
            infoBtn.visibility = View.GONE
            githubBtn.visibility = View.VISIBLE
            githubBtn.setOnClickListener { openInBrowser(popup, UpdateChecker.REPO_URL) }
            itemUpdate.setOnClickListener {
                runInteractiveCheck(popup, itemUpdate, icon, spinner, text, actionDivider, infoBtn, githubBtn)
            }
        }

        // Open above the anchor (the keyboard sits at the screen bottom).
        v.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        popup.showAsDropDown(anchor, 0, -(v.measuredHeight + anchor.height))
    }

    // Mic long-press menu: Paste last text / Retry last recording.
    private fun showMicMenu(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val v = LayoutInflater.from(ctx).inflate(R.layout.popup_mic, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.isForceDarkAllowed = false
        val menuWidth = (240 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(v, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
        trackPopup(popup)
        v.findViewById<View>(R.id.itemMicPaste).setOnClickListener { popup.dismiss(); pasteLast() }
        v.findViewById<View>(R.id.itemMicRetry).setOnClickListener { popup.dismiss(); retryTranscribe() }
        v.findViewById<View>(R.id.btnHistory).setOnClickListener { popup.dismiss(); showHistory(anchor) }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        popup.showAsDropDown(anchor, 0, -(v.measuredHeight + anchor.height))
    }

    // History dropdown: list of recent recognized texts, tap to insert.
    private fun showHistory(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val v = LayoutInflater.from(ctx).inflate(R.layout.popup_history, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.isForceDarkAllowed = false
        val menuWidth = (300 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(v, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
        trackPopup(popup)

        val container = v.findViewById<LinearLayout>(R.id.historyContainer)
        val history = prefs.getHistory()
        if (history.isEmpty()) {
            val empty = TextView(ctx)
            empty.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(44))
            empty.text = getString(R.string.history_empty)
            empty.setTextColor(0xFF6B7280.toInt())
            empty.textSize = 14f
            empty.gravity = Gravity.CENTER_VERTICAL
            empty.setPadding(dpi(16), 0, dpi(16), 0)
            container.addView(empty)
            v.findViewById<View>(R.id.historyClearDivider).visibility = View.GONE
            v.findViewById<View>(R.id.itemClearHistory).visibility = View.GONE
        } else {
            for (item in history) container.addView(makeHistoryRow(ctx, item, popup))
            v.findViewById<View>(R.id.itemClearHistory).setOnClickListener {
                popup.dismiss(); prefs.clearHistory()
            }
        }

        v.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        popup.showAsDropDown(anchor, 0, -(v.measuredHeight + anchor.height))
    }

    private fun makeHistoryRow(ctx: ContextThemeWrapper, text: String, popup: PopupWindow): View {
        val tv = TextView(ctx)
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(44))
        val oneLine = text.replace("\n", " ")
        tv.text = if (oneLine.length > 48) oneLine.take(48) + "…" else oneLine
        tv.setTextColor(0xFFE5E7EB.toInt())
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dpi(16), 0, dpi(16), 0)
        tv.maxLines = 1
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setBackgroundResource(R.drawable.bg_menu_item)
        tv.isClickable = true
        tv.setOnClickListener { popup.dismiss(); currentInputConnection?.commitText(text, 1) }
        return tv
    }

    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------------------
    // Enter-key palette (hold ↵)
    // -------------------------------------------------------------------------

    private fun showEnterPalette(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val v = LayoutInflater.from(ctx).inflate(R.layout.popup_enter_palette, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.isForceDarkAllowed = false
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = v.measuredWidth
        val h = v.measuredHeight
        val popup = PopupWindow(v, w, h, false)
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
        trackPopup(popup)
        // Punctuation keys keep the palette open (insert several in a row);
        // action keys (Send / Enter) perform and close it.
        v.findViewById<View>(R.id.palSpace).setOnClickListener { insert(" ") }
        v.findViewById<View>(R.id.palComma).setOnClickListener { insert(",") }
        v.findViewById<View>(R.id.palDot).setOnClickListener { insert(".") }
        v.findViewById<View>(R.id.palQuestion).setOnClickListener { insert("?") }
        v.findViewById<View>(R.id.palSend).setOnClickListener { popup.dismiss(); sendMessage() }
        v.findViewById<View>(R.id.palEnter).setOnClickListener { popup.dismiss(); sendEnterKey() }
        val xoff = (anchor.width - w) / 2
        popup.showAsDropDown(anchor, xoff, -(h + anchor.height))
    }

    private fun insert(s: String) { currentInputConnection?.commitText(s, 1) }

    private fun sendEnterKey() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private fun startRecording() {
        if (prefs.apiKey.isEmpty()) { setStatus(getString(R.string.status_error_api_key)); return }
        val tmpFile = File(cacheDir, "recording.wav")
        if (!recorder.start(tmpFile)) { setStatus(getString(R.string.status_error_record)); return }
        state = State.RECORDING
        recordStartMs = System.currentTimeMillis()
        updateUi()
        startTimer()
        waveform?.sampleProvider = { recorder.copyLatestSamples(it) }
        waveform?.startAnimating()
    }

    private fun cancelRecording() {
        stopTimer()
        waveform?.stopAnimating()
        recorder.cancel()
        state = State.IDLE
        updateUi()
        setStatus(getString(R.string.status_cancelled))
    }

    private fun stopAndTranscribe() {
        stopTimer()
        waveform?.stopAnimating()
        val wavFile = recorder.stop() ?: run { state = State.IDLE; updateUi(); return }
        wavFile.copyTo(lastWavCache, overwrite = true)
        transcribeFile(wavFile, deleteAfter = true, enterAfter = autoEnterOnStop)
    }

    private fun cancelTranscription() {
        transcribeJob?.cancel()
        state = State.IDLE
        updateUi()
        setStatus(getString(R.string.status_cancelled))
    }

    // -------------------------------------------------------------------------
    // Transcription
    // -------------------------------------------------------------------------

    private fun retryTranscribe() {
        if (state != State.IDLE) return
        if (!lastWavCache.exists() || lastWavCache.length() < 1000) {
            setStatus(getString(R.string.status_no_cache)); return
        }
        transcribeFile(lastWavCache, deleteAfter = false, enterAfter = false)
    }

    private fun pasteLast() {
        val last = prefs.getHistory().firstOrNull()
        if (last == null) { setStatus(getString(R.string.status_no_last_text)); return }
        currentInputConnection?.commitText(last, 1)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun transcribeFile(file: File, deleteAfter: Boolean, enterAfter: Boolean) {
        state = State.TRANSCRIBING
        updateUi()

        transcribeJob = scope.launch {
            val result = runCatching { whisper.transcribe(file) }
            if (deleteAfter) file.delete()
            if (!isActive) return@launch

            state = State.IDLE
            updateUi()

            result.fold(
                onSuccess = { raw ->
                    if (raw.isBlank()) { setStatus(getString(R.string.status_nothing)); return@fold }
                    val text = TextFormatter.format(raw, prefs.fmtEnable)
                    prefs.addHistory(text)
                    // Only insert if the keyboard is actually visible. If it was closed
                    // while transcribing, don't surprise-insert into whatever has focus now —
                    // the text stays in lastText and is reachable via "Paste last".
                    if (isInputViewShown) {
                        currentInputConnection?.commitText(text, 1)
                        if (enterAfter) {
                            delay(250)
                            currentInputConnection?.performEditorAction(
                                android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                            )
                        }
                    }
                    setStatus("✓ ${text.take(55)}${if (text.length > 55) "…" else ""}")
                },
                onFailure = { e ->
                    setStatus("Error: ${e.message?.take(60)}")
                    Toast.makeText(
                        this@AiVoiceBoardIME,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // Updates
    // -------------------------------------------------------------------------

    private fun currentVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }
            .getOrDefault("")

    private fun hasUpdate(): Boolean {
        val v = prefs.latestVersion
        return v.isNotEmpty() && UpdateChecker.isNewer(v, currentVersionName())
    }

    private fun updateBadge() {
        updateBadgeView?.visibility = if (hasUpdate()) View.VISIBLE else View.GONE
    }

    // Triggered on keyboard open, throttled to once per hour. Errors are silent.
    private fun maybeAutoCheckUpdate() {
        val hour = 60 * 60 * 1000L
        if (System.currentTimeMillis() - prefs.lastUpdateCheckMs < hour) return
        checkForUpdate(manual = false)
    }

    // manual = true → report result/error via Toast; false → silent (auto check).
    private fun checkForUpdate(manual: Boolean) {
        if (updateCheckJob?.isActive == true) return
        prefs.lastUpdateCheckMs = System.currentTimeMillis()   // record attempt, even on failure
        updateCheckJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { updateChecker.fetchLatest() } }
            result.fold(
                onSuccess = { info ->
                    if (UpdateChecker.isNewer(info.version, currentVersionName())) {
                        prefs.latestVersion = info.version
                        prefs.latestApkUrl = info.apkUrl
                        prefs.latestReleaseUrl = info.releaseUrl
                        if (manual) toast(getString(R.string.update_available, info.version))
                    } else {
                        prefs.latestVersion = ""
                        prefs.latestApkUrl = ""
                        if (manual) toast(getString(R.string.update_up_to_date))
                    }
                    updateBadge()
                },
                onFailure = { e ->
                    if (manual) toast(getString(R.string.update_check_error, e.message ?: ""))
                    // automatic check: stay silent
                }
            )
        }
    }

    private fun startUpdateDownload() {
        val url = prefs.latestApkUrl
        if (url.isEmpty()) { checkForUpdate(manual = true); return }
        try {
            val dest = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
            if (dest.exists()) dest.delete()
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("${getString(R.string.app_name)} ${prefs.latestVersion}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk")
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            prefs.pendingDownloadId = dm.enqueue(req)
            toast(getString(R.string.update_downloading))
        } catch (e: Exception) {
            toast(getString(R.string.update_check_error, e.message ?: ""))
        }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id != -1L && id == prefs.pendingDownloadId) onDownloadComplete(id)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    private fun onDownloadComplete(id: Long) {
        prefs.pendingDownloadId = -1L
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (!file.exists()) return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(install) }
            .onFailure { toast(getString(R.string.update_check_error, it.message ?: "")) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { toast(getString(R.string.update_check_error, it.message ?: "")) }
    }

    // Close the menu, hide the keyboard, then open a link (changelog / repo) in a browser.
    private fun openInBrowser(popup: PopupWindow, url: String) {
        runCatching { popup.dismiss() }
        requestHideSelf(0)
        openUrl(url)
    }

    // Interactive check from the overflow menu: spinner in place, keep the popup open.
    // On "newer" → morph the row into "Update app" + Info; otherwise dismiss + Toast.
    private fun runInteractiveCheck(
        popup: PopupWindow, item: View, icon: ImageView, spinner: View,
        text: TextView, actionDivider: View, infoBtn: View, githubBtn: View
    ) {
        if (updateCheckJob?.isActive == true) return
        prefs.lastUpdateCheckMs = System.currentTimeMillis()
        icon.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        item.isClickable = false
        text.text = getString(R.string.menu_checking_update)
        updateCheckJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { updateChecker.fetchLatest() } }
            result.fold(
                onSuccess = { info ->
                    val newer = UpdateChecker.isNewer(info.version, currentVersionName())
                    if (newer) {
                        prefs.latestVersion = info.version
                        prefs.latestApkUrl = info.apkUrl
                        prefs.latestReleaseUrl = info.releaseUrl
                    } else {
                        prefs.latestVersion = ""
                        prefs.latestApkUrl = ""
                    }
                    updateBadge()
                    if (newer && popup.isShowing) {
                        spinner.visibility = View.GONE
                        icon.visibility = View.VISIBLE
                        icon.setImageResource(R.drawable.ic_update)
                        text.text = "${getString(R.string.menu_update_now)} (${info.version})"
                        item.isClickable = true
                        item.setOnClickListener { popup.dismiss(); startUpdateDownload() }
                        actionDivider.visibility = View.VISIBLE
                        githubBtn.visibility = View.GONE
                        infoBtn.visibility = View.VISIBLE
                        infoBtn.setOnClickListener { openInBrowser(popup, info.releaseUrl) }
                    } else {
                        runCatching { popup.dismiss() }
                        toast(getString(R.string.update_up_to_date))
                    }
                },
                onFailure = { e ->
                    runCatching { popup.dismiss() }
                    toast(getString(R.string.update_check_error, e.message ?: ""))
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // IME switcher
    // -------------------------------------------------------------------------

    private fun switchToPreviousIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
    }

    // -------------------------------------------------------------------------
    // Timer
    // -------------------------------------------------------------------------

    private fun startTimer() {
        timerJob = scope.launch {
            while (state == State.RECORDING) {
                val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
                val mins = elapsed / 60
                val secs = elapsed % 60
                val t = if (mins > 0) "$mins:${secs.toString().padStart(2, '0')}" else "${secs}s"
                setStatus("● $t")
                delay(1000)
            }
        }
    }

    private fun stopTimer() { timerJob?.cancel(); timerJob = null }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun deleteChar() { currentInputConnection?.deleteSurroundingText(1, 0) }

    // Try send in order most likely to work across apps:
    // 1. The action the field explicitly declared (Send/Go/Done)
    // 2. IME_ACTION_SEND as universal fallback
    // 3. IME_ACTION_DONE (Telegram with "Send by Enter" off responds to this)
    private fun sendMessage() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val declared = info?.imeOptions
            ?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
            ?.takeIf {
                it != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
                it != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
            }
        if (declared != null) {
            ic.performEditorAction(declared)
        } else {
            // No explicit action — try Send then Done
            ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        }
    }

    private fun clearAll() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
    }

    private fun updateClearHover(rawX: Float, rawY: Float) {
        val btn = btnClearRef?.takeIf { it.visibility == View.VISIBLE } ?: return
        val loc = IntArray(2)
        btn.getLocationOnScreen(loc)
        val over = rawX >= loc[0] && rawX <= loc[0] + btn.width &&
                   rawY >= loc[1] && rawY <= loc[1] + btn.height
        if (over != isOverClear) {
            isOverClear = over
            btn.background = if (over)
                resources.getDrawable(R.drawable.bg_popup_active, null)
            else
                resources.getDrawable(R.drawable.bg_popup, null)
        }
    }

    private fun updateUi() {
        rowIdle?.visibility         = if (state == State.IDLE)         View.VISIBLE else View.GONE
        rowRecording?.visibility    = if (state == State.RECORDING)    View.VISIBLE else View.GONE
        rowTranscribing?.visibility = if (state == State.TRANSCRIBING) View.VISIBLE else View.GONE

        // Equalizer takes the centre zone only while recording; the timer text moves
        // to the left so the two don't overlap.
        waveform?.visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
        tvStatus?.gravity = if (state == State.RECORDING)
            Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER

        tvStatus?.setTextColor(when (state) {
            State.IDLE         -> 0xFF9CA3AF.toInt()
            State.RECORDING    -> 0xFFEF4444.toInt()
            State.TRANSCRIBING -> 0xFFF59E0B.toInt()
        })

        when (state) {
            State.IDLE         -> tvStatus?.text = buildIdleStatus()
            State.TRANSCRIBING -> setStatus(getString(R.string.status_transcribing))
            else               -> Unit
        }
    }

    private fun setStatus(text: String) { tvStatus?.text = text }

    // Builds "Tap [mic icon] to record" entirely in code — no XML position dependency
    private fun buildIdleStatus(): SpannableString {
        val prefix = "Tap "
        val placeholder = " "  // non-breaking space — won't be collapsed
        val suffix = " to record"
        val full = prefix + placeholder + suffix
        val spannable = SpannableString(full)
        val sizePx = (tvStatus?.textSize ?: 36f).toInt()
        val drawable: Drawable = resources.getDrawable(R.drawable.ic_mic, null).mutate().apply {
            setTint(0xFF6B7280.toInt())
            setBounds(0, 0, sizePx, sizePx)
        }
        spannable.setSpan(
            CenteredImageSpan(drawable),
            prefix.length,
            prefix.length + placeholder.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }
}

// ImageSpan that centers the drawable vertically within the text line
private class CenteredImageSpan(drawable: Drawable) : ImageSpan(drawable) {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val d = drawable
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            val iconHeight = d.bounds.height()
            val lineCenter = (pfm.ascent + pfm.descent) / 2
            fm.ascent  = lineCenter - iconHeight / 2
            fm.descent = lineCenter + iconHeight / 2
            fm.top    = fm.ascent
            fm.bottom = fm.descent
        }
        return d.bounds.right
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val d = drawable
        val fm = paint.fontMetricsInt
        val transY = y + (fm.ascent + fm.descent) / 2 - d.bounds.height() / 2
        canvas.save()
        canvas.translate(x, transY.toFloat())
        d.draw(canvas)
        canvas.restore()
    }
}
