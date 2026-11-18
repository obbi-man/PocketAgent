package ru.pocketagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V23Test {
    private val now = LocalDateTime.of(2026, 11, 16, 14, 0)
    private fun call(t: String) = (Router.route(t, now) as? Router.Route.Call)?.call

    // ---------- отмена таймеров / будильников / напоминаний (баг 2.2: «удали таймер…» ставил новый)

    @Test fun cancelTimerPhrasesNeverCreate() {
        for (p in listOf("Удали таймер на 5 минут который был", "Отмени таймер", "выключи таймер", "Останови таймер", "сбрось таймер",
                "Таймер выключи", "удалить таймер", "Стоп таймер", "отключи таймер на 10 минут", "cancel the timer")) {
            val c = call(p)
            assertEquals(p, "cancel_timer", c?.name)
        }
        assertEquals(300, call("Удали таймер на 5 минут который был")!!.args.getInt("seconds"))
        val neg = Router.route("Не ставь таймер", now)
        assertTrue(neg is Router.Route.Ask && (neg as Router.Route.Ask).pending == null)
        assertEquals("set_timer", call("Поставь таймер на 5 минут")?.name)
        assertEquals("set_timer", call("таймер на 3 минуты")?.name)
    }

    @Test fun cancelAlarmPhrases() {
        val a = call("Выключи будильник на 7 утра")!!
        assertEquals("cancel_alarm", a.name); assertEquals(7, a.args.getInt("hour")); assertEquals(0, a.args.getInt("minute"))
        assertTrue(call("Удали все будильники")!!.args.getBoolean("all"))
        assertEquals("cancel_alarm", call("Отмени будильник")?.name)
        assertEquals("cancel_alarm", call("будильник на 6:30 отмени")?.name)
        assertEquals(6, call("будильник на 6:30 отмени")!!.args.getInt("hour"))
        assertEquals("cancel_alarm", call("не буди меня завтра")?.name ?: "cancel_alarm".takeIf { Router.route("не буди меня завтра", now) is Router.Route.Ask })
        assertEquals("set_alarm", call("Разбуди меня в 7")?.name)
        assertEquals("set_alarm", call("Поставь будильник на 6:45")?.name)
    }

    @Test fun cancelReminderPhrases() {
        val r = call("Отмени напоминание про врача")!!
        assertEquals("cancel_reminder", r.name); assertTrue(r.args.optString("query").contains("врач"))
        assertEquals(21, call("Удали напоминание на 9 вечера")!!.args.getInt("hour"))
        assertEquals("cancel_reminder", call("удали все напоминания")?.name)
        assertTrue(call("удали все напоминания")!!.args.getBoolean("all"))
        assertEquals("cancel_reminder", call("Не напоминай про таблетки")?.name ?: "cancel_reminder".takeIf { Router.route("Не напоминай про таблетки", now) is Router.Route.Ask })
        // глагол «удалить» как содержание напоминания — это создание, а не отмена
        assertEquals("set_reminder", call("Напомни завтра в 10 удалить старые фото")?.name)
        assertEquals("set_reminder", call("Напомни через час выключить духовку")?.name)
        assertNotEquals("cancel_timer", call("Запиши: выключить таймер на плите в 5")?.name)
    }

    // ---------- баг 2.2: «поставь напоминалку на 5:00 утра» — модель ответила текстом, напоминания не было

    @Test fun reminderWordForms() {
        val c = call("поставь напоминалку на 5:00 утра")!!
        assertEquals("set_reminder", c.name); assertEquals("Напоминание", c.args.getString("text"))
        assertEquals(5, c.args.getInt("hour")); assertEquals(0, c.args.getInt("minute"))
        assertTrue(java.time.LocalDateTime.parse(c.args.getString("at")).isAfter(now))   // ближайшие 05:00 — завтра
        assertEquals(17, java.time.LocalDateTime.parse(c.args.getString("at")).dayOfMonth)
        for (p in listOf("создай напоминание на 18:30", "сделай напоминалку на 7 вечера", "Поставь напоминание на 9 утра", "напоминалка на 10:15",
                "поставь напоминалку на 5:00 утра выпить таблетки", "Напомни в 8 утра позвонить маме")) {
            assertEquals(p, "set_reminder", call(p)?.name)
        }
        assertEquals("Выпить таблетки", call("поставь напоминалку на 5:00 утра выпить таблетки")!!.args.getString("text"))
        assertEquals("cancel_reminder", call("удали напоминалку на 5 утра")?.name)
        assertEquals("list_reminders", call("покажи мои напоминалки")?.name)
    }

    @Test fun modelClaimWithoutToolIsNeverShown() {
        assertTrue(Agent.claimsAction("Конечно, вот ваш напоминание на 5:00 утра."))
        assertFalse(Agent.claimsAction("Сон важен для здоровья."))
        // модель «поставила» словами: агент не показывает выдумку
        val ag = Agent(notes, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
            val o = "Конечно, вот ваш напоминание на завтра."; onText(o); return o to null } }, MemoryReminders(), null)
        var out = ""; var tool = ""
        val ev = object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { tool = call.name; out = result.answer }
            override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) { out = text }; override fun onError(message: String) { out = "err:$message" }
            override fun isCancelled() = false
        }
        ag.handle("слушай, напоминалочку бы какую-нибудь", ev)
        assertEquals("", tool); assertEquals(Agent.NOT_DONE, out)
        // мягкое распознавание срабатывает до модели
        assertEquals("set_reminder", Router.relaxed("можешь поставить напоминалочку на 6 утра", now)?.call?.name)
    }

    @Test fun modelCannotCreateOnCancelPhrase() {
        val c = ToolCall("set_timer", JSONObject().put("seconds", 300))
        assertNull(Tools.validate(c, "Удали таймер на 5 минут который был"))
        assertNotNull(Tools.validate(c, "поставь таймер на 5 минут"))
    }

    private val notes = object : NotesStore { val l = mutableListOf<String>()
        override fun all() = l; override fun add(text: String) { l += text }; override fun clear() = l.clear() }

    @Test fun cancelReminderByTextTimeAndChoice() {
        val st = MemoryReminders()
        val base = System.currentTimeMillis() + 3600_000
        st.add(base, 9, 0, 0, "Позвонить врачу"); st.add(base + 1000, 21, 0, 0, "Выпить таблетки"); st.add(base + 2000, 21, 0, 0, "Позвонить сыну")
        // по тексту: один
        val r1 = Tools.execute(call("Отмени напоминание про врача")!!, notes, reminders = st)
        assertTrue(r1.answer, r1.answer.startsWith("Удалил напоминание") && st.all().none { it.text.contains("врач") })
        // по времени: два — спрашиваем какое
        val ag = Agent(notes, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = "" to "no model" }, st, null)
        var out = ""; var action: UiAction? = null
        val ev = object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { out = result.answer; action = result.action }
            override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) { out = text }; override fun onError(message: String) { out = "err:$message" }
            override fun isCancelled() = false
        }
        ag.handle("Удали напоминание на 21:00", ev)
        assertTrue(out, out.contains("Какое удалить") && action is UiAction.ChooseReminder)
        assertEquals(3 - 1, st.all().size)
        ag.handle("второе", ev)
        assertTrue(out, out.startsWith("Удалил напоминание") && out.contains("сыну"))
        assertEquals(listOf("Выпить таблетки"), st.all().map { it.text })
        // нет совпадений
        val r3 = Tools.execute(call("Отмени напоминание про собаку")!!, notes, reminders = st)
        assertTrue(r3.answer, r3.answer.startsWith("Не нашёл"))
    }

    @Test fun cancelTimerRemovesOwnFallbackTimers() {
        val st = MemoryReminders()
        st.add(System.currentTimeMillis() + 300_000, 14, 5, 0, "⏱ Таймер PocketAgent", alarm = true)
        val r = Tools.execute(call("Удали таймер на 5 минут который был")!!, notes, reminders = st)
        assertTrue(r.action is UiAction.CancelTimer); assertTrue(st.all().isEmpty())
    }

    // ---------- обновления

    private val sha = "a".repeat(64)

    @Test fun manifestOwnFormat() {
        val i = Updater.parse("""{"versionCode":7,"versionName":"2.3","apkUrl":"https://x/a.apk","sha256":"${sha.uppercase()}","changelog_ru":"Новое","minSdk":26,"size":123}""")
        assertEquals(7L, i.versionCode); assertEquals(sha, i.sha256); assertEquals("Новое", i.changelog); assertEquals(123L, i.size)
        assertTrue(Updater.isNewer(i, 6, 34)); assertFalse(Updater.isNewer(i, 7, 34)); assertFalse(Updater.isNewer(i, 6, 25))
        assertThrows(Updater.UpdateError::class.java) { Updater.parse("""{"versionCode":7,"apkUrl":"u","sha256":"zz"}""") }
        assertThrows(Updater.UpdateError::class.java) { Updater.parse("<html>quota</html>") }
    }

    @Test fun manifestGitHubFormat() {
        val gh = """{"tag_name":"v2.3","name":"PocketAgent 2.3","body":"Что нового:\n- файлы\nversionCode: 7\nsha256: $sha",
            "assets":[{"name":"PocketAgent-2.3.apk","browser_download_url":"https://gh/full.apk","size":5},{"name":"PocketAgent-2.3-lite.apk","browser_download_url":"https://gh/lite.apk","size":3}]}"""
        val i = Updater.parse(gh)
        assertEquals(7L, i.versionCode); assertEquals("https://gh/lite.apk", i.apkUrl); assertEquals("2.3", i.versionName)
        assertFalse(i.changelog.contains("sha256")); assertTrue(i.changelog.contains("файлы"))
        assertEquals(2_003_001L, Updater.codeFromName("2.3.1"))
    }

    @Test fun autoCheckPolicy() {
        val day = Updater.DAY_MS
        assertTrue(Updater.shouldAutoCheck(true, true, 0, day, unmetered = true, wifiOnly = true))
        assertFalse(Updater.shouldAutoCheck(true, true, 0, day, unmetered = false, wifiOnly = true))
        assertTrue(Updater.shouldAutoCheck(true, true, 0, day, unmetered = false, wifiOnly = false))
        assertFalse(Updater.shouldAutoCheck(true, true, day, day + 1000, true, true))   // не чаще раза в сутки
        assertFalse(Updater.shouldAutoCheck(false, true, 0, day, true, true))           // URL пуст
        assertFalse(Updater.shouldAutoCheck(true, false, 0, day, true, true))           // выключено пользователем
    }

    @Test fun driveVirusScanInterstitial() {
        val html = """<html><body><form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
            <input type="submit" value="Download anyway"/><input type="hidden" name="id" value="FILEID">
            <input type="hidden" name="export" value="download"><input type="hidden" name="confirm" value="t">
            <input type="hidden" name="uuid" value="1234-abcd"></form></body></html>"""
        assertEquals("https://drive.usercontent.google.com/download?id=FILEID&export=download&confirm=t&uuid=1234-abcd",
            Updater.driveConfirmUrl(html, "https://drive.google.com/uc?export=download&id=FILEID"))
        val old = """<a href="/uc?export=download&amp;confirm=AbC_1&amp;id=FILEID">Download anyway</a>"""
        assertEquals("https://drive.usercontent.google.com/download?id=FILEID&export=download&confirm=AbC_1",
            Updater.driveConfirmUrl(old, "https://drive.google.com/uc?export=download&id=FILEID"))
        assertNull(Updater.driveConfirmUrl("<html>Quota exceeded</html>", "https://x?id=1"))
    }

    // ---------- файлы и экран

    private val sample = "Договор аренды № 15-А.\nАрендатор: Иванов Пётр, телефон +7 912 345-67-89, почта petr@example.ru.\n" +
        "Арендная плата составляет 25 000 руб. в месяц и вносится до 5 числа.\nДата подписания: 12.03.2025.\n" +
        "Арендатор обязуется содержать квартиру в чистоте. Домашние животные разрешены только с согласия владельца."

    @Test fun docRetrievalAndExtractiveAnswers() {
        val d = DocIndex("договор.pdf", DocIndex.Kind.FILE, sample)
        assertTrue(d.search("животные").first().contains("животные"))
        val f = DocQA.find("найди про животных", d)
        assertTrue(f, f.contains("«Домашние животные разрешены"))
        assertTrue(DocQA.find("найди бассейн", d).contains("не нашёл"))
        assertTrue(DocQA.extractNumbers("какой тут телефон", d)!!.contains("+7 912 345-67-89"))
        assertTrue(DocQA.extractNumbers("какая почта", d)!!.contains("petr@example.ru"))
        assertTrue(DocQA.extractNumbers("какая дата", d)!!.contains("12.03.2025"))
        DocQA.extractNumbers("какая сумма", d)!!.let { assertTrue(it, it.replace(Regex("[\\s\u00A0]"), "").contains("25000")) }
        assertEquals(DocQA.Intent.SUMMARY, DocQA.intent("перескажи файл"))
        assertEquals(DocQA.Intent.SUMMARY, DocQA.intent("О чём документ?"))
        assertEquals(DocQA.Intent.FIND, DocQA.intent("найди сроки оплаты"))
        assertEquals(DocQA.Intent.TRANSLATE, DocQA.intent("переведи"))
        assertEquals(DocQA.Intent.NUMBER, DocQA.intent("какой тут номер"))
        assertEquals(DocQA.Intent.READ, DocQA.intent("что тут написано"))
    }

    @Test fun docPickRules() {
        val file = DocIndex("a.txt", DocIndex.Kind.FILE, "x y z")
        val scr = DocIndex("экран", DocIndex.Kind.SCREEN, "экранный текст")
        assertSame(file, DocQA.pick("перескажи файл", file, scr, false))
        assertSame(scr, DocQA.pick("что тут написано", file, scr, false))
        assertSame(scr, DocQA.pick("Который час?", file, scr, true))   // нажата «Спросить про экран»
        assertNull(DocQA.pick("Который час?", file, scr, false))
        assertNull(DocQA.pick("Поставь будильник на 7", file, null, false))
        assertSame(file, DocQA.pick("какой тут телефон", file, null, false))
        val todo = DocIndex("spisok.txt", DocIndex.Kind.FILE, "Понедельник — врач в 10:00, кабинет 214.\nПятница — забрать посылку, код 4471.")
        assertTrue(DocQA.extractNumbers("какой тут номер кабинета", todo)!!.contains("кабинет 214"))   // экран не читали — «тут» = файл
        val pdf = DocIndex("a.pdf", DocIndex.Kind.FILE, "4. Домашние животные разрешены только с\nписьменного согласия Арендодателя.\n5. Срок 11 месяцев.")
        assertEquals("Домашние животные разрешены только с письменного согласия Арендодателя.", pdf.quotes("животные", 1).single().removePrefix("4. "))
    }

    @Test fun agentAnswersFromFileWithoutInventing() {
        val ag = Agent(notes, object : Agent.Llm { override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean) = "" to "no model" }, null, null)
        ag.file = DocIndex("договор.pdf", DocIndex.Kind.FILE, sample)
        var out = ""; var used: DocIndex? = null
        ag.handle("найди в файле про животных", object : Agent.Events {
            override fun onToolResult(call: ToolCall, result: ToolResult, source: String) { out = "tool" }
            override fun onAssistantStart() {}; override fun onAssistantText(fullSoFar: String) {}
            override fun onAssistantDone(text: String) { out = text }; override fun onError(message: String) { out = "err" }
            override fun isCancelled() = false
            override fun onDocUsed(doc: DocIndex) { used = doc }
        })
        assertTrue(out, out.contains("Домашние животные") && out.contains("📎 договор.pdf")); assertNotNull(used)
    }

    @Test fun bigFileChunks() {
        val big = (1..400).joinToString("\n") { "Абзац $it про разное, слово$it и ещё немного текста для объёма." }
        val d = DocIndex("big.txt", DocIndex.Kind.FILE, big)
        assertTrue(d.chunks.size > 20); assertTrue(d.chunks.all { it.length <= DocIndex.CHUNK + 100 })
        assertTrue(d.search("слово377").single().contains("слово377"))
        assertTrue(d.head(1000).length <= 1003)
    }

    @Test fun docxAndEncodings() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Привет,</w:t></w:r><w:r><w:t xml:space="preserve"> мир &amp; друзья</w:t></w:r></w:p><w:p><w:r><w:t>Вторая</w:t><w:tab/><w:t>строка</w:t></w:r></w:p></w:body></w:document>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("x".toByteArray()); z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()) }
        assertEquals("Привет, мир & друзья\nВторая\tстрока", DocText.docx(ByteArrayInputStream(bos.toByteArray())))
        assertEquals("Тест кодировки", DocText.readText(ByteArrayInputStream("Тест кодировки".toByteArray(charset("windows-1251")))))
        assertEquals("pdf", DocText.kindOf("a.PDF", null)); assertEquals("docx", DocText.kindOf("b.docx", null))
        assertEquals("image", DocText.kindOf("c.jpg", "image/jpeg")); assertEquals("text", DocText.kindOf("d.csv", null))
    }

    @Test fun screenTextClean() {
        val t = ScreenText.clean(listOf("Настройки", "  Настройки ", "•", "Wi-Fi", "Подключено к Дом", "", "Wi-Fi"))
        assertEquals("Настройки\nWi-Fi\nПодключено к Дом", t)
    }

    @Test fun noteMirrorOrder() {
        val s = NoteMirror.sort(listOf(NoteMirror.App("org.telegram.messenger", "Telegram"), NoteMirror.App("com.google.android.keep", "Keep"),
            NoteMirror.App("com.samsung.android.app.notes", "Samsung Notes"), NoteMirror.App("a.b", "Альфа")))
        assertEquals(listOf("Samsung Notes", "Keep", "Telegram", "Альфа"), s.map { it.label })
        assertEquals("Купить молоко", NoteMirror.title("\nКупить молоко\nи хлеб"))
        assertEquals("1. a\n\n2. b", NoteMirror.exportAll(listOf("a", "b")))
    }
}
