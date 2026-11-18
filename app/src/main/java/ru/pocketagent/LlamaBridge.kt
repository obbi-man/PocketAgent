package ru.pocketagent

/** Тонкая обёртка над JNI (libpocketagent.so = llama.cpp + pocket_core). */
object LlamaBridge {
    init { System.loadLibrary("pocketagent") }

    interface Callback {
        /** Кусок текста (целые UTF-8 символы). Вернуть false — остановить. */
        fun onPiece(bytes: ByteArray): Boolean
        fun onError(bytes: ByteArray)
    }

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String?
    external fun nativeUnload()
    external fun nativeStop()
    external fun nativeCountTokens(text: String): Int
    external fun nativeSystemInfo(): String
    external fun nativeInstallCrashHandler(path: String)
    external fun nativeRecentLog(): String
    external fun nativeSetVerbose(v: Boolean)
    external fun nativeGenerate(
        prompt: String, maxTokens: Int, temp: Float, topK: Int, topP: Float,
        repeatPenalty: Float, callback: Callback
    ): ByteArray?

    /** Удобная обёртка. Возвращает (текст, ошибка). */
    fun generate(
        prompt: String, maxTokens: Int, temp: Float, onText: (String) -> Boolean
    ): Pair<String, String?> {
        var error: String? = null
        val res = nativeGenerate(prompt, maxTokens, temp, 20, 0.8f, 1.05f, object : Callback {
            override fun onPiece(bytes: ByteArray): Boolean = onText(String(bytes, Charsets.UTF_8))
            override fun onError(bytes: ByteArray) { error = String(bytes, Charsets.UTF_8) }
        })
        return Pair(res?.let { String(it, Charsets.UTF_8) } ?: "", error)
    }
}
