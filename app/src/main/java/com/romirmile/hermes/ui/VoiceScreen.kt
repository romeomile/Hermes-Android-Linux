package com.romirmile.hermes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.romirmile.hermes.MessageRole
import com.romirmile.hermes.R
import com.romirmile.hermes.data.AgentTtsConfig
import com.romirmile.hermes.data.ChatterboxVoice
import com.romirmile.hermes.data.HermesVoice
import com.romirmile.hermes.data.PhoneVoice
import com.romirmile.hermes.data.SpeechEngine
import com.romirmile.hermes.data.VoiceConfigClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

private enum class VoiceStage { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

private val STOP_PHRASES = setOf("stop", "stop it", "stop please", "cancel", "never mind", "nevermind")

/**
 * Speech is synthesized in sentence-sized chunks so playback starts early, but a free speech model is
 * request-capped (20/min, and 50–1000/day by account), so chunks stay generous: one long answer
 * should not burn a day's budget.
 */
private const val DIRECT_CHUNK_CHARS = 1200
private const val RELAY_CHUNK_CHARS = 1200

/**
 * Chunk size for the on-device model: shorter than the network engines on purpose, so the first
 * sentence is audible while the rest of a long reply is still being synthesized.
 */
private const val CHATTERBOX_CHUNK_CHARS = 220

/** Conventional OpenAI-compatible speech model when the user leaves the field blank. */
private const val DEFAULT_OWN_SPEECH_MODEL = "tts-1"

/**
 * Hands-free conversation with the same chat session: the phone listens (on-device speech
 * recognition), the agent answers through the gateway, the reply is spoken (on-device TTS) and
 * listening starts again — until the session is ended. Tapping the orb barges in.
 */
@Composable
fun VoiceScreen(vm: HermesViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val sending by vm.sending.collectAsState()
    val settings by vm.settings.collectAsState()

    // Spoken replies can come from the agent's own TTS setup instead of the phone engine.
    val agentVoice = settings.speechEngine == SpeechEngine.AGENT
    val ownVoice = settings.speechEngine == SpeechEngine.OWN
    val chatterboxVoice = settings.speechEngine == SpeechEngine.CHATTERBOX
    var agentVoiceOk by remember { mutableStateOf(true) }
    var agentCfg by remember { mutableStateOf<AgentTtsConfig?>(null) }
    var agentVoiceFailed by remember { mutableStateOf(false) }
    var ownVoiceFailed by remember { mutableStateOf(false) }
    var chatterboxFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var speechJob by remember { mutableStateOf<Job?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    val messages = sessions.firstOrNull { it.id == currentId }?.messages.orEmpty()
    val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val lastReply = lastAssistant?.text.orEmpty()

    var stage by remember { mutableStateOf(VoiceStage.IDLE) }
    var heard by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val tts = remember { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }

    val listeningIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    fun beginListening() {
        if (!granted || muted) {
            stage = VoiceStage.IDLE
            return
        }
        partial = ""
        stage = VoiceStage.LISTENING
        runCatching { recognizer.startListening(listeningIntent) }
            .onFailure { stage = VoiceStage.ERROR }
    }

    suspend fun playAndWait(file: File): Boolean = suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        mediaPlayer = player
        continuation.invokeOnCancellation {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
        } catch (t: Throwable) {
            runCatching { player.release() }
            if (mediaPlayer === player) mediaPlayer = null
            if (continuation.isActive) continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        player.setOnCompletionListener {
            runCatching { it.release() }
            if (mediaPlayer === it) mediaPlayer = null
            if (continuation.isActive) continuation.resume(true)
        }
        player.setOnErrorListener { _, _, _ ->
            runCatching { player.release() }
            if (mediaPlayer === player) mediaPlayer = null
            if (continuation.isActive) continuation.resume(false)
            true
        }
        player.start()
    }

    fun speakWithPhone(clean: String) {
        if (!ttsReady) {
            beginListening()
            return
        }
        stage = VoiceStage.SPEAKING
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hermes-${System.currentTimeMillis()}")
    }

    /**
     * Speaks through the agent's own TTS configuration (`POST /api/audio/speak`): the provider,
     * voice and credential set on the agent do the synthesis, so no speech key lives in the app.
     * Split by sentence so the first audio starts while the rest is still being synthesised, and any
     * unavailability (older build, provider failure) falls back to the phone engine.
     */
    fun speakWithAgent(clean: String) {
        stage = VoiceStage.SPEAKING
        speechJob?.cancel()
        // The agent may have handed us its own speech endpoint (direct): then the app chooses the
        // model/voice and only the credential comes from the agent. Otherwise the agent speaks.
        val direct = agentCfg?.takeIf { it.isDirect }
        val directModel = direct?.let { settings.speechModel.ifBlank { it.model.orEmpty() } }.orEmpty()
        val directBaseUrl = direct?.baseUrl
        val directKey = direct?.apiKey
        val useDirect = directBaseUrl != null && directKey != null && directModel.isNotBlank()
        val chunkLimit = if (useDirect) DIRECT_CHUNK_CHARS else RELAY_CHUNK_CHARS

        speechJob = scope.launch {
            for (chunk in speechChunks(clean, chunkLimit)) {
                val bytes = runCatching {
                    withContext(Dispatchers.IO) {
                        if (useDirect) {
                            HermesVoice.synthesizeDirect(
                                baseUrl = directBaseUrl,
                                apiKey = directKey,
                                model = directModel,
                                voice = settings.speechVoice.ifBlank { direct?.voice },
                                text = chunk
                            )
                        } else {
                            HermesVoice.speak(settings.endpoint, settings.apiKey, chunk)
                        }
                    }
                }.getOrNull()
                if (bytes == null) {
                    if (!agentVoiceFailed) {
                        agentVoiceFailed = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.voice_agent_unavailable),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    speakWithPhone(chunk)
                    return@launch
                }
                val file = File(context.cacheDir, "speech-${System.currentTimeMillis()}.mp3")
                val played = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                    .let { playAndWait(file) }
                file.delete()
                if (!played) return@launch
            }
            if (!muted) beginListening() else stage = VoiceStage.IDLE
        }
    }

    /**
     * Speaks through a speech provider the user configured in the app itself (their own endpoint,
     * key, model and voice) — no agent-side support needed. Split by sentence, and any failure falls
     * back to the phone engine so a call never goes silent.
     */
    fun speakWithOwn(clean: String) {
        stage = VoiceStage.SPEAKING
        speechJob?.cancel()
        val baseUrl = settings.ownSpeechUrl
        val providerKey = settings.ownSpeechKey
        val providerModel = settings.ownSpeechModel.ifBlank { DEFAULT_OWN_SPEECH_MODEL }
        val providerVoice = settings.ownSpeechVoice
        speechJob = scope.launch {
            for (chunk in speechChunks(clean, DIRECT_CHUNK_CHARS)) {
                val bytes = runCatching {
                    withContext(Dispatchers.IO) {
                        HermesVoice.synthesizeDirect(
                            baseUrl = baseUrl,
                            apiKey = providerKey,
                            model = providerModel,
                            voice = providerVoice.ifBlank { null },
                            text = chunk
                        )
                    }
                }.getOrNull()
                if (bytes == null) {
                    if (!ownVoiceFailed) {
                        ownVoiceFailed = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.voice_own_unavailable),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    speakWithPhone(chunk)
                    return@launch
                }
                val file = File(context.cacheDir, "speech-own-${System.currentTimeMillis()}.mp3")
                val played = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                    .let { playAndWait(file) }
                file.delete()
                if (!played) return@launch
            }
            if (!muted) beginListening() else stage = VoiceStage.IDLE
        }
    }

    /**
     * Speaks with the model that runs on this device (`libchatterbox.so`): the weight pack is
     * copied out of the APK on first use, and every later line is synthesized locally — no agent,
     * no endpoint, no key. Split by sentence so the first audio starts while the rest is still
     * being synthesized, and any failure falls back to the phone engine.
     */
    fun speakWithChatterbox(clean: String) {
        stage = VoiceStage.SPEAKING
        speechJob?.cancel()
        speechJob = scope.launch {
            for (chunk in speechChunks(clean, CHATTERBOX_CHUNK_CHARS)) {
                val bytes = runCatching {
                    withContext(Dispatchers.IO) { ChatterboxVoice.synthesize(context, chunk) }
                }.getOrNull()
                if (bytes == null) {
                    if (!chatterboxFailed) {
                        chatterboxFailed = true
                        val message = if (ChatterboxVoice.modelsInstalled(context)) {
                            R.string.voice_chatterbox_unavailable
                        } else {
                            R.string.voice_chatterbox_no_pack
                        }
                        Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                    }
                    speakWithPhone(chunk)
                    return@launch
                }
                val file = File(context.cacheDir, "speech-chatterbox-${System.currentTimeMillis()}.wav")
                val played = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                    .let { playAndWait(file) }
                file.delete()
                if (!played) return@launch
            }
            if (!muted) beginListening() else stage = VoiceStage.IDLE
        }
    }

    fun speak(raw: String) {
        val clean = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*`#_>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1500)
        if (clean.isBlank()) {
            beginListening()
            return
        }
        when {
            agentVoice && agentVoiceOk -> speakWithAgent(clean)
            chatterboxVoice -> speakWithChatterbox(clean)
            ownVoice && settings.ownSpeechUrl.isNotBlank() -> speakWithOwn(clean)
            else -> speakWithPhone(clean)
        }
    }

    /** Stops whatever is currently being spoken, from either engine. */
    fun stopSpeech() {
        speechJob?.cancel()
        speechJob = null
        ChatterboxVoice.cancel()
        runCatching { tts.stop() }
        mediaPlayer?.let { player ->
            mediaPlayer = null
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    fun interrupt() {
        stopSpeech()
        if (sending) vm.stop()
        beginListening()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
        if (isGranted) beginListening()
        else Toast.makeText(context, R.string.voice_no_permission, Toast.LENGTH_LONG).show()
    }

    // speech callbacks
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                stage = VoiceStage.LISTENING
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) partial = text
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                partial = ""
                if (text.isBlank()) {
                    if (!muted) beginListening()
                    return
                }
                heard = text
                if (STOP_PHRASES.contains(text.lowercase().trim(' ', '.', '!', '?'))) {
                    interrupt()
                } else if (text.isNotEmpty()) {
                    stage = VoiceStage.THINKING
                    vm.send(text)
                }
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        granted = false
                        stage = VoiceStage.ERROR
                    }

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (!muted && !sending) beginListening()

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Unit
                    else -> stage = VoiceStage.IDLE
                }
            }
        })
        onDispose {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
    }

    // text-to-speech wiring: when the utterance ends, listen again
    DisposableEffect(tts, ttsReady) {
        if (ttsReady) {
            // Steer the engine only when a voice was picked here. An empty choice leaves the phone's
            // own text-to-speech settings in charge — forcing the locale used to reset a voice the
            // user had chosen in those settings, so the change looked like it did nothing.
            if (settings.phoneVoice.isNotBlank()) PhoneVoice.apply(tts, settings.phoneVoice)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    stage = VoiceStage.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    if (!muted) beginListening() else stage = VoiceStage.IDLE
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stage = VoiceStage.IDLE
                }
            })
        }
        onDispose { stopSpeech() }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopSpeech()
            runCatching { tts.shutdown() }
        }
    }

    // The call stays alive when the screen is left, so the session is opened here rather than tied
    // to this composition; the red button is what actually ends it.
    LaunchedEffect(Unit) {
        vm.startVoiceSession()
        if (granted) beginListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Agent voice is a capability of the connected agent, not a guess: ask it what it can do once
    // per call — direct (the app may pick the model) or relay (the agent speaks) — and fall back
    // cleanly to the phone voice when it offers neither.
    LaunchedEffect(agentVoice, settings.endpoint, settings.apiKey) {
        if (!agentVoice) {
            agentVoiceOk = true
            return@LaunchedEffect
        }
        val config = withContext(Dispatchers.IO) {
            runCatching { VoiceConfigClient.fetch(settings.endpoint, settings.apiKey) }.getOrNull()
        }
        agentCfg = config
        agentVoiceOk = config != null
        if (!agentVoiceOk && !agentVoiceFailed) {
            agentVoiceFailed = true
            Toast.makeText(
                context,
                context.getString(R.string.voice_agent_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // A voice picked in Settings applies to the next utterance, not only after a restart.
    LaunchedEffect(settings.phoneVoice, ttsReady) {
        if (ttsReady) PhoneVoice.apply(tts, settings.phoneVoice)
    }

    // Speak the newest answer and go back to listening. Also covers a reply that arrived while the
    // call screen was closed: it has not been read out yet, so it is spoken on return.
    LaunchedEffect(sending, lastAssistant?.id, ttsReady, agentVoice) {
        if (sending) {
            stage = VoiceStage.THINKING
            return@LaunchedEffect
        }
        val message = lastAssistant ?: return@LaunchedEffect
        if (vm.hasSpoken(message.id)) return@LaunchedEffect
        // The phone engine only counts once initialised; the agent's voice needs no local engine.
        if (!agentVoice && !ttsReady) return@LaunchedEffect
        vm.markSpoken(message.id)
        if (message.text.isNotBlank()) speak(message.text) else beginListening()
    }

    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (stage == VoiceStage.LISTENING || stage == VoiceStage.SPEAKING) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0E0E)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_back), tint = Color.White)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.voice_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.fillMaxWidth(0f))
            }

            Spacer(Modifier.size(28.dp))

            Text(
                when (stage) {
                    VoiceStage.LISTENING -> stringResource(R.string.voice_listening)
                    VoiceStage.THINKING -> stringResource(R.string.voice_thinking)
                    VoiceStage.SPEAKING -> stringResource(R.string.voice_speaking)
                    VoiceStage.ERROR -> stringResource(R.string.voice_error)
                    VoiceStage.IDLE -> if (muted) stringResource(R.string.voice_muted)
                    else stringResource(R.string.voice_tap_to_talk)
                },
                color = Color(0xFFEDEDED),
                fontSize = 17.sp
            )

            Spacer(Modifier.size(24.dp))

            Box(
                Modifier
                    .size(220.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1C))
                    .clickable { interrupt() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_hermes_logo),
                    contentDescription = null,
                    modifier = Modifier.size(190.dp).clip(CircleShape)
                )
            }

            Spacer(Modifier.size(26.dp))

            val caption = partial.ifBlank { heard }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color(0xFFBDBDBD),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }
            if (lastReply.isNotBlank() && stage != VoiceStage.SPEAKING) {
                Spacer(Modifier.size(10.dp))
                Text(
                    lastReply.take(220),
                    color = Color(0xFF8A8A8A),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        muted = !muted
                        if (muted) {
                            runCatching { recognizer.stopListening() }
                            stopSpeech()
                            stage = VoiceStage.IDLE
                        } else {
                            beginListening()
                        }
                    }
                ) {
                    Icon(
                        if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        stringResource(
                            if (muted) R.string.voice_unmute else R.string.voice_mute
                        ),
                        tint = Color.White
                    )
                }

                FilledIconButton(
                    onClick = {
                        stopSpeech()
                        vm.endVoiceSession()
                        onClose()
                    },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFE5484D),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        stringResource(R.string.voice_end),
                        Modifier.size(26.dp)
                    )
                }

                IconButton(onClick = { interrupt() }) {
                    Icon(Icons.Default.Mic, stringResource(R.string.voice_tap_to_talk), tint = Color(0xFF8A8A8A))
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

/**
 * Splits a reply into speakable chunks on sentence boundaries, so long answers start playing after
 * one sentence instead of waiting for the whole synthesis.
 */
private fun speechChunks(text: String, limit: Int = RELAY_CHUNK_CHARS): List<String> {
    if (text.length <= limit) return listOf(text)
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    Regex("[^.!?]+[.!?]*\\s*").findAll(text).forEach { sentence ->
        val piece = sentence.value
        if (current.length + piece.length > limit && current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.clear()
        }
        current.append(piece)
    }
    if (current.isNotBlank()) chunks += current.toString().trim()
    return chunks.filter { it.isNotBlank() }
}
