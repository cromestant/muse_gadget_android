package com.cromestant.musegadget.audio

import com.cromestant.musegadget.link.GadgetLink
import com.cromestant.musegadget.link.VoiceTurnListener

/**
 * Live voice transport: hands PTT frames to the gadget link, which
 * dispatches to a worker thread (never blocks the caller — PttController
 * calls this synchronously on the UI thread).
 */
class LinkVoiceTransport(
    private val link: () -> GadgetLink?,
    private val listener: VoiceTurnListener,
) : VoiceTransport {
    override fun sendVoiceNote(json: String) {
        System.out.println("MuseGadgetVoice: sendVoiceNote len=" + json.length)
        val l = link()
        System.out.println("MuseGadgetVoice: gadgetLink " + (if (l == null) "NULL" else "present"))
        (l ?: run { listener.onError("gadget link not ready"); return }).sendVoiceTurn(json, listener)
    }
}
