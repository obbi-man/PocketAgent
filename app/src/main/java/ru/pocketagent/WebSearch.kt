package ru.pocketagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Необязательный поиск в интернете («погуглить»): Википедия (ru REST API), DuckDuckGo HTML,
 * погода (wttr.in), курс валют (ЦБ РФ через cbr-xml-daily.ru). Таймаут 8 с.
 * Найденные фрагменты отдаются модели как контекст; ответ — кратко и со ссылкой на источник.
 */
object WebSearch {
    private const val F = "(?iu)"
    const val TIMEOUT_MS = 8000
    private const val UA = "PocketAgent/2.2 (Android; offline assistant; +https://github.com/)"

    /** direct — готовый точный ответ без модели (маленькая модель склонна дописывать выдумки). */
    data class Result(val source: String, val url: String, val text: String, val direct: String? = null,
                      val amounts: List<Price> = emptyList(), val kind: Kind = Kind.FACT, val query: String = "")
    enum class Kind { FACT, PRICE, WEATHER, CURRENCY, NEWS }
    /** Сумма, найденная в тексте источника. currency: RUB / USD / EUR / CNY. */
    data class Price(val amount: Double, val currency: String, val raw: String)
    /** Один результат поисковой выдачи. */
    data class Item(val title: String, val snippet: String, val url: String)

    // ---------- цены: только то, что есть в тексте выдачи, никаких «примерно» от модели

    fun isPriceQuery(q: String) = Regex("$F(сколько\\s+стоит|сколько\\s+стоят|(?<![\\p{L}])цен[аыуе](?![\\p{L}])|стоимост|почём|почем|(?<![\\p{L}])price)").containsMatchIn(q)

    /** «Сколько стоит 4060» → «RTX 4060 цена»: короткие коды видеокарт и т. п. — в понятный поисковику запрос. */
    fun expandPriceQuery(q: String): String {
        var p = q.trim().trimEnd('?', '!', '.', ' ')
        p = p.replace(Regex("$F^(а\\s+)?(скажи|подскажи|узнай)?[,\\s]*(сколько\\s+(сейчас\\s+)?сто(ит|ят)|какая\\s+цена(\\s+(на|у))?|цена(\\s+(на|у))?|стоимость|почём|почем)\\s*"), "")
        p = p.replace(Regex("$F\\s*(цена|стоимость|сколько\\s+стоит)$"), "").trim()
        val gpu = Regex("$F^(rtx|gtx|rx)?\\s*((?:10|16|20|30|40|50)[5-9]0)\\s*(ti|super)?$").find(p)
        if (gpu != null) {
            val num = gpu.groupValues[2]; val suffix = gpu.groupValues[3].let { if (it.isBlank()) "" else " " + it.replaceFirstChar(Char::uppercase).let { s -> if (s.equals("ti", true)) "Ti" else "Super" } }
            val brand = gpu.groupValues[1].ifBlank { if (num.startsWith("10") || num.startsWith("16")) "GTX" else "RTX" }.uppercase()
            p = "$brand $num$suffix"
        }
        return if (p.isBlank()) q.trim() else "$p цена"
    }

    private const val NUM = "(?:\\d{1,3}(?:[\\u00A0\\u202F ,]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?(?!\\d)"
    private val PRICE_RE = Regex(
        "$F(?:(\\$|€|₽)\\s?($NUM))|" +
        "(?:(?<![\\d.,])($NUM)\\s?(₽|руб(?:л[\\p{L}]*)?\\.?|р\\.(?![\\p{L}])|\\$|usd|долл[\\p{L}]*\\.?|€|eur|евро|юан[\\p{L}]*)(?![\\p{L}]))")

    private fun parseNum(n: String): Double? {
        val t = n.replace(Regex("[\\u00A0\\u202F ]"), "")
        val v = if (Regex("^\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?$").matches(t)) t.replace(",", "") else t.replace(',', '.')
        return v.toDoubleOrNull()
    }

    fun currencyCode(w: String): String? {
        val t = w.lowercase()
        return when {
            t == "₽" || t.startsWith("руб") || t == "р." || t.startsWith("рубл") -> "RUB"
            t == "$" || t == "usd" || t.startsWith("долл") || t.startsWith("доллар") -> "USD"
            t == "€" || t == "eur" || t == "евро" -> "EUR"
            t.startsWith("юан") || t == "cny" -> "CNY"
            else -> null
        }
    }

