package ru.pocketagent

/**
 * 2.4: фактические вопросы и вопросы о сущностях (места, люди, события, история) — только из источников.
 * Модель о фактах не говорит (0.5B/1.5B уверенно выдумывают организации и даты).
 * Свободный чат модели — для болтовни, советов, помощи с текстом.
 */
object Factual {
    private const val F = "(?iu)"
    private const val NB = "(?<![\\p{L}])"
    private const val NA = "(?![\\p{L}])"

    /** Болтовня, советы, помощь с текстом — сюда модель может отвечать сама. */
    private val CHAT = Regex("$F^\\s*(привет|здравствуй|добр(ое|ый)\\s+(утро|день|вечер)|спасибо|пока|как\\s+(у\\s+тебя\\s+)?дела|как\\s+ты|что\\s+делаешь|ты\\s+кто|кто\\s+ты|как\\s+тебя\\s+зовут)" +
        "|$NB(посоветуй|подскажи,?\\s+как\\s+(лучше|мне)|как\\s+(лучше|мне)\\s+|помоги\\s+(написать|сформулировать|придумать|составить|подобрать)|напиши\\s+(мне\\s+)?(поздравление|стих|письмо|текст|сообщение|тост)|придумай|сочини|перефразируй|исправь\\s+(ошибки|текст)|как\\s+сказать|как\\s+написать|пошути|анекдот|скучно|грустно|мне\\s+(плохо|одиноко))$NA")

    /** Явные формы «расскажи/что знаешь/что можешь сказать про…», «что такое», «кто такой», отсылки к прошлому ответу. */
    private val ASK = Regex("$F$NB(" +
        "что\\s+(ты\\s+)?(знаешь|можешь\\s+(сказать|рассказать)|скажешь|расскажешь|известно)\\s+(про|о|об|обо|насчёт|насчет)|" +
        "расскажи(те)?\\s+(мне\\s+)?(подробнее\\s+|побольше\\s+|ещё\\s+|еще\\s+)?(про|о|об|обо)|" +
        "(ты\\s+)?(начал|начала|начинал)\\s+(писать|говорить|рассказывать)\\s+(про|о|об|обо)|" +
        "(а\\s+)?(про|о|об)\\s+.{2,60}\\s+подробнее|подробнее\\s+(про|о|об)|" +
        "что\\s+(это\\s+)?за\\s+|что\\s+такое|кто\\s+так(ой|ая|ие)|кто\\s+(был|была|были)|чем\\s+(известен|известна|знаменит|знаменита)|" +
        "история\\s+[\\p{L}]|истори[юи]\\s+[\\p{L}]|когда\\s+(основан|построен|открыт|появил|был|была|было|родил|умер)|где\\s+находится|" +
        "в\\s+каком\\s+году|сколько\\s+лет\\s+(городу|музею|храму|зданию)" +
        ")")

    /** Слова о местах/людях/событиях: вопрос с ними — фактический, даже без «расскажи». */
    private val ENTITY_WORDS = Regex("$F$NB(музе[йяюеи]\\p{L}*|центр\\p{L}*|театр\\p{L}*|храм\\p{L}*|собор\\p{L}*|памятник\\p{L}*|площад\\p{L}*|улиц\\p{L}*|парк\\p{L}*|завод\\p{L}*|крепост\\p{L}*|дворц\\p{L}*|дворец|битв\\p{L}*|войн\\p{L}*|революци\\p{L}*|царь|царя|князь|князя|императ\\p{L}*|писател\\p{L}*|поэт\\p{L}*|учён\\p{L}*|ученый|президент\\p{L}*|город\\p{L}*|област\\p{L}*|река|реки|озеро|гора|остров\\p{L}*|страна|страны|организаци\\p{L}*|общество|партия|компани\\p{L}*)$NA")

    private val QWORD = Regex("$F$NB(кто|что|когда|где|откуда|сколько|какой|какая|какое|какие|каким|почему|зачем)$NA")

