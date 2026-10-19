package app.medobservation

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** 会話や要約に使うAI。端末内か自宅サーバかを差し替えられる。 */
interface ChatModel {
    suspend fun complete(system: String, prompt: String): String
}

/** 端末内で動かすGemma（LiteRT-LM）。記録はスマホの外に出ない。 */
class LocalGemma(private val cacheDir: File) : ChatModel {
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private val lock = Mutex()
    var backendName: String = ""
        private set

    val isLoaded get() = engine != null

    suspend fun load(modelPath: String) = lock.withLock {
        withContext(Dispatchers.Default) {
            if (engine != null && loadedPath == modelPath) return@withContext
            engine?.close()
            engine = null
            // GPUが使えればGPU、だめならCPUで読み込む
            val tried = mutableListOf<Throwable>()
            for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
                try {
                    val e = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir.path))
                    e.initialize()
                    engine = e
                    loadedPath = modelPath
                    backendName = backend.name
                    return@withContext
                } catch (t: Throwable) {
                    tried += t
                }
            }
            throw IllegalStateException("モデルを読み込めませんでした: ${tried.lastOrNull()?.message}")
        }
    }

    override suspend fun complete(system: String, prompt: String): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: throw IllegalStateException("端末内のモデルがまだ読み込まれていません")
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
            )
            e.createConversation(config).use { it.sendMessage(prompt).toString() }
        }
    }

    fun close() {
        engine?.close()
        engine = null
        loadedPath = null
    }
}

/**
 * 自宅の推論サーバ（Ollama、vLLM、llama.cpp など OpenAI互換の /v1/chat/completions）。
 * 暗号化されていない http は、自宅LANとTailscaleのアドレスにだけ許可する。
 */
class HomeServer(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : ChatModel {

    override suspend fun complete(system: String, prompt: String): String = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "設定で自宅サーバのアドレスを入れてください" }
        val url = URL("$baseUrl/v1/chat/completions")
        checkTransport(url)
        val body = JSONObject().apply {
            if (model.isNotBlank()) put("model", model)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", prompt))
            })
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("自宅サーバがエラーを返しました（$code）")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun checkTransport(url: URL) {
        if (url.protocol == "https") return
        val addr = InetAddress.getByName(url.host)
        val b = addr.address.map { it.toInt() and 0xff }
        val ok = addr.isLoopbackAddress || addr.isSiteLocalAddress ||
            (b.size == 4 && b[0] == 100 && b[1] in 64..127) // Tailscale (100.64.0.0/10)
        if (!ok) throw IllegalStateException("http で接続できるのは自宅LANかTailscaleのアドレスだけです。https を使ってください")
    }
}

/** モデルの返事からJSONを取り出す。前後に文が付いていても読む。 */
fun extractJson(text: String): JSONObject? {
    val s = text.indexOf('{')
    val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return runCatching { JSONObject(text.substring(s, e + 1)) }.getOrNull()
}
