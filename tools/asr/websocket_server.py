import argparse
import asyncio
import json
import logging

import numpy as np
import sherpa_onnx
import websockets


class StreamTranscript:
    def __init__(self, recognizer, sample_rate, language):
        self.recognizer = recognizer
        self.sample_rate = sample_rate
        self.stream = recognizer.create_stream()
        self.stream.set_option("language", language)
        self.completed_segments = []
        self.last_sent = ""

    async def accept(self, websocket, samples):
        self.stream.accept_waveform(self.sample_rate, samples)
        await self._decode_ready(websocket)

    async def finish(self, websocket):
        self.stream.input_finished()
        await self._decode_ready(websocket)
        await websocket.send("Done!")

    async def _decode_ready(self, websocket):
        while self.recognizer.is_ready(self.stream):
            self.recognizer.decode_stream(self.stream)
            partial = self.recognizer.get_result(self.stream)

            if self.recognizer.is_endpoint(self.stream):
                if partial:
                    self.completed_segments.append(partial)
                self.recognizer.reset(self.stream)
                partial = ""

            text = " ".join((*self.completed_segments, partial)).strip()
            if text and text != self.last_sent:
                await websocket.send(json.dumps({"text": text}, ensure_ascii=False))
                self.last_sent = text


async def serve_connection(websocket, recognizer, sample_rate, language):
    transcript = StreamTranscript(recognizer, sample_rate, language)
    try:
        async for message in websocket:
            if isinstance(message, str):
                if message == "Done":
                    await transcript.finish(websocket)
                    return
                continue

            if len(message) % 4 != 0:
                logging.warning("Ignoring malformed ASR audio frame (%d bytes)", len(message))
                continue

            samples = np.frombuffer(message, dtype=np.float32)
            if samples.size:
                await transcript.accept(websocket, samples)
    except websockets.ConnectionClosed:
        return
    except Exception:
        logging.exception("ASR WebSocket client failed")
        await websocket.close(code=1011, reason="ASR processing failed")


def create_recognizer(args):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=args.tokens,
        encoder=args.encoder,
        decoder=args.decoder,
        joiner=args.joiner,
        num_threads=args.threads,
        sample_rate=args.sample_rate,
        feature_dim=80,
        enable_endpoint_detection=True,
        provider="cpu",
    )


async def run_server(args):
    recognizer = create_recognizer(args)

    async def handler(websocket, path=None):
        await serve_connection(websocket, recognizer, args.sample_rate, args.language)

    async with websockets.serve(handler, "127.0.0.1", args.port):
        logging.info("Sherpa WebSocket listening on 127.0.0.1:%d (language=%s)", args.port, args.language)
        await asyncio.Future()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tokens", required=True)
    parser.add_argument("--encoder", required=True)
    parser.add_argument("--decoder", required=True)
    parser.add_argument("--joiner", required=True)
    parser.add_argument("--language", default="es-ES")
    parser.add_argument("--sample-rate", type=int, default=16000)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--port", type=int, default=6006)
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(run_server(args))


if __name__ == "__main__":
    main()
