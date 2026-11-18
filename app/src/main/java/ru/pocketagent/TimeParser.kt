package ru.pocketagent

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Разбор русских выражений времени: «через 10 минут», «завтра в 8 утра», «в 23:00», «на 23:00»,
 * «каждый будний день в 7», «в половине восьмого», «в пятницу в 10», «15 ноября в 9», «вечером».
 * Чистый Kotlin (java.time доступен с API 26) — покрыт юнит-тестами.
 * ВАЖНО: только (?iu) и явные классы букв (ICU на Android).
 */
object TimeParser {
    private const val F = "(?iu)"
    private const val L = "[\\p{L}\\p{N}_]"          // «буква слова»
    private const val NB = "(?<![\\p{L}\\p{N}_])"     // начало слова
    private const val NA = "(?![\\p{L}\\p{N}_])"      // конец слова

    data class When(
        val at: LocalDateTime,              // ближайшее срабатывание
        val days: Set<DayOfWeek> = emptySet(),   // повтор по дням недели (пусто — однократно)
        val hasExplicitTime: Boolean,
        val relative: Boolean,              // «через …»
        val rest: String,                   // текст без временных выражений
        val strong: Boolean = true          // явное время («23:00», «8 утра», «завтра», «через») — не просто «в 7»
    ) {
        val hour get() = at.hour
        val minute get() = at.minute
    }

