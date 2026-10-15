#!/usr/bin/env python3
"""Tiny Edge-TTS HTTP server for the Muse Gadget.

GET /tts?text=<urlencoded>&voice=<shortname> -> MP3 audio bytes
GET /voices -> JSON list of available voices (cached)

Runs on the Dell (LAN-reachable). The Portal Mini fetches MP3s over HTTP.
"""
import asyncio
import json
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

import edge_tts

DEFAULT_VOICE = "en-US-EmmaMultilingualNeural"
PORT = 8765

_voices_cache = None

async def synth(text, voice):
    communicate = edge_tts.Communicate(text, voice)
    audio = b""
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            audio += chunk["data"]
    return audio

async def get_voices():
    global _voices_cache
    if _voices_cache is None:
        voices = await edge_tts.list_voices()
        _voices_cache = [
            {"short_name": v["ShortName"], "locale": v["Locale"],
             "gender": v["Gender"], "name": v["FriendlyName"]}
            for v in voices
        ]
    return _voices_cache

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        if parsed.path == "/tts":
            text = qs.get("text", [""])[0]
            voice = qs.get("voice", [DEFAULT_VOICE])[0]
            if not text.strip():
                self.send_error(400, "missing text")
                return
            try:
                audio = asyncio.run(synth(text[:4000], voice))
            except Exception as e:
                self.send_error(502, str(e)[:200])
                return
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(audio)))
            self.end_headers()
            self.wfile.write(audio)
        elif parsed.path == "/voices":
            try:
                voices = asyncio.run(get_voices())
            except Exception as e:
                self.send_error(502, str(e)[:200])
                return
            body = json.dumps(voices).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
