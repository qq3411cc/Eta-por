package io.github.mangi.eta.agent.voice.local

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import io.github.mangi.eta.agent.voice.LocalAsrBackend
import io.github.mangi.eta.agent.voice.LocalAsrSupport
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.data.model.LocalSpeechConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx 离线识别实现（`src/localasr` 源码集）。
 *
 * 只有 `app/libs/sherpa-onnx.aar` 存在时 build.gradle.kts 才会把本源码集加入编译，
 * 因此这里的 import 不会影响不含 AAR 的常规构建。
 *
 * 依赖 API（sherpa-onnx v1.12.x，`com.k2fsa.sherpa.onnx`）：
 *   OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(...))
 *   OfflineModelConfig(paraformer/senseVoice, tokens, numThreads, provider, modelType)
 *   recognizer.createStream().acceptWaveform(samples, sampleRate) → decode → getResult(stream).text
 * 换 AAR 大版本前请核对上述签名。
 */
internal class SherpaLocalAsrBackend : LocalAsrBackend {
    override val id: String = "sherpa-onnx"

    /** 能走到这里说明 AAR 已编入、JNI 库可加载。 */
    override val available: Boolean = true

    private var appContext: Context? = null
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null
    private val mutex = Mutex()

    override fun attach(context: Context) {
        appContext = context.applicationContext
    }

    override fun describe(): String = "sherpa-onnx（本地）"

    override suspend fun recognize(
        wav: ByteArray,
        config: LocalSpeechConfig,
        onProgress: (String) -> Unit,
    ): String = mutex.withLock {
        val context = appContext ?: throw SpeechFailure(
            SpeechErrorCode.LOCAL_BACKEND,
            "本地识别未初始化，请重开设置页后重试",
        )
        val dir = LocalAsrSupport.modelDir(context, config)
        val model = LocalAsrSupport.modelFile(dir) ?: throw SpeechFailure(
            SpeechErrorCode.LOCAL_MODEL,
            "缺少模型文件：${dir.absolutePath}",
        )
        val tokens = LocalAsrSupport.tokensFile(dir) ?: throw SpeechFailure(
            SpeechErrorCode.LOCAL_MODEL,
            "缺少 tokens.txt：${dir.absolutePath}",
        )
        val key = buildString {
            append(model.absolutePath).append('|')
            append(tokens.absolutePath).append('|')
            append(config.modelType).append('|')
            append(config.threads).append('|')
            append(config.language)
        }
        val engine = withContext(Dispatchers.IO) {
            val existing = recognizer
            if (existing != null && loadedKey == key) {
                existing
            } else {
                existing?.release()
                recognizer = null
                loadedKey = null
                onProgress("正在加载本地模型")
                create(model, tokens, config).also {
                    recognizer = it
                    loadedKey = key
                }
            }
        }
        val (samples, sampleRate) = LocalAsrSupport.wavToSamples(wav)
        if (samples.isEmpty()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
        onProgress("正在本地识别")
        val text = withContext(Dispatchers.Default) {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
        if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
        text
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    private fun create(model: File, tokens: File, config: LocalSpeechConfig): OfflineRecognizer {
        val threads = config.threads.coerceIn(1, 8)
        val modelConfig = if (config.modelType.equals(MODEL_TYPE_SENSE_VOICE, ignoreCase = true)) {
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = config.language.ifBlank { "zh" },
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath,
                numThreads = threads,
                provider = "cpu",
                modelType = MODEL_TYPE_SENSE_VOICE,
            )
        } else {
            OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = model.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = threads,
                provider = "cpu",
                modelType = MODEL_TYPE_PARAFORMER,
            )
        }
        return try {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = modelConfig,
                ),
            )
        } catch (error: Throwable) {
            throw SpeechFailure(
                SpeechErrorCode.LOCAL_MODEL,
                "本地模型加载失败：${error.message ?: error.javaClass.simpleName}",
                detail = error.toString(),
            )
        }
    }

    private companion object {
        const val MODEL_TYPE_PARAFORMER = "paraformer"
        const val MODEL_TYPE_SENSE_VOICE = "sensevoice"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
    }
}
