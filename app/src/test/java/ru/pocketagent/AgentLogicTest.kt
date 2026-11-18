package ru.pocketagent

import org.junit.Assert.*
import org.junit.Test

class AgentLogicTest {
    private fun call(t: String) = (Router.route(t) as? Router.Route.Call)?.call

    @Test fun calculator() {
        assertEquals("1825", Calculator.format(Calculator.eval("37*49+12")))
        assertEquals("36", Calculator.format(Calculator.eval("15/100*240")))
        assertEquals("3.5", Calculator.format(Calculator.eval("7 / 2")))
        assertEquals("12", Calculator.format(Calculator.eval("sqrt 144")))
        assertEquals("1024", Calculator.format(Calculator.eval("2^10")))
        assertEquals("6", Calculator.format(Calculator.eval("2 х 3")))
        assertEquals("-4", Calculator.format(Calculator.eval("-(2+2)")))
    }

    @Test fun routes() {
        assertEquals("calculator", call("Сколько будет 37*49+12?")?.name)
        assertEquals("calculator", call("15% от 240")?.name)
        assertEquals("calculator", call("What is 2+2?")?.name)
        assertEquals("get_datetime", call("Который сейчас час?")?.name)
        assertEquals("get_datetime", call("какое сегодня число")?.name)
        val n = call("Запомни: купить молоко"); assertEquals("save_note", n?.name); assertEquals("купить молоко", n?.args?.getString("text"))
        assertEquals("read_notes", call("Покажи мои заметки")?.name)
        assertEquals("read_notes", call("что у меня в заметках?")?.name)
        assertEquals("clear_notes", call("удали все заметки")?.name)
        val tm = call("Поставь таймер на 5 минут"); assertEquals("set_timer", tm?.name); assertEquals(300, tm?.args?.getInt("seconds"))
        assertEquals(5400, call("таймер на 1 час 30 минут")?.args?.getInt("seconds"))
        assertEquals(1800, call("засеки полчаса")?.args?.getInt("seconds"))
        assertEquals(120, call("таймер на две минуты")?.args?.getInt("seconds"))
        assertTrue(Router.route("поставь таймер") is Router.Route.Ask)
        assertEquals("https://ya.ru", call("Открой сайт ya.ru")?.args?.getString("url"))
        assertEquals("https://youtube.com", call("открой ютуб")?.args?.getString("url"))
        assertEquals("номер подъезда 4", call("сохрани в заметки номер подъезда 4")?.args?.getString("text"))
        assertEquals("the wifi password is on the fridge", call("Remember that the wifi password is on the fridge")?.args?.getString("text"))
        assertEquals(180, call("Please set a timer for three minutes")?.args?.getInt("seconds"))
        assertNull(Router.route("Привет! Кто ты?"))
        assertNull(Router.route("Расскажи про фотосинтез"))
        assertNull(Router.route("У меня 2 кота"))
    }

    @Test fun parser() {
        val a = ToolCallParser.parse("<tool_call>\n{\"name\": \"calculator\", \"arguments\": {\"expression\": \"2+2\"}}\n</tool_call>")
        assertEquals("calculator", a?.name)
        val b = ToolCallParser.parse("```json\n{\n  \"name\": \"set_timer\",\n  \"arguments\": {\"seconds\": 180}\n}\n```")
        assertEquals(180, b?.args?.getInt("seconds"))
        assertNull(ToolCallParser.parse("{\"name\": \"open_youtube\", \"arguments\": []}"))
        assertNull(ToolCallParser.parse("Просто текст без JSON"))
    }

    @Test fun validation() {
        // типичная ошибка 0.3B: вызвать get_datetime на приветствие
        assertNull(Tools.validate(ToolCall("get_datetime", org.json.JSONObject()), "Привет, кто ты?"))
        assertNotNull(Tools.validate(ToolCall("get_datetime", org.json.JSONObject()), "what time is it"))
        assertNull(Tools.validate(ToolCall("calculator", org.json.JSONObject().put("expression", "abc")), "посчитай"))
        assertEquals(180, Tools.validate(ToolCall("set_timer", org.json.JSONObject().put("seconds", 3)), "set a timer for 3 minutes")?.args?.getInt("seconds"))
        assertNull(Tools.validate(ToolCall("open_url", org.json.JSONObject().put("url", "javascript:alert(1)")), "open"))
    }

    @Test fun agentFlowWithFakeLlm() {
        val store = object : NotesStore { val l = mutableListOf<String>()
            override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }
        var lastPrompt = ""
        val fake = object : Agent.Llm {
            override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
                lastPrompt = prompt
                val out = if (prompt.contains("# Tools")) "<tool_call>\n{\"name\": \"get_datetime\", \"arguments\": {}}\n</tool_call>" else "Привет! Я Pocket Agent."
                onText(out); return Pair(out, null)
            }
        }
        val ag = Agent(store, fake)
        val log = mutableListOf<String>()
        val ev = object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { log += "tool:${call.name}:$source" }
            override fun onAssistantStart() {}
            override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) { log += "done:$text" }
            override fun onError(message: String) { log += "err:$message" }
            override fun isCancelled() = false
        }
        ag.handle("Запомни: позвонить маме", ev)
        assertEquals(listOf("позвонить маме"), store.l)
        ag.handle("Привет", ev)                         // нет ключевых слов → сразу чат
        assertTrue(log.last().startsWith("done:Привет"))
        ag.handle("какое время года лучше для отпуска", ev) // «время» → планировщик предложил get_datetime → отклонено → чат
        assertFalse(log.any { it.startsWith("tool:get_datetime") })
        assertTrue(log.last().startsWith("done:"))
        ag.handle("подскажи текущее время пожалуйста", ev)  // правило не сработало, модель → валидно
        assertTrue(log.any { it == "tool:get_datetime:модель" } || log.any { it == "tool:get_datetime:правило" })
        assertTrue(lastPrompt.startsWith("<|im_start|>system"))
        assertTrue(ag.chatPrompt().endsWith("<|im_start|>assistant\n"))
    }

    /** Android использует ICU regex: (?U), \\w и \\b ведут себя иначе или падают. Запрещаем их в исходниках. */
    @Test fun noAndroidIncompatibleRegex() {
        val dir = java.io.File("src/main/java/ru/pocketagent")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val code = f.readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
            assertFalse("${f.name}: (?U)", code.contains("(?U)") || code.contains("(?iU)"))
            assertFalse("${f.name}: \\w", code.contains("\\\\w"))
            assertFalse("${f.name}: \\b", code.contains("\\\\b"))
        }
    }

    @Test fun routerNeverThrowsOnGreetings() {
        for (t in listOf("привет", "Привет!", "здравствуй", "hi", "😀", "", "   ", "ёжик в тумане", "таймер", "открой"))
            Router.route(t)
    }
}
