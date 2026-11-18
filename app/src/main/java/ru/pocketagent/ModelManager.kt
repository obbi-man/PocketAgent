package ru.pocketagent

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelSpec(
    val id: String, val title: String, val file: String, val size: Long, val sha256: String,
    val urls: List<String>, val note: String, val minRamGb: Int = 2
) { val sizeMb get() = size / 1_000_000 }

/** Где лежит модель: встроенная в APK (assets), скачанная из каталога или импортированная. */
class ModelManager(private val ctx: Context) {
    companion object {
        private fun hf(repo: String, f: String) = listOf(
            "https://huggingface.co/$repo/resolve/main/$f",
            "https://hf-mirror.com/$repo/resolve/main/$f")
        val VIKHR = ModelSpec("vikhr", "Vikhr-Qwen2.5-0.5B (русский)", "Vikhr-Qwen-2.5-0.5B-instruct-Q4_0.gguf",
            351_890_688L, "9416c50be56f36f2c857e698698c01b2e0d7f759719e1f492577609569e1b735",
            hf("Vikhrmodels/Vikhr-Qwen-2.5-0.5B-instruct-GGUF", "Vikhr-Qwen-2.5-0.5B-instruct-Q4_0.gguf"),
            "Qwen2.5-0.5B, дообученная на русском (Vikhrmodels, Apache 2.0). Лучше всего говорит по-русски.")
        val POCKET = ModelSpec("pocket", "MNN-Pocket-0.3B (из статьи)", "MNN-Pocket-0.3B-Instruct-Q4_1.gguf",
            191_896_672L, "a281c127e388b16537fd3f5d31ab32d3f87739e6dc514ac1093c80d9eac8d097",
            hf("taobao-mnn/MNN-Pocket-0.3B-GGUF", "MNN-Pocket-0.3B-Instruct-Q4_1.gguf"),
            "Alibaba MNN, Apache 2.0. Быстрая и маленькая, но русский знает плохо (обучена на китайском/английском).")
        val VIKHR15 = ModelSpec("vikhr15", "Vikhr-Qwen2.5-1.5B (русский, большая)", "Vikhr-Qwen-2.5-1.5b-Instruct-Q4_0.gguf",
            934_607_488L, "1175c925fb5a3ef687edd778af68608ab45ca85fd589de65a62474706e5c1dd0",
            hf("Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF", "Vikhr-Qwen-2.5-1.5b-Instruct-Q4_0.gguf"),
            "Умнее, но в ~3 раза медленнее и требует много памяти. Рекомендуется телефон с 6+ ГБ ОЗУ.", minRamGb = 6)
        val CATALOG = listOf(VIKHR, VIKHR15, POCKET)
        // для совместимости со старым кодом
        val MODEL_FILE get() = POCKET.file
        private const val PREFS = "model"
    }

    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val importedFile = File(dir, "imported.gguf")

    /** Текущая модель каталога. В лайт-версии по умолчанию — русская Vikhr, в полной — встроенная Pocket. */
    var spec: ModelSpec
        get() = CATALOG.firstOrNull { it.id == prefs.getString("selected", null) } ?: defaultSpec()
        set(v) { prefs.edit().putString("selected", v.id).apply() }

    fun fileOf(m: ModelSpec) = File(dir, m.file)
    fun ready(m: ModelSpec) = fileOf(m).exists() && fileOf(m).length() == m.size
    fun partOf(m: ModelSpec) = File(dir, m.file + ".part")

    val builtinFile get() = fileOf(spec)
    val partFile get() = partOf(spec)

    var useImported: Boolean
        get() = prefs.getBoolean("use_imported", false) && importedFile.exists()
        set(v) { prefs.edit().putBoolean("use_imported", v).apply() }

    fun activeFile(): File = if (useImported) importedFile else builtinFile
    fun activeName(): String = if (useImported) (prefs.getString("imported_name", "imported.gguf") ?: "imported.gguf") else spec.title

    private fun assetExists(name: String) = try { ctx.assets.open(name).use { true } } catch (_: Exception) { false }
    /** По умолчанию: модель, встроенная в APK (если есть), иначе быстрая Vikhr 0.5B. */
    private fun defaultSpec(): ModelSpec = CATALOG.firstOrNull { assetExists(it.file) } ?: VIKHR
    fun hasBundledAsset(): Boolean = assetExists(spec.file)

    fun builtinReady(): Boolean = ready(spec)
    fun hasBundledAssetFor(m: ModelSpec): Boolean = assetExists(m.file)

    private fun freeBytes(): Long = StatFs(dir.absolutePath).availableBytes

