package com.techgeni.whisperSpeechToText

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

class AndroidAudioRecorder(private val context: Context) : AudioRecorder {

    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var audioFile: File? = null

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val sampleRate = 16000 // 16 kHz
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
    }

    override fun start(outputFile: File?) {
        audioFile = outputFile
        recorder = createRecorder()

        recorder?.apply {
            startRecording()
            isRecording = true

            Thread {
                writeAudioData()
            }.start()
        }
    }

    private fun writeAudioData() {
        val audioData = ByteArray(4096)
        val outputStream = FileOutputStream(audioFile)

        // Headerni yozish (dummy header)
        outputStream.write("RIFF".toByteArray())
        outputStream.write(ByteArray(4)) // Placeholder for file size
        outputStream.write("WAVE".toByteArray())
        outputStream.write("fmt ".toByteArray())
        outputStream.write(ByteArray(16)) // Placeholder for fmt chunk size
        outputStream.write("data".toByteArray())
        outputStream.write(ByteArray(4)) // Placeholder for data chunk size

        var totalAudioLen = 0
        while (isRecording) {
            val read = recorder?.read(audioData, 0, audioData.size) ?: 0
            if (read > 0) {
                outputStream.write(audioData, 0, read)
                totalAudioLen += read
            }
        }

        // Headerni yangilash
        outputStream.flush()
        updateWavHeader(outputStream, totalAudioLen)

        outputStream.close()
    }

    private fun updateWavHeader(outputStream: FileOutputStream, totalAudioLen: Int) {
        // WAV headerni yangilash uchun yangi faylni ochamiz
        val fileSize = totalAudioLen + 36 // File size - 8 (RIFF + size) + 36 (fmt + data)

        // Header uchun o'lchovlarni hisoblash
        outputStream.channel.position(0) // Fayl boshiga qaytish
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(fileSize))
        outputStream.write("WAVE".toByteArray())
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16)) // fmt chunk size
        outputStream.write(shortToByteArray(1)) // audio format (PCM)
        outputStream.write(shortToByteArray(1)) // mono channel
        outputStream.write(intToByteArray(16000)) // sample rate
        outputStream.write(intToByteArray(32000)) // byte rate
        outputStream.write(shortToByteArray(2)) // block align
        outputStream.write(shortToByteArray(16)) // bits per sample
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(totalAudioLen)) // data chunk size
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteArray(4).apply {
            this[0] = (value and 0xFF).toByte()
            this[1] = (value shr 8 and 0xFF).toByte()
            this[2] = (value shr 16 and 0xFF).toByte()
            this[3] = (value shr 24 and 0xFF).toByte()
        }
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteArray(2).apply {
            this[0] = (value.toInt() and 0xFF).toByte()
            this[1] = (value.toInt() shr 8 and 0xFF).toByte()
        }
    }

    override fun stop() {
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}