package app.medobservation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NoteTheme { App(vm) } }
    }
}

private val Teal = Color(0xFF2F6F6A)
private val TealLight = Color(0xFF7CC2BA)

@Composable
fun NoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = TealLight, onPrimary = Color(0xFF0E2422), primaryContainer = Color(0xFF213634),
        onPrimaryContainer = Color(0xFFCBE7E3), background = Color(0xFF141A1A), surface = Color(0xFF1C2423),
        onBackground = Color(0xFFE3EBE9), onSurface = Color(0xFFE3EBE9), surfaceVariant = Color(0xFF263130),
        onSurfaceVariant = Color(0xFF9AA9A6), tertiary = Color(0xFFE0A35A),
    ) else lightColorScheme(
        primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFE1EFED),
        onPrimaryContainer = Color(0xFF173C39), background = Color(0xFFF3F5F4), surface = Color.White,
        onBackground = Color(0xFF1D2A2A), onSurface = Color(0xFF1D2A2A), surfaceVariant = Color(0xFFE6ECEB),
        onSurfaceVariant = Color(0xFF5D6B6A), tertiary = Color(0xFF9A5B14),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) { Talk("話す"), Log("日誌"), Visit("受診用"), Setup("設定") }

@Composable
fun App(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Talk) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Text(t.label.take(1), fontWeight = FontWeight.Bold) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (tab) {
                Tab.Talk -> TalkScreen(vm) { tab = Tab.Setup }
                Tab.Log -> LogScreen(vm)
                Tab.Visit -> VisitScreen(vm)
                Tab.Setup -> SetupScreen(vm)
            }
        }
    }
}

@Composable
private fun Header(title: String, sub: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
        Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- 話す ----------------

@Composable
private fun TalkScreen(vm: AppViewModel, openSetup: () -> Unit) {
    val lines by vm.chat.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val help by vm.showHelp.collectAsState()
    val model by vm.model.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    val needsModel = vm.settings.chatBackend == "local" && !model.loaded

    Column(Modifier.fillMaxSize().imePadding()) {
        Header("服薬観察ノート", "話した中から、服薬と体調をその日の日誌に残します")
        if (needsModel) {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (model.present) "端末内のAIを読み込むと、会話から日誌をつけられます。"
                        else "端末内で動くAI（Gemma）がまだ入っていません。設定から入れてください。",
                        fontSize = 14.sp,
                    )
                    if (model.present) Button(onClick = { vm.loadModel() }, enabled = !model.busy) {
                        Text(if (model.busy) "読み込み中…" else "読み込む")
                    } else OutlinedButton(onClick = openSetup) { Text("設定を開く") }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lines) { l -> Bubble(l) }
            if (thinking) item { Bubble(ChatLine(false, "考えています…", note = true)) }
            if (help) item { HelpCard() }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), minLines = 2, maxLines = 6,
                placeholder = { Text("例：午前中は座って作業できた。昼から少し頭が痛い") },
            )
            Button(
                onClick = { vm.send(input); input = "" },
                enabled = input.isNotBlank() && !thinking && !needsModel,
            ) { Text("送る") }
        }
    }
}

@Composable
private fun Bubble(l: ChatLine) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.fromMe) Arrangement.End else Arrangement.Start) {
        if (l.note) {
            Text(l.text, fontSize = 12.sp, color = c.primary)
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (l.fromMe) c.primaryContainer else c.surface,
                tonalElevation = if (l.fromMe) 0.dp else 1.dp,
                modifier = Modifier.widthIn(max = 320.dp),
            ) { Text(l.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun HelpCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        SelectionContainer {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ひとりで抱えないでください。", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                Text("つらさが強いときは、主治医のクリニックか、次の窓口に連絡してください。", fontSize = 14.sp)
                Text("よりそいホットライン 0120-279-338（24時間）", fontSize = 14.sp)
                Text("いのちの電話 0570-783-556", fontSize = 14.sp)
                Text("今すぐ危ないときは 119", fontSize = 14.sp)
            }
        }
    }
}

// ---------------- 日誌 ----------------

@Composable
private fun LogScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("日誌", "日ごとに、医師と共有するかを選べます") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (!adding) OutlinedButton(onClick = { adding = true }) { Text("手で追加") }
                else ManualForm(vm.today(), onCancel = { adding = false }) { d, o -> vm.addManual(d, o); adding = false }
            }
        }
        if (entries.isEmpty()) item {
            Text(
                "記録はまだありません。「話す」で今日の様子を書くと、ここに並びます。",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(entries, key = { it.date }) { e -> EntryCard(e, { vm.toggleShare(e) }, { confirmDelete = e }) }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${e.date} の記録を消しますか？") },
            text = { Text("この端末からも完全に消え、元に戻せません。") },
            confirmButton = { TextButton(onClick = { vm.delete(e); confirmDelete = null }) { Text("消す") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
        )
    }
}

@Composable
private fun EntryCard(e: Entry, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(c.surface, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.date, fontFamily = FontFamily.Monospace, color = c.primary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            FilterChip(selected = e.share, onClick = onShare, label = { Text(if (e.share) "医師と共有する" else "共有しない") })
        }
        if (e.meds.isNotEmpty()) Text(e.meds.joinToString("、"), fontSize = 13.sp, color = c.primary)
        FIELDS.forEach { (k, n) ->
            if (e.text(k).isNotBlank()) Row {
                Text(n, Modifier.width(84.dp), fontSize = 12.sp, color = c.onSurfaceVariant)
                Text(e.text(k), fontSize = 14.sp, color = if (k == "concern") c.tertiary else c.onSurface)
            }
        }
        TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) { Text("この日を消す", fontSize = 12.sp) }
    }
}

