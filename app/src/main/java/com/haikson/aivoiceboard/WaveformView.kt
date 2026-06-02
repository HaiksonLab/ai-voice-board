package com.haikson.aivoiceboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View

// Live spectrum analyzer: runs an FFT over the most recent mic samples each frame
// and draws one bar per logarithmic frequency band, so the bars move with the real
// voice spectrum (not a synced cosmetic pulse). An auto-gain normaliser keeps the
// bars using the full height at any voice level. Pull-based: [sampleProvider] fills a
// buffer with the latest samples; the view animates itself.
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val FFT_SIZE = 1024
        private const val SAMPLE_RATE = 16000      // must match AudioRecorder
        private const val BAR_COUNT = 16
        private const val F_MIN = 80.0             // lowest band edge (Hz)
        private const val F_MAX = 6000.0           // highest band edge (Hz)
        private const val ATTACK = 0.55f           // bar rises fast to a peak
        private const val DECAY = 0.16f            // bar falls slower, like a real meter
        private const val MIN_LEVEL = 0.03f        // faint resting dots
        private const val GAMMA = 0.70             // <1 lifts mid-levels for a livelier look

        // Auto-gain: the normaliser chases the loudest band quickly and releases slowly,
        // so bars fill the height for quiet and loud voices alike. Lower NORM_FLOOR =
        // more sensitive (also picks up more silence noise).
        private const val NORM_FLOOR = 1.0f
        private const val NORM_ATTACK = 0.30f
        private const val NORM_RELEASE = 0.05f
    }

    // Source of the latest samples, set by the IME while recording.
    var sampleProvider: ((FloatArray) -> Unit)? = null

    private val samples = FloatArray(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE)
    private val bandLo = IntArray(BAR_COUNT)
    private val bandHi = IntArray(BAR_COUNT)
    private val bandRaw = FloatArray(BAR_COUNT)
    private val level = FloatArray(BAR_COUNT) { MIN_LEVEL }
    private var norm = NORM_FLOOR
    private var running = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Dim grey. Force-dark is disabled for this view (see init), so this renders exactly
        // as set on the always-dark keyboard (no inversion).
        color = 0xFF6B7280.toInt()
    }
    private val rect = RectF()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        // The keyboard is always dark by design. Opt this view out of the system's
        // "force dark" / vendor (MIUI) dark inversion so the bars keep the exact colour
        // we set, instead of being auto-inverted (which made lighter colours look darker).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isForceDarkAllowed = false
        }
        for (n in 0 until FFT_SIZE)
            hann[n] = (0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (FFT_SIZE - 1)))).toFloat()

        val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE
        val nyqBin = FFT_SIZE / 2
        for (i in 0 until BAR_COUNT) {
            val f0 = F_MIN * Math.pow(F_MAX / F_MIN, i.toDouble() / BAR_COUNT)
            val f1 = F_MIN * Math.pow(F_MAX / F_MIN, (i + 1.0) / BAR_COUNT)
            val lo = (f0 / binHz).toInt().coerceIn(1, nyqBin - 1)
            val hi = (f1 / binHz).toInt().coerceIn(lo + 1, nyqBin)
            bandLo[i] = lo; bandHi[i] = hi
        }
    }

    fun startAnimating() {
        if (running) return
        running = true
        postOnAnimation(tick)
    }

    fun stopAnimating() {
        running = false
        removeCallbacks(tick)
        for (i in level.indices) level[i] = MIN_LEVEL
        norm = NORM_FLOOR
        invalidate()
    }

    private fun step() {
        val provider = sampleProvider ?: return
        provider(samples)

        for (n in 0 until FFT_SIZE) {
            re[n] = samples[n] * hann[n]
            im[n] = 0f
        }
        fft(re, im)

        var frameMax = 0f
        for (i in 0 until BAR_COUNT) {
            val lo = bandLo[i]; val hi = bandHi[i]
            var sum = 0f
            for (b in lo until hi) {
                sum += Math.sqrt((re[b] * re[b] + im[b] * im[b]).toDouble()).toFloat()
            }
            val raw = sum / (hi - lo)
            bandRaw[i] = raw
            if (raw > frameMax) frameMax = raw
        }

        // Auto-gain: rise fast toward the loudest band, release slowly, never below floor.
        val nTarget = if (frameMax > NORM_FLOOR) frameMax else NORM_FLOOR
        norm += (nTarget - norm) * (if (nTarget > norm) NORM_ATTACK else NORM_RELEASE)
        if (norm < NORM_FLOOR) norm = NORM_FLOOR

        for (i in 0 until BAR_COUNT) {
            val t = (bandRaw[i] / norm).coerceIn(0f, 1f)
            val target = Math.pow(t.toDouble(), GAMMA).toFloat().coerceAtLeast(MIN_LEVEL)
            val k = if (target > level[i]) ATTACK else DECAY
            level[i] += (target - level[i]) * k
        }
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val avail = right - left
        val h = height.toFloat()
        if (avail <= 0f || h <= 0f) return

        val barW = dp(2.5f)
        val gap = dp(3f)
        val groupW = BAR_COUNT * barW + (BAR_COUNT - 1) * gap
        var x = left + (avail - groupW) / 2f       // centre the group in the free zone
        val cy = h / 2f
        val maxBarH = h * 0.9f
        val radius = barW / 2f

        for (i in 0 until BAR_COUNT) {
            val bh = (maxBarH * level[i]).coerceAtLeast(barW)   // at least a round dot
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            x += barW + gap
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // In-place iterative radix-2 Cooley-Tukey FFT; size must be a power of two.
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = Math.cos(ang).toFloat()
            val wIm = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val bRe = re[i + k + half]; val bIm = im[i + k + half]
                    val tRe = curRe * bRe - curIm * bIm
                    val tIm = curRe * bIm + curIm * bRe
                    val aRe = re[i + k]; val aIm = im[i + k]
                    re[i + k] = aRe + tRe
                    im[i + k] = aIm + tIm
                    re[i + k + half] = aRe - tRe
                    im[i + k + half] = aIm - tIm
                    val nRe = curRe * wRe - curIm * wIm
                    val nIm = curRe * wIm + curIm * wRe
                    curRe = nRe; curIm = nIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
