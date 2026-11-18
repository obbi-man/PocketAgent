package ru.pocketagent

import org.junit.Assert.*
import org.junit.Test
import ru.pocketagent.WebSearch.Item
import ru.pocketagent.WebSearch.Kind
import ru.pocketagent.WebSearch.Price

class SearchFollowUpTest {
    private val rates = mapOf("RUB" to 1.0, "USD" to 80.0, "EUR" to 90.0, "CNY" to 11.0)

    // ---------- цены

    @Test fun priceQueryExpansion() {
        assertTrue(WebSearch.isPriceQuery("Сколько стоит 4060"))
        assertTrue(WebSearch.isPriceQuery("цена iphone 15"))
        assertFalse(WebSearch.isPriceQuery("кто такой Пушкин"))
        assertEquals("RTX 4060 цена", WebSearch.expandPriceQuery("Сколько стоит 4060"))
        assertEquals("RTX 4070 Ti цена", WebSearch.expandPriceQuery("сколько стоит 4070 ti?"))
        assertEquals("GTX 1660 цена", WebSearch.expandPriceQuery("цена 1660"))
        assertEquals("iPhone 15 цена", WebSearch.expandPriceQuery("Сколько стоит iPhone 15"))
        assertNull(Router.route("Сколько стоит 4060"))   // не калькулятор и не другой инструмент
    }

    @Test fun pricesAreOnlyTakenFromText() {
        val p = WebSearch.extractPrices("Видеокарта RTX 4060 8 ГБ — от 32 999 ₽ в наличии")
        assertEquals(1, p.size); assertEquals(32999.0, p[0].amount, 0.001); assertEquals("RUB", p[0].currency)
        assertEquals(299.0, WebSearch.extractPrices("MSRP is \$299 at launch")[0].amount, 0.001)
        assertEquals(1500.0, WebSearch.extractPrices("стоит \$1,500").first().amount, 0.001)
        assertEquals("USD", WebSearch.extractPrices("около 1500 долларов").first().currency)
        assertEquals(31490.0, WebSearch.extractPrices("Цена: 31490 руб.").first().amount, 0.001)
        // голые числа (модель, объём памяти, год) — не цены
        assertTrue(WebSearch.extractPrices("RTX 4060 8 ГБ GDDR6, 2023 год, 115 Вт").isEmpty())
    }

    @Test fun noPriceFallback() {
        val items = listOf(
            Item("NVIDIA GeForce RTX 4060 — характеристики", "Видеокарта на архитектуре Ada Lovelace, 8 ГБ памяти.", "https://www.nvidia.com/ru-ru/x"),
            Item("Обзор RTX 4060: тесты в играх", "Сравнение с RTX 3060 и RX 7600 в 20 играх.", "https://www.ixbt.com/y"),
            Item("Третий результат", "Что-то ещё", "https://example.com"))
        val (text, amounts) = WebSearch.priceAnswer(items)
        assertTrue(text, text.startsWith("Точную цену не нашёл"))
        assertTrue(text.contains("NVIDIA GeForce RTX 4060 — характеристики"))
        assertTrue(text.contains("Обзор RTX 4060"))
        assertFalse(text.contains("Третий результат"))           // только 1–2 заголовка
        assertFalse(Regex("\\d+\\s*(долл|\\$|руб|₽)").containsMatchIn(text))   // никаких выдуманных сумм
        assertTrue(amounts.isEmpty())
    }

    @Test fun priceAnswerQuotesSnippetAndPrefersRubles() {
        val items = listOf(
            Item("RTX 4060 review", "Launched at \$299 MSRP.", "https://www.tomshardware.com/a"),
            Item("Видеокарта RTX 4060 купить", "Цены от 31 990 ₽. Доставка завтра.", "https://www.dns-shop.ru/b"))
        val (text, amounts) = WebSearch.priceAnswer(items)
        assertTrue(text, text.contains("31 990 ₽")); assertTrue(text.contains("dns-shop.ru"))
        assertEquals("RUB", amounts.first().currency)
        assertTrue(text.indexOf("31 990") < text.indexOf("299"))
    }

    // ---------- уточнения

    @Test fun followUpResolution() {
        val price = FollowUp.Ctx("RTX 4060 цена", Kind.PRICE, listOf(Price(299.0, "USD", "\$299")))
        assertEquals(FollowUp.Res.Convert("RUB"), FollowUp.resolve("А в рублях", price))
        assertEquals(FollowUp.Res.Convert("RUB"), FollowUp.resolve("а в рублях?", price))
        assertEquals(FollowUp.Res.Convert("EUR"), FollowUp.resolve("а в евро", price))
        assertEquals(FollowUp.Res.Convert("USD"), FollowUp.resolve("сколько это в долларах?", price))
        val weather = FollowUp.Ctx("погода в Екатеринбурге", Kind.WEATHER, emptyList())
        assertEquals(FollowUp.Res.Requery("погода в Екатеринбурге завтра"), FollowUp.resolve("а завтра?", weather))
        assertEquals(FollowUp.Res.Requery("погода в Казани"), FollowUp.resolve("А в Казани?", weather))
        assertEquals(FollowUp.Res.Requery("погода в Екатеринбурге"), FollowUp.resolve("а там?", weather))
        val fact = FollowUp.Ctx("кто такой Пушкин?", Kind.FACT, emptyList())
        assertEquals(FollowUp.Res.Requery("сколько лет прожил Пушкин"), FollowUp.resolve("а сколько лет прожил?", fact))
        assertEquals(FollowUp.Res.Requery("сколько стоит RTX 4060"), FollowUp.resolve("а сколько стоит?", price))
        // не уточнения
        assertNull(FollowUp.resolve("кто такой Лермонтов?", fact))
        assertNull(FollowUp.resolve("А в рублях", null))
        assertNull(FollowUp.resolve("Напомни завтра в 9 выпить витамины", weather))
    }

