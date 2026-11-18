package ru.pocketagent

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Реальная сеть: запускается только с NET_TEST=1. Печатает найденное в NET_TEST_OUT. */
class WebSearchNetTest {
    @Test fun live() {
        assumeTrue(System.getenv("NET_TEST") == "1")
        val out = StringBuilder()
        for (q in listOf("кто такой Пушкин?", "какая погода в Екатеринбурге", "погода в Москве завтра", "погода в Перми", "курс доллара", "когда началась Вторая мировая война", "последние новости космос")) {
            val t0 = System.currentTimeMillis()
            val r = try { WebSearch.search(q) } catch (e: Exception) { null.also { out.append("ERR $q: $e\n") } }
            out.append("=== $q (${System.currentTimeMillis() - t0} мс) → ${r?.source} ${r?.url}\nОТВЕТ: ${r?.direct}\n---\n${r?.text}\n\n")
            if (r != null && q.contains("Пушкин")) {
                // промпт для модели — сохраняем, чтобы прогнать через настоящую модель
                java.io.File(System.getenv("NET_TEST_OUT") + ".prompt").writeText(Agent(object : NotesStore {
                    override fun all() = emptyList<String>(); override fun add(text: String) {}; override fun clear() {}
                }, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = Pair("", null) })
                    .also { it.history += Msg("user", q) }.searchPrompt(q, r))
            }
        }
        // диалоги с уточнениями через настоящего агента (модель — заглушка, которая громко ругается, если её вызвали)
        val real = object : Agent.Searcher {
            override fun enabled() = true; override fun online() = true
            override fun search(q: String) = WebSearch.search(q)
            override fun lookup(q: String) = WebSearch.lookup(q)
            override fun rates() = WebSearch.cbrRates()
        }
        val noLlm = object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = Pair("[МОДЕЛЬ ВЫЗВАНА]", null as String?) }
        val notes = object : NotesStore { override fun all() = emptyList<String>(); override fun add(text: String) {}; override fun clear() {} }
        for (dialog in listOf(listOf("Сколько стоит 4060", "А в рублях", "а в евро"), listOf("погода в Екатеринбурге", "а завтра?", "а в Казани?"), listOf("1500 долларов в рублях"),
                listOf("Расскажи про Екатеринбург", "Ты начал писать о центре истории екб, что про него можешь сказать", "А что про него можешь сказать?", "Кто такой Бажов?", "Что можешь сказать про плотинку"))) {
            val ag = Agent(notes, noLlm, null, real)
            out.append("##### ДИАЛОГ\n")
            for (q in dialog) {
                var ans = ""
                ag.handle(q, object : Agent.Events {
                    override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { ans = "tool ${call.name}: ${result.answer}" }
                    override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
                    override fun onAssistantDone(text: String) { ans = text }
                    override fun onError(message: String) { ans = "ERR $message" }
                    override fun isCancelled() = false
                })
                out.append("> $q\n$ans\n\n")
            }
        }
        java.io.File(System.getenv("NET_TEST_OUT")).writeText(out.toString())
    }
}