    /** Распаковка модели из APK во внутреннюю память (один раз, ~192 МБ). */
    fun extractBuiltin(progress: (Long, Long) -> Unit) {
        if (builtinReady()) return
        val m = spec
        val total = try { ctx.assets.openFd(m.file).use { it.length } } catch (_: Exception) { m.size }
        if (freeBytes() < total + 20_000_000) throw IllegalStateException("Мало места: нужно ~${total / 1_000_000 + 20} МБ свободной памяти")
        ctx.assets.open(m.file).use { copy(it, builtinFile, total, progress) }
        if (builtinFile.length() != m.size) { builtinFile.delete(); throw IllegalStateException("Модель распаковалась не полностью") }
    }

    /** Импорт GGUF-файла из памяти телефона (Storage Access Framework). */
    fun import(uri: Uri, displayName: String, progress: (Long, Long) -> Unit) {
        val resolver = ctx.contentResolver
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        resolver.openInputStream(uri)!!.use { ins ->
            val head = ByteArray(4)
            val bin = ins.buffered(1 shl 20)
            bin.mark(8)
            if (bin.read(head) != 4 || String(head, Charsets.US_ASCII) != "GGUF") throw IllegalArgumentException("Это не GGUF-файл")
            bin.reset()
            if (total > 0 && freeBytes() < total + 20_000_000) throw IllegalStateException("Мало места для импорта")
            copy(bin, importedFile, total, progress)
        }
        prefs.edit().putString("imported_name", displayName).putBoolean("use_imported", true).apply()
    }

    /**
     * Скачивание модели: Hugging Face, затем hf-mirror.com. Докачка (HTTP Range) в файл .part,
     * проверка SHA-256, только потом — переименование в рабочий файл.
     */
    fun download(progress: (Long, Long) -> Unit, cancelled: () -> Boolean = { false }) {
        val m = spec
        val MODEL_SIZE = m.size
        val partFile = partOf(m); val builtinFile = fileOf(m)
        if (freeBytes() < MODEL_SIZE + 20_000_000 - partFile.length()) throw IllegalStateException("Мало места: нужно ~${MODEL_SIZE / 1_000_000 + 20} МБ свободной памяти")
        var last: Exception? = null
        for (u in m.urls) {
            for (attempt in 1..3) {
                if (cancelled()) throw IllegalStateException("отменено")
                try {
                    downloadFrom(m, u, progress, cancelled)
                    progress(-1, MODEL_SIZE) // -1 = идёт проверка
                    val sha = sha256(partFile)
                    if (sha != m.sha256) { partFile.delete(); throw IllegalStateException("контрольная сумма не совпала, файл удалён") }
                    if (builtinFile.exists()) builtinFile.delete()
                    if (!partFile.renameTo(builtinFile)) throw IllegalStateException("не удалось сохранить файл")
                    return
                } catch (e: Exception) {
                    last = e
                    if (e.message == "отменено") throw e
                    Thread.sleep(1500L * attempt)
                }
            }
        }
        throw last ?: IllegalStateException("не удалось скачать")
    }

    private fun downloadFrom(m: ModelSpec, url: String, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val MODEL_SIZE = m.size
        val partFile = partOf(m)
        var have = if (partFile.exists()) partFile.length() else 0L
        if (have > MODEL_SIZE) { partFile.delete(); have = 0 }
        if (have == MODEL_SIZE) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketAgent/1.1 (Android)")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                206 -> true
                200 -> { have = 0; false }          // сервер не поддержал докачку — начинаем заново
                416 -> { partFile.delete(); throw IllegalStateException("HTTP 416, начну заново") }
                else -> throw IllegalStateException("HTTP $code")
            }
            java.io.FileOutputStream(partFile, append).use { out ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16)
                    var done = have; var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw IllegalStateException("отменено")
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - lastReport > 1_000_000) { progress(done, MODEL_SIZE); lastReport = done }
                    }
                    progress(done, MODEL_SIZE)
                }
            }
            if (partFile.length() != MODEL_SIZE) throw IllegalStateException("соединение оборвалось (${partFile.length() / 1_000_000} МБ), докачаю")
        } finally { conn.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(1 shl 20); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copy(ins: InputStream, dst: File, total: Long, progress: (Long, Long) -> Unit) {
        val tmp = File(dst.parentFile, dst.name + ".part")
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 20)
            var done = 0L; var lastReport = 0L
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n
                if (done - lastReport > 2_000_000) { progress(done, total); lastReport = done }
            }
            progress(done, total)
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("не удалось сохранить файл")
    }
}

class PrefsNotes(ctx: Context) : NotesStore {
    private val p = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)
    override fun all(): List<String> {
        val a = org.json.JSONArray(p.getString("list", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }
    override fun add(text: String) {
        val a = org.json.JSONArray(p.getString("list", "[]")).put(text)
        p.edit().putString("list", a.toString()).apply()
    }
    override fun clear() { p.edit().putString("list", "[]").apply() }
    fun removeAt(i: Int) {
        val a = org.json.JSONArray(p.getString("list", "[]"))
        if (i in 0 until a.length()) { a.remove(i); p.edit().putString("list", a.toString()).apply() }
    }
}
