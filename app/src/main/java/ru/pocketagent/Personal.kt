package ru.pocketagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Факт о пользователе. key — тип: name, city, birthday, rel:<кто>, health, like, dislike, fact. */
data class Fact(val id: Long, val key: String, val text: String, val ts: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("key", key).put("text", text).put("ts", ts)
    companion object { fun of(o: JSONObject) = Fact(o.getLong("id"), o.getString("key"), o.getString("text"), o.optLong("ts")) }
}

/** Локальная память фактов (JSON-файл во внутренней памяти; без файла — только в памяти, для тестов). */
class FactStore(private val file: File? = null) {
    private val list = mutableListOf<Fact>()
    @Volatile var enabled = true
    init { load() }

    @Synchronized private fun load() {
        list.clear()
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            enabled = o.optBoolean("enabled", true)
            val a = o.optJSONArray("facts") ?: JSONArray()
            for (i in 0 until a.length()) list += Fact.of(a.getJSONObject(i))
        } catch (_: Exception) {}
    }

    @Synchronized private fun save() {
        val f = file ?: return
        val o = JSONObject().put("enabled", enabled).put("facts", JSONArray(list.map { it.toJson() }))
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    @Synchronized fun all(): List<Fact> = list.toList()
    @Synchronized fun toggle(v: Boolean) { enabled = v; save() }

    /** Добавить/обновить: name, city, birthday и rel:<кто> — единственные (новый заменяет старый). */
    @Synchronized fun put(key: String, text: String): Fact {
        val single = key in setOf("name", "city", "birthday") || key.startsWith("rel:")
        if (single) list.removeAll { it.key == key }
        list.removeAll { it.text.equals(text, true) }
        val f = Fact((list.maxOfOrNull { it.id } ?: 0) + 1, key, text)
        list += f; save(); return f
    }
    @Synchronized fun update(id: Long, text: String) { val i = list.indexOfFirst { it.id == id }; if (i >= 0) { list[i] = list[i].copy(text = text); save() } }
    @Synchronized fun remove(id: Long) { list.removeAll { it.id == id }; save() }
    @Synchronized fun clear() { list.clear(); save() }
    fun get(key: String) = all().lastOrNull { it.key == key }
    fun name(): String? = get("name")?.text?.let { Personal.nameOf(it) }
    fun city(): String? = get("city")?.text?.let { Personal.cityOf(it) }

    /** Подходящие к сообщению факты (по словам), не больше max; имя — всегда. */
    fun relevant(message: String, max: Int = 8): List<Fact> {
        val keys = DocIndex.keywords(message)
        val all = all()
        val scored = all.map { f -> f to keys.count { f.text.lowercase().replace('ё', 'е').contains(it) } }
        val picked = (all.filter { it.key == "name" } + scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first } +
            all.filter { it.key == "city" || it.key == "health" }).distinct()
        return picked.take(max)
    }
}

/** Извлечение фактов, команды памяти и стиль общения. Чистые функции — покрыты тестами. */
object Personal {
    private const val F = "(?iu)"
    private const val L = "[\\p{L}-]"
    private const val NB = "(?<![\\p{L}])"

    data class Extracted(val key: String, val text: String)

    private val RELS = linkedMapOf(
        "внук" to "внука", "внучк" to "внучку", "сын" to "сына", "доч" to "дочь", "жен" to "жену", "муж" to "мужа",
        "брат" to "брата", "сестр" to "сестру", "мам" to "маму", "пап" to "папу", "кот" to "кота", "кошк" to "кошку", "собак" to "собаку", "пёс" to "пса", "пес" to "пса",
    )