    /** Все суммы с валютой из текста (только явно написанные — «4060» без валюты ценой не считается). */
    fun extractPrices(text: String): List<Price> = PRICE_RE.findAll(text).mapNotNull { m ->
        val g = m.groupValues
        val (num, cur) = if (g[1].isNotEmpty()) g[2] to g[1] else g[3] to g[4]
        val code = currencyCode(cur.trimEnd('.')) ?: currencyCode(cur) ?: return@mapNotNull null
        val v = parseNum(num) ?: return@mapNotNull null
        if (v <= 0.0) null else Price(v, code, m.value.trim())
    }.toList()

    /** Фраза из сниппета вокруг суммы (чтобы цитировать источник, а не пересказывать). */
    private fun sentenceAround(text: String, raw: String): String {
        val i = text.indexOf(raw).coerceAtLeast(0)
        var a = i; while (a > 0 && text[a - 1] !in ".!?…•|") a--
        var b = i + raw.length; while (b < text.length && text[b] !in ".!?…•|") b++
        return text.substring(a, b).trim().trim('-', '—', ' ').let { if (it.length > 180) it.take(178) + "…" else it }
    }

    private fun domain(u: String) = try { java.net.URI(u).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }

    /** Ответ про цену только из выдачи. Нет суммы в сниппетах — «Точную цену не нашёл» и 1–2 заголовка. */
    fun priceAnswer(items: List<Item>): Pair<String, List<Price>> {
        data class Hit(val item: Item, val price: Price)
        val hits = items.flatMap { it -> extractPrices(it.title + ". " + it.snippet).map { p -> Hit(it, p) } }
        if (hits.isEmpty()) {
            val tops = items.take(2).joinToString("\n") { "• " + it.title + (domain(it.url).let { d -> if (d.isBlank()) "" else " ($d)" }) }
            return ("Точную цену не нашёл." + if (tops.isBlank()) "" else "\nВот что нашлось:\n$tops") to emptyList()
        }
        val ordered = hits.sortedBy { if (it.price.currency == "RUB") 0 else 1 }.distinctBy { it.item.url }.take(2)
        val sb = StringBuilder("Нашёл в поиске:")
        for (h in ordered) {
            val quote = sentenceAround(h.item.title + ". " + h.item.snippet, h.price.raw)
            sb.append("\n• «").append(quote).append("»")
            val d = domain(h.item.url); if (d.isNotBlank()) sb.append(" — ").append(d)
        }
        sb.append("\nЦены в магазинах меняются — проверьте на сайте.")
        return sb.toString() to ordered.map { it.price }
    }

    fun price(q: String): Result? {
        val query = expandPriceQuery(q)
        val items = ddgItems(query)
        if (items.isEmpty()) return null
        val (answer, amounts) = priceAnswer(items)
        return Result("DuckDuckGo", items.first().url, answer, answer, amounts, Kind.PRICE, query)
    }

    /** Курсы ЦБ РФ: рублей за 1 единицу валюты. */
    fun cbrRates(): Map<String, Double> {
        val v = JSONObject(http("https://www.cbr-xml-daily.ru/daily_json.js")).getJSONObject("Valute")
        val out = HashMap<String, Double>()
        for (k in v.keys()) { val o = v.getJSONObject(k); out[k] = o.optDouble("Value") / o.optInt("Nominal", 1).coerceAtLeast(1) }
        out["RUB"] = 1.0
        return out
    }

