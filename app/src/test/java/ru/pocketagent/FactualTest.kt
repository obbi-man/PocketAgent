package ru.pocketagent

import org.junit.Assert.*
import org.junit.Test

class FactualTest {
    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }

    private class Llm(val reply: String = "Екатеринбург — большой город на Урале.") : Agent.Llm {
        val calls = mutableListOf<Pair<String, Float>>()
        override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
            calls += prompt to temp; onText(reply); return reply to null
        }
    }

    private class Search(var online: Boolean = true, val db: Map<String, String> = DB) : Agent.Searcher {
        val lookups = mutableListOf<String>()
        override fun enabled() = true
        override fun online() = online
        override fun search(q: String): WebSearch.Result? = lookup(q)
        override fun lookup(q: String): WebSearch.Result? {
            lookups += q
            val text = db.entries.firstOrNull { q.lowercase().contains(it.key) }?.value ?: return null
            return WebSearch.Result("Википедия", "https://ru.wikipedia.org/wiki/" + q.replace(' ', '_'), text,
                direct = WebSearch.firstSentences(text, 2), kind = WebSearch.Kind.FACT, query = q)
        }
        companion object {
            val DB = mapOf(
                "ельцин" to "«Ельцин Центр» — общественный, культурный и образовательный центр в Екатеринбурге, открытый в 2015 году. В нём работает музей первого президента России.",
                "музей истории екатеринбурга" to "Музей истории Екатеринбурга — муниципальный музей в Екатеринбурге, основанный в 1940 году. Расположен на улице Карла Либкнехта.",
                "екатеринбург" to "Екатеринбург — город в России, административный центр Свердловской области. Основан в 1723 году.",
                "бажов" to "Павел Петрович Бажов — русский советский писатель, автор сказов «Малахитовая шкатулка».",
            )
        }
    }

    private class Rec : Agent.Events {
        var out = ""; var web: String? = "unset"
        override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { out = "tool:${call.name}" }
        override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
        override fun onAssistantDone(text: String) { out = text }; override fun onError(message: String) { out = "err:$message" }
        override fun isCancelled() = false
        override fun onWebSearch(source: String?, url: String?) { web = source }
    }

    private val phrase = "Ты начал писать о центре истории екб, что про него можешь сказать"

    @Test fun routingPositives() {
        for (p in listOf(phrase, "Что ты знаешь про Демидовых?", "что можешь сказать о плотинке", "Что такое ипотека", "Кто такой Бажов",
                "Расскажи о Плотинке", "а про Ельцин центр подробнее", "Ельцин центр — что это?", "Когда основан Екатеринбург",
                "Расскажи подробнее про храм на крови", "Что за музей истории екб", "Какой самый старый музей в екб?", "А что Демидовы построили?",
                "История Екатеринбурга", "Ты начала рассказывать про Бажова"))
            assertTrue(p, Factual.isFactual(p))
    }

    @Test fun routingNegatives() {
        for (p in listOf("Привет, как дела?", "Спасибо!", "Посоветуй, что подарить внуку", "Помоги написать поздравление маме с днём рождения",
                "Придумай тост на юбилей", "Как лучше сказать начальнику, что я заболел", "Мне скучно", "Напиши стих про осень", "Пошути"))
            assertFalse(p, Factual.isFactual(p))
    }

    @Test fun entityAndAliases() {
        assertEquals("центре истории екб", Factual.entity(phrase, null))
        assertEquals(listOf("Ельцин Центр", "Музей истории Екатеринбурга"), Factual.queries("центре истории екб", null))
        // без «екб» — по городу из памяти («Живу в Екатеринбурге»)
        assertEquals(listOf("Ельцин Центр", "Музей истории Екатеринбурга"), Factual.queries("центр истории", "Екатеринбурге"))
        // местоимение → тема прошлого вопроса
        assertEquals("Ельцин Центр", Factual.entity("А что про него ещё можешь сказать?", "Ельцин Центр"))
        assertEquals("Ельцин центр", Factual.entity("а про Ельцин центр подробнее", null))
        assertEquals("Бажова", Factual.entity("Ты начала рассказывать про Бажова", null))
        assertEquals("Ельцин Центр", Factual.entity("Расскажи о нём подробнее", "Ельцин Центр"))
        assertTrue(Factual.isFactual("А что ещё про него знаешь?", hasTopic = true))
        assertFalse(Factual.isFactual("А что про него?", hasTopic = false))
        assertEquals("Екатеринбург", Factual.cityIn("музей в екб"))
        assertEquals("Екатеринбурга", Factual.genitive("Екатеринбург")); assertEquals("Москвы", Factual.genitive("Москва"))
        assertEquals("Казани", Factual.genitive("Казань"))
        assertEquals(listOf("театр драмы", "театр драмы Екатеринбурга"), Factual.queries("театр драмы", "Екатеринбурге"))
        assertEquals(listOf("оперный театр Екатеринбурга", "оперный театр Екатеринбург"), Factual.queries("оперный театр екб", null))
        assertEquals(listOf("Бажов"), Factual.queries("Бажов", "Екатеринбурге"))
    }

    @Test fun exactBugPhraseWithContextQuotesSources() {
        val llm = Llm(); val s = Search(); val ag = Agent(notes, llm, null, s); val ev = Rec()
        ag.handle("Расскажи про Екатеринбург", ev)
        assertTrue(ev.out, ev.out.contains("«Екатеринбург — город в России") && ev.out.contains("Источник: Википедия"))
        ag.handle(phrase, ev)
        assertTrue(s.lookups.toString(), s.lookups.containsAll(listOf("Ельцин Центр", "Музей истории Екатеринбурга")))
        assertTrue(ev.out, ev.out.contains("Ельцин Центр — «") && ev.out.contains("Музей истории Екатеринбурга — «") && ev.out.contains("Источник"))
        assertFalse(ev.out, ev.out.contains("антигитлер"))
        assertTrue("модель не должна отвечать на факты", llm.calls.isEmpty())
        // «а что про него ещё?» — тема из контекста
        s.lookups.clear()
        ag.handle("А что про него можешь сказать?", ev)
        assertTrue(s.lookups.toString(), s.lookups.first() == "Ельцин Центр")
    }

    @Test fun rememberedCityResolvesWithoutAbbreviation() {
        val s = Search(); val ag = Agent(notes, Llm(), null, s); val ev = Rec()
        ag.facts.put("city", "Живу в Екатеринбурге")
        ag.handle("Что можешь сказать о центре истории?", ev)
        assertTrue(s.lookups.toString(), s.lookups.contains("Ельцин Центр"))
    }

    @Test fun offlineNeverInventsAndHedgesOnlyWhenInsisted() {
        val llm = Llm("Не уверен, но это, кажется, музей. ЕАО основано в 1933 году."); val s = Search(online = false)
        val ag = Agent(notes, llm, null, s); val ev = Rec()
        ag.handle(phrase, ev)
        assertTrue(ev.out, ev.out.startsWith("Точно не знаю — нет интернета, а сам могу ошибиться."))
        assertTrue(llm.calls.isEmpty())
        ag.handle("Ну предположи", ev)
        assertEquals(1, llm.calls.size); assertEquals(0.3f, llm.calls[0].second, 0.001f)
        assertTrue(ev.out, ev.out.startsWith("Не уверен") && !ev.out.contains("1933") && ev.out.contains("без проверки"))
        // без «предположи» модель не спрашиваем
        ag.handle(phrase, ev); ag.handle("Как дела?", ev)
        assertFalse(ev.out.contains("Точно не знаю"))
    }

    @Test fun notFoundAndSearchOff() {
        val s = Search(db = emptyMap()); val llm = Llm(); val ag = Agent(notes, llm, null, s); val ev = Rec()
        ag.handle("Что ты знаешь про Зюзюкинский мост?", ev)
        assertTrue(ev.out, ev.out.startsWith(Factual.NOT_FOUND)); assertTrue(llm.calls.isEmpty())
        val off = object : Agent.Searcher { override fun enabled() = false; override fun online() = true; override fun search(q: String): WebSearch.Result? = null }
        val ag2 = Agent(notes, llm, null, off)
        ag2.handle("Кто такой Бажов?", ev)
        assertTrue(ev.out, ev.out.startsWith("Точно не знаю — поиск в интернете выключен")); assertTrue(llm.calls.isEmpty())
        val ag3 = Agent(notes, llm, null, null)
        ag3.handle("Кто такой Бажов?", ev)
        assertTrue(ev.out, ev.out.startsWith("Точно не знаю")); assertTrue(llm.calls.isEmpty())
    }

    @Test fun chatTemperatures() {
        val llm = Llm("Попробуйте ложиться пораньше."); val ag = Agent(notes, llm, null, Search()); val ev = Rec()
        ag.handle("Посоветуй, как лучше выспаться", ev)
        assertEquals(0.5f, llm.calls.last().second, 0.001f)
        ag.handle("Мне нужно решить вопрос с работой", ev)
        assertEquals(0.3f, llm.calls.last().second, 0.001f)
    }

    @Test fun relevanceFilter() {
        assertTrue(WebSearch.relevant("Музей истории Екатеринбурга", "Музей истории Екатеринбурга — муниципальный музей"))
        assertFalse(WebSearch.relevant("Музей истории Екатеринбурга", "Еврейское общество основано в 1933 году"))
    }
}
