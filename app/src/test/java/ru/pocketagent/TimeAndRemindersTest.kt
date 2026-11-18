package ru.pocketagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class TimeAndRemindersTest {
    // понедельник, 16 ноября 2026, 21:40
    private val now = LocalDateTime.of(2026, 11, 16, 21, 40)
    private fun p(t: String, morning: Boolean = false) = TimeParser.parse(t, now, morning)
    private fun call(t: String) = (Router.route(t, now) as? Router.Route.Call)?.call
    private fun at(c: ToolCall?) = c?.args?.getString("at")

    @Test fun relative() {
        assertEquals(LocalDateTime.of(2026, 11, 16, 21, 50), p("через 10 минут")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 22, 10), p("через полчаса")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 23, 40), p("через два часа")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 22, 55), p("через 1 час 15 минут")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 21, 45), p("через пять минут")!!.at)
        assertTrue(p("через 10 минут")!!.relative)
    }

    @Test fun absolute() {
        assertEquals(LocalDateTime.of(2026, 11, 17, 8, 0), p("завтра в 8 утра")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 23, 0), p("в 23:00")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 23, 0), p("на 23:00")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 30), p("в 7:30")!!.at)          // уже прошло сегодня → завтра
        assertEquals(LocalDateTime.of(2026, 11, 17, 19, 0), p("завтра в 7 вечера")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 0), p("в 7")!!.at)             // ближайшее из 7:00 / 19:00
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 0), p("в 7", morning = true)!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0), p("послезавтра в 10")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), p("в пятницу в 10 утра")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 30), p("в половине восьмого утра")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 12, 0), p("завтра в полдень")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 0), p("завтра утром")!!.at)
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), p("1 декабря в 9")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 23, 0), p("в 11 вечера")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 30), p("в семь тридцать утра")!!.at)
    }

    @Test fun repeating() {
        val w = p("каждый будний день в 7")!!
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), w.days)
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 0), w.at)
        assertEquals(7, p("каждый день в 9 вечера")!!.days.size)
        assertEquals(LocalDateTime.of(2026, 11, 16, 21, 0).plusDays(1), p("каждый день в 9 вечера")!!.at)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), p("по выходным в 10")!!.days)
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 0), p("по выходным в 10")!!.at)
    }

    @Test fun durationsAreNotTimes() {
        assertNull(p("таймер на 5 минут"))
        assertNull(p("Маша в 5 классе")?.takeIf { it.strong })
        assertNull(p("привет"))
        assertNull(p("сколько будет 2+2"))
    }

    @Test fun reminderRoutes() {
        val r1 = call("Напомни через 10 минут выключить плиту")
        assertEquals("set_reminder", r1?.name); assertEquals("2026-11-16T21:50", at(r1))
        assertEquals("Выключить плиту", r1?.args?.getString("text"))

        val r2 = call("напомни мне завтра в 8 утра выпить таблетки")
        assertEquals("2026-11-17T08:00", at(r2)); assertEquals("Выпить таблетки", r2?.args?.getString("text"))

        // главный кейс: «Запиши на 23:00 …» — это напоминание, а не просто заметка
        val r3 = call("Запиши на 23:00 сделать зарядку")
        assertEquals("set_reminder", r3?.name); assertEquals("2026-11-16T23:00", at(r3))
        assertEquals("Сделать зарядку", r3?.args?.getString("text"))

        val r4 = call("напоминай каждый будний день в 7 принять лекарство")
        assertEquals("set_reminder", r4?.name); assertEquals(31, r4?.args?.getInt("days"))

        assertEquals("Позвонить маме", call("напомни в 23:00 позвонить маме")?.args?.getString("text"))
        assertEquals("list_reminders", call("какие у меня напоминания?")?.name)
        assertEquals("list_reminders", call("покажи напоминания")?.name)
    }

    @Test fun reminderFollowUp() {
        val ask = Router.route("напомни позвонить врачу", now)
        assertTrue(ask is Router.Route.Ask)
        val pend = (ask as Router.Route.Ask).pending!!
        assertEquals("Позвонить врачу", pend.text)
        val c = (Router.followUp(pend, "завтра в 10", now) as Router.Route.Call).call
        assertEquals("set_reminder", c.name); assertEquals("2026-11-17T10:00", c.args.getString("at"))
        assertEquals("Позвонить врачу", c.args.getString("text"))
    }

    @Test fun alarmRoutes() {
        val a = call("Разбуди меня в 7")
        assertEquals("set_alarm", a?.name); assertEquals(7, a?.args?.getInt("hour")); assertEquals(0, a?.args?.getInt("minute"))
        val b = call("поставь будильник на 6:45")
        assertEquals(6, b?.args?.getInt("hour")); assertEquals(45, b?.args?.getInt("minute"))
        val c = call("будильник каждый будний день в 7")
        assertEquals(31, c?.args?.getInt("days")); assertEquals(7, c?.args?.getInt("hour"))
        val d = call("разбуди через 8 часов")
        assertEquals(5, d?.args?.getInt("hour")); assertEquals(40, d?.args?.getInt("minute"))
        assertEquals("show_alarms", call("покажи будильники")?.name)
        assertEquals("show_alarms", call("мои будильники")?.name)
        assertTrue(Router.route("поставь будильник", now) is Router.Route.Ask)
        // таймеры по-прежнему работают
        assertEquals(300, call("поставь таймер на 5 минут")?.args?.getInt("seconds"))
        assertEquals("set_timer", call("засеки 10 минут")?.name)
    }

    @Test fun plainNotesStayNotes() {
        assertEquals("save_note", call("Запиши: купить хлеб")?.name)
        assertEquals("save_note", call("запомни что ключи у соседки")?.name)
        assertEquals("save_note", call("Запиши: Маша в 5 классе")?.name)
    }

    @Test fun readNotesIntents() {
        for (t in listOf("Что там с заметками", "что с заметками?", "покажи заметки", "какие у меня заметки", "мои заметки",
                "что я записал", "Что я записывала?", "заметки?", "Заметки", "прочитай мои заметки", "есть ли заметки"))
            assertEquals(t, "read_notes", call(t)?.name)
    }

    @Test fun saveNoteValidation() {
        val fake = ToolCall("save_note", JSONObject().put("text", "Пример текста для заметки"))
        assertNull(Tools.validate(fake, "Что там с заметками"))
        assertNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "купить молоко")), "что там с заметками"))
        assertNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "Текст заметки")), "запиши заметку"))
        assertNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "встреча с врачом в пятницу")), "запиши что-нибудь"))
        assertNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "купить хлеб")), "мне надо купить хлеб"))   // нет глагола
        assertNotNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "купить хлеб и молоко")), "запиши купить хлеб и молоко пожалуйста"))
        assertNotNull(Tools.validate(ToolCall("save_note", JSONObject().put("text", "код от домофона 1234")), "Запомни, код от домофона 1234"))
    }

    @Test fun agentDoesNotSaveFakeNotes() {
        val store = object : NotesStore { val l = mutableListOf<String>()
            override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }
        val fake = object : Agent.Llm {
            override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
                val out = if (prompt.contains("# Tools")) "<tool_call>{\"name\": \"save_note\", \"arguments\": {\"text\": \"Пример текста для заметки\"}}</tool_call>" else "ок"
                onText(out); return Pair(out, null)
            }
        }
        val tools = mutableListOf<String>()
        val ev = object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { tools += call.name }
            override fun onAssistantStart() {}
            override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) {}
            override fun onError(message: String) {}
            override fun isCancelled() = false
        }
        val ag = Agent(store, fake, MemoryReminders())
        for (t in listOf("Что там с заметками", "а что с моими заметками", "заметки сохраняются?", "расскажи как работают заметки")) ag.handle(t, ev)
        assertTrue("fake note saved: ${store.l}", store.l.isEmpty())
        ag.handle("Напомни через 10 минут выключить плиту", ev)
        assertEquals("set_reminder", tools.last())
    }

    @Test fun reminderExecuteAndHumanText() {
        val mem = MemoryReminders()
        val c = call("напомни завтра в 8 утра выпить таблетки")!!
        val res = Tools.execute(c, object : NotesStore { override fun all() = emptyList<String>(); override fun add(text: String) {}; override fun clear() {} },
            java.util.Date.from(now.atZone(java.time.ZoneId.systemDefault()).toInstant()), mem)
        assertTrue(res.ok); assertEquals(1, mem.all().size)
        assertEquals("Хорошо, напомню завтра в 8:00: «Выпить таблетки».", res.answer)
        assertEquals("🔔", res.card?.icon)
        assertEquals("по будням в 7:00", TimeParser.human(LocalDateTime.of(2026, 11, 17, 7, 0), now, TimeParser.fromMask(31)))
    }

    @Test fun webSearchHeuristics() {
        assertTrue(WebSearch.looksFactual("кто такой Пушкин?"))
        assertTrue(WebSearch.looksFactual("какая погода в Москве"))
        assertTrue(WebSearch.looksFactual("курс доллара"))
        assertTrue(WebSearch.looksFactual("последние новости"))
        assertFalse(WebSearch.looksFactual("привет"))
        assertFalse(WebSearch.looksFactual("как дела?"))
        assertEquals("Пушкин", WebSearch.topic("кто такой Пушкин?"))
        assertEquals("Вторая мировая война", WebSearch.topic("когда началась Вторая мировая война?"))
        assertEquals("Эльбрус", WebSearch.topic("какая высота Эльбрус"))
        assertEquals("Александр Пушкин (1799—1837) — русский поэт. Родился в Москве.",
            WebSearch.firstSentences("Александр Пушкин (1799—1837) — русский поэт. Родился в Москве. Третье предложение.", 2))
        assertTrue(WebSearch.cityVariants("Екатеринбурге").contains("Екатеринбург"))
        assertTrue(WebSearch.cityVariants("Москве").contains("Москва"))
        assertTrue(WebSearch.cityVariants("Перми").contains("Пермь"))
    }
}
