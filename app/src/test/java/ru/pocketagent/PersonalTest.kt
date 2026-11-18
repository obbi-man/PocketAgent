package ru.pocketagent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonalTest {
    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun add(text: String) { l += text }; override fun all() = l.toList(); override fun clear() = l.clear() }

    private class Rec : Agent.Events {
        var out = ""; var prompt = ""; val saved = mutableListOf<Pair<List<Fact>, Boolean>>(); var tool: String? = null
        override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { tool = call.name; out = result.answer }
        override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
        override fun onAssistantDone(text: String) { out = text }; override fun onError(message: String) { out = "err:$message" }
        override fun isCancelled() = false
        override fun onFactsSaved(facts: List<Fact>, auto: Boolean) { saved += facts to auto }
    }

    private fun agent(rec: Rec, reply: String = "Ответ модели.") = Agent(notes, object : Agent.Llm {
        override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> { rec.prompt = prompt; onText(reply); return reply to null }
    }, null, null)

    private fun keys(t: String) = Personal.extract(t).associate { it.key to it.text }

    @Test fun extractBasics() {
        assertEquals("Меня зовут Пётр", keys("Меня зовут пётр")["name"])
        assertEquals("Меня зовут Пётр Иванович", keys("меня зовут Пётр Иванович")["name"])
        assertEquals("Живу в Екатеринбурге", keys("Я живу в Екатеринбурге уже 30 лет")["city"])
        assertEquals("Живу в Нижнем Тагиле", keys("мы живём в нижнем тагиле")["city"])
        assertTrue(keys("Мой день рождения 12 марта")["birthday"]!!.contains("12 марта"))
        val rel = Personal.extract("Внука зовут Миша").single()
        assertTrue(rel.key, rel.key.startsWith("rel:") && rel.text.contains("Миша") && rel.text.contains("нук"))
        assertTrue(Personal.extract("У меня аллергия на пенициллин").any { it.key == "health" && it.text.contains("пенициллин") })
        assertTrue(Personal.extract("Я люблю зелёный чай").any { it.text.contains("чай") })
        assertTrue(Personal.extract("Какая погода завтра?").isEmpty())
        assertTrue(Personal.extract("Поставь будильник на 7 утра").isEmpty())
    }

    @Test fun commands() {
        assertTrue(Personal.command("Запомни, что внука зовут Миша") is Personal.Cmd.Remember)
        val r = Personal.command("Запомни, что я не ем острое") as Personal.Cmd.Remember
        assertTrue(r.facts.single().text, r.facts.single().text.contains("острое"))
        // «запомни код домофона 4471» без «что» — это заметка, не факт
        assertNull(Personal.command("Запомни код домофона 4471"))
        assertEquals("save_note", (Router.route("Запомни код домофона 4471") as? Router.Route.Call)?.call?.name)
        assertEquals(Personal.Cmd.Recall("name"), Personal.command("Как меня зовут?"))
        assertEquals(Personal.Cmd.Recall(null), Personal.command("Что ты обо мне знаешь?"))
        assertTrue((Personal.command("Забудь всё обо мне") as Personal.Cmd.Forget).all)
        val f = Personal.command("Забудь про аллергию") as Personal.Cmd.Forget
        assertFalse(f.all); assertTrue(f.query.contains("аллерги"))
        assertEquals(Personal.Cmd.Style("short"), Personal.command("Отвечай короче"))
        assertEquals(Personal.Cmd.Style("ty"), Personal.command("говори на ты"))
        assertEquals(Personal.Cmd.Style("noemoji"), Personal.command("без смайликов пожалуйста"))
        assertTrue(Personal.command("Меня зовут Пётр") is Personal.Cmd.Remember)
        assertNull(Personal.command("Сколько будет 2+2"))
    }

    @Test fun storeSingleKeysPersistAndRelevant() {
        val file = File.createTempFile("mem", ".json"); file.delete()
        val s = FactStore(file)
        s.put("name", "Меня зовут Пётр"); s.put("name", "Меня зовут Павел")
        s.put("city", "Живу в Екатеринбурге"); s.put("rel:внук", "Внука зовут Миша"); s.put("like", "Люблю рыбалку")
        assertEquals(4, s.all().size)
        assertEquals("Павел", s.name()); assertEquals("Екатеринбурге", s.city())
        val s2 = FactStore(file)
        assertEquals(4, s2.all().size)
        val rel = s2.relevant("что подарить внуку на день рождения").map { it.key }
        assertTrue(rel.toString(), "rel:внук" in rel && "name" in rel)
        assertTrue(s2.relevant("привет").none { it.key == "like" })
        s2.toggle(false); assertFalse(FactStore(file).enabled)
    }

    @Test fun agentRememberRecallForget() {
        val rec = Rec(); val ag = agent(rec)
        ag.handle("Меня зовут Пётр", rec)
        assertTrue(rec.out, rec.out.contains("Пётр") && rec.out.contains("Запомнил"))
        ag.handle("Запомни, что у меня аллергия на пенициллин", rec)
        assertTrue(rec.out, rec.out.startsWith("Запомнил"))
        ag.handle("Как меня зовут?", rec)
        assertEquals("Вас зовут Пётр.", rec.out)
        ag.handle("Что ты обо мне знаешь?", rec)
        assertTrue(rec.out, rec.out.contains("Пётр") && rec.out.contains("пенициллин"))
        // факты и имя уходят в системную подсказку
        ag.handle("Что посоветуешь от простуды при аллергии?", rec)
        assertTrue(rec.prompt, rec.prompt.contains("пенициллин") && rec.prompt.contains("Пользователя зовут Пётр"))
        ag.handle("Забудь про аллергию", rec)
        assertTrue(rec.out, rec.out.startsWith("Забыл"))
        assertTrue(ag.facts.all().none { it.text.contains("пенициллин") })
        ag.handle("Забудь всё обо мне", rec)
        assertTrue(ag.facts.all().isEmpty())
    }

    @Test fun autoExtractionWithUndoEventAndNoNoteHijack() {
        val rec = Rec(); val ag = agent(rec)
        ag.handle("У меня аллергия на мёд, что можно к чаю?", rec)
        val (facts, auto) = rec.saved.single()
        assertTrue(auto); assertTrue(facts.single().text.contains("мед") || facts.single().text.contains("мёд"))
        assertEquals("Ответ модели.", rec.out)        // ответ продолжается обычным путём
        ag.handle("У меня аллергия на мёд, что можно к чаю?", rec)
        assertEquals(1, rec.saved.size)               // повтор не дублирует
        // память выключена — ничего не пишем
        ag.facts.toggle(false)
        ag.handle("Я живу в Казани, где поесть?", rec)
        assertEquals(1, rec.saved.size)
        ag.handle("Как меня зовут?", rec); assertEquals(Agent.MEMORY_OFF, rec.out)
        // «запомни код…» → заметка
        ag.facts.toggle(true)
        ag.handle("Запомни код домофона 4471", rec)
        assertEquals("save_note", rec.tool); assertTrue(notes.all().any { it.contains("4471") })
    }

    @Test fun reminderPhrasesStillRouteWithMemoryOn() {
        val rec = Rec(); val ag = agent(rec)
        ag.handle("поставь напоминалку на 5:00 утра", rec)
        assertEquals("set_reminder", rec.tool)
    }

    @Test fun styleProfileAndOverrides() {
        val st = StyleProfile()
        assertTrue(st.style().vy); assertFalse(st.style().short); assertFalse(st.style().emoji)
        repeat(6) { st.observe("скажи погоду :)") }
        val s = st.style(); assertFalse(s.vy); assertTrue(s.short); assertTrue(s.emoji)
        assertTrue(st.promptLine("Пётр").contains("«ты»") && st.promptLine("Пётр").contains("Пётр"))
        st.apply("vy"); st.apply("noemoji"); st.apply("long")
        val o = st.style(); assertTrue(o.vy); assertFalse(o.emoji); assertFalse(o.short)
        assertEquals("Привет ", Personal.stripEmoji("Привет 😊"))
    }

    @Test fun shortStyleCapsAnswerAndStripsEmoji() {
        val rec = Rec(); val ag = agent(rec, "Луна — спутник Земли. Она светит отражённым светом. До неё 384 тысячи км. Там нет воздуха 😊.")
        ag.handle("Отвечай короче", rec); assertTrue(rec.out.contains("короче"))
        ag.handle("Посоветуй, как отдохнуть вечером", rec)
        assertEquals("Луна — спутник Земли. Она светит отражённым светом.", rec.out)
        assertTrue(rec.prompt.contains("1–2 предложения") && rec.prompt.contains("Не используй эмодзи"))
    }

    @Test fun weatherUsesRememberedCity() {
        val rec = Rec(); val ag = agent(rec)
        ag.facts.put("city", "Живу в Екатеринбурге")
        assertEquals("какая погода завтра в Екатеринбурге", ag.withCity("какая погода завтра?"))
        assertEquals("погода в Москве", ag.withCity("погода в Москве"))
        assertEquals("какая погода в выходные в Екатеринбурге", ag.withCity("какая погода в выходные"))
        assertEquals("как дела", ag.withCity("как дела"))
    }

    @Test fun allergyWarningOnBadAdvice() {
        val rec = Rec(); val ag = agent(rec, "Примите пенициллин три раза в день. Пейте много воды.")
        ag.handle("Запомни, что у меня аллергия на пенициллин", rec)
        ag.handle("Чем лечить простуду?", rec)
        assertTrue(rec.out, rec.out.contains("⚠️") && rec.out.contains("аллергия на пенициллин"))
        assertTrue(rec.prompt.contains("Никогда не советуй"))
        assertNull(ag.healthWarning("Пейте чай с лимоном."))
    }
}
