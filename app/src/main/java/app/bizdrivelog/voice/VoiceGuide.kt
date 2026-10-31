package app.bizdrivelog.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/** 화면에 보여 줄 음성 진행 상태 */
sealed interface VoicePhase {
    data object Idle : VoicePhase
    data class Speaking(val text: String) : VoicePhase
    data class Listening(val partial: String = "") : VoicePhase
    data class Heard(val text: String) : VoicePhase
}

object VoiceState {
    private val _phase = MutableStateFlow<VoicePhase>(VoicePhase.Idle)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    internal fun set(phase: VoicePhase) {
        _phase.value = phase
    }
}

/**
 * Pleos TextToSpeech·SpeechToText(ON_DEVICE: 앱 등록·네트워크 불필요) 래퍼.
 * 실패·무응답은 null로 돌려주고 호출부는 조용히 화면 입력으로 넘어간다.
 */
class VoiceGuide(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val ttsReady = CompletableDeferred<Boolean>()
    private val sttReady = CompletableDeferred<Boolean>()

    @Volatile private var speaking: CompletableDeferred<Boolean>? = null
    @Volatile private var listening: CompletableDeferred<String?>? = null
    @Volatile private var recognizerReady: CompletableDeferred<Unit>? = null
    @Volatile private var lastPartial = ""
    @Volatile private var maxRms = 0.0
    /** 이전 요청의 stop/done 콜백이 늦게 와서 새 요청을 끝내지 않도록, 새 요청의 onStart 전에는 무시한다 */
    @Volatile private var awaitingStart = false

    private val ttsListener = object : EventListener {
        override fun onStart() { awaitingStart = false }
        override fun onReady() = Unit
        override fun onDone() { if (!awaitingStart) speaking?.complete(true) }
        override fun onStop() { if (!awaitingStart) speaking?.complete(false) }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "tts error $errorType")
            speaking?.complete(false)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private val sttListener = object : ResultListener {
        override fun onReady() { Log.i(TAG, "stt ready"); recognizerReady?.complete(Unit) }
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            if (stt.isNotBlank()) lastPartial = stt
            if (completed) listening?.complete(stt.ifBlank { lastPartial }.ifBlank { null })
            else VoiceState.set(VoicePhase.Listening(stt))
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) { if (rms > maxRms) maxRms = rms }
        override fun onEndedRecognition() { listening?.complete(lastPartial.ifBlank { null }) }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "stt error $errorType")
            listening?.complete(lastPartial.ifBlank { null })
        }
    }

    // 리스너·로캘은 initialize 성공 뒤에만 설정할 수 있다 (TTS는 먼저 부르면 UninitializedPropertyAccessException)
    init {
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    val ok = runCatching { tts.addEventListener(ttsListener); tts.setLocale(Locale.KOREA) }
                        .onFailure { Log.w(TAG, "tts setup failed", it) }.isSuccess
                    ttsReady.complete(ok)
                }
                override fun onFailure(errorType: TtsError) {
                    Log.w(TAG, "tts init failed $errorType")
                    ttsReady.complete(false)
                }
            })
        }.onFailure { Log.e(TAG, "tts unavailable", it); ttsReady.complete(false) }

        runCatching {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    val ok = runCatching { stt.addListener(sttListener); stt.setLocale(Locale.KOREA) }
                        .onFailure { Log.w(TAG, "stt setup failed", it) }.isSuccess
                    sttReady.complete(ok)
                }
                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "stt init failed $errorType")
                    sttReady.complete(false)
                }
            })
        }.onFailure { Log.e(TAG, "stt unavailable", it); sttReady.complete(false) }
    }

    /** 말하고 끝날 때까지 기다린다 */
    suspend fun speak(text: String): Boolean {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ttsReady.await() } != true) return false
        awaitingStart = true
        val done = CompletableDeferred<Boolean>().also { speaking = it }
        VoiceState.set(VoicePhase.Speaking(text))
        Log.i(TAG, "tts: $text")
        runCatching { tts.speak(text) }.onFailure { Log.w(TAG, "tts speak failed", it); done.complete(false) }
        val ok = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() } ?: false
        speaking = null
        VoiceState.set(VoicePhase.Idle)
        return ok
    }

    /** 한 번 듣고 인식한 문장을 돌려준다. 무응답·실패는 null */
    suspend fun listen(): String? {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { sttReady.await() } != true) return null
        lastPartial = ""
        maxRms = 0.0
        val ready = CompletableDeferred<Unit>().also { recognizerReady = it }
        val result = CompletableDeferred<String?>().also { listening = it }
        VoiceState.set(VoicePhase.Listening())
        Log.i(TAG, "stt listening")
        runCatching { stt.request() }.onFailure { Log.w(TAG, "stt request failed", it); result.complete(null) }
        // 듣기 제한은 인식기가 준비된 뒤부터 센다. 인식 엔진 시작이 늦어도 답할 시간을 줄이지 않으려는 것
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            select<Unit> {
                ready.onAwait { }
                result.onAwait { }
            }
        }
        val text = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { result.await() } ?: lastPartial.ifBlank { null }
        runCatching { stt.stop() }
        listening = null
        recognizerReady = null
        Log.i(TAG, "stt: ${text ?: "(no answer)"} maxRms=$maxRms")
        VoiceState.set(if (text != null) VoicePhase.Heard(text) else VoicePhase.Idle)
        return text
    }

    suspend fun ask(prompt: String): String? = if (speak(prompt)) listen() else null

    fun stop() {
        if (speaking != null) runCatching { tts.stop() }
        if (listening != null) runCatching { stt.stop() }
        speaking?.complete(false)
        listening?.complete(null)
        VoiceState.set(VoicePhase.Idle)
    }

    fun release() {
        stop()
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    companion object {
        private const val TAG = "BizDriveLog"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SPEAK_TIMEOUT_MS = 15_000L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val LISTEN_TIMEOUT_MS = 10_000L
    }
}

/** 설정의 음성 안내 토글 (기본 켜짐) */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
}