@Composable
private fun ManualForm(today: String, onCancel: () -> Unit, onSave: (String, Observation) -> Unit) {
    var date by rememberSaveable { mutableStateOf(today) }
    var meds by rememberSaveable { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, String>() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(date, { date = it }, label = { Text("日付（YYYY-MM-DD）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(meds, { meds = it }, label = { Text("服薬（読点かカンマで区切る）") }, modifier = Modifier.fillMaxWidth())
        FIELDS.forEach { (k, n) ->
            OutlinedTextField(values[k].orEmpty(), { values[k] = it }, label = { Text(n) }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(date.trim(), Observation(meds.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() }, values.toMap()))
                },
                enabled = Regex("""\d{4}-\d{2}-\d{2}""").matches(date.trim()),
            ) { Text("日誌に追記") }
            TextButton(onClick = onCancel) { Text("やめる") }
        }
    }
}

// ---------------- 受診用 ----------------

@Composable
private fun VisitScreen(vm: AppViewModel) {
    val summary by vm.summary.collectAsState()
    val busy by vm.summaryBusy.collectAsState()
    val entries by vm.entries.collectAsState()
    var days by rememberSaveable { mutableStateOf(30) }
    val ctx = LocalContext.current
    val count = remember(entries, days) { vm.sharedText(days).first }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("受診用", "「共有する」にした日だけを、医師が数分で読める要約にします")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(14, 30, 60).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("${d}日") }) }
            }
            Text("対象：${count}日分", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.makeSummary(days) }, enabled = !busy && count > 0) { Text("要約を作る") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("受診用の要約", summary))
                    Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
                }, enabled = summary.isNotBlank() && !busy) { Text("コピー") }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(summary.ifBlank { "まだ要約はありません。受診の前日などに作ってください。" }, Modifier.padding(14.dp), fontSize = 14.sp)
                }
            }
            Text(
                "要約は自宅サーバが設定されていればそちらで作り、なければ端末内のAIで作ります。薬の量や治療の判断は主治医と相談してください。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 設定 ----------------

@Composable
private fun SetupScreen(vm: AppViewModel) {
    val model by vm.model.collectAsState()
    val s = vm.settings
    var backend by rememberSaveable { mutableStateOf(s.chatBackend) }
    var url by rememberSaveable { mutableStateOf(s.serverUrl) }
    var modelName by rememberSaveable { mutableStateOf(s.serverModel) }
    var key by rememberSaveable { mutableStateOf(s.serverKey) }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("設定", "記録はこの端末の中だけに保存され、バックアップにも含まれません")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("端末内のAI（Gemma 4 E2B）", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    model.loaded -> "読み込み済み"
                    model.present -> "入っています（未読み込み）"
                    else -> "まだ入っていません。約2.6GBあるので、Wi-Fiでダウンロードしてください。"
                }, fontSize = 14.sp,
            )
            if (model.progress >= 0) LinearProgressIndicator(progress = { model.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (model.message.isNotBlank()) Text(model.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.downloadModel() }, enabled = !model.busy && model.progress < 0) { Text("ダウンロード") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !model.busy) { Text("ファイルから") }
                if (model.present && !model.loaded) OutlinedButton(onClick = { vm.loadModel() }, enabled = !model.busy) { Text("読み込む") }
            }

            HorizontalDivider()
            Text("会話に使うAI", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = backend == "local", onClick = { backend = "local"; s.chatBackend = "local" })
                Text("端末内（記録が外に出ない）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = backend == "server", onClick = { backend = "server"; s.chatBackend = "server" })
                Text("自宅サーバ")
            }

            HorizontalDivider()
            Text("自宅サーバ（受診用の要約に使います）", fontWeight = FontWeight.SemiBold)
            Text(
                "Ollama・vLLM・llama.cpp など、OpenAI互換のAPIを持つサーバのアドレスを入れてください。外出先からはTailscaleなどのVPN経由を想定しています。http は自宅LANとTailscaleのアドレスだけで使えます。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(url, { url = it }, label = { Text("アドレス（例 http://100.x.x.x:11434）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(modelName, { modelName = it }, label = { Text("モデル名（例 gemma3:27b）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("APIキー（必要な場合だけ）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                s.serverUrl = url; s.serverModel = modelName; s.serverKey = key
                Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show()
            }) { Text("保存") }

            HorizontalDivider()
            Text("記録の書き出し", fontWeight = FontWeight.SemiBold)
            Text("自分でバックアップしたいときに、全記録をJSONでコピーします。貼り付け先に注意してください。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("服薬観察ノート", vm.store.exportJson()))
                Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
            }) { Text("JSONをコピー") }
        }
        Spacer(Modifier.height(32.dp))
    }
}