    private val CITY_ABBR = linkedMapOf(
        "екб" to "Екатеринбург", "ебург" to "Екатеринбург", "ёбург" to "Екатеринбург", "екат" to "Екатеринбург", "екатеринбурге" to "Екатеринбург",
        "спб" to "Санкт-Петербург", "питер" to "Санкт-Петербург", "питере" to "Санкт-Петербург", "мск" to "Москва", "москве" to "Москва",
        "нск" to "Новосибирск", "новосиб" to "Новосибирск", "челяба" to "Челябинск", "нн" to "Нижний Новгород", "казани" to "Казань",
    )

    /** Известные двусмысленные места: «центр истории екб» — это Ельцин Центр или Музей истории Екатеринбурга. */
    private data class Alias(val re: Regex, val city: String?, val names: List<String>)
    private val ALIASES = listOf(
        Alias(Regex("$F(центр\\p{L}*\\s+(истори\\p{L}*|ельцин\\p{L}*)|ельцин[\\s-]*центр\\p{L}*|музе\\p{L}*\\s+истори\\p{L}*)"), "Екатеринбург",
            listOf("Ельцин Центр", "Музей истории Екатеринбурга")),
        Alias(Regex("$F(плотинк\\p{L}*)"), "Екатеринбург", listOf("Плотина городского пруда на реке Исеть")),
        Alias(Regex("$F(храм\\p{L}*\\s+на\\s+крови)"), "Екатеринбург", listOf("Храм на Крови (Екатеринбург)")),
        Alias(Regex("$F(эрмитаж\\p{L}*)"), null, listOf("Государственный Эрмитаж")),
    )

    /** «а что про него можешь сказать», «расскажи о нём подробнее», «а ещё?» — отсылка к прошлой теме. */
    private val PRON = "(него|неё|нее|нём|нем|ней|них|это|этом|этого|этой|ним|ней)"
    private val FOLLOW = Regex("$F^\\s*(а\\s+|и\\s+|ну\\s+)?(" +
        "(что|чего)\\s+(ещё\\s+|еще\\s+)?(ты\\s+)?(про|о|об|обо)\\s+$PRON|" +
        "(что|чего)\\s+(ещё\\s+|еще\\s+)?(можешь\\s+(сказать|рассказать)|знаешь|расскажешь|известно)\\s+(про|о|об|обо)\\s+$PRON|" +
        "расскажи(те)?\\s+(ещё\\s+|еще\\s+)?((про|о|об|обо)\\s+$PRON|подробнее|побольше|ещё|еще)|" +
        "(а\\s+)?подробнее|продолжай|дальше)")
    fun isFollowUp(text: String) = FOLLOW.containsMatchIn(text.trim())

    fun isChat(text: String) = CHAT.containsMatchIn(text.trim())

    /** Это фактический вопрос / вопрос о сущности (или отсылка к прошлому ответу)? */
    fun isFactual(text: String, hasTopic: Boolean = false): Boolean {
        val t = text.trim()
        if (hasTopic && isFollowUp(t)) return true
        if (t.length < 5) return false
        if (ASK.containsMatchIn(t)) return true
        if (isChat(t)) return false
        if (WebSearch.looksFactual(t)) return true
        // «Ельцин центр?», «а музей истории екб» — вопрос с местом/человеком/событием
        val isQuestion = t.endsWith("?") || QWORD.containsMatchIn(t)
        if (isQuestion && (ENTITY_WORDS.containsMatchIn(t) || cityIn(t) != null)) return true
        // собственное имя не в начале фразы + вопрос: «а что Демидовы сделали?»
        if (isQuestion && Regex("(?u)[\\p{Ll},]\\s+\\p{Lu}\\p{Ll}{2,}").containsMatchIn(t)) return true
        return false
    }