    @Test fun conversion() {
        assertEquals(23920.0, FollowUp.convert(Price(299.0, "USD", ""), "RUB", rates)!!, 0.01)
        assertEquals(400.0, FollowUp.convert(Price(32000.0, "RUB", ""), "USD", rates)!!, 0.01)
        val t = FollowUp.conversionText(Price(299.0, "USD", ""), "RUB", rates)
        assertTrue(t, t.contains("23\u00A0920") && t.contains("руб.") && t.contains("ЦБ"))
        val e = FollowUp.explicitConversion("1500 долларов в рублях")!!
        assertEquals(1500.0, e.first.amount, 0.0); assertEquals("USD", e.first.currency); assertEquals("RUB", e.second)
    }

    @Test fun markdownAndLength() {
        assertEquals("Это важно и полезно", Agent.stripMarkdown("Это **важно** и **полезно**"))
        assertEquals("• пункт", Agent.stripMarkdown("* пункт"))
        assertFalse(Agent.cleanOutput("## Заголовок\n**Ответ:** да").contains("*"))
        val long = "Первое. Второе. Третье. Четвёртое. Пятое. Шестое."
        assertEquals("Первое. Второе. Третье. Четвёртое.", Agent.capSentences(long))
        // как на эмуляторе: список оборвался на «2.»
        val cut = Agent.capSentences("Сон помогает телу восстановиться. Он помогает:\n\n1. Повысить энергию — тело отдыхает.\n2.")
        assertFalse(cut, cut.endsWith("2.")); assertFalse(cut.contains("1."))
        assertTrue(cut, cut.endsWith("отдыхает."))
        assertEquals("Сон важен. Он нужен для отдыха.", Agent.capSentences("Сон важен. Он нужен для отдыха. Также во время сна мозг"))
    }

    // ---------- агент целиком: «Сколько стоит 4060» → «А в рублях» без модели

    private class FakeSearcher(val result: WebSearch.Result) : Agent.Searcher {
        val queries = mutableListOf<String>()
        override fun enabled() = true
        override fun online() = true
        override fun search(q: String): WebSearch.Result { queries += q; return result }
        override fun rates() = mapOf("RUB" to 1.0, "USD" to 80.0, "EUR" to 90.0)
    }

    private fun run(ag: Agent, text: String): String {
        var out = ""
        ag.handle(text, object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { out = "tool:${call.name}" }
            override fun onAssistantStart() {}
            override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) { out = text }
            override fun onError(message: String) { out = "err:$message" }
            override fun isCancelled() = false
        })
        return out
    }

    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }

    private fun llmCounter(counter: IntArray, maxSeen: IntArray) = object : Agent.Llm {
        override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
            counter[0]++; maxSeen[0] = maxOf(maxSeen[0], maxTokens)
            val o = "**Ответ** номер один. Два. Три. Четыре. Пять. Шесть."; onText(o); return o to null
        }
    }

    @Test fun agentPriceFollowUpUsesCbrNotModel() {
        val calls = IntArray(1); val maxTok = IntArray(1)
        val (text, amounts) = WebSearch.priceAnswer(listOf(Item("RTX 4060 купить", "от 31 990 ₽", "https://www.dns-shop.ru/b")))
        val fs = FakeSearcher(WebSearch.Result("DuckDuckGo", "u", text, text, amounts, Kind.PRICE, "RTX 4060 цена"))
        val ag = Agent(notes, llmCounter(calls, maxTok), null, fs)
        val a1 = run(ag, "Сколько стоит 4060")
        assertTrue(a1, a1.contains("31 990 ₽") && a1.contains("Источник"))
        val a2 = run(ag, "А в долларах")
        assertTrue(a2, a2.contains("≈") && a2.contains("$") && a2.contains("ЦБ"))
        val a3 = run(ag, "а в евро")            // контекст сохраняется между уточнениями
        assertTrue(a3, a3.contains("€"))
        val a4 = run(ag, "а в рублях")
        assertTrue(a4, a4.startsWith("Сумма уже в этой валюте") && !a4.contains(".."))
        assertEquals(0, calls[0])   // модель не вызывалась ни разу
    }

    @Test fun agentNoPriceFollowUpDoesNotInvent() {
        val calls = IntArray(1); val maxTok = IntArray(1)
        val (text, amounts) = WebSearch.priceAnswer(listOf(Item("RTX 4060 обзор", "Тесты в играх", "https://ixbt.com/a")))
        val fs = FakeSearcher(WebSearch.Result("DuckDuckGo", "u", text, text, amounts, Kind.PRICE, "RTX 4060 цена"))
        val ag = Agent(notes, llmCounter(calls, maxTok), null, fs)
        assertTrue(run(ag, "Сколько стоит 4060").startsWith("Точную цену не нашёл"))
        assertEquals(Agent.NO_AMOUNT, run(ag, "А в рублях"))
        // «а в рублях» вообще без контекста — тоже без модели
        val ag2 = Agent(notes, llmCounter(calls, maxTok), null, fs)
        assertEquals(Agent.NO_CTX_CONVERT, run(ag2, "А в рублях?"))
        assertEquals(0, calls[0])
        // обычный ответ модели: ≤ 160 токенов, без **, не длиннее 4 предложений
        val ag3 = Agent(notes, llmCounter(calls, maxTok), null, null)
        val free = run(ag3, "Привет! Как дела?")
        assertFalse(free.contains("*")); assertTrue(maxTok[0] <= 160)
        assertEquals(4, Regex("[.!?](\\s|$)").findAll(free).count())
    }
}