    fun cap(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    fun nameOf(fact: String) = Regex("$F^Меня зовут\\s+(.+?)\\.?$").find(fact)?.groupValues?.get(1) ?: fact
    fun cityOf(fact: String) = Regex("$F^Живу в\\s+(.+?)\\.?$").find(fact)?.groupValues?.get(1) ?: fact

    private fun clean(s: String) = s.trim().trim(',', '.', '!', ';', ':', ' ').replace(Regex("\\s+"), " ")
    private fun cut(s: String) = clean(s.split(Regex("$F(,|\\.|!|\\?|;|\\s+(и|а|но|что|чтобы|потому)\\s+)")).first())

    /** Факты из сообщения (простые шаблоны). */
    fun extract(raw: String): List<Extracted> {
        val t = " $raw "
        val out = mutableListOf<Extracted>()
        Regex("$F${NB}(меня зовут|мое имя|моё имя|зови меня|называй меня)\\s+($L{2,}(?:\\s+$L{2,})?)").find(t)?.let {
            val n = cap(it.groupValues[2].split(Regex("\\s+(и|а|,)\\s*")).first())
            if (n.lowercase() !in setOf("никак", "как", "не")) out += Extracted("name", "Меня зовут $n")
        }
        Regex("$F${NB}(я живу|живу|я из|мы живем|мы живём)\\s+(в|во|из)\\s+($L{3,}(?:[\\s-]$L{3,})?)").find(t)?.let {
            val city = cap(cut(it.groupValues[3]).replace(Regex("$F\\s+(уже|сейчас|давно|с\\s+\\d+).*$"), ""))
            out += Extracted("city", "Живу в $city")
        }
        Regex("$F${NB}(мой день рождения|у меня день рождения|я родился|я родилась|день рождения у меня)\\s+(\\d{1,2}[\\s.]+(?:\\d{1,2}|$L+)(?:[\\s.]+\\d{2,4})?)").find(t)?.let {
            out += Extracted("birthday", "День рождения: " + clean(it.groupValues[2]))
        }
        Regex("$F${NB}(мо(?:его|ю|я|й|ю)\\s+)?($L{3,})\\s+зовут\\s+($L{2,})").findAll(t).forEach { m ->
            val who = m.groupValues[2].lowercase()
            if (who == "меня") return@forEach
            val rel = RELS.entries.firstOrNull { who.startsWith(it.key) } ?: return@forEach
            out += Extracted("rel:${rel.value}", "${rel.value.replaceFirstChar { it.uppercase() }} зовут ${cap(m.groupValues[3])}")
        }
        Regex("$F${NB}у меня\\s+(аллергия\\s+на\\s+[\\p{L}\\s-]{3,40}|диабет|сахарный диабет|гипертония|давление|астма|подагра|непереносимость\\s+[\\p{L}\\s-]{3,30})").find(t)?.let {
            out += Extracted("health", "У меня " + cut(it.groupValues[1]))
        }
        Regex("$F${NB}мне нельзя\\s+([\\p{L}\\s-]{3,40})").find(t)?.let { out += Extracted("health", "Мне нельзя " + cut(it.groupValues[1])) }
        Regex("$F${NB}я (очень )?(люблю|обожаю)\\s+([\\p{L}\\s-]{3,40})").find(t)?.let { out += Extracted("like", "Люблю " + cut(it.groupValues[3])) }
        Regex("$F${NB}я (не люблю|терпеть не могу)\\s+([\\p{L}\\s-]{3,40})").find(t)?.let { out += Extracted("dislike", "Не люблю " + cut(it.groupValues[2])) }
        Regex("$F${NB}я (вегетарианец|вегетарианка|веган|пенсионер|пенсионерка|на пенсии)").find(t)?.let { out += Extracted("fact", "Я " + it.groupValues[1]) }
        return out.distinctBy { it.key + it.text }
    }

    sealed class Cmd {
        data class Remember(val facts: List<Extracted>, val explicit: Boolean) : Cmd()
        data class Forget(val query: String, val all: Boolean) : Cmd()
        data class Recall(val key: String?) : Cmd()   // null — всё
        data class Style(val change: String) : Cmd()   // short, long, ty, vy, noemoji, emoji
    }

    private val REMEMBER = Regex("$F^\\s*(пожалуйста[,\\s]+)?(запомни|запиши себе|учти|имей в виду)[,:\\s]+(что|то что)\\s+(.+)$")
    private val FORGET = Regex("$F^\\s*(пожалуйста[,\\s]+)?(забудь|удали из памяти|сотри из памяти)\\s*(,|:|про|о|об|что)?\\s*(.*)$")

    /** Команда памяти/стиля или null. «запомни код 4471» (без «что») — это заметка, не факт. */
    fun command(raw: String): Cmd? {
        val t = raw.trim()                       // оригинал — для текста фактов (Пётр, а не Петр)
        val low = t.lowercase().replace('ё', 'е')
        // стиль
        when {
            Regex("$F(отвечай|говори|пиши)\\s+(покороче|короче|кратко|коротко|кратче)").containsMatchIn(low) -> return Cmd.Style("short")
            Regex("$F(отвечай|говори|пиши)\\s+(подробнее|подробно|длиннее|развернуто|развёрнуто)").containsMatchIn(low) -> return Cmd.Style("long")
            Regex("$F(говори|обращайся|давай|можно|будем)\\s+(со мной\\s+)?на\\s+ты").containsMatchIn(low) -> return Cmd.Style("ty")
            Regex("$F(говори|обращайся|давай|будем)\\s+(со мной\\s+)?на\\s+вы").containsMatchIn(low) -> return Cmd.Style("vy")
            Regex("$F(без|не используй|не надо|убери)\\s+(смайлик|эмодзи|смайл|emoji)").containsMatchIn(low) -> return Cmd.Style("noemoji")
            Regex("$F(можно|добавляй|используй|ставь)\\s+(смайлик|эмодзи|смайл|emoji)").containsMatchIn(low) -> return Cmd.Style("emoji")
        }
        // вспомнить
        when {
            Regex("$F(как меня зовут|ты знаешь,? как меня зовут|ты помнишь,? как меня зовут|мое имя\\?|как мое имя)").containsMatchIn(low) -> return Cmd.Recall("name")
            Regex("$F(где я живу|в каком городе я живу|откуда я)").containsMatchIn(low) -> return Cmd.Recall("city")
            Regex("$F(когда (у меня|мой) день рождения)").containsMatchIn(low) -> return Cmd.Recall("birthday")
            Regex("$F(что ты (обо|про) мне знаешь|что ты (обо|про) меня помнишь|что ты знаешь обо мне|что ты помнишь обо мне|что ты запомнил обо мне)").containsMatchIn(low) -> return Cmd.Recall(null)
        }
        Regex("$F^\\s*как зовут\\s+(мо(?:его|ю|я|й)\\s+)?($L{3,})\\s*\\??$").find(low)?.let { m ->
            RELS.entries.firstOrNull { m.groupValues[2].startsWith(it.key) }?.let { return Cmd.Recall("rel:${it.value}") }
        }
        FORGET.find(t.replace('ё', 'е'))?.let { m ->
            val q = m.groupValues[4].trim().trimEnd('.', '!')
            val all = q.isBlank() || Regex("$F^(все|всё)(\\s+(обо|про)\\s+мне)?$|^обо мне$").matches(q)
            return Cmd.Forget(q, all)
        }
        REMEMBER.find(t)?.let { m ->
            val body = m.groupValues[4].trim().trimEnd('.', '!')
            val ex = extract(body)
            return Cmd.Remember(ex.ifEmpty { listOf(Extracted("fact", firstPerson(body))) }, explicit = true)
        }
        // сообщение — само по себе факт («Меня зовут Пётр», «Я живу в Екатеринбурге»), без вопроса
        val ex = extract(t)
        if (ex.isNotEmpty() && !t.contains('?') && t.length < 120 && ex.any { it.key == "name" || it.key == "city" || it.key == "birthday" || it.key.startsWith("rel:") } &&
            Regex("$F^\\s*(а\\s+|кстати,?\\s+|слушай,?\\s+)?(меня зовут|мое имя|моё имя|зови меня|называй меня|я живу|живу|я из|мы живем|мы живём|мой день рождения|у меня день рождения|я родил|мо(его|ю|я|й)\\s+$L+\\s+зовут|$L+\\s+зовут)").containsMatchIn(t))
            return Cmd.Remember(ex, explicit = true)
        return null
    }

    /** «что я люблю чай» → «Я люблю чай»; «у внука аллергия…» — как есть. */
    fun firstPerson(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    /** Ответ на «как меня зовут» и т. п. */
    fun recall(store: FactStore, key: String?, vy: Boolean): String {
        val facts = store.all()
        if (facts.isEmpty()) return if (vy) "Пока я о вас ничего не знаю. Скажите, например: «Меня зовут Пётр» или «Запомни, что я живу в Екатеринбурге»."
            else "Пока я о тебе ничего не знаю. Скажи, например: «Меня зовут Пётр»."
        return when {
            key == null -> (if (vy) "Вот что я о вас знаю:\n" else "Вот что я о тебе знаю:\n") + facts.joinToString("\n") { "• " + it.text }
            key == "name" -> store.name()?.let { if (vy) "Вас зовут $it." else "Тебя зовут $it." } ?: (if (vy) "Вы ещё не говорили, как вас зовут." else "Ты ещё не говорил, как тебя зовут.")
            key == "city" -> store.city()?.let { if (vy) "Вы живёте в городе $it." else "Ты живёшь в городе $it." } ?: "Я не знаю, где вы живёте."
            else -> store.get(key)?.text?.let { "$it." } ?: "Этого я пока не знаю."
        }
    }

    // ---------- стиль общения

    fun stripEmoji(s: String) = s.replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}\\x{FE0F}]"), "").replace(Regex(" {2,}"), " ")
}

