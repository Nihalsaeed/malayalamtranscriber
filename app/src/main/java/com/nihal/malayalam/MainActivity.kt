package com.nihal.malayalam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText

// LiteRT IMPORTS
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ExperimentalApi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val MODEL_FILENAME = "gemma-3n-it.litertlm"

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var selectedAudioBytes: ByteArray? = null

    // Recording variables
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var tempRecordFile: File? = null

    private lateinit var tvResult: TextView
    private lateinit var tvSelectedFile: TextView
    private lateinit var btnPickAudio: Button
    private lateinit var btnRecordAudio: Button
    private lateinit var btnRun: Button
    private lateinit var etPrompt: TextInputEditText

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                processAudioUri(uri)
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                toggleRecording()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvResult)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        btnPickAudio = findViewById(R.id.btnPickAudio)
        btnRecordAudio = findViewById(R.id.btnRecordAudio)
        btnRun = findViewById(R.id.btnRun)
        etPrompt = findViewById(R.id.etPrompt)

        findViewById<Button>(R.id.btnLoadModel).setOnClickListener {
            initializeModel()
        }

        btnPickAudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg"))
            }
            pickAudioLauncher.launch(intent)
        }

        btnRecordAudio.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                toggleRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnRun.setOnClickListener {
            runInference()
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun initializeModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Loading Engine..."
                }

                val appDir = getExternalFilesDir(null)
                val modelFile = File(appDir, MODEL_FILENAME)

                if (!modelFile.exists()) {
                    val msg = "Model missing!\nPush to:\n${modelFile.absolutePath}"
                    Log.e("LiteRT", msg)
                    withContext(Dispatchers.Main) { tvResult.text = msg }
                    return@launch
                }

                // Configure Engine (CPU for Safety)
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU,
                    audioBackend = Backend.CPU,
                    cacheDir = appDir?.absolutePath,
                    maxNumTokens = 1024
                )

                engine = Engine(engineConfig)
                engine?.initialize()
                conversation = engine?.createConversation(ConversationConfig())

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ready!", Toast.LENGTH_SHORT).show()
                    tvResult.text = "Engine Ready. Pick or Record Audio."
                    btnPickAudio.isEnabled = true
                    btnRecordAudio.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Init Error: ${e.message}"
                    Log.e("LiteRT", "Failed", e)
                }
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        try {
            tempRecordFile = File(cacheDir, "mic_record_${UUID.randomUUID()}.m4a")

            // Use the correct constructor based on Android version
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder = recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(tempRecordFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            btnRecordAudio.text = "Stop Recording"
            tvSelectedFile.text = "Recording..."
            tvResult.text = "Recording..."
            btnPickAudio.isEnabled = false
            btnRun.isEnabled = false

        } catch (e: IOException) {
            Log.e("AudioRecord", "Start failed", e)
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            btnRecordAudio.text = "Record Audio"
            btnPickAudio.isEnabled = true

            // Convert the recorded file
            tempRecordFile?.let { file ->
                processRecordedFile(file)
            }

        } catch (e: Exception) {
            Log.e("AudioRecord", "Stop failed", e)
        }
    }

    // Process file from File Picker
    private fun processAudioUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Converting audio to 16kHz WAV..."
                    btnRun.isEnabled = false
                }

                val rawFile = AudioHelper.copyUriToCache(this@MainActivity, uri) ?: throw Exception("Copy failed")
                val convertedFile = AudioHelper.convertTo16BitWav(rawFile, this@MainActivity)
                rawFile.delete()

                if (convertedFile == null || !convertedFile.exists()) {
                    throw Exception("Conversion failed")
                }

                val bytes = convertedFile.readBytes()
                selectedAudioBytes = bytes
                convertedFile.delete()

                withContext(Dispatchers.Main) {
                    tvSelectedFile.text = "File Loaded (${bytes.size} bytes)"
                    tvResult.text = "Audio ready. Click Run."
                    btnRun.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("AudioProcess", "Error", e)
                withContext(Dispatchers.Main) { tvResult.text = "Error: ${e.message}" }
            }
        }
    }

    // Process file from Mic Recorder
    private fun processRecordedFile(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Processing recording..."
                }

                // Convert the M4A recording to 16kHz WAV
                val convertedFile = AudioHelper.convertTo16BitWav(file, this@MainActivity)
                file.delete() // Clean up raw recording

                if (convertedFile == null || !convertedFile.exists()) {
                    throw Exception("Conversion failed")
                }

                val bytes = convertedFile.readBytes()
                selectedAudioBytes = bytes
                convertedFile.delete()

                withContext(Dispatchers.Main) {
                    tvSelectedFile.text = "Recording Loaded (${bytes.size} bytes)"
                    tvResult.text = "Recording ready. Click Run."
                    btnRun.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("AudioProcess", "Error", e)
                withContext(Dispatchers.Main) { tvResult.text = "Error: ${e.message}" }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun runInference() {
        val promptText = etPrompt.text.toString()
        if (conversation == null) return

        tvResult.text = "Thinking..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentList = mutableListOf<Content>()

                selectedAudioBytes?.let { audio ->
                    contentList.add(Content.AudioBytes(audio))
                }

                if (promptText.isNotEmpty()) {
                    contentList.add(Content.Text(promptText))
                }

                val message = Message.of(contentList)

                var fullResponse = ""
                conversation?.sendMessageAsync(message, object : MessageCallback {
                    override fun onMessage(partial: Message) {
                        fullResponse += partial.toString()
                        runOnUiThread { tvResult.text = fullResponse }
                    }
                    override fun onDone() {
                        Log.d("LiteRT", "Done")
                    }
                    override fun onError(e: Throwable) {
                        Log.e("LiteRT", "Error", e)
                        runOnUiThread { tvResult.text = "Error: ${e.message}" }
                    }
                })

            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Error: ${e.message}"
                }
            }
        }
    }
}