    /** Первые предложения текста (не режем по «г.», «т. е.» и инициалам). */
    fun firstSentences(t: String, n: Int, maxLen: Int = 420): String {
        val out = StringBuilder(); var count = 0; var i = 0
        while (i < t.length && count < n) {
            val c = t[i]; out.append(c)
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= t.length || t[i + 1] == ' ' || t[i + 1] == '\n')) {
                val prev = out.toString().trimEnd('.').takeLastWhile { it.isLetter() }
                val abbrev = prev.length <= 2 && prev.firstOrNull()?.let { it.isUpperCase() || prev in setOf("г", "гг", "в", "вв", "т", "е", "др", "н", "э", "ок", "им", "св") } == true
                if (!abbrev) { count++; if (out.length > maxLen) break }
            }
            i++
        }
        return out.toString().trim()
    }

    /** Похоже ли сообщение на фактический вопрос, на который поможет поиск. */
    fun looksFactual(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.length < 6) return false
        return Regex("$F(?<![\\p{L}])(кто\\s+(так|был|была|был[аи]?|написал|изобр|придум|открыл|сейчас|является|победил|выиграл)|кто\\s+такой|кто\\s+такая|что\\s+такое|что\\s+значит|что\\s+означает|когда\\s+(был|была|было|были|родил|умер|началась|закончилась|откры|будет|изобрел)|в\\s+каком\\s+году|где\\s+(находится|находятся|родил|живет|живёт)|сколько\\s+(лет|жителей|стоит|весит|человек|километров)|столиц[\\p{L}]*|новост[\\p{L}]*|погод[\\p{L}]*|курс\\s+(доллара|евро|юаня|валют|рубля)|курс(?![\\p{L}])|почему\\s+|как\\s+называется|какая\\s+высота|какой\\s+рост|население|чемпион|президент|расскажи\\s+(про|о|об)\\s+|загугли|погугли|найди\\s+в\\s+интернете|поищи)").containsMatchIn(t)
    }

    fun http(url: String, timeoutMs: Int = TIMEOUT_MS): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "ru,en;q=0.5")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Ключевая часть вопроса: «кто такой Пушкин?» → «Пушкин». */
    fun topic(q: String): String {
        var t = q.trim().trimEnd('?', '!', '.', ' ')
        repeat(2) {
            t = t.replace(Regex("$F^(расскажи\\s+(мне\\s+)?(про|о|об)|загугли|погугли|поищи(\\s+в\\s+интернете)?|найди\\s+в\\s+интернете|скажи[,]?|а\\s+|подскажи[,]?|" +
                "кто\\s+так(ой|ая|ие|ое)|кто\\s+был[аи]?|кто\\s+написал|кто\\s+изобрел|кто\\s+изобрёл|кто\\s+открыл|кто\\s+придумал|что\\s+такое|что\\s+значит|что\\s+означает|" +
                "когда\\s+(был[аио]?|началась|начался|началось|закончилась|закончился|родился|родилась|умер|умерла|основан[аоы]?|построен[аоы]?|открыт[аоы]?|изобрели|появил[\\p{L}]*)|" +
                "в\\s+каком\\s+году\\s+([\\p{L}]+\\s+)?|где\\s+(находится|находятся|расположен[аоы]?)|какая\\s+высота|какой\\s+рост|какая\\s+столица|столица|сколько\\s+лет|население)\\s+"), "")
        }
        return t.trim().ifEmpty { q.trim() }
    }

    // ---------- источники

    fun wikipedia(q: String): Result? {
        val term = topic(q)
        val os = JSONArray(http("https://ru.wikipedia.org/w/api.php?action=opensearch&format=json&limit=3&namespace=0&search=${enc(term)}"))
        val names = mutableListOf<String>()
        os.optJSONArray(1)?.let { a -> for (i in 0 until a.length()) names += a.getString(i) }
        // полнотекстовый поиск понимает падежи («центре истории») — если по префиксу ничего
        if (names.isEmpty()) try {
            val sr = JSONObject(http("https://ru.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=${enc(term)}"))
                .getJSONObject("query").getJSONArray("search")
            for (i in 0 until sr.length()) names += sr.getJSONObject(i).getString("title")
        } catch (_: Exception) { }
        for (title in names) {
            val js = try { JSONObject(http("https://ru.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}")) } catch (_: Exception) { continue }
            if (js.optString("type") == "disambiguation") continue
            var extract = js.optString("extract").trim()
            // полное вступление статьи (даты, произведения) — меньше поводов для выдумок
            try {
                val q2 = JSONObject(http("https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json&titles=${enc(title)}"))
                val pages = q2.getJSONObject("query").getJSONObject("pages")
                val intro = pages.getJSONObject(pages.keys().next()).optString("extract").trim()
                if (intro.length > extract.length) extract = intro
            } catch (_: Exception) { }
            extract = extract.replace(Regex("[\\u0301]"), "").replace(Regex("\\s*\\([^()]{0,120}\\)"), { m -> if (m.value.any(Char::isDigit)) m.value else "" })
            if (extract.length < 40) continue
            val url = js.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "https://ru.wikipedia.org/wiki/${enc(title)}"
            return Result("Википедия", url, extract.take(1600), direct = firstSentences(extract.replace('\n', ' '), 2))
        }
        return null
    }

    /** Справка о сущности (место, человек, событие): Википедия, потом поиск. Результат должен быть про запрос. */
    fun lookup(q: String): Result? {
        var lastErr: Exception? = null
        fun attempt(f: () -> Result?): Result? = try { f() } catch (e: Exception) { lastErr = e; null }
        val r = attempt { wikipedia(q)?.takeIf { relevant(q, it.text) } } ?: attempt { duckduckgo(q)?.takeIf { relevant(q, it.text) } }
        if (r == null && lastErr != null) throw lastErr!!
        return r?.copy(kind = Kind.FACT, query = q)
    }

    /** Хотя бы половина значимых слов запроса (по основам) есть в тексте. */
    fun relevant(q: String, text: String): Boolean {
        val keys = DocIndex.keywords(q)
        if (keys.isEmpty()) return true
        val low = text.lowercase().replace('ё', 'е')
        val hit = keys.count { low.contains(it) }
        return hit * 2 >= keys.size
    }

    fun ddgItems(q: String): List<Item> {
        val html = http("https://html.duckduckgo.com/html/?q=${enc(q)}&kl=ru-ru")
        val titles = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { strip(it.groupValues[1]) }.toList()
        return (0 until minOf(10, snippets.size)).map { i ->
            Item(titles.getOrNull(i)?.groupValues?.get(2)?.let { strip(it) } ?: "",
                snippets[i], titles.getOrNull(i)?.groupValues?.get(1)?.let { decodeDdgUrl(it) } ?: "")
        }.filter { it.snippet.isNotBlank() }
    }

    /** Ответ из выдачи — цитатой из первого сниппета, без пересказа моделью (чтобы не появлялись выдуманные числа). */
    fun snippetAnswer(items: List<Item>): String {
        val top = items.first()
        val quote = firstSentences(top.snippet, 2, 300)
        val d = domain(top.url)
        val more = items.drop(1).take(1).joinToString("") { "\nЕщё: " + it.title + (domain(it.url).let { x -> if (x.isBlank()) "" else " ($x)" }) }
        return "По данным поиска" + (if (d.isNotBlank()) " ($d)" else "") + ": «" + quote + "»" + more
    }

    fun duckduckgo(q: String): Result? {
        val items = ddgItems(q)
        if (items.isEmpty()) return null
        val ctx = items.take(4).joinToString("\n") { "- ${it.title}: ${it.snippet}" }.take(1500)
        val answer = snippetAnswer(items)
        return Result("DuckDuckGo", items.first().url, ctx, answer, extractPrices(ctx), Kind.FACT, q)
    }

    /** Новости: Google News RSS (заголовки и издания) — без модели, только факты. */
    fun news(q: String): Result? {
        var term = q.lowercase().replace(Regex("$F(какие|последние|свежие|главные|новости|новость|сегодня|сейчас|расскажи|покажи|что|нового|в\\s+мире|про|о|об|по|за)(?![\\p{L}])"), " ").replace(Regex("\\s+"), " ").trim()
        val url = if (term.isBlank()) "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru" else "https://news.google.com/rss/search?q=${enc(term)}&hl=ru&gl=RU&ceid=RU:ru"
        val xml = http(url)
        val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { strip(it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "")) }.filter { it.length > 10 }.take(5).toList()
        if (titles.isEmpty()) return null
        val direct = "Свежие заголовки:\n" + titles.joinToString("\n") { "• $it" }
        return Result("Google Новости", if (term.isBlank()) "https://news.google.com/?hl=ru" else "https://news.google.com/search?q=${enc(term)}&hl=ru", direct, direct)
    }

    private fun decodeDdgUrl(h: String): String {
        val m = Regex("uddg=([^&]+)").find(h) ?: return h
        return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
    }

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    private val wmo = mapOf(0 to "ясно", 1 to "в основном ясно", 2 to "переменная облачность", 3 to "пасмурно", 45 to "туман", 48 to "изморозь",
        51 to "слабая морось", 53 to "морось", 55 to "сильная морось", 56 to "ледяная морось", 57 to "ледяная морось",
        61 to "небольшой дождь", 63 to "дождь", 65 to "сильный дождь", 66 to "ледяной дождь", 67 to "ледяной дождь",
        71 to "небольшой снег", 73 to "снег", 75 to "сильный снег", 77 to "снежная крупа", 80 to "ливень", 81 to "ливни", 82 to "сильные ливни",
        85 to "снегопад", 86 to "сильный снегопад", 95 to "гроза", 96 to "гроза с градом", 99 to "гроза с сильным градом")

    /** Падежные варианты: «Екатеринбурге» → «Екатеринбург», «Москве» → «Москва», «Перми» → «Пермь». */
    fun cityVariants(city: String): List<String> {
        val c = city.trim().replaceFirstChar { it.uppercase() }
        return listOfNotNull(
            if (c.endsWith("ии")) c.dropLast(2) + "ия" else null,
            if (c.endsWith("е")) c.dropLast(1) else null,
            if (c.endsWith("е")) c.dropLast(1) + "а" else null,
            if (c.endsWith("и")) c.dropLast(1) + "ь" else null,
            if (c.endsWith("и")) c.dropLast(1) + "а" else null,
            c).distinct()
    }

    /** Погода: геокодер и прогноз Open-Meteo (понимает русские названия); без города — wttr.in по IP. */
    fun weather(q: String): Result? {
        val city = Regex("$F(?:в|во)\\s+([\\p{L}-]{3,}(?:\\s+[\\p{L}-]{3,})?)").find(q)?.groupValues?.get(1)
            ?.replace(Regex("$F\\s+(сегодня|завтра|сейчас)$"), "")
            ?.takeUnless { Regex("$F^(город[\\p{L}]*|завтра|сегодня|выходные|субботу|воскресенье)$").matches(it) }
        val tomorrow = q.lowercase().contains("завтра")
        if (city != null) {
            for (v in cityVariants(city)) {
                val g = try { JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?name=${enc(v)}&count=1&language=ru&format=json")) } catch (_: Exception) { continue }
                val loc = g.optJSONArray("results")?.optJSONObject(0) ?: continue
                val name = loc.optString("name")
                if (name.lowercase().take(4) != v.lowercase().take(4)) continue
                val lat = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
                val f = try { JSONObject(http("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_min,temperature_2m_max,weather_code&timezone=auto&forecast_days=3", 4500)) }
                    catch (_: Exception) {
                        // прогноз Open-Meteo недоступен — тот же город по координатам через wttr.in
                        return wttr("%.3f,%.3f".format(java.util.Locale.US, lat, lon), name, tomorrow)
                    }
                val cur = f.getJSONObject("current"); val d = f.getJSONObject("daily")
                fun r(x: Double) = Math.round(x).toInt()
                val now = "сейчас ${r(cur.getDouble("temperature_2m"))}°, ощущается как ${r(cur.getDouble("apparent_temperature"))}°, ${wmo[cur.optInt("weather_code")] ?: ""}, ветер ${r(cur.getDouble("wind_speed_10m") / 3.6)} м/с"
                fun day(i: Int) = "от ${r(d.getJSONArray("temperature_2m_min").getDouble(i))}° до ${r(d.getJSONArray("temperature_2m_max").getDouble(i))}°, ${wmo[d.getJSONArray("weather_code").optInt(i)] ?: ""}"
                val direct = if (tomorrow) "Завтра в городе $name ${day(1)}." else "В городе $name $now. Сегодня ${day(0)}, завтра ${day(1)}."
                return Result("Open-Meteo", "https://open-meteo.com/", direct, direct)
            }
        }
        // без города (или не нашли) — по IP через wttr.in
        return wttr("", null, tomorrow)
    }

    /** wttr.in: place — «широта,долгота» или пусто (по IP). name — как назвать место в ответе. */
    private fun wttr(place: String, name: String?, tomorrow: Boolean): Result {
        val js = JSONObject(http("https://wttr.in/${place}?format=j1&lang=ru", 4000))
        val cur = js.getJSONArray("current_condition").getJSONObject(0)
        val desc = (cur.optJSONArray("lang_ru")?.optJSONObject(0)?.optString("value") ?: "").lowercase()
        val days = js.optJSONArray("weather")
        fun day(i: Int) = days?.optJSONObject(i)?.let { "от ${it.optString("mintempC")}° до ${it.optString("maxtempC")}°" } ?: ""
        val where = if (name != null) "в городе $name" else ""
        val ipNote = if (name == null) " (Место определено по интернет-подключению — назовите город для точности.)" else ""
        val direct = if (tomorrow) "Завтра ${where.ifEmpty { "" }} ${day(1)}.".replace(Regex("\\s+"), " ").replace(" .", ".") + ipNote
            else ((if (name != null) "В городе $name сейчас" else "Сейчас") + " ${cur.optString("temp_C")}°, ощущается как ${cur.optString("FeelsLikeC")}°, $desc. Сегодня ${day(0)}, завтра ${day(1)}." + ipNote)
        return Result("wttr.in", "https://wttr.in/", direct, direct)
    }

    /** Курсы ЦБ РФ (USD, EUR, CNY и др.). */
    fun currency(q: String): Result? {
        val js = JSONObject(http("https://www.cbr-xml-daily.ru/daily_json.js"))
        val v = js.getJSONObject("Valute")
        val want = buildList {
            val t = q.lowercase()
            if (t.contains("доллар") || t.contains("usd")) add("USD")
            if (t.contains("евро") || t.contains("eur")) add("EUR")
            if (t.contains("юан") || t.contains("cny")) add("CNY")
            if (t.contains("тенге")) add("KZT")
            if (t.contains("фунт")) add("GBP")
            if (isEmpty()) { add("USD"); add("EUR"); add("CNY") }
        }
        val sb = StringBuilder("Официальный курс ЦБ РФ на ${js.optString("Date").take(10)}:\n")
        for (code in want) {
            val o = v.optJSONObject(code) ?: continue
            sb.append("${o.optInt("Nominal")} ${o.optString("Name")} = ${"%.2f".format(o.optDouble("Value"))} руб.\n")
        }
        val txt = sb.toString().trim()
        return Result("ЦБ РФ (cbr-xml-daily.ru)", "https://www.cbr.ru/currency_base/daily/", txt, txt)
    }

    /** Главная точка входа: выбирает источник по типу вопроса. Бросает исключение, если сети нет. */
    fun search(q: String): Result? {
        val t = q.lowercase()
        var lastErr: Exception? = null
        fun attempt(f: () -> Result?): Result? = try { f() } catch (e: Exception) { lastErr = e; null }
        val r = when {
            Regex("$F погод|^погод").containsMatchIn(" $t") -> attempt { weather(q)?.copy(kind = Kind.WEATHER, query = q) }
            isPriceQuery(t) -> attempt { price(q) }
            Regex("$F(?<![\\p{L}])курс").containsMatchIn(t) -> attempt { currency(q)?.copy(kind = Kind.CURRENCY, query = q) }
            Regex("$F(новост)").containsMatchIn(t) -> attempt { news(q)?.copy(kind = Kind.NEWS, query = q) } ?: attempt { duckduckgo(q) }
            Regex("$F(сегодня|вчера|сейчас|последн|202[0-9])").containsMatchIn(t) -> attempt { duckduckgo(q) } ?: attempt { wikipedia(q) }
            else -> attempt { wikipedia(q) } ?: attempt { duckduckgo(q) }
        }?.let { if (it.query.isEmpty()) it.copy(query = q) else it }
        if (r == null && lastErr != null) throw lastErr!!
        return r
    }

    fun contextPrompt(r: Result): String =
        "Найденные в интернете сведения (источник: ${r.source}):\n${r.text.trim()}\n\n" +
        "Ответь на вопрос пользователя кратко (2–4 предложения), по-русски, опираясь ТОЛЬКО на эти сведения. " +
        "Не добавляй ничего, чего нет в сведениях (никаких других названий, дат и чисел). Если в сведениях нет ответа — так и скажи."
}
