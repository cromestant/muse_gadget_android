package com.cromestant.musegadget.audio

/**
 * Voice-note JSON framing. NOTE_HEAD/NOTE_TAIL per muse_chat_priv.h:71;
 * the base64 WAV sits between them inside the JSON string.
 */
object VoiceNote {
    const val NOTE_HEAD =
        "{\"message\":\"\",\"output_modality\":\"text\",\"items\":[{\"type\":\"file\",\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\""
    const val NOTE_TAIL = "\"}]}"

    fun frameVoiceNote(wavBase64: String): String = NOTE_HEAD + wavBase64 + NOTE_TAIL
}
