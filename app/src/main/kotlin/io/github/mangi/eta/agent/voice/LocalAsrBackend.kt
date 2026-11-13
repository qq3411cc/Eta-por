package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.LocalSpeechConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地（离线）语音识别后端契约。
 *
 * main 源码集只依赖本接口，编译期不依赖 sherpa-onnx：AAR 缺失时由 [UnavailableBackend]
 * 兜底，构建照常通过，运行时给出明确错误。真正的实现在 `src/localasr` 源码集，
 * 只有把 AAR 放进 `app/libs` 时才会参与编译。
 */
internal interface LocalAsrBackend {
    /** 后端标识，只用于日志。 */
    val id: String

    /** 实现是否可用（AAR 是否编入、动态库能否加载）。 */
    val available: Boolean

    /** 人读状态，设置页展示用。 */
    fun describe(): String

    /**
     * 识别一段完整 WAV（PCM16、单声道、通常 16 kHz）。
     * 失败必须抛 [SpeechFailure]，不得静默返回空串以外的结果。
     */
    suspend fun recognize(wav: ByteArray, config: LocalSpeechConfig, onProgress: (String) -> Unit): String

    /** 释放常驻引擎；切换模型或退出设置页时调用。 */
    fun release()

    /** 装配后注入应用 Context；实现可忽略。 */
    fun attach(context: Context) = Unit
}

/** 未编入本地组件时的占位实现。 */
internal object UnavailableBackend : LocalAsrBackend {
    override val id: String = "unavailable"
    override val available: Boolean = false
    override fun describe(): String = "当前安装包未包含离线识别组件"
    override suspend fun recognize(
        wav: ByteArray,
        config: LocalSpeechConfig,
        onProgress: (String) -> Unit,
    ): String = throw SpeechFailure(
        SpeechErrorCode.LOCAL_BACKEND,
        "当前安装包未包含离线识别组件，请安装带离线语音的构建",
    )
    override fun release() = Unit
}

/**
 * 反射装配器。
 *
 * 只按类名加载本仓库自己的实现类（`src/localasr`），不反射第三方 data class，
 * 因此 AAR 版本变化不会波及装配逻辑。
 */
internal object LocalAsrBackends {
    private const val IMPLEMENTATION = "io.github.mangi.eta.agent.voice.local.SherpaLocalAsrBackend"

    @Volatile
    private var cached: LocalAsrBackend? = null

    fun current(context: Context): LocalAsrBackend {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also {
                it.attach(context.applicationContext)
                cached = it
            }
        }
    }

    /** 仅供设置页「重新检测」与测试使用。 */
    fun reset() {
        synchronized(this) {
            cached?.release()
            cached = null
        }
    }

    private fun load(): LocalAsrBackend = try {
        val type = Class.forName(IMPLEMENTATION)
        val instance = type.getDeclaredConstructor().newInstance()
        instance as? LocalAsrBackend ?: UnavailableBackend
    } catch (_: Throwable) {
        UnavailableBackend
    }
}

/**
 * 本地模型定位与音频转换。
 *
 * 模型不随包分发：默认目录是应用私有 `files/models/asr`，也可在设置页指定绝对路径。
 * 目录内需要 `model.int8.onnx`（或 `model.onnx`）与 `tokens.txt`。
 */
internal object LocalAsrSupport {
    private val MODEL_CANDIDATES = listOf("model.int8.onnx", "model.onnx", "model.fp16.onnx")

    fun defaultModelDir(context: Context): File = File(context.filesDir, "models/asr")

    fun modelDir(context: Context, config: LocalSpeechConfig): File =
        config.modelDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) } ?: defaultModelDir(context)

    fun modelFile(dir: File): File? = MODEL_CANDIDATES.map { File(dir, it) }.firstOrNull { it.isFile }

    fun tokensFile(dir: File): File? = File(dir, "tokens.txt").takeIf { it.isFile }

    /** 给设置页用的就绪说明，永远返回人读文本，不抛异常。 */
    fun readiness(context: Context, config: LocalSpeechConfig): String {
        val dir = modelDir(context, config)
        if (!dir.isDirectory) return "模型目录不存在：${dir.absolutePath}"
        val model = modelFile(dir) ?: return "缺少模型文件（model.int8.onnx / model.onnx）：${dir.absolutePath}"
        val tokens = tokensFile(dir) ?: return "缺少 tokens.txt：${dir.absolutePath}"
        val megabytes = model.length() / (1024.0 * 1024.0)
        return "模型就绪：${model.name}（%.1f MB）+ ${tokens.name}".format(megabytes)
    }

    /**
     * WAV → [-1, 1] 浮点采样。返回采样率交给 sherpa-onnx 自行重采样到模型采样率。
     * 只接受 PCM16；其它位深直接报错，避免把噪声喂给模型。
     */
    fun wavToSamples(wav: ByteArray): Pair<FloatArray, Int> {
        if (wav.size < 44 || wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) {
            throw SpeechFailure(SpeechErrorCode.AUDIO, "录音数据不是有效的 WAV")
        }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 16_000
        var channels = 1
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= wav.size) {
                        channels = buffer.getShort(body + 2).toInt().coerceAtLeast(1)
                        sampleRate = buffer.getInt(body + 4).takeIf { it > 0 } ?: 16_000
                        bits = buffer.getShort(body + 14).toInt()
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(size, wav.size - body).coerceAtLeast(0)
                    break
                }
            }
            if (size < 0) break
            offset = body + size + (size and 1)
        }
        if (dataOffset < 0 || dataSize <= 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "录音数据缺少音频帧")
        if (bits != 16) throw SpeechFailure(SpeechErrorCode.AUDIO, "只支持 16 位录音，当前 $bits 位")
        val frames = dataSize / 2 / channels
        if (frames <= 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "录音数据缺少音频帧")
        val samples = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val index = dataOffset + (i * channels + channel) * 2
                if (index + 1 >= wav.size) break
                sum += buffer.getShort(index) / 32768f
            }
            samples[i] = sum / channels
        }
        return samples to sampleRate
    }
}
