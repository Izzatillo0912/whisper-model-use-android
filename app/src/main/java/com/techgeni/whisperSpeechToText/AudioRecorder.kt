package com.techgeni.whisperSpeechToText

import java.io.File

interface AudioRecorder {

    fun start(outputFile: File?)
    fun stop()
}