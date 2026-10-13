package com.cromestant.musegadget.audio

/** Outbound voice-note sink. Fake impl stores; link layer does not exist yet. */
interface VoiceTransport {
    fun sendVoiceNote(json: String)
}

/** Offline fake: stores last json + info, no network. */
class FakeVoiceTransport : VoiceTransport {
    @Volatile var lastJson: String? = null
        private set
    @Volatile var sendCount: Int = 0
        private set

    override fun sendVoiceNote(json: String) {
        lastJson = json
        sendCount++
    }
}
