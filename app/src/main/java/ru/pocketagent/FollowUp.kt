package ru.pocketagent

import kotlin.math.roundToLong

/**
 * Короткие уточнения после ответа с поиском: «а в рублях?», «а в евро», «а завтра?», «а в Казани?», «а там?», «а сколько …».
 * Берём предыдущий запрос/тему; суммы переводим по курсу ЦБ, а не просим модель «прикинуть».
 */
object FollowUp {
    private const val F = "(?iu)"

    /** Что помним о последнем ответе с поиском. */
    data class Ctx(val query: String, val kind: WebSearch.Kind, val amounts: List<WebSearch.Price>)

    sealed class Res {
        /** Перевести найденную сумму в валюту target. */
        data class Convert(val target: String) : Res()
        /** Повторить поиск с уточнённым запросом. */
        data class Requery(val query: String) : Res()
    }

    fun currencyOf(word: String): String? {
        val t = word.lowercase()
        return when {
            t.startsWith("рубл") || t.startsWith("руб") || t == "₽" -> "RUB"
            t.startsWith("доллар") || t.startsWith("долл") || t == "$" || t == "usd" -> "USD"
            t.startsWith("евро") || t == "eur" || t == "€" -> "EUR"
            t.startsWith("юан") || t == "cny" -> "CNY"
            else -> null
        }
    }

    private val CUR = "(рубл[\\p{L}]*|руб\\.?|доллар[\\p{L}]*|долл\\.?|евро|юан[\\p{L}]*|usd|eur|₽|\\$|€)"

    /** «а в рублях», «сколько это в евро?», «переведи в доллары» → целевая валюта. */
    fun currencyFollowUp(text: String): String? {
        val t = text.trim().trimEnd('?', '!', '.', ' ')
        if (t.split(Regex("\\s+")).size > 6) return null
        val m = Regex("$F^(а\\s+|и\\s+)?(сколько\\s+)?(это\\s+|будет\\s+|это\\s+будет\\s+)?(переведи\\s+|пересчитай\\s+)?(в|во)\\s+$CUR$").find(t) ?: return null
        return currencyOf(m.groupValues[6])
    }

    /** Явное «1500 долларов в рублях» — без контекста. */
    fun explicitConversion(text: String): Pair<WebSearch.Price, String>? {
        val t = text.trim().trimEnd('?', '!', '.', ' ')
        val m = Regex("$F^(сколько\\s+(будет\\s+)?|переведи\\s+)?(\\d[\\d\\s]*(?:[.,]\\d+)?)\\s*$CUR\\s+(в|во)\\s+$CUR$").find(t) ?: return null
        val amount = m.groupValues[3].replace(Regex("\\s"), "").replace(',', '.').toDoubleOrNull() ?: return null
        val from = currencyOf(m.groupValues[4]) ?: return null
        val to = currencyOf(m.groupValues[6]) ?: return null
        if (from == to) return null
        return WebSearch.Price(amount, from, m.groupValues[3].trim() + " " + m.groupValues[4]) to to
    }

    /** Разбор уточнения. null — это не уточнение (или нечего уточнять). */
    fun resolve(text: String, ctx: Ctx?): Res? {
        if (ctx == null) return null
        val t = text.trim().trimEnd('?', '!', '.', ' ')
        val low = t.lowercase()
        if (t.split(Regex("\\s+")).size > 7) return null
        currencyFollowUp(t)?.let { return Res.Convert(it) }
        val base = ctx.query.trim().trimEnd('?', '!', '.', ' ')
        // «а там?» / «а ещё?» — повторить тот же запрос
        if (Regex("$F^(а|и)\\s+(там|тогда|ещё|еще)$").matches(low)) return Res.Requery(base)
        // «а завтра?», «а послезавтра», «а сегодня»
        Regex("$F^(а|и)\\s+(завтра|послезавтра|сегодня|сейчас|на\\s+выходных)$").find(low)?.let { m ->
            val w = m.groupValues[2]
            val stripped = base.replace(Regex("$F(?<![\\p{L}])(завтра|послезавтра|сегодня|сейчас|на\\s+выходных)(?![\\p{L}])"), " ").replace(Regex("\\s+"), " ").trim()
            return Res.Requery("$stripped $w".trim())
        }
        // «а в Казани?» — для погоды/новостей меняем место
        Regex("$F^(а|и)\\s+(в|во|на)\\s+([\\p{L}][\\p{L}\\- ]{1,40})$").find(t)?.let { m ->
            val place = m.groupValues[2] + " " + m.groupValues[3]
            return when (ctx.kind) {
                WebSearch.Kind.WEATHER, WebSearch.Kind.NEWS -> {
                    val noPlace = base.replace(Regex("$F\\s+(в|во|на)\\s+[\\p{L}\\-]+(\\s+[\\p{L}\\-]+)?$"), "").trim()
                    val word = if (ctx.kind == WebSearch.Kind.WEATHER) (if (noPlace.isBlank()) "погода" else noPlace) else (if (noPlace.isBlank()) "новости" else noPlace)
                    Res.Requery("$word $place")
                }
                else -> Res.Requery("$base $place")
            }
        }
        // «а сколько …», «а когда …», «а где …» — тот же предмет
        Regex("$F^а\\s+(сколько|когда|где|кто|какой|какая|какое|какие)(\\s+.*)?$").find(t)?.let { m ->
            val subject = when (ctx.kind) {
                WebSearch.Kind.PRICE -> base.replace(Regex("$F\\s*цена$"), "")
                else -> WebSearch.topic(base)
            }
            val rest = (m.groupValues[1] + m.groupValues[2]).trim()
            return Res.Requery("$rest $subject".trim())
        }
        return null
    }

    fun fmt(v: Double): String {
        val r = if (v >= 100) v.roundToLong().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
        val parts = r.split('.')
        val int = parts[0].reversed().chunked(3).joinToString("\u00A0").reversed()
        return if (parts.size > 1) int + "," + parts[1] else int
    }

    fun sign(code: String) = when (code) { "RUB" -> "руб."; "USD" -> "$"; "EUR" -> "€"; "CNY" -> "юаней"; else -> code }

    /** Перевод суммы по курсам ЦБ (рублей за единицу). null — нет курса. */
    fun convert(p: WebSearch.Price, target: String, rates: Map<String, Double>): Double? {
        val from = rates[p.currency] ?: return null
        val to = rates[target] ?: return null
        if (to <= 0) return null
        return p.amount * from / to
    }

    fun conversionText(p: WebSearch.Price, target: String, rates: Map<String, Double>, date: String = ""): String {
        val v = convert(p, target, rates) ?: return "Не нашёл курс для перевода."
        val rate = if (p.currency != "RUB") rates[p.currency] else rates[target]
        val rateTxt = if (rate != null) " (курс ЦБ РФ${if (date.isNotBlank()) " на $date" else ""}: 1 ${sign(if (p.currency != "RUB") p.currency else target)} = ${fmt(rate)} руб.)" else ""
        return "${fmt(p.amount)} ${sign(p.currency)} ≈ ${fmt(v)} ${sign(target)}$rateTxt"
    }
}