    /** Город из сокращений/форм в тексте («екб» → Екатеринбург). */
    fun cityIn(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}ё-]+")).filter { it.isNotBlank() }
        for (w in words) CITY_ABBR[w]?.let { return it }
        return null
    }

    fun genitive(city: String): String {
        val parts = city.split(" ")
        fun g(w: String) = when {
            w.endsWith("а") -> w.dropLast(1) + (if (w.dropLast(1).lastOrNull() in "гкхжчшщ".toList()) "и" else "ы")
            w.endsWith("ь") -> w.dropLast(1) + "и"
            w.endsWith("ий") -> w.dropLast(2) + "его"
            w.endsWith("й") -> w.dropLast(1) + "я"
            w.endsWith("о") || w.endsWith("и") || w.endsWith("е") -> w
            else -> w + "а"
        }
        return if (parts.size == 2 && parts[0].endsWith("ий")) g(parts[0]) + " " + g(parts[1]) else parts.joinToString(" ") { if (it.contains('-')) it.split('-').let { p -> p.dropLast(1).joinToString("-") + "-" + g(p.last()) } else g(it) }
    }

    private val PRONOUN_ONLY = Regex("$F^(он|она|оно|они|него|неё|нее|нём|нем|ней|них|это|этом|этого|этой|этот|эта|тот|том|той|там|него\\s+подробнее|подробнее)$")
    private val LEAD = Regex("$F^(\\s*(а|и|ну|слушай|кстати|скажи|подскажи)[,]?\\s+)*(" +
        "(ты\\s+)?(начал|начала|начинал)\\s+(писать|говорить|рассказывать)\\s+(про|о|об|обо)|" +
        "что\\s+(ты\\s+)?(знаешь|можешь\\s+(сказать|рассказать)|скажешь|расскажешь|известно)\\s+(про|о|об|обо|насчёт|насчет)|" +
        "расскажи(те)?\\s+(мне\\s+)?(подробнее\\s+|побольше\\s+|ещё\\s+|еще\\s+)?(про|о|об|обо)|подробнее\\s+(про|о|об)|(про|о|об)|" +
        "что\\s+(это\\s+)?за|что\\s+такое|кто\\s+так(ой|ая|ие)|кто\\s+(был|была|были)|чем\\s+(известен|известна|знаменит|знаменита)|история|историю|где\\s+находится" +
        ")\\s+")

    /** Сущность из вопроса; местоимение («что про него можешь сказать») → тема прошлого ответа. */
    fun entity(text: String, previousTopic: String?): String? {
        val clauses = text.split(Regex("[,;!?]|\\s+и\\s+(что|расскажи)")).map { it.trim() }.filter { it.isNotBlank() }
        for (c in clauses) {
            val e = clean(c)
            if (e != null && !PRONOUN_ONLY.matches(e)) return e
        }
        return previousTopic?.takeIf { it.isNotBlank() }
    }

    private fun clean(clause: String): String? {
        var t = clause.trim().trimEnd('.', ' ')
        val before = t
        t = t.replace(LEAD, "")
        t = t.replace(Regex("$F\\s+(подробнее|поподробнее|побольше|можешь\\s+сказать|можешь\\s+рассказать|расскажи|знаешь)$"), "").trim()
        t = t.replace(Regex("$F^(а\\s+|и\\s+|ну\\s+)?((что|чего)\\s+)?(ещё\\s+|еще\\s+)?(ты\\s+)?(про|о|об|обо)\\s+"), "").trim()
        t = t.replace(Regex("$F(\\s+(ещё|еще|вообще|такого|интересного))+(?=\\s|$)"), "").trim()
        t = t.replace(Regex("$F\\s+(можешь\\s+сказать|можешь\\s+рассказать|скажешь|расскажешь|знаешь|известно)$"), "").trim()
        if (FOLLOW.matches(clause.trim().trimEnd('.', '?', '!', ' '))) return null
        if (t == before && QWORD.containsMatchIn(t.split(" ").first())) {
            // «что про него можешь сказать», «когда основан …» — уже без ведущих слов
            t = t.replace(Regex("$F^(что|кто|когда|где|как)\\s+(про|о|об)?\\s*"), "").replace(Regex("$F\\s*(можешь\\s+сказать|можешь\\s+рассказать|скажешь|знаешь)$"), "").trim()
        }
        if (t.length < 2) return null
        return t
    }

    /** Двусмысленное известное место → несколько названий (ищем и цитируем все). */
    fun alias(entity: String, userCity: String?): List<String>? {
        val city = cityIn(entity) ?: userCity?.let { normCity(it) }
        return ALIASES.firstOrNull { it.re.containsMatchIn(entity) && (it.city == null || city == null || it.city == city) }?.names
    }

    /** Варианты запросов для поиска: алиасы, расшифровка «екб», город пользователя для местных мест. */
    fun queries(entity: String, userCity: String?): List<String> {
        val city = cityIn(entity) ?: userCity?.let { normCity(it) }
        alias(entity, userCity)?.let { return it }
        var e = entity
        // убрать сокращение города из сущности и добавить полное название
        CITY_ABBR.keys.forEach { k -> e = e.replace(Regex("$F$NB$k$NA"), "").trim() }
        e = e.replace(Regex("\\s+"), " ").trim()
        val local = ENTITY_WORDS.containsMatchIn(e) && !Regex("$F(страна|страны|президент|войн|революци|царь|императ)").containsMatchIn(e)
        return when {
            city != null && cityIn(entity) != null -> listOf("$e ${genitive(city)}".trim(), "$e $city".trim())
            city != null && local -> listOf(e, "$e ${genitive(city)}")
            else -> listOf(e)
        }.distinct().filter { it.isNotBlank() }
    }

    /** «Екатеринбурге» (из памяти «Живу в Екатеринбурге») → «Екатеринбург». */
    fun normCity(c: String): String {
        val w = c.trim()
        CITY_ABBR[w.lowercase()]?.let { return it }
        return when {
            w.endsWith("бурге") || w.endsWith("граде") || w.endsWith("ске") || w.endsWith("ове") || w.endsWith("еве") || w.endsWith("ине") -> w.dropLast(1)
            w.endsWith("ане") || w.endsWith("ани") -> w.dropLast(1) + "ь"
            w.endsWith("ве") -> w.dropLast(1) + "а"
            else -> w
        }
    }

    // ---------- честные ответы без источника

    const val NO_NET = "Точно не знаю — нет интернета, а сам могу ошибиться."
    const val NOT_FOUND = "Точно не знаю — в источниках не нашёл, а сам могу ошибиться."
    const val SEARCH_OFF = "Точно не знаю — поиск в интернете выключен, а сам могу ошибиться. Включить: меню → «Настройки» → «Поиск в интернете»."
    const val INSIST_HINT = "\nЕсли всё же нужно моё предположение — скажите «предположи»."
    private val INSIST = Regex("$F^\\s*(ну\\s+)?(всё\\s+равно|все\\s+равно|предположи|угадай|как\\s+думаешь|а\\s+ты\\s+как\\s+думаешь|ну\\s+скажи|скажи\\s+хоть\\s+что-?нибудь|хотя\\s+бы\\s+примерно|примерно|давай\\s+(без\\s+интернета|сам)|ответь\\s+сам)")
    fun isInsist(text: String) = INSIST.containsMatchIn(text) && text.length < 60

    fun hedgePrompt(topic: String) = "Пользователь просит предположение без интернета о теме «$topic». Ответь ОДНИМ коротким предложением, начни со слов «Не уверен, но». " +
        "Не придумывай названий организаций, дат и чисел, если не уверен; лучше скажи общее."

    /** Ответ цитатами из найденных источников. */
    fun answer(results: List<Pair<String, WebSearch.Result>>): String {
        if (results.size == 1) {
            val r = results[0].second
            val quote = r.direct ?: WebSearch.firstSentences(r.text, 2, 400)
            return if (quote.trimStart().startsWith("По данным")) quote else "По данным ${source(r.source)}: «${quote.trim().trim('«', '»')}»"
        }
        val sb = StringBuilder("Похоже, речь об одном из этих мест:\n")
        results.forEach { (name, r) ->
            val q = (r.direct ?: WebSearch.firstSentences(r.text, 2, 400)).trim().trim('«', '»')
            sb.append("\n• $name — «").append(WebSearch.firstSentences(q, 2, 320)).append("» (${r.source})")
        }
        return sb.toString()
    }

    private fun source(s: String) = when (s) { "Википедия" -> "Википедии"; else -> s }
}
