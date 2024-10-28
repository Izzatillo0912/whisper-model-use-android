package com.techgeni.whisperSpeechToText

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.techgeni.hidestatusandnavigation.R
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var resultText: TextView
    private lateinit var mediaRecorder: MediaRecorder
    private val audioRecorder: AudioRecorder by lazy { AndroidAudioRecorder(this) }
    private var audioFile: File? = null
    private var isRecording = false
    var path : String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        resultText = findViewById(R.id.transcribedText)

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.DIRECTORY_RECORDINGS
        } else {
            Environment.DIRECTORY_MUSIC
        }
        val a = getExternalFilesDir(directory)
        audioFile = File(a, "user.wav")
        audioFile?.createNewFile()
        path = audioFile?.path.toString()

        try {
            audioRecorder.start(audioFile)
            isRecording = true
            recordButton.text = "Stop Recording"
            Toast.makeText(applicationContext, "Ovozingizni yozish boshlandi!", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            audioRecorder.stop()
            Log.e("AUDIO", "stopRecording: STOPED", )
            convertAudioToText(path)
            recordButton.text = "Start Recording"
            isRecording = false

        } else {
            Toast.makeText(applicationContext, "Ovoz yozib olinmadi Xatolik", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun convertAudioToText(audioFilePath: String) {
        // Modelni yuklash
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        val session = OrtEnvironment.getEnvironment().createSession(assets.open("decoder_model_int8.onnx").readBytes(), options)
        val encoderSession = OrtEnvironment.getEnvironment().createSession(assets.open("encoder_model_fp16.onnx").readBytes(), options)

        // Ovoz faylini o'qish
        Log.e("MAin", "convertAudioToText: $audioFilePath")
        val audioData = readAudioFile(audioFilePath)

//        // Ovozdan matn olish
        val outputText = processAudioWithModel(session, encoderSession, audioData)

        // Natijani ekranga chiqarish
        resultText.text = outputText
    }

    // Ovoz faylini o'qish
    private fun readAudioFile(filePath: String): FloatArray {
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(filePath)

        var audioTrackIndex = -1
        val trackCount = mediaExtractor.trackCount

        for (i in 0 until trackCount) {
            val format = mediaExtractor.getTrackFormat(i)
            val mimeType = format.getString(MediaFormat.KEY_MIME)

            if (mimeType?.startsWith("audio/") == true) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex == -1) {
            throw IllegalArgumentException("Audio track not found")
        }

        mediaExtractor.selectTrack(audioTrackIndex)

        // Audio o'qish jarayoni
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB
        val audioData = mutableListOf<Float>()

        while (true) {
            val sampleSize = mediaExtractor.readSampleData(buffer, 0)

            if (sampleSize < 0) {
                break // Ovoz tugadi
            }

            // Audio ma'lumotlarni float array ga aylantirish
            buffer.rewind()
            val audioBytes = ByteArray(sampleSize)
            buffer.get(audioBytes)

            // Ovozdan float array ga o'tkazish
            for (i in audioBytes.indices step 2) {
                val sample = (audioBytes[i].toInt() or (audioBytes[i + 1].toInt() shl 8)).toShort().toFloat()
                audioData.add(sample / Short.MAX_VALUE) // Normalizatsiya
            }

            mediaExtractor.advance()
        }

        mediaExtractor.release()

        return audioData.toFloatArray()
    }

    fun adjustAudioLength(audioData: FloatArray, requiredLength: Int): FloatArray {
        return when {
            audioData.size > requiredLength -> audioData.copyOf(requiredLength) // Cropping
            audioData.size < requiredLength -> audioData + FloatArray(requiredLength - audioData.size) // Padding
            else -> audioData
        }
    }

    fun reshapeAudioDataTo3D(audioData: FloatArray): Array<Array<FloatArray>> {
        val featureSize = 80  // Kernel kanallar bilan mos bo'lishi kerak
        val timeSteps = audioData.size / featureSize
        val reshapedData = Array(1) { Array(timeSteps) { FloatArray(featureSize) } }

        // Ovozli ma'lumotlarni reshapedData'ga ko'chirish
        for (i in 0 until timeSteps) {
            for (j in 0 until featureSize) {
                reshapedData[0][i][j] = audioData[i * featureSize + j]
            }
        }
        return reshapedData
    }

    // Ovozdan matn olish jarayoni
    private fun processAudioWithModel(session: OrtSession, encoderSession: OrtSession, audioData: FloatArray): String {

        val requiredLength = 3000 * 80 // Modelga mos o'lcham
        val adjustedAudioData = adjustAudioLength(audioData, requiredLength)
        val reshapedAudioData = reshapeAudioDataTo3D(adjustedAudioData)

        // Encoder modeliga ovoz ma'lumotini yuborish
        val encoderInputName = encoderSession.inputNames.iterator().next()
        val encoderInputTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), reshapedAudioData)
        val encoderResult = encoderSession.run(mapOf(encoderInputName to encoderInputTensor))

        // Encoder natijasini decoder modeliga yuborish
        val encoderOutput = encoderResult[0].value as FloatArray
        val decoderInputName = session.inputNames.iterator().next()
        val decoderInputTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), encoderOutput)
        val decoderResult = session.run(mapOf(decoderInputName to decoderInputTensor))

        // Natija olish
        val outputText = decoderResult[0].value as Array<String>

        return outputText.joinToString(" ")
    }
}
