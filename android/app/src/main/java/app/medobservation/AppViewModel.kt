package app.medobservation

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class ChatLine(val fromMe: Boolean, val text: String, val note: Boolean = false)

data class ModelState(
    val present: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val progress: Int = -1,
    val message: String = "",
)

const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
const val MODEL_FILE = "gemma-4-E2B-it.litertlm"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val store = DiaryStore(ctx)
    val settings = Settings(ctx)
    private val gemma = LocalGemma(ctx.cacheDir)

    val entries = MutableStateFlow(store.load())
    val chat = MutableStateFlow(
        listOf(ChatLine(false, "今日の調子や、薬を飲んでみて感じたこと、困っていることを気軽に書いてください。話した中から日誌に必要なところだけ拾って残します。"))
    )
    val thinking = MutableStateFlow(false)
    val showHelp = MutableStateFlow(false)
    val summary = MutableStateFlow("")
    val summaryBusy = MutableStateFlow(false)
    private val _model = MutableStateFlow(ModelState())
    val model = _model.asStateFlow()

    private val history = ArrayDeque<Pair<String, String>>() // (利用者, 返事) の直近数往復
    private var pollJob: Job? = null

    init {
        refreshModelState()
        if (settings.downloadId >= 0) watchDownload(settings.downloadId)
    }

    fun today(): String = LocalDate.now().toString()

    // ---- モデル ----

    private fun modelFile(): File? {
        val ext = File(ctx.getExternalFilesDir(null), MODEL_FILE)
        if (ext.exists() && ext.length() > 0) return ext
        val imported = File(ctx.filesDir, MODEL_FILE)
        if (imported.exists() && imported.length() > 0) return imported
        return null
    }

    private fun refreshModelState() {
        _model.update { it.copy(present = modelFile() != null, loaded = gemma.isLoaded) }
    }

    /** Hugging Face から Gemma 4 E2B（約2.6GB）をアプリ専用フォルダに落とす。Wi-Fi推奨。 */
    fun downloadModel() {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        File(ctx.getExternalFilesDir(null), MODEL_FILE).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Gemma 4 E2B（服薬観察ノート）")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, MODEL_FILE)
        val id = dm.enqueue(req)
        settings.downloadId = id
        watchDownload(id)
    }

    private fun watchDownload(id: Long) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val dm = ctx.getSystemService(DownloadManager::class.java)
            while (true) {
                val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
                if (!c.moveToFirst()) { c.close(); settings.downloadId = -1; break }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                c.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        settings.downloadId = -1
                        _model.update { it.copy(progress = -1, message = "ダウンロードが終わりました。「読み込む」を押してください。") }
                        refreshModelState()
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        settings.downloadId = -1
                        _model.update { it.copy(progress = -1, message = "ダウンロードに失敗しました。通信環境を確認して、もう一度試してください。") }
                        break
                    }
                    else -> {
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        _model.update { it.copy(progress = pct, message = "ダウンロード中 $pct%") }
                    }
                }
                delay(1500)
            }
        }
    }

    /** 自分で用意した .litertlm ファイルを取り込む。 */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _model.update { it.copy(busy = true, message = "モデルを取り込んでいます…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val dest = File(ctx.filesDir, MODEL_FILE)
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { input.copyTo(it, 1 shl 20) }
                    }
                }
            }.onSuccess {
                _model.update { it.copy(busy = false, message = "取り込みました。「読み込む」を押してください。") }
            }.onFailure { e ->
                _model.update { it.copy(busy = false, message = "取り込めませんでした: ${e.message}") }
            }
            refreshModelState()
        }
    }

    fun loadModel() {
        val f = modelFile() ?: return
        viewModelScope.launch {
            _model.update { it.copy(busy = true, message = "モデルを読み込んでいます（数十秒かかることがあります）…") }
            runCatching { gemma.load(f.path) }
                .onSuccess { _model.update { it.copy(busy = false, message = "読み込みました（${gemma.backendName}）") } }
                .onFailure { e -> _model.update { it.copy(busy = false, message = e.message ?: "読み込めませんでした") } }
            refreshModelState()
        }
    }

    private fun chatModel(): ChatModel =
        if (settings.chatBackend == "server") server() else gemma

    private fun server() = HomeServer(settings.serverUrl, settings.serverModel, settings.serverKey)

    // ---- 話す ----

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || thinking.value) return
        chat.update { it + ChatLine(true, msg) }
        thinking.value = true
        viewModelScope.launch {
            try {
                val ctxText = history.joinToString("\n") { (u, a) -> "利用者: $u\nあなた: $a" }
                val prompt = buildString {
                    if (ctxText.isNotEmpty()) append("これまでの会話:\n").append(ctxText).append("\n\n")
                    append("今回の利用者のメッセージ:\n").append(msg)
                }
                val raw = chatModel().complete(Prompts.chat(today()), prompt)
                val json = extractJson(raw)
                val reply = json?.optString("reply")?.takeIf { it.isNotBlank() }
                    ?: raw.substringBefore('{').trim().ifEmpty { "記録しておきますね。" }
                chat.update { it + ChatLine(false, reply) }
                history.addLast(msg to reply)
                while (history.size > 4) history.removeFirst()
                if (json?.optBoolean("crisis") == true) showHelp.value = true
                val obs = json?.optJSONObject("entry")?.let { e ->
                    val meds = e.optJSONArray("meds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                    Observation(meds, FIELDS.associate { (k, _) -> k to e.optString(k, "") })
                }
                if (obs != null && !obs.isEmpty()) {
                    entries.value = withContext(Dispatchers.IO) { store.append(today(), obs) }
                    chat.update { it + ChatLine(false, "✓ ${today()} の日誌に追記しました", note = true) }
                }
            } catch (e: Throwable) {
                chat.update { it + ChatLine(false, "うまく処理できませんでした：${e.message}", note = true) }
            } finally {
                thinking.value = false
            }
        }
    }

    // ---- 日誌 ----

    fun addManual(date: String, obs: Observation) {
        viewModelScope.launch { entries.value = withContext(Dispatchers.IO) { store.append(date, obs) } }
    }

    fun toggleShare(e: Entry) {
        viewModelScope.launch { entries.value = withContext(Dispatchers.IO) { store.setShare(e.date, !e.share) } }
    }

    fun delete(e: Entry) {
        viewModelScope.launch { entries.value = withContext(Dispatchers.IO) { store.delete(e.date) } }
    }

    // ---- 受診用 ----

    fun sharedText(days: Int): Pair<Int, String> {
        val from = LocalDate.now().minusDays(days.toLong()).toString()
        val picked = entries.value.filter { it.share && it.date >= from }.sortedBy { it.date }
        val text = picked.joinToString("\n\n") { e ->
            buildList {
                add("■ ${e.date}")
                if (e.meds.isNotEmpty()) add("服薬: ${e.meds.joinToString("、")}")
                FIELDS.forEach { (k, n) -> if (e.text(k).isNotBlank()) add("$n: ${e.text(k)}") }
            }.joinToString("\n")
        }
        return picked.size to text
    }

    /** 詳しい要約は自宅サーバに頼む。未設定なら端末内Gemma、それもなければ記録をそのまま出す。 */
    fun makeSummary(days: Int) {
        val (count, text) = sharedText(days)
        if (count == 0) { summary.value = "この期間に共有する記録がありません。"; return }
        summaryBusy.value = true
        summary.value = "まとめています…"
        viewModelScope.launch {
            val model: ChatModel? = when {
                settings.serverUrl.isNotBlank() -> server()
                gemma.isLoaded -> gemma
                else -> null
            }
            summary.value = if (model == null) text else runCatching {
                model.complete(Prompts.SUMMARY_SYSTEM, Prompts.summary(text))
            }.getOrElse { e -> "要約を作れませんでした（${e.message}）。記録をそのまま表示します。\n\n$text" }
            summaryBusy.value = false
        }
    }

    override fun onCleared() {
        gemma.close()
    }
}
