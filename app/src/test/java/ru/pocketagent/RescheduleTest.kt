package ru.pocketagent

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** 2.6: «Перенеси напоминалку на 8 вечера» переносит существующее, а не создаёт «Перенеси напоминалку». */
class RescheduleTest {
    private val now = LocalDateTime.of(2026, 11, 17, 12, 0)
    private val nowD: Date = Date.from(now.atZone(ZoneId.systemDefault()).toInstant())
    private fun call(t: String, recent: String? = null) = (Router.route(t, now, recent) as? Router.Route.Call)?.call
    private fun ms(t: LocalDateTime) = Router.millis(t)
    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }
    private fun store(vararg r: Pair<LocalDateTime, String>) = MemoryReminders().also { st -> r.forEach { (t, x) -> st.add(ms(t), t.hour, t.minute, 0, x) } }
    private fun exec(c: ToolCall, st: RemindersStore) = Tools.execute(c, notes, nowD, st)

    @Test fun reschedulePhrasingsNeverCreate() {
        for (p in listOf("Перенеси напоминалку на 8 вечера", "перенеси напоминание на 20:00", "Сдвинь напоминание на 20:00",
            "передвинь напоминалку на 8 вечера", "поменяй время напоминания на 20:00", "измени напоминание на 8 вечера",
            "Перенеси его на 8 вечера", "перенеси это на 20:00", "отложи напоминание на 20:00", "Можешь перенести напоминалку на 8 вечера?")) {
            val c = call(p, "reminder")
            assertNotNull(p, c); assertEquals(p, "edit_reminder", c!!.name)
            assertEquals(p, "at", c.args.getString("mode")); assertEquals(p, 20, c.args.getInt("hour")); assertEquals(p, 0, c.args.getInt("minute"))
            assertFalse(p, c.args.has("query"))
        }
        // relaxed() (страховка после модели) тоже не создаёт
        assertEquals("edit_reminder", Router.relaxed("Перенеси напоминалку на 8 вечера", now)?.call?.name)
        // обычный разговор не трогаем
        for (p in listOf("Измени это", "поменяй это на более вежливое", "отложи этот вопрос", "исправь ошибки в тексте", "перенеси слово на новую строку")) {
            val r = Router.route(p, now)
            assertFalse(p, (r as? Router.Route.Call)?.call?.name in setOf("edit_reminder", "set_reminder", "move_alarm") || r is Router.Route.Ask)
        }
        // а настоящее содержание — по-прежнему создание
        val c = call("Напомни в 20:00 перенести встречу с Ваней")!!
        assertEquals("set_reminder", c.name); assertTrue(c.args.getString("text").lowercase().contains("перенести встречу"))
    }

    @Test fun theBugFromThePhone() {
        val st = store(LocalDateTime.of(2026, 11, 17, 18, 0) to "Сделать дизайн проект агента под сметы")
        val res = exec(call("Перенеси напоминалку на 8 вечера")!!, st)
        assertTrue(res.answer, res.ok)
        val r = st.all().single()
        assertEquals("Сделать дизайн проект агента под сметы", r.text)
        assertEquals(20, r.hour); assertEquals(0, r.minute); assertEquals(ms(LocalDateTime.of(2026, 11, 17, 20, 0)), r.atMs)
        assertEquals("Напоминание перенесено: сегодня в 20:00", res.card!!.title)
        assertTrue(res.card!!.body.startsWith("Сделать дизайн проект агента под сметы"))
        assertTrue(res.action is UiAction.ReminderSet)
    }

    @Test fun agentUsesMostRecentInChatAndPickerWhenAmbiguous() {
        val st = store(LocalDateTime.of(2026, 11, 17, 15, 0) to "Купить хлеб", LocalDateTime.of(2026, 11, 17, 18, 0) to "Сделать дизайн проект")
        val names = mutableListOf<ToolCall>(); val results = mutableListOf<ToolResult>()
        val ev = object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { names += call; results += result }
            override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) {}; override fun onError(message: String) {}
            override fun isCancelled() = false
        }
        val ag = Agent(notes, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = "" to "no model" }, st, null)
        // два напоминания, в чате о них не говорили → выбор
        ag.handle("Перенеси напоминалку на 22:00", ev)
        assertEquals("edit_reminder", names.last().name)
        assertTrue(results.last().action is UiAction.ChooseEdit)
        assertEquals(2, st.all().size)
        ag.handle("второе", ev)
        assertEquals(22, st.all().first { it.text == "Сделать дизайн проект" }.hour)
        assertEquals(15, st.all().first { it.text == "Купить хлеб" }.hour)
        // «перенеси его» — то, о котором только что говорили
        ag.handle("перенеси его на час позже", ev)
        assertEquals(23, st.all().first { it.text == "Сделать дизайн проект" }.hour)
        // новое в чате → «отмени напоминалку» удаляет именно его
        ag.handle("Напомни в 19:00 позвонить маме", ev)
        assertEquals(3, st.all().size)
        ag.handle("отмени напоминалку", ev)
        assertEquals(listOf("Купить хлеб", "Сделать дизайн проект"), st.all().map { it.text }.sorted())
        assertFalse(names.any { it.name == "set_reminder" && it.args.getString("text").lowercase().contains("перенес") })
    }

    @Test fun matchByTextAndOldTime() {
        val st = store(LocalDateTime.of(2026, 11, 17, 15, 0) to "Купить хлеб", LocalDateTime.of(2026, 11, 17, 18, 0) to "Позвонить маме")
        val c = call("перенеси напоминание про хлеб на 16:30")!!
        assertEquals("хлеб", c.args.getString("query"))
        exec(c, st)
        assertEquals(16, st.all().first { it.text == "Купить хлеб" }.hour); assertEquals(30, st.all().first { it.text == "Купить хлеб" }.minute)
        exec(call("перенеси напоминание с 18:00 на 21:00")!!, st)
        assertEquals(21, st.all().first { it.text == "Позвонить маме" }.hour)
        // без существительного, по словам: «перенеси звонок маме на 22:00» → ищем напоминание
        val soft = call("перенеси позвонить маме на 22:00")!!
        assertEquals("edit_reminder", soft.name)
        exec(soft, st); assertEquals(22, st.all().first { it.text == "Позвонить маме" }.hour)
        // «на завтра» — то же время завтра
        exec(call("перенеси напоминание про хлеб на завтра")!!, st)
        assertEquals(ms(LocalDateTime.of(2026, 11, 18, 16, 30)), st.all().first { it.text == "Купить хлеб" }.atMs)
        // не нашли — честно, со списком, ничего не создаём
        val nf = exec(call("перенеси напоминание про собаку на 10 вечера")!!, st)
        assertFalse(nf.ok); assertTrue(nf.answer.contains("Не нашёл")); assertEquals(2, st.all().size)
    }

    @Test fun cancelTextAndSnooze() {
        val st = store(LocalDateTime.of(2026, 11, 17, 18, 0) to "Сделать дизайн проект")
        val cc = call("отмени напоминалку")!!; assertEquals("cancel_reminder", cc.name)
        assertEquals("cancel_reminder", call("удали напоминалку")!!.name)
        val t = call("измени текст напоминания на купить молоко и хлеб")!!
        assertEquals("edit_reminder", t.name); assertEquals("Купить молоко и хлеб", t.args.getString("new_text"))
        exec(t, st)
        assertEquals("Купить молоко и хлеб", st.all().single().text); assertEquals(18, st.all().single().hour)
        assertEquals("edit_reminder", call("переименуй напоминание в «Позвонить в банк»")!!.name)
        // «отложи на 10 минут»: будущее напоминание сдвигается на 10 минут
        val s = call("отложи на 10 минут")!!
        assertEquals("edit_reminder", s.name); assertEquals("snooze", s.args.getString("mode")); assertEquals(600, s.args.getInt("seconds"))
        exec(s, st)
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 18, 10)), st.all().single().atMs)
        // только что сработавшее — новое через 10 минут от сейчас, с тем же текстом
        val fired = Reminder(77, ms(now.minusMinutes(1)), 11, 59, 0, "Выпить таблетки", sound = 1)
        st.fired[77] = fired to nowD.time - 30_000
        val res = exec(call("отложи на 10 минут")!!, st)
        assertTrue(res.answer, res.ok); assertEquals("Напоминание отложено: сегодня в 12:10", res.card!!.title)
        val n = st.all().first { it.text == "Выпить таблетки" }
        assertEquals(ms(now.plusMinutes(10)), n.atMs); assertEquals(1, n.sound)
        assertEquals(77, (res.action as UiAction.ReminderSet).stopRinging)
        assertTrue(st.fired.isEmpty())
        // «отложи напоминание» без времени — 10 минут по умолчанию
        assertEquals(600, call("отложи напоминание")!!.args.getInt("seconds"))
    }

    @Test fun commandWordsNeverBecomeReminderText() {
        for (x in listOf("Перенеси напоминалку", "Удали", "отмени это", "Измени напоминание", "сдвинь его")) assertTrue(x, Router.isCommandOnly(x))
        for (x in listOf("Перенести встречу с Ваней", "Удалить старые фото", "Купить хлеб")) assertFalse(x, Router.isCommandOnly(x))
        // даже если модель/правила дали такой текст — новое напоминание не создаётся
        val st = MemoryReminders()
        val bad = ToolCall("set_reminder", org.json.JSONObject().put("text", "Перенеси напоминалку").put("at_ms", ms(now.plusHours(8))).put("hour", 20).put("minute", 0))
        val r = exec(bad, st)
        assertFalse(r.ok); assertTrue(st.all().isEmpty())
        // модель предлагает set_reminder на команду переноса — отклоняется
        assertNull(Tools.validate(bad, "Перенеси напоминалку на 8 вечера"))
        // «напоминалка перенеси на 8» в любом порядке слов — не создание
        assertNotEquals("set_reminder", call("напоминалку перенеси на 8 вечера")?.name)
        assertNotEquals("set_reminder", call("поставь напоминалку на 8 вечера перенеси")?.name)
    }

    @Test fun alarms() {
        val c = call("перенеси будильник на 7")!!
        assertEquals("move_alarm", c.name); assertEquals(7, c.args.getInt("hour")); assertEquals(0, c.args.getInt("minute"))
        val c2 = call("передвинь будильник с 6:30 на 7:15")!!
        assertEquals(6, c2.args.getInt("old_hour")); assertEquals(30, c2.args.getInt("old_minute")); assertEquals(7, c2.args.getInt("hour")); assertEquals(15, c2.args.getInt("minute"))
        val res = exec(c2, MemoryReminders())
        val mv = res.action as UiAction.MoveAlarm
        assertEquals(6, mv.oldHour); assertEquals(7, mv.hour); assertEquals("Будильник перенесён на 7:15", res.card!!.title)
        // без старого времени — честно говорим, что старый надо выключить в списке
        assertTrue(exec(c, MemoryReminders()).card!!.body.contains("не знаю"))
        // свой резервный будильник меняется сам
        val st = MemoryReminders(); st.add(ms(LocalDateTime.of(2026, 11, 18, 6, 30)), 6, 30, 0, "⏰ Будильник", alarm = true)
        exec(call("перенеси будильник на 7")!!, st)
        assertEquals(7, st.all().single().hour)
        assertTrue(call("перенеси будильник") .let { it == null } && Router.route("перенеси будильник", now) is Router.Route.Ask)
        assertEquals("cancel_alarm", call("выключи будильник на 7")!!.name)
        assertEquals("set_alarm", call("поставь будильник на 7 утра")!!.name)
    }
}
