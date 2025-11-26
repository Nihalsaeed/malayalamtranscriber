package com.nihal.malayalam

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText

// LITERT IMPORTS
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

class MainActivity : AppCompatActivity() {

    private val MODEL_FILENAME = "gemma-3n-it.litertlm"

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var selectedAudioBytes: ByteArray? = null

    private lateinit var tvResult: TextView
    private lateinit var tvSelectedFile: TextView
    private lateinit var btnPickAudio: Button
    private lateinit var btnRun: Button
    private lateinit var etPrompt: TextInputEditText

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                processAudioFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvResult)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        btnPickAudio = findViewById(R.id.btnPickAudio)
        btnRun = findViewById(R.id.btnRun)
        etPrompt = findViewById(R.id.etPrompt)

        findViewById<Button>(R.id.btnLoadModel).setOnClickListener {
            initializeModel()
        }

        btnPickAudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // Allow ALL audio types (Opus, MP3, M4A)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg"))
            }
            pickAudioLauncher.launch(intent)
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
                    tvResult.text = "Engine Ready. Load Audio."
                    btnPickAudio.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Init Error: ${e.message}"
                    Log.e("LiteRT", "Failed", e)
                }
            }
        }
    }

    // NEW FUNCTION: Handles copying AND converting
    private fun processAudioFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Converting audio to 16kHz WAV..."
                    btnRun.isEnabled = false
                }

                // 1. Copy the user's file (Opus, MP3, etc) to a temp file
                val rawFile = AudioHelper.copyUriToCache(this@MainActivity, uri)
                if (rawFile == null) {
                    throw Exception("Failed to copy audio file")
                }

                // 2. Convert it to the format Gemma needs (16kHz Mono WAV)
                val convertedFile = AudioHelper.convertTo16BitWav(rawFile, this@MainActivity)

                // Cleanup the raw input file to save space
                rawFile.delete()

                if (convertedFile == null || !convertedFile.exists()) {
                    throw Exception("FFmpeg conversion failed")
                }

                // 3. Read the bytes of the CLEAN WAV file
                val bytes = convertedFile.readBytes()
                selectedAudioBytes = bytes

                // Cleanup the wav file (bytes are now in memory)
                convertedFile.delete()

                withContext(Dispatchers.Main) {
                    tvSelectedFile.text = "Audio Ready (${bytes.size} bytes)"
                    tvResult.text = "Audio converted & ready."
                    btnRun.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("AudioProcess", "Error", e)
                withContext(Dispatchers.Main) {
                    tvResult.text = "Error processing audio: ${e.message}"
                }
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

                // Audio First
                selectedAudioBytes?.let { audio ->
                    contentList.add(Content.AudioBytes(audio))
                }

                // Text Second
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
                        Log.d("LiteRT", "Inference Done")
                    }
                    override fun onError(e: Throwable) {
                        Log.e("LiteRT", "Inference Error", e)
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