/** Профиль стиля: счётчики по последним ~50 сообщениям + явные настройки пользователя. */
class StyleProfile(private val file: File? = null) {
    data class Style(val vy: Boolean, val short: Boolean, val emoji: Boolean)
    private val lens = ArrayDeque<Int>(); private var ty = 0; private var vy = 0; private var emo = 0; private var n = 0
    /** null — подстраиваться автоматически. */
    var forceVy: Boolean? = null; var forceShort: Boolean? = null; var forceEmoji: Boolean? = null
    var adapt = true

    init { load() }

    fun observe(msg: String) {
        if (msg.isBlank()) return
        val t = msg.lowercase()
        lens.addLast(msg.length); if (lens.size > 50) lens.removeFirst()
        // экспоненциальное «забывание», чтобы окно было ~50 сообщений
        if (n >= 50) { ty = ty * 49 / 50; vy = vy * 49 / 50; emo = emo * 49 / 50 } else n++
        if (Regex("(?iu)(?<![\\p{L}])(ты|тебя|тебе|твой|скажи|сделай|подскажи|напиши|расскажи|покажи|поставь|можешь|знаешь|помоги)(?![\\p{L}])").containsMatchIn(t)) ty += 2
        if (Regex("(?iu)(?<![\\p{L}])(вы|вас|вам|ваш|скажите|сделайте|подскажите|напишите|расскажите|покажите|поставьте|можете|знаете|помогите|здравствуйте)(?![\\p{L}])").containsMatchIn(t)) vy += 2
        if (Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]|[:;]-?[)(DP]|\\){2,}").containsMatchIn(msg)) emo++
        save()
    }

    fun style(): Style {
        val autoVy = vy > ty || (vy == 0 && ty == 0)   // по умолчанию — на «вы» (вежливо для старшего поколения)
        val avg = if (lens.isEmpty()) 40.0 else lens.average()
        val autoShort = lens.size >= 5 && avg < 35
        val autoEmoji = n >= 3 && emo * 5 >= n          // смайлики есть хотя бы в 20% сообщений
        return Style(forceVy ?: (if (adapt) autoVy else true), forceShort ?: (if (adapt) autoShort else false), forceEmoji ?: (if (adapt) autoEmoji else false))
    }

    fun apply(change: String) {
        when (change) { "short" -> forceShort = true; "long" -> forceShort = false; "ty" -> forceVy = false; "vy" -> forceVy = true; "noemoji" -> forceEmoji = false; "emoji" -> forceEmoji = true }
        save()
    }

    fun promptLine(name: String?): String {
        val s = style()
        return (if (s.vy) " Обращайся к пользователю на «вы»." else " Обращайся к пользователю на «ты».") +
            (if (s.short) " Отвечай очень коротко: 1–2 предложения." else "") +
            (if (s.emoji) " Можно изредка добавить эмодзи." else " Не используй эмодзи.") +
            (if (name != null) " Пользователя зовут $name — иногда обращайся по имени." else "")
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            ty = o.optInt("ty"); vy = o.optInt("vy"); emo = o.optInt("emo"); n = o.optInt("n"); adapt = o.optBoolean("adapt", true)
            forceVy = if (o.has("fvy")) o.getBoolean("fvy") else null
            forceShort = if (o.has("fshort")) o.getBoolean("fshort") else null
            forceEmoji = if (o.has("femoji")) o.getBoolean("femoji") else null
            o.optJSONArray("lens")?.let { a -> for (i in 0 until a.length()) lens.addLast(a.getInt(i)) }
        } catch (_: Exception) {}
    }

    fun save() {
        val f = file ?: return
        val o = JSONObject().put("ty", ty).put("vy", vy).put("emo", emo).put("n", n).put("adapt", adapt).put("lens", JSONArray(lens.toList()))
        forceVy?.let { o.put("fvy", it) }; forceShort?.let { o.put("fshort", it) }; forceEmoji?.let { o.put("femoji", it) }
        try { f.writeText(o.toString()) } catch (_: Exception) {}
    }
}
