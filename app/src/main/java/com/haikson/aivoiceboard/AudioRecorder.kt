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
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var totalPcmBytes = 0L

    @Volatile var isRecording = false
        private set

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
                }
            }
        }.also { it.start() }

        return true
    }

    // Stops recording and finalises the WAV file. Returns the file, or null on error.
    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
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
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        outputStream?.close()
        outputStream = null
        outputFile?.delete()
        outputFile = null
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
