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

// IMPORTS
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
                // Grants permission to read this specific URI
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                loadAudioFile(uri)
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
            // FIX: Use */* to show ALL files, not just strictly defined wavs
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                // Optional: extra mime types if needed
                // putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "application/octet-stream"))
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
                    backend = Backend.CPU, // Keep on CPU for safety
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

    private fun loadAudioFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    selectedAudioBytes = bytes
                    withContext(Dispatchers.Main) {
                        tvSelectedFile.text = "Loaded: ${bytes.size} bytes"
                        tvResult.text = "Audio ready. Click Run."
                        btnRun.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioLoad", "Failed", e)
                withContext(Dispatchers.Main) {
                    tvResult.text = "Failed to read audio: ${e.message}"
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