    private val numWords = linkedMapOf(
        "двадцать три" to 23, "двадцать два" to 22, "двадцать один" to 21, "двадцать одну" to 21,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "одну" to 1, "один" to 1, "одна" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10
    )
    private val ordinals = mapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвертого" to 4, "четвёртого" to 4, "пятого" to 5,
        "шестого" to 6, "седьмого" to 7, "восьмого" to 8, "девятого" to 9, "десятого" to 10,
        "одиннадцатого" to 11, "двенадцатого" to 12
    )
    private val months = mapOf(
        "январ" to 1, "феврал" to 2, "март" to 3, "апрел" to 4, "ма" to 5, "июн" to 6, "июл" to 7,
        "август" to 8, "сентябр" to 9, "октябр" to 10, "ноябр" to 11, "декабр" to 12
    )
    private val weekdays = listOf(
        "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "сред" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятниц" to DayOfWeek.FRIDAY, "суббот" to DayOfWeek.SATURDAY,
        "воскресень" to DayOfWeek.SUNDAY
    )
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** Числительные словами → цифры («в семь» → «в 7», «через пять минут» → «через 5 минут»). */
    fun normalizeNumbers(s: String): String {
        var t = " " + s.lowercase().replace('ё', 'е') + " "
        for ((w, n) in numWords) t = t.replace(Regex("$F$NB${w.replace("ё", "е")}$NA"), n.toString())
        return t
    }

    private class Cut(var s: String) {
        /** Находит первое совпадение, вырезает его из строки и возвращает. */
        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
            return m
        }
    }

    /**
     * @param preferMorning для будильников: «в 7» без «утра/вечера» = 07:00; для напоминаний — ближайшее из 7:00/19:00.
     */
    fun parse(text: String, now: LocalDateTime, preferMorning: Boolean = false): When? {
        val c = Cut(normalizeNumbers(text))
        var days: Set<DayOfWeek> = emptySet()
        var dayOffset: Int? = null
        var date: LocalDate? = null
        var weekday: DayOfWeek? = null
        var h: Int? = null; var m = 0
        var period: String? = null    // утро / день / вечер / ночь
        var found = false
        var strong = false

        // --- повторы
        c.take(Regex("$F$NB(?:каждый\\s+будний\\s+день|каждые\\s+будни|по\\s+будням|в\\s+будни|по\\s+рабочим\\s+дням|каждый\\s+рабочий\\s+день|по\\s+будним\\s+дням)$NA"))
            ?.let { days = WEEKDAYS; found = true }
        if (days.isEmpty()) c.take(Regex("$F$NB(?:по\\s+выходным|в\\s+выходные|каждые\\s+выходные|по\\s+выходным\\s+дням)$NA"))
            ?.let { days = WEEKEND; found = true }
        if (days.isEmpty()) c.take(Regex("$F$NB(?:каждый\\s+день|ежедневно|каждые\\s+сутки)$NA"))
            ?.let { days = DayOfWeek.values().toSet(); found = true }
        if (days.isEmpty()) c.take(Regex("$F$NB(?:каждое|каждый|каждую)\\s+(утро|вечер|ночь)$NA"))?.let {
            days = DayOfWeek.values().toSet(); found = true
            period = when (it.groupValues[1]) { "утро" -> "утр"; "вечер" -> "вечер"; else -> "ноч" }
        }
        if (days.isEmpty()) {
            for ((w, d) in weekdays) {
                val mm = c.take(Regex("$F$NB(?:каждый|каждую|каждое|по)\\s+$w$L*")) ?: continue
                days = setOf(d); found = true; mm.value; break
            }
        }

        // --- «через …»
        var relative = false
        var relSeconds = 0
        c.take(Regex("$F$NB(?:через|спустя)\\s+((?:пару\\s+|несколько\\s+)?(?:полчаса|полтора\\s+часа|часик|час|минуту|минутку|секунду|" +
                "\\d+(?:[.,]\\d+)?\\s*(?:час$L*|ч$NA|минут$L*|мин$L*|секунд$L*|сек$L*)(?:\\s+(?:и\\s+)?\\d+\\s*(?:минут$L*|мин$L*))?))"))?.let { mm ->
            val body = mm.groupValues[1]
            val sec = when {
                body.startsWith("пару минут") -> 120
                body.startsWith("несколько минут") -> 300
                body == "часик" -> 3600
                body.startsWith("полтора") -> 5400
                else -> Router.parseDuration(body) ?: 0
            }
            if (sec > 0) { relative = true; relSeconds = sec; found = true }
        }

        // --- день
        c.take(Regex("$F$NB(послезавтра|завтра|сегодня)$NA"))?.let {
            dayOffset = when (it.groupValues[1]) { "сегодня" -> 0; "завтра" -> 1; else -> 2 }; found = true
        }
        if (dayOffset == null) c.take(Regex("$F$NB(\\d{1,2})\\s+(январ|феврал|марта|апрел|мая|июн|июл|август|сентябр|октябр|ноябр|декабр)$L*"))?.let {
            val d = it.groupValues[1].toInt()
            val mon = months.entries.first { e -> it.groupValues[2].startsWith(e.key) }.value
            try {
                var dt = LocalDate.of(now.year, mon, d)
                if (dt.isBefore(now.toLocalDate())) dt = dt.plusYears(1)
                date = dt; found = true
            } catch (_: Exception) { }
        }
        if (dayOffset == null && date == null && days.isEmpty()) {
            for ((w, d) in weekdays) {
                if (c.take(Regex("$F$NB(?:в|во)\\s+$w$L*")) != null) { weekday = d; found = true; break }
            }
        }

        // --- время суток
        fun setPeriod(p: String) { period = when { p.startsWith("утр") -> "утр"; p.startsWith("дн") || p.startsWith("днем") || p.startsWith("днём") -> "дн"; p.startsWith("веч") -> "вечер"; else -> "ноч" } }
        val periodRe = "(утра|дня|вечера|ночи)"
        // «в половине восьмого (утра)»
        c.take(Regex("$F$NB(?:в\\s+)?половин[\\p{L}]*\\s+(${ordinals.keys.joinToString("|")})(?:\\s+$periodRe)?$NA"))?.let {
            val n = ordinals[it.groupValues[1]] ?: 1
            h = (n - 1 + 24) % 24; m = 30; found = true; strong = true
            if (it.groupValues[2].isNotEmpty()) setPeriod(it.groupValues[2])
        }
        // «в 23:00», «на 7.30», «к 9:15», «в 7 30» (точка и пробел — только после предлога; двоеточие — всегда)
        for (re in listOf(
            Regex("$F$NB(?:в|во|на|к|ко|около)\\s*(\\d{1,2})[:. ](\\d{2})(?![\\d])(?!\\s*(?:минут|мин|сек|%|раз))(?:\\s*(?:час$L*))?(?:\\s+$periodRe)?$NA"),
            Regex("$F(?<![\\d.,:])(\\d{1,2}):(\\d{2})(?![\\d])(?:\\s+$periodRe)?$NA"))) {
            if (h != null) break
            val mm = re.find(c.s) ?: continue
            val hh = mm.groupValues[1].toInt(); val mi = mm.groupValues[2].toInt()
            if (hh in 0..24 && mi in 0..59) {
                c.take(re); h = hh % 24; m = mi; found = true; strong = true
                if (mm.groupValues[3].isNotEmpty()) setPeriod(mm.groupValues[3])
            }
        }
        // «в 8 утра», «в 7 часов 30 минут», «в 7», «на 7 утра», «в 10 вечера»
        if (h == null) {
            val re = Regex("$F$NB(в|во|на|к|ко|около)\\s+(\\d{1,2})(?![\\d:.,])(?:\\s*(час$L*|ч$NA))?(?:\\s+(\\d{1,2})\\s*(?:минут$L*|мин$L*))?(?:\\s+$periodRe)?$NA(?!\\s*(?:минут|мин|сек|%|раз|процент|рубл|штук|километр|метр|градус))")
            val mm = re.find(c.s)
            if (mm != null) {
                val prep = mm.groupValues[1]; val hasHourWord = mm.groupValues[3].isNotEmpty()
                val hasPeriod = mm.groupValues[5].isNotEmpty()
                // «на 2 часа» — это длительность, а не время
                val isDuration = prep == "на" && hasHourWord && !hasPeriod
                val hh = mm.groupValues[2].toInt()
                if (!isDuration && hh in 0..24) {
                    c.take(re); h = hh % 24; m = mm.groupValues[4].toIntOrNull()?.takeIf { it in 0..59 } ?: 0; found = true
                    if (hasPeriod) setPeriod(mm.groupValues[5])
                    if (hasPeriod || hasHourWord) strong = true
                }
            }
        }
        // «8 утра» без предлога
        if (h == null) c.take(Regex("$F$NB(\\d{1,2})\\s+$periodRe$NA"))?.let {
            h = it.groupValues[1].toInt() % 24; found = true; strong = true; setPeriod(it.groupValues[2])
        }
        c.take(Regex("$F$NB(?:в\\s+)?(полдень|полночь)$NA"))?.let { h = if (it.groupValues[1] == "полдень") 12 else 0; m = 0; found = true; strong = true }
        // «утром», «вечером», «днём», «ночью» (без конкретного часа)
        c.take(Regex("$F$NB(утром|днем|днём|вечером|ночью|с\\s+утра)$NA"))?.let {
            val p = it.groupValues[1]
            if (period == null) setPeriod(if (p.startsWith("с ")) "утр" else p)
            found = true
        }

        if (!found) return null
        if (relative || days.isNotEmpty() || dayOffset != null || date != null || weekday != null) strong = true
        val rest = cleanup(c.s)

        if (relative && h == null) {
            val at = now.plusSeconds(relSeconds.toLong()).withNano(0)
            return When(at, emptySet(), hasExplicitTime = true, relative = true, rest = rest, strong = true)
        }

        val explicit = h != null
        var hour = h ?: when (period) { "утр" -> 9; "дн" -> 13; "вечер" -> 19; "ноч" -> 23; else -> 9 }
        if (h != null) hour = applyPeriod(hour, period)
        val ambiguous = h != null && period == null && hour in 1..11 && !preferMorning

        fun at(d: LocalDate, hr: Int) = LocalDateTime.of(d, LocalTime.of(hr, m))
        val today = now.toLocalDate()

        val result: LocalDateTime = when {
            days.isNotEmpty() -> nextOccurrence(now, hour, m, days)
            date != null -> at(date!!, hour)
            dayOffset != null -> at(today.plusDays(dayOffset!!.toLong()), hour)
            weekday != null -> {
                var d = today
                while (d.dayOfWeek != weekday || !at(d, hour).isAfter(now)) d = d.plusDays(1)
                at(d, hour)
            }
            else -> {
                // только время: ближайшее будущее; «в 7» без уточнения — 7:00 или 19:00, что раньше наступит
                val cands = if (ambiguous) listOf(hour, hour + 12) else listOf(hour)
                cands.flatMap { hr -> listOf(at(today, hr), at(today.plusDays(1), hr)) }
                    .filter { it.isAfter(now) }.minOrNull()!!
            }
        }
        return When(result, days, explicit, relative = false, rest = rest, strong = strong)
    }

    private fun applyPeriod(h: Int, p: String?): Int = when (p) {
        "утр" -> if (h == 12) 0 else h
        "дн" -> if (h in 1..6) h + 12 else h
        "вечер" -> if (h in 1..11) h + 12 else h
        "ноч" -> if (h == 12) 0 else if (h in 8..11) h + 12 else h
        else -> h
    }

    fun nextOccurrence(now: LocalDateTime, hour: Int, minute: Int, days: Set<DayOfWeek>): LocalDateTime {
        var d = now.toLocalDate()
        repeat(8) {
            val t = LocalDateTime.of(d, LocalTime.of(hour, minute))
            if ((days.isEmpty() || d.dayOfWeek in days) && t.isAfter(now)) return t
            d = d.plusDays(1)
        }
        return LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.of(hour, minute))
    }

    private fun cleanup(s: String): String = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', '!', '?', ':', '-', '—', ' ')

    // ---------- человекочитаемый вывод
    private val ru = Locale("ru")

    fun hm(t: LocalDateTime) = "%d:%02d".format(t.hour, t.minute)

    fun daysText(days: Set<DayOfWeek>): String = when {
        days.size == 7 -> "каждый день"
        days == WEEKDAYS -> "по будням"
        days == WEEKEND -> "по выходным"
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, ru) }
    }

    /** «сегодня в 23:00», «завтра в 8:00», «в пятницу, 20 ноября, в 10:00», «по будням в 7:00». */
    fun human(at: LocalDateTime, now: LocalDateTime, days: Set<DayOfWeek> = emptySet()): String {
        if (days.isNotEmpty()) return "${daysText(days)} в ${hm(at)}"
        val d = at.toLocalDate(); val today = now.toLocalDate()
        val dayStr = when (d) {
            today -> "сегодня"
            today.plusDays(1) -> "завтра"
            today.plusDays(2) -> "послезавтра"
            else -> {
                val wd = d.dayOfWeek.getDisplayName(TextStyle.FULL, ru)
                val mon = d.month.getDisplayName(TextStyle.FULL, ru)   // «ноября» (родительный в java.time FULL для ru)
                "$wd, ${d.dayOfMonth} $mon"
            }
        }
        return "$dayStr в ${hm(at)}"
    }

    /** Битовая маска дней (Пн=бит0 … Вс=бит6) — для хранения. */
    fun toMask(days: Set<DayOfWeek>) = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
    fun fromMask(mask: Int) = DayOfWeek.values().filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
}
