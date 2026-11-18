package ru.pocketagent

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Текст прикреплённого файла или экрана, разбитый на фрагменты для поиска по ключевым словам. */
class DocIndex(val name: String, val kind: Kind, rawText: String) {
    enum class Kind { FILE, SCREEN }
    val text: String = rawText.replace("\r", "").replace(Regex("[ \t\u00A0]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
    val chunks: List<String> = split(text)

    fun search(query: String, k: Int = 3): List<String> {
        val keys = keywords(query)
        if (keys.isEmpty()) return emptyList()
        return chunks.mapIndexed { i, c ->
            val low = c.lowercase()
            val score = keys.sumOf { key -> Regex("(?<![\\p{L}\\d])" + Regex.escape(key)).findAll(low).count().coerceAtMost(5) * (if (key.length > 4) 2 else 1) }
            Triple(i, c, score)
        }.filter { it.third > 0 }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(k).sortedBy { it.first }.map { it.second }
    }

    /** Лучшие предложения с ключевыми словами (для цитат). */
    fun quotes(query: String, n: Int = 2): List<String> {
        val keys = keywords(query)
        if (keys.isEmpty()) return emptyList()
        // переносы строк внутри предложения (PDF) склеиваем
        val joined = text.replace(Regex("(?<![.!?:;…])\n(?=[\\p{Ll}\\d«(])"), " ")
        val sentences = joined.split(Regex("(?<=[.!?…])\\s+|\n+")).map { it.trim() }.filter { it.length >= 3 }
        return sentences.map { s -> s to keys.count { s.lowercase().contains(it) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { short(it.first, 260) }
    }

    /** Начало текста в пределах бюджета (для пересказа). */
    fun head(maxChars: Int): String = if (text.length <= maxChars) text else text.substring(0, maxChars).substringBeforeLast(' ') + " …"

    companion object {
        const val CHUNK = 700
        private val STOP = setOf("что", "как", "где", "это", "тут", "здесь", "там", "файл", "файле", "файла", "документ", "документе",
            "текст", "тексте", "экран", "экране", "найди", "найти", "про", "для", "или", "есть", "написано", "написан", "какой", "какая",
            "какие", "каком", "мне", "меня", "пожалуйста", "покажи", "скажи", "the", "and", "what", "когда", "сколько", "кто", "там", "все", "всё", "ли")
        fun stem(w: String): String = if (w.length > 6) w.substring(0, w.length - 2) else if (w.length > 4) w.substring(0, w.length - 1) else w
        fun keywords(q: String): List<String> = Regex("[\\p{L}\\d]+").findAll(q.lowercase().replace('ё', 'е')).map { it.value }
            .filter { it.length >= 3 && it !in STOP || it.any(Char::isDigit) }.map { if (it.any(Char::isDigit)) it else stem(it) }.distinct().toList()
        fun short(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n).substringBeforeLast(' ') + "…"
        fun split(t: String): List<String> {
            if (t.isBlank()) return emptyList()
            val out = mutableListOf<String>(); val cur = StringBuilder()
            for (para in t.split(Regex("\n+"))) {
                var p = para.trim()
                while (p.length > CHUNK) {   // длинный абзац режем по предложениям/словам
                    val cut = p.lastIndexOf(". ", CHUNK).takeIf { it > CHUNK / 2 } ?: p.lastIndexOf(' ', CHUNK).takeIf { it > 0 } ?: CHUNK
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                    out += p.substring(0, cut + 1).trim(); p = p.substring(cut + 1).trim()
                }
                if (cur.length + p.length > CHUNK && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                if (p.isNotEmpty()) { if (cur.isNotEmpty()) cur.append('\n'); cur.append(p) }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}

/** Извлечение текста из файлов (без сети). */
object DocText {
    const val MAX_CHARS = 400_000
    class Unsupported(msg: String) : Exception(msg)

    fun kindOf(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm", "srt") || mime?.startsWith("text/") == true || mime == "application/json" -> "text"
            ext == "pdf" || mime == "application/pdf" -> "pdf"
            ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp") -> "image"
            else -> "unknown"
        }
    }

    fun readText(input: InputStream): String {
        val bytes = input.readBytes()
        val utf = String(bytes, Charsets.UTF_8)
        // старые файлы Windows-1251: много «�» в UTF-8
        val bad = utf.count { it == '\uFFFD' }
        val s = if (bad > 3 && bad > utf.length / 50) String(bytes, charset("windows-1251")) else utf
        return s.removePrefix("\uFEFF").take(MAX_CHARS)
    }

    fun html(s: String) = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

    /** DOCX = zip с word/document.xml: абзацы <w:p>, текст <w:t>, табуляции и переносы. */
    fun docx(input: InputStream): String {
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = String(z.readBytes(), Charsets.UTF_8)
                    return docxXml(xml).take(MAX_CHARS)
                }
                e = z.nextEntry
            }
        }
        throw Unsupported("в файле DOCX не найден текст")
    }

    fun docxXml(xml: String): String = xml
        .replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br[^>]*/>"), "\n")
        .replace(Regex("</w:tc>"), "\t")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
}

/** Ответы на вопросы о файле/экране: поиск по фрагментам + цитаты; модель — только для пересказа/перевода/свободного вопроса. */
object DocQA {
    enum class Intent { SUMMARY, READ, FIND, NUMBER, TRANSLATE, ASK }

    private val SCREEN_REF = Regex("(?iu)(?<![\\p{L}\\d])(на экране|с экрана|экран\\p{L}*|тут|здесь|на странице|эт(у|ой|а) страниц\\p{L}*)")
    private val FILE_REF = Regex("(?iu)(?<![\\p{L}\\d])(файл\\p{L}*|документ\\p{L}*|в тексте|текст\\p{L}*|pdf|пдф|вложени\\p{L}*|статье|статья|книг\\p{L}*)")
    private val SUMMARY = Regex("(?iu)(перескаж|пересказ|кратко|вкратце|о ч[её]м|суть|главное|резюм|summar)")
    private val READ = Regex("(?iu)(что (тут|здесь|там|на экране|в файле)? ?написано|прочитай|прочти|зачитай)")
    private val FIND = Regex("(?iu)^(найди|найти|поищи|ищи|где (говорится|сказано|написано|упоминается)( про| о| об)?)\\s+(.+)")
    private val NUMBER = Regex("(?iu)(номер|телефон|почт|e-?mail|ссылк|сумм|цен[аы]|стоимост|дат[аыу]|адрес)")
    private val TRANSLATE = Regex("(?iu)^(переведи|перевод|translate)")

    fun intent(text: String): Intent = when {
        TRANSLATE.containsMatchIn(text.trim()) -> Intent.TRANSLATE
        FIND.containsMatchIn(text.trim()) -> Intent.FIND
        SUMMARY.containsMatchIn(text) -> Intent.SUMMARY
        READ.containsMatchIn(text) -> Intent.READ
        Regex("(?iu)^(какой|какая|какие|назови|скажи|дай)(?![\\p{L}\\d])").containsMatchIn(text.trim()) && NUMBER.containsMatchIn(text) -> Intent.NUMBER
        else -> Intent.ASK
    }

    /** К какому документу относится вопрос (null — не к документу). force — вопрос задан из «Спросить про экран». */
    fun pick(text: String, file: DocIndex?, screen: DocIndex?, forceScreen: Boolean): DocIndex? {
        if (forceScreen && screen != null) return screen
        val t = text.trim()
        if (screen != null && SCREEN_REF.containsMatchIn(t)) return screen
        if (file != null && (FILE_REF.containsMatchIn(t) || FIND.containsMatchIn(t) || SUMMARY.containsMatchIn(t) || READ.containsMatchIn(t))) return file
        if (screen != null && (SUMMARY.containsMatchIn(t) || READ.containsMatchIn(t) || TRANSLATE.containsMatchIn(t))) return screen
        if (file != null && TRANSLATE.containsMatchIn(t)) return file
        // экран не читали — «тут/здесь» и «какой номер/телефон…» относятся к прикреплённому файлу
        if (file != null && screen == null && (SCREEN_REF.containsMatchIn(t) || intent(t) == Intent.NUMBER)) return file
        return null
    }

    fun label(d: DocIndex) = if (d.kind == DocIndex.Kind.SCREEN) "на экране" else "в файле «${d.name}»"
    fun quote(s: String) = "«" + s.trim().trimEnd('.') + "»"

    // —— извлечение без модели ——
    private val PHONE = Regex("(?<![\\d])(\\+?\\d[\\d ()\\-]{8,}\\d)(?![\\d])")
    private val EMAIL = Regex("[\\p{L}\\d_.+-]+@[\\p{L}\\d_-]+\\.[\\p{L}\\d_.]+")
    private val URL = Regex("https?://\\S+|www\\.\\S+")
    private val DATE = Regex("(?iu)(?<![\\p{L}\\d])(\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}|\\d{1,2} (января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)( \\d{4})?)")

    fun extractNumbers(text: String, d: DocIndex): String? {
        val t = text.lowercase()
        fun list(name: String, items: List<String>): String? = items.map { it.trim() }.distinct().take(5).takeIf { it.isNotEmpty() }
            ?.let { "$name ${label(d)}: " + it.joinToString(", ") + "." }
        // «номер кабинета / квартиры / дома / заказа» → число рядом с этим словом
        Regex("номер\\s+(\\p{L}{3,})").find(t)?.groupValues?.get(1)?.let { w ->
            val st = DocIndex.stem(w.replace('ё', 'е')).take(6)
            if (!st.startsWith("телеф")) {
                val m = Regex("(?iu)" + Regex.escape(st) + "\\p{L}*\\s*(№|n|номер)?\\s*[:.]?\\s*(\\d[\\d\\-/]*[\\p{L}]?)").findAll(d.text).map { it.value.trim() }.toList()
                list("Нашёл ${label(d)}", m)?.let { return it.replace("Нашёл ${label(d)}: ", "Нашёл ${label(d)}: ") }
            }
        }
        return when {
            Regex("телефон|номер").containsMatchIn(t) -> list("Номер", PHONE.findAll(d.text).map { it.value }.filter { it.count(Char::isDigit) in 10..13 }.toList())
                ?: list("Номер", Regex("№\\s?[\\p{L}\\d_-]+").findAll(d.text).map { it.value }.toList())
            Regex("почт|mail").containsMatchIn(t) -> list("Почта", EMAIL.findAll(d.text).map { it.value }.toList())
            t.contains("ссылк") -> list("Ссылка", URL.findAll(d.text).map { it.value.trimEnd('.', ',', ')') }.toList())
            Regex("дат").containsMatchIn(t) -> list("Дата", DATE.findAll(d.text).map { it.value }.toList())
            Regex("сумм|цен|стоим").containsMatchIn(t) -> WebSearch.extractPrices(d.text).take(5).takeIf { it.isNotEmpty() }
                ?.let { ps -> "Суммы ${label(d)}: " + ps.joinToString(", ") { FollowUp.fmt(it.amount) + " " + FollowUp.sign(it.currency).trimEnd('.') } + "." }
            else -> null
        }
    }

    fun find(query: String, d: DocIndex): String {
        val q = FIND.find(query.trim())?.groupValues?.get(4)?.trim()?.trimEnd('?', '.') ?: query
        val qs = d.quotes(q, 3)
        return if (qs.isEmpty()) "${label(d).replaceFirstChar { it.uppercase() }} не нашёл «$q»."
        else "Нашёл ${label(d)}:\n" + qs.joinToString("\n") { "• " + quote(it) }
    }

    fun read(d: DocIndex): String = "${label(d).replaceFirstChar { it.uppercase() }} написано:\n" + d.head(700)

    fun summaryPrompt(d: DocIndex, budget: Int) = "<|im_start|>system\nТы кратко пересказываешь текст по-русски: 3–4 простых предложения, без списков и без выдумок.<|im_end|>\n" +
        "<|im_start|>user\nТекст:\n${d.head(budget)}\n\nПерескажи кратко, о чём этот текст.<|im_end|>\n<|im_start|>assistant\n"

    fun translatePrompt(fragment: String, toEnglish: Boolean) = "<|im_start|>system\nТы переводчик. Переводи точно, без пояснений.<|im_end|>\n" +
        "<|im_start|>user\nПереведи на ${if (toEnglish) "английский" else "русский"} язык:\n$fragment<|im_end|>\n<|im_start|>assistant\n"

    fun askPrompt(question: String, fragments: List<String>) = "<|im_start|>system\nОтвечай по-русски коротко (1–3 предложения) ТОЛЬКО по приведённому тексту. " +
        "Если в тексте нет ответа — скажи «В тексте этого нет».<|im_end|>\n<|im_start|>user\nТекст:\n${fragments.joinToString("\n…\n")}\n\nВопрос: $question<|im_end|>\n<|im_start|>assistant\n"

    fun isCyrillic(s: String) = s.count { it in 'а'..'я' || it in 'А'..'Я' } > s.count { it in 'a'..'z' || it in 'A'..'Z' }
}

/** Текст экрана из дерева View (AssistStructure). */
object ScreenText {
    const val MAX = 30_000
    /** Убираем дубли и мусор (одиночные символы-значки), сохраняем порядок. */
    fun clean(lines: List<String>): String {
        val seen = HashSet<String>(); val out = StringBuilder()
        for (raw in lines) {
            val s = raw.replace(Regex("\\s+"), " ").trim()
            if (s.length < 2 && !s.any(Char::isLetterOrDigit)) continue
            if (s.isEmpty() || !seen.add(s.lowercase())) continue
            if (out.length + s.length > MAX) break
            out.append(s).append('\n')
        }
        return out.toString().trim()
    }
}
