package com.haikson.aivoiceboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.style.ImageSpan
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import kotlinx.coroutines.*
import java.io.File

class AiVoiceBoardIME : InputMethodService() {

    // --- State ---
    private enum class State { IDLE, RECORDING, TRANSCRIBING }
    private var state = State.IDLE

    private var lastText = ""
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

    // --- Views ---
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
    }

    override fun onCreateInputView(): View {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val view = LayoutInflater.from(ctx).inflate(R.layout.keyboard_view, null)

        tvStatus       = view.findViewById(R.id.tvStatus)
        waveform       = view.findViewById(R.id.waveform)
        rowIdle        = view.findViewById(R.id.rowIdle)
        rowRecording   = view.findViewById(R.id.rowRecording)
        rowTranscribing = view.findViewById(R.id.rowTranscribing)

        // Left: tap = switch IME, long press = overflow menu
        val btnSwitchIme = view.findViewById<ImageButton>(R.id.btnSwitchIme)
        btnSwitchIme.setOnClickListener { switchToPreviousIme() }
        btnSwitchIme.setOnLongClickListener { showOverflowMenu(it); true }

        // IDLE row
        btnClearRef = view.findViewById<android.widget.TextView>(R.id.btnClear)
        view.findViewById<ImageButton>(R.id.btnMic).setOnClickListener { startRecording() }

        view.findViewById<ImageButton>(R.id.btnEnter).setOnClickListener {
            currentInputConnection?.commitText("\n", 1)
        }

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
        return view
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
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
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overflow menu (long press on ⌨)
    // -------------------------------------------------------------------------

    private fun showOverflowMenu(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_AiVoiceBoard)
        val popup = android.widget.PopupMenu(ctx, anchor)

        popup.menu.add(0, 1, 0, getString(R.string.menu_retry))
            .setIcon(R.drawable.ic_retry)
        popup.menu.add(0, 2, 0, getString(R.string.menu_paste_last))
            .setIcon(R.drawable.ic_paste)
        popup.menu.add(0, 3, 0, getString(R.string.menu_settings))
            .setIcon(R.drawable.ic_settings)

        // Force icons visible
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                popup.setForceShowIcon(true)
            } else {
                val f = popup.javaClass.getDeclaredField("mPopup")
                f.isAccessible = true
                val helper = f.get(popup)
                helper?.javaClass
                    ?.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                    ?.also { it.isAccessible = true }
                    ?.invoke(helper, true)
            }
        } catch (_: Exception) {}

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> retryTranscribe()
                2 -> pasteLast()
                3 -> openSettings()
            }
            true
        }
        popup.show()
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
        if (lastText.isEmpty()) { setStatus(getString(R.string.status_no_last_text)); return }
        currentInputConnection?.commitText(lastText, 1)
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
                    lastText = text
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
