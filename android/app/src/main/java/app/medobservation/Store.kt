package app.medobservation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 日誌の1日分。内容はすべて本人が話したことの要約で、推測や診断は入れない。 */
data class Entry(
    val date: String,
    val share: Boolean = true,
    val meds: List<String> = emptyList(),
    val texts: Map<String, String> = emptyMap(),
) {
    fun text(key: String) = texts[key].orEmpty()
}

/** 日誌の項目。キーはWeb版と同じ。 */
val FIELDS = listOf(
    "did" to "できたこと",
    "feel" to "体感",
    "compare" to "比較",
    "sleep" to "睡眠",
    "creativity" to "創造性",
    "concern" to "気になること",
)

/** 追記したい内容。会話からの抽出でも手入力でも同じ形。 */
data class Observation(
    val meds: List<String> = emptyList(),
    val texts: Map<String, String> = emptyMap(),
) {
    fun isEmpty() = meds.none { it.isNotBlank() } && texts.values.none { it.isNotBlank() }
}

/**
 * 日誌はアプリ専用領域の1ファイルだけに置く。
 * allowBackup=false とバックアップ除外ルールで、端末の外には出さない。
 */
class DiaryStore(context: Context) {
    private val file = File(context.filesDir, "diary.json")

    @Synchronized
    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.date }
    }

    @Synchronized
    private fun saveAll(entries: List<Entry>) {
        val arr = JSONArray()
        entries.sortedByDescending { it.date }.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, "diary.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** その日の記録に追記する。同じ文が既にあれば重ねない。 */
    @Synchronized
    fun append(date: String, obs: Observation): List<Entry> {
        val all = load().toMutableList()
        val i = all.indexOfFirst { it.date == date }
        val cur = if (i >= 0) all[i] else Entry(date = date)
        val meds = cur.meds.toMutableList()
        obs.meds.map { it.trim() }.filter { it.isNotEmpty() && it !in meds }.forEach { meds += it }
        val texts = cur.texts.toMutableMap()
        FIELDS.forEach { (k, _) -> texts[k] = mergeText(cur.text(k), obs.texts[k].orEmpty()) }
        val next = cur.copy(meds = meds, texts = texts.filterValues { it.isNotBlank() })
        if (i >= 0) all[i] = next else all += next
        saveAll(all)
        return load()
    }

    @Synchronized
    fun setShare(date: String, share: Boolean): List<Entry> {
        saveAll(load().map { if (it.date == date) it.copy(share = share) else it })
        return load()
    }

    @Synchronized
    fun delete(date: String): List<Entry> {
        saveAll(load().filterNot { it.date == date })
        return load()
    }

    /** Web版の書き出しと同じ形のJSON。移行や本人によるバックアップ用。 */
    fun exportJson(): String = JSONArray().apply { load().forEach { put(toJson(it)) } }.toString(2)

    private fun mergeText(a: String, b: String): String {
        val x = a.trim()
        val y = b.trim()
        return when {
            y.isEmpty() -> x
            x.isEmpty() -> y
            x.contains(y) -> x
            else -> "$x / $y"
        }
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("date", e.date)
        put("share", e.share)
        put("meds", JSONArray(e.meds))
        FIELDS.forEach { (k, _) -> if (e.text(k).isNotBlank()) put(k, e.text(k)) }
    }

    private fun fromJson(o: JSONObject): Entry {
        val meds = o.optJSONArray("meds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val texts = FIELDS.associate { (k, _) -> k to o.optString(k, "") }.filterValues { it.isNotBlank() }
        return Entry(date = o.getString("date"), share = o.optBoolean("share", true), meds = meds, texts = texts)
    }
}

/** 設定。日誌と同じくバックアップ対象外の領域に置く。 */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("serverUrl", "") ?: ""
        set(v) = p.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()
    var serverModel: String
        get() = p.getString("serverModel", "") ?: ""
        set(v) = p.edit().putString("serverModel", v.trim()).apply()
    var serverKey: String
        get() = p.getString("serverKey", "") ?: ""
        set(v) = p.edit().putString("serverKey", v.trim()).apply()
    /** "local" = 端末内Gemma、"server" = 自宅サーバ */
    var chatBackend: String
        get() = p.getString("chatBackend", "local") ?: "local"
        set(v) = p.edit().putString("chatBackend", v).apply()
    var downloadId: Long
        get() = p.getLong("downloadId", -1L)
        set(v) = p.edit().putLong("downloadId", v).apply()
}
