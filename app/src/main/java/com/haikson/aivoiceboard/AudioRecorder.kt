package com.haikson.aivoiceboard

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

// Analogous to the MCI waveaudio recording in the AHK script.
// Records raw PCM from the microphone and saves it as a proper WAV file.
class AudioRecorder {

    companion object {
        private const val SAMPLE_RATE = 16000   // Whisper works best at 16 kHz
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNEL_COUNT = 1
        const val FFT_SIZE = 1024               // ring of recent samples exposed for the spectrum view
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var totalPcmBytes = 0L

    @Volatile var isRecording = false
        private set

    // Current mic loudness as a 0..1 peak of the most recent PCM block.
    // Read by the waveform view while recording; does not affect the WAV output.
    @Volatile var amplitude: Float = 0f
        private set

    // Ring buffer of the most recent normalised samples (-1..1), exposed to the
    // spectrum view for live FFT. Written by the recording thread, read by the UI.
    private val fftRing = FloatArray(FFT_SIZE)
    private var fftWritePos = 0
    private val fftLock = Any()

    // Returns false if AudioRecord could not be initialised (e.g. permission denied).
    fun start(file: File): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) return false

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNELS, ENCODING,
            bufferSize * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        outputFile = file
        totalPcmBytes = 0L
        amplitude = 0f
        synchronized(fftLock) { fftRing.fill(0f); fftWritePos = 0 }
        val fos = FileOutputStream(file)
        outputStream = fos

        // Reserve space for the WAV header — will be written properly on stop()
        fos.write(ByteArray(44))

        audioRecord = record
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    totalPcmBytes += read
                    processBlock(buffer, read)
                }
            }
        }.also { it.start() }

        return true
    }

    // Stops recording and finalises the WAV file. Returns the file, or null on error.
    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        amplitude = 0f
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val fos = outputStream ?: return null
        outputStream = null

        // Go back and write the correct WAV header now that we know the data size
        fos.flush()
        fos.close()

        val file = outputFile ?: return null
        writeWavHeader(file, totalPcmBytes)
        return file
    }

    fun cancel() {
        isRecording = false
        amplitude = 0f
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        outputStream?.close()
        outputStream = null
        outputFile?.delete()
        outputFile = null
    }

    // Updates the peak amplitude and appends the block's samples to the FFT ring.
    private fun processBlock(buffer: ByteArray, lengthBytes: Int) {
        var peak = 0
        synchronized(fftLock) {
            var i = 0
            var pos = fftWritePos
            while (i + 1 < lengthBytes) {
                val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
                fftRing[pos] = s / 32768f
                pos = (pos + 1) % FFT_SIZE
                i += 2
            }
            fftWritePos = pos
        }
        amplitude = (peak / 32768f).coerceIn(0f, 1f)
    }

    // Copies the most recent FFT_SIZE samples in chronological order into [out].
    // [out] must be at least FFT_SIZE long; exactly FFT_SIZE values are written.
    fun copyLatestSamples(out: FloatArray) {
        synchronized(fftLock) {
            val start = fftWritePos
            for (k in 0 until FFT_SIZE) {
                out[k] = fftRing[(start + k) % FFT_SIZE]
            }
        }
    }

    // Re-writes the 44-byte WAV header at the start of an already-written file.
    private fun writeWavHeader(file: File, pcmDataBytes: Long) {
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)

        val byteRate = (SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toLong()
        val blockAlign = (CHANNEL_COUNT * BITS_PER_SAMPLE / 8)

        raf.write("RIFF".toByteArray())
        raf.writeIntLE((pcmDataBytes + 36).toInt())
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.writeIntLE(16)                     // PCM subchunk size
        raf.writeShortLE(1)                    // AudioFormat = PCM
        raf.writeShortLE(CHANNEL_COUNT)
        raf.writeIntLE(SAMPLE_RATE)
        raf.writeIntLE(byteRate.toInt())
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(BITS_PER_SAMPLE)
        raf.write("data".toByteArray())
        raf.writeIntLE(pcmDataBytes.toInt())

        raf.close()
    }

    private fun java.io.RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun java.io.RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
