package ru.pocketagent

import android.content.Context

/** Общие для приложения и окна помощника агент и модель (один процесс — одна загруженная модель). */
object Engine {
    fun agent(ctx: Context): Agent {
        Session.agent?.let { return it }
        val app = ctx.applicationContext
        return Agent(PrefsNotes(app), object : Agent.Llm {
            override fun generate(prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean): Pair<String, String?> {
                if (Session.loadedPath == null) {
                    val err = ensureLoaded(app)   // окно помощника: модель могла быть ещё не загружена
                    if (err != null) return Pair("", err)
                }
                CrashReporter.markBusy("generate")
                return try { LlamaBridge.generate(prompt, maxTokens, temp, onText) }
                catch (t: Throwable) { Pair("", "сбой генерации: $t") }
                finally { CrashReporter.clearBusy() }
            }
        }, AndroidReminders(app), AppSearcher(app)).also {
            it.agentMode = app.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("agent", true)
            // память и стиль — только во внутренней памяти приложения (allowBackup=false: не попадают в облачную копию)
            it.facts = FactStore(java.io.File(app.filesDir, "memory.json"))
            it.style = StyleProfile(java.io.File(app.filesDir, "style.json"))
            Session.agent = it
        }
    }

    /** Загружает активную модель, если она уже распакована/скачана. Вызывать в фоновом потоке. null — успех. */
    @Synchronized fun ensureLoaded(ctx: Context): String? {
        val models = ModelManager(ctx)
        val f = models.activeFile()
        if (Session.loadedPath == f.absolutePath) return null
        if (!models.useImported && !models.builtinReady()) return "модель ещё не готова — откройте PocketAgent один раз"
        val low = DeviceInfo.isLowRam(ctx)
        val threads = if (low) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val ctxLen = if (low) 2048 else 4096
        CrashReporter.markBusy("load_model ${f.name} ctx=$ctxLen threads=$threads")
        val err = try { LlamaBridge.nativeLoad(f.absolutePath, ctxLen, threads) } catch (t: Throwable) { "сбой загрузки: $t" } finally { CrashReporter.clearBusy() }
        if (err == null) Session.loadedPath = f.absolutePath
        return err
    }
}
