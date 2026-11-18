package ru.pocketagent

import org.junit.Test
import java.io.File

/** Выгружает реальные промпты агента в файлы для проверки нативного инференса на ПК (если задан PROMPT_DUMP_DIR). */
class PromptDumpTest {
    @Test fun dump() {
        val dir = System.getenv("PROMPT_DUMP_DIR") ?: return
        File(dir).mkdirs()
        val store = object : NotesStore { override fun all() = listOf<String>(); override fun add(text: String) {}; override fun clear() {} }
        val dummy = object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = Pair("", null as String?) }
        val planner = listOf("Please set a timer for three minutes", "Remember that the wifi password is on the fridge",
            "подскажи сколько времени", "открой страницу погоды yandex.ru/pogoda", "сохрани в заметки номер подъезда 4")
        planner.forEachIndexed { i, q -> File(dir, "plan$i.txt").writeText(Agent(store, dummy).plannerPrompt(q)) }
        val chats = listOf("Hi! Who are you?", "Explain photosynthesis in two sentences.", "Привет! Кто ты?", "Напиши короткое стихотворение про море.")
        chats.forEachIndexed { i, q -> val a = Agent(store, dummy); a.history += Msg("user", q); File(dir, "chat$i.txt").writeText(a.chatPrompt()) }
        // многоходовой диалог (проверка KV-префикс-кэша)
        val a = Agent(store, dummy)
        a.history += Msg("user", "My name is Obbi."); a.history += Msg("assistant", "Nice to meet you, Obbi!"); a.history += Msg("user", "What is my name?")
        File(dir, "chat9_multiturn.txt").writeText(a.chatPrompt())
    }
}
