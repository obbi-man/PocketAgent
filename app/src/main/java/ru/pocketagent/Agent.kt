package ru.pocketagent

data class Msg(val role: String, val content: String)   // role: user | assistant

/** Построение ChatML-промптов и цикл «правила → модель-планировщик → инструмент / поиск → ответ». */
class Agent(
    private val notes: NotesStore,
    private val llm: Llm,
    private val reminders: RemindersStore? = null,
    private val searcher: Searcher? = null
) {

    interface Llm {
        /** Возвращает (полный текст, ошибка). onText получает куски; false — стоп. */
        fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?>
    }

    interface Searcher {
        fun enabled(): Boolean
        fun online(): Boolean
        fun search(q: String): WebSearch.Result?
        /** Справка о сущности (2.4). По умолчанию — обычный поиск. */
        fun lookup(q: String): WebSearch.Result? = search(q)
        /** Курсы ЦБ РФ (рублей за 1 единицу); null — не удалось. */
        fun rates(): Map<String, Double>? = null
    }

    interface Events {
        fun onToolResult(call: ToolCall, result: ToolResult, source: String)
        fun onAssistantStart()
        fun onAssistantText(fullSoFar: String)
        fun onAssistantDone(text: String)
        fun onError(message: String)
        fun isCancelled(): Boolean
        /** Перед ответом с поиском: source — откуда сведения; null — искать не получилось (нет сети/ошибка). */
        fun onWebSearch(source: String?, url: String?) {}
        /** Ответ дан по прикреплённому файлу или тексту экрана. */
        fun onDocUsed(doc: DocIndex) {}
        /** Сохранены факты о пользователе (auto — извлечены из обычного сообщения: показать «Запомнил» с отменой). */
        fun onFactsSaved(facts: List<Fact>, auto: Boolean) {}
    }

    val history = mutableListOf<Msg>()
    var agentMode = true
    @Volatile var pending: Router.Pending? = null
    /** Последний ответ с поиском — для уточнений «а в рублях?», «а завтра?». */
    @Volatile var lastSearch: FollowUp.Ctx? = null
    /** Прикреплённый файл и прочитанный экран (окно помощника). */
    @Volatile var file: DocIndex? = null
    @Volatile var screen: DocIndex? = null
    /** Следующий вопрос — про экран (нажата «Спросить про экран»). */
    @Volatile var forceScreen = false
    /** Память о пользователе и стиль общения (на телефоне — JSON-файлы во внутренней памяти). */
    var facts: FactStore = FactStore()
    var style: StyleProfile = StyleProfile()
    /** Тема последнего фактического вопроса — для «что про него можешь сказать». */
    @Volatile var lastTopic: String? = null
    /** Не нашли/нет сети — если пользователь настаивает («предположи»), даём осторожную фразу. */
    @Volatile private var pendingHedge: String? = null
    /** 2.6: о чём шла речь в этом чате — для «перенеси его», «отмени напоминалку». */
    @Volatile var lastObj: String? = null
    @Volatile var lastReminderId: Int = 0
    @Volatile var lastAlarm: Pair<Int, Int>? = null

    companion object {
        const val SYSTEM_CHAT = "Ты — PocketAgent, дружелюбный помощник, который работает прямо на телефоне без интернета " +
                "(небольшая локальная языковая модель). Отвечай на языке пользователя, обычно по-русски: просто, вежливо и коротко. " +
                "Если не уверен в факте — честно скажи, что не знаешь, и не выдумывай. Не называй организаций, имён, названий и исторических событий, если тебя о них не спрашивали и ты не уверен. Не называй цены, суммы, даты и другие числа, если не уверен в них. " +
                "Отвечай кратко: 2–4 предложения, без списков, заголовков и разметки (никаких **)."
        const val SYSTEM_LIVE = " Сейчас идёт голосовой разговор: отвечай очень коротко, 1–2 предложения, без списков, смайликов и разметки."
        const val SYSTEM_TOOLS = "You are Pocket Agent, an assistant running offline on the user's phone. " +
                "Decide whether one of the tools is needed for the LAST user message. If a tool is needed, output exactly one tool call " +
                "with correct arguments taken from the user's message (never invent example text). If no tool is needed, answer briefly in the user's language without any tool call."
        const val OFFLINE_NOTE = "\n\n(Интернета нет — это ответ модели без проверки, он может быть неточным.)"

        fun toolsBlock(system: String): String {
            val sb = StringBuilder()
            sb.append("<|im_start|>system\n").append(system).append("\n\n")
            sb.append("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
            sb.append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
            for (t in Tools.schemas) sb.append("\n").append(t.toString())
            sb.append("\n</tools>\n\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
            sb.append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call><|im_end|>\n")
            return sb.toString()
        }

        const val MAX_TOKENS_CHAT = 160
        const val MEMORY_OFF = "Память выключена. Включить: меню профиля → «Что я о вас знаю» → «Память»."
        const val NOT_DONE = "Я ничего не поставил — не понял время или что сделать. Скажите, например: «напомни в 5 утра выпить таблетки», «разбуди меня в 7:30» или «таймер на 10 минут»."
        private val CLAIM = Regex("(?iu)(поставил|установил|создал|завёл|завел|добавил|вот ваш|вот твой|вот ваше|ваше напоминание|ваш будильник|напоминание на|будильник на|таймер на|напомню вам|напомню тебе|разбужу)")
        fun claimsAction(reply: String) = CLAIM.containsMatchIn(reply)
        const val MAX_TOKENS_LIVE = 100
        const val NO_CTX_CONVERT = "Не понял, какую сумму перевести. Скажите, например: «1500 долларов в рублях»."
        const val NO_AMOUNT = "В прошлом ответе не было суммы — переводить нечего. Можно сказать, например: «1500 долларов в рублях»."

        /** Убирает markdown (**жирный**, # заголовки, маркеры списков). */
        fun stripMarkdown(s: String): String = s
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("__(.+?)__"), "$1")
            .replace("**", "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("(?m)^\\s*[*\\-]\\s+"), "• ")
            .replace(Regex("`+"), "")

        /** Не длиннее 4 предложений; недописанный хвост (оборвался лимит токенов, пустой «2.») — отрезаем. */
        fun capSentences(s: String, n: Int = 4): String {
            var t = s.replace(Regex("(?m)^\\s*\\d{1,2}[.)]\\s*"), "• ")          // «1. …» → «• …», чтобы «1.» не считалось предложением
                .replace(Regex("(?m)^•\\s*$\\n?"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
            val c = WebSearch.firstSentences(t, n, 2000)
            if (c.length >= 20) t = c
            t = t.replace(Regex("[\\s•:–—-]+$"), "")
            if (t.isNotEmpty() && t.last() !in ".!?…»)") {
                val cut = maxOf(t.lastIndexOf(". "), t.lastIndexOf(".\n"), t.lastIndexOf("! "), t.lastIndexOf("? "))
                t = if (cut >= t.length * 0.4) t.substring(0, cut + 1) else "$t…"
            }
            return t.ifBlank { s.trim() }
        }

        fun cleanOutput(s: String): String = stripMarkdown(s
            .replace(Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|im_start\\|>.*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace("<|im_end|>", "").replace("<|endoftext|>", "")
        ).trim()
    }

    private fun turns(maxChars: Int, dropLast: Boolean = false): String {
        val src = if (dropLast && history.isNotEmpty()) history.subList(0, history.size - 1) else history
        val picked = ArrayDeque<Msg>()
        var used = 0
        for (m in src.asReversed()) {
            used += m.content.length
            if (used > maxChars && picked.isNotEmpty()) break
            picked.addFirst(m)
        }
        return picked.joinToString("") { "<|im_start|>${it.role}\n${it.content}<|im_end|>\n" }
    }

    /** Факты о пользователе (по словам последнего сообщения, до 8) и стиль ответа — в системную подсказку. */
    fun personalBlock(): String {
        val last = history.lastOrNull { it.role == "user" }?.content ?: ""
        val fs = if (facts.enabled) facts.relevant(last) else emptyList()
        val known = if (fs.isEmpty()) "" else " Что известно о пользователе: " + fs.joinToString("; ") { it.text } + "."
        val care = if (fs.any { it.key == "health" }) " Никогда не советуй то, на что у пользователя аллергия или что ему нельзя; о лекарствах — только «спросите врача»." else ""
        return known + care + style.promptLine(if (facts.enabled) facts.name() else null)
    }

    fun chatPrompt(live: Boolean = false): String =
        "<|im_start|>system\n$SYSTEM_CHAT${if (live) SYSTEM_LIVE else ""}${personalBlock()}<|im_end|>\n" + turns(6000) + "<|im_start|>assistant\n"

    fun searchPrompt(userText: String, r: WebSearch.Result, live: Boolean = false): String =
        "<|im_start|>system\n$SYSTEM_CHAT${if (live) SYSTEM_LIVE else ""}${personalBlock()}<|im_end|>\n" + turns(1500, dropLast = true) +
        "<|im_start|>user\n${WebSearch.contextPrompt(r)}\n\nВопрос: $userText<|im_end|>\n<|im_start|>assistant\n"

    fun plannerPrompt(userText: String): String =
        toolsBlock(SYSTEM_TOOLS) + "<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n"

    /** Обработка одного сообщения. Вызывается в фоновом потоке. */
    fun handle(userText: String, ev: Events, live: Boolean = false) {
        history += Msg("user", userText)
        val ctx = lastSearch; lastSearch = null
        try { style.observe(userText) } catch (_: Throwable) {}

        if (agentMode) {
            // 0) ответ на уточнение («Когда напомнить?» → «завтра в 10»)
            pending?.let { p ->
                pending = null
                val r = try { Router.followUp(p, userText) } catch (_: Throwable) { null }
                if (r is Router.Route.Call) { runTool(r.call, "правило", ev); return }
            }
        }
        // 0б) короткие уточнения к прошлому ответу с поиском («а в рублях?», «а завтра?») и «1500 $ в рублях»
        if (try { followUp(userText, ctx, ev) } catch (_: Throwable) { false }) return

        // 0в) память о пользователе и стиль: «меня зовут…», «как меня зовут», «забудь…», «отвечай короче»
        if (pending == null && (try { memoryStep(userText, ev) } catch (t: Throwable) { System.err.println("memory failed: $t"); false })) return

        // 0г) вопрос о файле/экране
        val force = forceScreen; forceScreen = false
        val doc = DocQA.pick(userText, file, screen, force)
        if (doc != null && (force || !(agentMode && Router.route(userText) is Router.Route.Call))) {
            when (try { docAnswer(userText, doc, ev, live) } catch (t: Throwable) { System.err.println("doc failed: $t"); false }) {
                true, null -> return
                false -> {}
            }
        }

        if (agentMode) {
            // 1) правила
            val routed = try { Router.route(userText, recent = lastObj) } catch (e: Throwable) {
                System.err.println("router failed: $e"); null   // правила не должны ронять приложение
            }
            when (val r = routed) {
                is Router.Route.Call -> { runTool(r.call, "правило", ev); return }
                is Router.Route.Ask -> { pending = r.pending; finish(r.question, ev); return }
                null -> {}
            }
            // 2) модель как планировщик (JSON tool call), только если похоже на запрос к инструменту
            if (try { Router.mightNeedTool(userText) } catch (_: Throwable) { false }) {
                val (out, err) = llm.generate(plannerPrompt(userText), 160, 0.2f) { !ev.isCancelled() }
                if (err != null) { ev.onError(err); return }
                if (ev.isCancelled()) return
                val call = try { ToolCallParser.parse(out)?.let { Tools.validate(it, userText) } } catch (_: Throwable) { null }
                if (call != null) { runTool(call, "модель", ev); return }
                // некорректный/лишний вызов — отвечаем обычным чатом
            }
        }

        // 2б) страховка: похоже на команду (напоминание/будильник/таймер), но правила не поняли — мягкое распознавание
        if (agentMode && Router.ACTION_WORDS.containsMatchIn(userText) && !Router.isCancelCommand(userText)) {
            val r = try { Router.relaxed(userText) } catch (_: Throwable) { null }
            if (r != null) { runTool(r.call, "правило", ev); return }
        }

        // 3) факты — только из источников. Модель о фактах не говорит: 0.5B/1.5B уверенно выдумывают.
        val hedge = pendingHedge; pendingHedge = null
        if (hedge != null && Factual.isInsist(userText)) { hedgeAnswer(hedge, ev); return }
        val prompt = chatPrompt(live)
        val note = ""
        val s = searcher
        val special = WebSearch.looksFactual(userText) && Regex("(?iu)(погод|курс|новост|сколько\\s+сто|(?<![\\p{L}])цен[аыуе](?![\\p{L}])|почём|почем)").containsMatchIn(userText)
        if (special) {
            if (s == null || !s.enabled()) { noFact(Factual.SEARCH_OFF, userText, ev); return }
            when (searchAndAnswer(userText, ev)) {
                true -> return
                false -> { noFact(if (s.online()) Factual.NOT_FOUND else Factual.NO_NET, userText, ev); return }
                null -> return   // отменено
            }
        }
        if (Factual.isFactual(userText, hasTopic = lastTopic != null)) { factualAnswer(userText, ev); return }
        // свободный чат: болтовня/советы/текст — живее; прочее — осторожнее
        val temp = if (Factual.isChat(userText)) 0.5f else 0.3f

        // 4) ответ модели со стримингом
        ev.onAssistantStart()
        val sb = StringBuilder()
        val (out, err) = llm.generate(prompt, if (live) MAX_TOKENS_LIVE else MAX_TOKENS_CHAT, temp) { piece ->
            sb.append(piece)
            ev.onAssistantText(cleanOutput(sb.toString()))
            !ev.isCancelled() && !sb.contains("<tool_call>")
        }
        if (err != null) { ev.onError(err); history.removeAt(history.lastIndex); return }
        val st = style.style()
        var body = capSentences(cleanOutput(out).lines().filterNot { it.trim().startsWith("Источник") }.joinToString("\n").trim(), if (st.short) 2 else 4)
        if (!st.emoji) body = Personal.stripEmoji(body).trim()
        healthWarning(body)?.let { body = "$body\n\n$it" }
        // модель «поставила» напоминание/будильник словами, но инструмент не запускался — не делаем вид
        if (agentMode && claimsAction(body) && Router.ACTION_WORDS.containsMatchIn(userText)) body = NOT_DONE
        val text = body.ifBlank { "…" } + note
        history += Msg("assistant", text)
        ev.onAssistantDone(text)
    }

    /**
     * Поиск и ответ ТОЛЬКО из источника (цитата/извлечение, без модели — она выдумывает числа).
     * true — ответили; false — не нашли/нет сети; null — отменено.
     */
    /** «какая погода» без города → город пользователя из памяти. */
    fun withCity(query: String): String {
        val city = if (facts.enabled) facts.city() else null
        if (city == null || !Regex("(?iu)погод").containsMatchIn(query)) return query
        if (Regex("(?iu)(?<![\\p{L}])(в|во)\\s+[\\p{L}-]{3,}").findAll(query).any { !Regex("(?iu)^(в|во)\\s+(город|завтра|сегодня|выходные|субботу|воскресенье|понедельник|вторник|среду|четверг|пятницу)").containsMatchIn(it.value) }) return query
        return query.trimEnd('?', '.', '!', ' ') + " в $city"
    }

    /** Модель советует то, на что у пользователя аллергия/запрет из памяти → честное предупреждение. */
    fun healthWarning(answer: String): String? {
        if (!facts.enabled) return null
        val low = answer.lowercase().replace('ё', 'е')
        for (f in facts.all().filter { it.key == "health" }) {
            val m = Regex("(?iu)(аллергия на|непереносимость|нельзя)\\s+([\\p{L}-]{3,})").find(f.text.replace('ё', 'е')) ?: continue
            val w = m.groupValues[2].lowercase()
            val stem = if (w.length > 6) w.take(w.length - 2) else w
            if (low.contains(stem)) return "⚠️ Помню: «${f.text}». Не принимайте ничего из ответа выше без врача — модель могла ошибиться."
        }
        return null
    }

    private fun memoryStep(text: String, ev: Events): Boolean {
        val vy = style.style().vy
        when (val c = Personal.command(text)) {
            is Personal.Cmd.Style -> {
                style.apply(c.change)
                finish(when (c.change) {
                    "short" -> "Хорошо, буду отвечать короче."; "long" -> "Хорошо, буду отвечать подробнее."
                    "ty" -> "Договорились, будем на «ты»."; "vy" -> "Хорошо, буду обращаться к вам на «вы»."
                    "noemoji" -> "Хорошо, без смайликов."; else -> "Хорошо, иногда добавлю смайлик 🙂"
                }, ev); return true
            }
            is Personal.Cmd.Recall -> {
                if (!facts.enabled) { finish(MEMORY_OFF, ev); return true }
                finish(Personal.recall(facts, c.key, vy), ev); return true
            }
            is Personal.Cmd.Forget -> {
                if (c.all) { val n = facts.all().size; facts.clear(); finish(if (n == 0) "Я и так ничего о вас не помню." else "Хорошо, я забыл всё, что знал о вас.", ev); return true }
                val keys = DocIndex.keywords(c.query)
                val hit = facts.all().filter { f -> val low = f.text.lowercase().replace('ё', 'е'); keys.isNotEmpty() && keys.any { low.contains(it) } }
                if (hit.isEmpty()) { finish("Такого я о вас не запоминал.", ev); return true }
                hit.forEach { facts.remove(it.id) }
                finish("Забыл: " + hit.joinToString("; ") { it.text } + ".", ev); return true
            }
            is Personal.Cmd.Remember -> {
                if (!facts.enabled) { finish(MEMORY_OFF, ev); return true }
                val saved = c.facts.map { facts.put(it.key, it.text) }
                ev.onFactsSaved(saved, auto = false)
                val name = saved.firstOrNull { it.key == "name" }?.let { Personal.nameOf(it.text) }
                finish(if (name != null) "Приятно познакомиться, $name! Запомнил." else "Запомнил: " + saved.joinToString("; ") { it.text } + ".", ev)
                return true
            }
            null -> {
                // лёгкое автоизвлечение устойчивых фактов из обычного сообщения — с «Запомнил» и отменой, ответ продолжается
                if (facts.enabled) {
                    val have = facts.all().map { it.text.lowercase() }.toSet()
                    val fresh = Personal.extract(text).filter { it.text.lowercase() !in have }
                    if (fresh.isNotEmpty()) ev.onFactsSaved(fresh.map { facts.put(it.key, it.text) }, auto = true)
                }
                return false
            }
        }
    }

    /** Вопрос о сущности/факте: поиск по вариантам (алиасы, город), ответ цитатами. Без источника — честно «не знаю». */
    private fun factualAnswer(text: String, ev: Events) {
        val ent = Factual.entity(text, lastTopic) ?: text.trim()
        lastTopic = ent
        val s = searcher
        if (s == null || !s.enabled()) { noFact(Factual.SEARCH_OFF, ent, ev); return }
        if (!s.online()) { noFact(Factual.NO_NET, ent, ev); return }
        val city = if (facts.enabled) facts.city() else null
        val alias = Factual.alias(ent, city)
        val qs = alias ?: Factual.queries(ent, city)
        val found = mutableListOf<Pair<String, WebSearch.Result>>()
        var failed = 0
        for (q in qs.take(3)) {
            if (ev.isCancelled()) return
            val r = try { s.lookup(q) } catch (_: Throwable) { failed++; null } ?: continue
            if (found.none { it.second.url == r.url }) found += q to r
            if (alias == null) break        // обычный вопрос — достаточно первого источника
        }
        if (ev.isCancelled()) return
        if (found.isEmpty()) { noFact(if (failed == qs.take(3).size) Factual.NO_NET else Factual.NOT_FOUND, ent, ev); return }
        ev.onWebSearch(found[0].second.source, found[0].second.url)
        val sources = found.map { it.second.source }.distinct().joinToString(", ")
        lastSearch = FollowUp.Ctx(found[0].first, WebSearch.Kind.FACT, emptyList())
        finish(Factual.answer(found) + "\n\nИсточник: $sources", ev)
    }

    private fun noFact(msg: String, topic: String, ev: Events) {
        pendingHedge = topic
        ev.onWebSearch(null, null)
        finish(msg + Factual.INSIST_HINT, ev)
    }

    /** Пользователь настаивает: одна осторожная фраза модели (низкая температура), с пометкой. */
    private fun hedgeAnswer(topic: String, ev: Events) {
        ev.onAssistantStart()
        val p = "<|im_start|>system\n${Factual.hedgePrompt(topic)}<|im_end|>\n<|im_start|>user\n$topic<|im_end|>\n<|im_start|>assistant\n"
        val (out, err) = llm.generate(p, 60, 0.3f) { !ev.isCancelled() }
        if (err != null) { ev.onError(err); return }
        var t = WebSearch.firstSentences(cleanOutput(out).replace('\n', ' '), 1, 220).trim()
        if (t.isBlank()) t = "Не уверен."
        if (!t.lowercase().startsWith("не уверен")) t = "Не уверен, но: " + t.replaceFirstChar { it.lowercase() }
        val text = "$t\n(Это предположение без проверки — может быть неверным.)"
        history += Msg("assistant", text)
        ev.onAssistantDone(text)
    }

    private fun searchAndAnswer(query0: String, ev: Events): Boolean? {
        val query = withCity(query0)
        val s = searcher ?: return false
        val res = if (s.online()) try { s.search(query) } catch (_: Throwable) { null } else null
        if (ev.isCancelled()) return null
        if (res == null) return false
        val direct = res.direct ?: WebSearch.firstSentences(res.text, 2, 400)
        ev.onWebSearch(res.source, res.url)
        val text = direct + "\n\nИсточник: ${res.source}"
        lastSearch = FollowUp.Ctx(res.query.ifBlank { query }, res.kind, res.amounts)
        if (res.kind == WebSearch.Kind.FACT) lastTopic = WebSearch.topic(query)
        finish(text, ev)
        return true
    }

    /** Уточнения и пересчёт валют. true — обработано. */
    private fun followUp(text: String, ctx: FollowUp.Ctx?, ev: Events): Boolean {
        val s = searcher
        val searchOn = s != null && s.enabled()
        fun rates(): Map<String, Double>? = if (s != null && s.online()) try { s.rates() } catch (_: Throwable) { null } else null
        fun convertAnswer(p: WebSearch.Price, target: String): Boolean {
            lastSearch = ctx ?: FollowUp.Ctx(text, WebSearch.Kind.CURRENCY, listOf(p))   // «а в евро?» после «а в рублях?»
            if (p.currency == target) { finish("Сумма уже в этой валюте: ${FollowUp.fmt(p.amount)} ${FollowUp.sign(target)}".trimEnd('.') + ".", ev); return true }
            val r = rates()
            if (r == null) { finish("Не получилось узнать курс ЦБ РФ — нужен интернет.", ev); return true }
            ev.onWebSearch("ЦБ РФ", "https://www.cbr.ru/currency_base/daily/")
            finish(FollowUp.conversionText(p, target, r) + "\n\nИсточник: ЦБ РФ", ev)
            return true
        }
        if (searchOn) FollowUp.explicitConversion(text)?.let { (p, to) -> return convertAnswer(p, to) }
        when (val r = FollowUp.resolve(text, ctx)) {
            is FollowUp.Res.Convert -> {
                val c = ctx!!
                val p = c.amounts.firstOrNull { it.currency != r.target } ?: c.amounts.firstOrNull()
                if (p == null) { lastSearch = c; finish(NO_AMOUNT, ev); return true }
                return convertAnswer(p, r.target)
            }
            is FollowUp.Res.Requery -> {
                if (!searchOn) return false
                return searchAndAnswer(r.query, ev) != false
            }
            null -> {
                // «а в рублях?» без прошлой суммы — не даём модели сочинять
                if (FollowUp.currencyFollowUp(text) != null) { finish(NO_CTX_CONVERT, ev); return true }
                return false
            }
        }
    }

    /** true — ответили, null — отменено, false — не получилось. */
    private fun docAnswer(q: String, d: DocIndex, ev: Events, live: Boolean): Boolean? {
        if (d.text.isBlank()) { ev.onDocUsed(d); finish(if (d.kind == DocIndex.Kind.SCREEN) "На экране не нашлось текста." else "В файле нет текста.", ev); return true }
        val tag = if (d.kind == DocIndex.Kind.SCREEN) "\n\n📱 экран прочитан на телефоне" else "\n\n📎 ${d.name}"
        var started = false
        fun reply(text: String) { history += Msg("assistant", text); if (!started) ev.onAssistantStart(); ev.onAssistantDone(text) }
        fun gen(prompt: String, max: Int, temp: Float = 0.3f): String? {
            started = true
            ev.onAssistantStart()
            val sb = StringBuilder()
            val (out, err) = llm.generate(prompt, max, temp) { piece -> sb.append(piece); ev.onAssistantText(cleanOutput(sb.toString())); !ev.isCancelled() }
            if (ev.isCancelled()) return null
            if (err != null) return ""
            return capSentences(cleanOutput(out), 5)
        }
        ev.onDocUsed(d)
        when (DocQA.intent(q)) {
            DocQA.Intent.FIND -> { reply(DocQA.find(q, d) + tag); return true }
            DocQA.Intent.READ -> { reply(DocQA.read(d) + tag); return true }
            DocQA.Intent.NUMBER -> DocQA.extractNumbers(q, d)?.let { reply(it + tag); return true }
            DocQA.Intent.SUMMARY -> {
                val budget = if (live) 1800 else 3000
                val out = gen(DocQA.summaryPrompt(d, budget), 200) ?: return null
                val text = out.ifBlank { "Начало текста: " + d.head(400) } +
                    (if (d.text.length > budget) "\n(пересказ по началу текста — ${d.text.length / 1000} тыс. знаков)" else "") + tag
                reply(text); return true
            }
            DocQA.Intent.TRANSLATE -> {
                val frag = (d.search(q, 1).firstOrNull() ?: d.head(600)).let { DocIndex.short(it, 600) }
                val toEn = Regex("(?iu)англ|english").containsMatchIn(q) || (!Regex("(?iu)русск").containsMatchIn(q) && DocQA.isCyrillic(frag))
                val out = gen(DocQA.translatePrompt(frag, toEn), 260, 0.2f) ?: return null
                reply((if (out.isBlank()) "Не получилось перевести." else "Перевод:\n$out") + "\n\nОригинал: " + DocQA.quote(DocIndex.short(frag, 200)) + tag)
                return true
            }
            else -> {}
        }
        // свободный вопрос: подбираем фрагменты по словам вопроса
        val frags = d.search(q, 2).ifEmpty { listOf(d.head(1200)) }
        val out = gen(DocQA.askPrompt(q, frags.map { DocIndex.short(it, 900) }), 160) ?: return null
        val quotes = d.quotes(q, 1)
        // маленькая модель иногда «не видит» ответ, который есть в цитате — тогда показываем цитату
        val denies = Regex("(?iu)(нет информации|этого нет|не содержит|не указан|не упомина|нет данных)").containsMatchIn(out)
        if (denies && quotes.isNotEmpty()) { reply("Вот что есть ${DocQA.label(d)}: " + DocQA.quote(quotes[0]) + tag); return true }
        val text = out.ifBlank { "Вот что нашёл:" } + (if (quotes.isNotEmpty()) "\n\nЦитата: " + DocQA.quote(quotes[0]) else "") + tag
        reply(text); return true
    }

    private fun runTool(call: ToolCall, source: String, ev: Events) {
        val a = call.args
        // контекст чата: «последнее напоминание/будильник, о котором говорили»
        if (call.name in setOf("edit_reminder", "cancel_reminder") && !a.has("id") && lastReminderId > 0) a.put("recent", lastReminderId)
        lastAlarm?.let { (h, m) ->
            if (call.name == "move_alarm" && !a.has("old_hour")) a.put("old_hour", h).put("old_minute", m)
            if (call.name == "cancel_alarm" && a.optBoolean("last", false) && !a.has("hour")) a.put("hour", h).put("minute", m)
        }
        val res = Tools.execute(call, notes, reminders = reminders)
        (res.action as? UiAction.ChooseReminder)?.let { pending = Router.Pending("cancel_reminder", it.candidates.joinToString(",") { r -> r.id.toString() }) }
        (res.action as? UiAction.ChooseEdit)?.let { pending = Router.Pending("edit_reminder", it.candidates.joinToString(",") { r -> r.id.toString() }, it.args) }
        if (res.ok) when (call.name) {
            "set_reminder", "edit_reminder" -> (res.action as? UiAction.ReminderSet)?.let { lastReminderId = it.reminder.id; lastObj = "reminder" }
            "set_alarm", "move_alarm" -> { lastAlarm = a.getInt("hour") to a.getInt("minute"); lastObj = "alarm" }
            "cancel_reminder" -> if (res.action !is UiAction.ChooseReminder) { lastReminderId = 0; lastObj = null }
            "cancel_alarm" -> { lastAlarm = null; lastObj = null }
        }
        history += Msg("assistant", res.answer)
        ev.onToolResult(call, res, source)
    }

    private fun finish(text: String, ev: Events) {
        history += Msg("assistant", text)
        ev.onAssistantStart()
        ev.onAssistantDone(text)
    }
}
