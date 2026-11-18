package ru.pocketagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReminderSoundTest {
    private val now = LocalDateTime.of(2026, 11, 17, 8, 0)
    private fun call(t: String) = (Router.route(t, now) as? Router.Route.Call)?.call
    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }

    @Test fun soundModifiers() {
        val c = call("Напомни со звуком завтра в 8 выпить таблетки")!!
        assertEquals("set_reminder", c.name); assertEquals(1, c.args.getInt("sound"))
        assertEquals("выпить таблетки", c.args.getString("text").lowercase())
        assertEquals(8, c.args.getInt("hour"))
        val g = call("напомни в 21:00 позвонить сыну громко")!!
        assertEquals(1, g.args.getInt("sound")); assertFalse(g.args.getString("text"), g.args.getString("text").contains("громко"))
        val q = call("Напомни без звука в 15:30 проверить почту")!!
        assertEquals(2, q.args.getInt("sound")); assertFalse(q.args.getString("text").contains("звук"))
        assertEquals(1, call("поставь напоминалку со звуковым сигналом на 5:00 утра")!!.args.getInt("sound"))
        // без модификатора — как в настройках (поле не задано)
        val plain = call("поставь напоминалку на 5:00 утра")!!
        assertEquals("set_reminder", plain.name); assertFalse(plain.args.has("sound"))
        // не напоминания — маршрутизация как раньше
        assertEquals("set_timer", call("поставь таймер на 5 минут")?.name)
        assertEquals("cancel_timer", call("Удали таймер на 5 минут который был")?.name)
        assertNotEquals("set_reminder", call("Поставь будильник на 7 утра громко")?.name)
    }

    @Test fun policy() {
        val r = Reminder(1, 0, 8, 0, 0, "т")
        assertTrue(SoundPolicy.loud(true, r)); assertFalse(SoundPolicy.loud(false, r))
        assertTrue(SoundPolicy.loud(false, r.copy(sound = 1))); assertFalse(SoundPolicy.loud(true, r.copy(sound = 2)))
        assertTrue(SoundPolicy.loud(false, r.copy(alarm = true)))
    }

    @Test fun oldRemindersStillLoad() {
        // запись 2.4 без поля snd — читается как «как в настройках», то есть со звуком по умолчанию
        val old = JSONObject("""{"id":3,"at":1763350000000,"h":21,"m":0,"days":0,"text":"Позвонить сыну","alarm":false}""")
        val r = ReminderJson.decode(old)
        assertEquals(0, r.sound); assertEquals("Позвонить сыну", r.text); assertEquals(21, r.hour)
        val back = ReminderJson.decode(ReminderJson.encode(r.copy(sound = 1)))
        assertEquals(1, back.sound); assertEquals(r.copy(sound = 1), back)
    }

    @Test fun executeStoresSoundAndSaysSo() {
        val st = MemoryReminders()
        val res = Tools.execute(call("Напомни со звуком завтра в 8 выпить таблетки")!!, notes, reminders = st)
        assertTrue(res.answer, res.answer.contains("со звуком"))
        assertEquals(1, st.all().single().sound)
        assertTrue(res.card!!.body.contains("🔊"))
        Tools.execute(call("поставь напоминалку на 5:00 утра")!!, notes, reminders = st)
        assertEquals(0, st.all().last().sound)
    }

    @Test fun agentRoutesSoundReminderWithoutModel() {
        val st = MemoryReminders()
        val ag = Agent(notes, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = "" to "no model" }, st, null)
        var tool = ""
        ag.handle("напомни со звуком через 10 минут снять кашу", object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { tool = call.name }
            override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) {}; override fun onError(message: String) {}
            override fun isCancelled() = false
        })
        assertEquals("set_reminder", tool); assertEquals(1, st.all().single().sound)
        assertTrue(st.all().single().text.lowercase().contains("кашу"))
    }
}
