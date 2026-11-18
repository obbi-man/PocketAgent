package ru.pocketagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pocketagent.SpeakPolicy.Source

class SpeakPolicyTest {
    @Test fun typedRepliesAreSilentByDefault() {
        // настройки по умолчанию: голосовые — да, «всегда» — нет
        assertFalse(SpeakPolicy.shouldSpeak(Source.TYPED, voiceReplies = true, always = false))
    }
    @Test fun voiceAndLiveAreSpoken() {
        assertTrue(SpeakPolicy.shouldSpeak(Source.VOICE, voiceReplies = true, always = false))
        assertTrue(SpeakPolicy.shouldSpeak(Source.LIVE, voiceReplies = true, always = false))
    }
    @Test fun voiceRepliesCanBeTurnedOff() {
        assertFalse(SpeakPolicy.shouldSpeak(Source.VOICE, voiceReplies = false, always = false))
        assertTrue(SpeakPolicy.shouldSpeak(Source.LIVE, voiceReplies = false, always = false))   // живой режим всегда голосом
    }
    @Test fun alwaysSpeaksTypedToo() {
        assertTrue(SpeakPolicy.shouldSpeak(Source.TYPED, voiceReplies = false, always = true))
        assertTrue(SpeakPolicy.shouldSpeak(Source.VOICE, voiceReplies = false, always = true))
    }
}
