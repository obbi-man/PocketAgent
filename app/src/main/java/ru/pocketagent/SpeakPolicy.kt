package ru.pocketagent

/** Когда озвучивать ответ. Набранный текстом запрос — по умолчанию без голоса (баг 2.1: читал вслух всё подряд). */
object SpeakPolicy {
    enum class Source { TYPED, VOICE, LIVE }

    /** voiceReplies — «Озвучивать ответы на голосовые запросы» (вкл.), always — «Всегда озвучивать» (выкл.). */
    fun shouldSpeak(source: Source, voiceReplies: Boolean, always: Boolean): Boolean = when (source) {
        Source.LIVE -> true                       // живой режим — разговор голосом
        Source.VOICE -> voiceReplies || always
        Source.TYPED -> always
    }
}
