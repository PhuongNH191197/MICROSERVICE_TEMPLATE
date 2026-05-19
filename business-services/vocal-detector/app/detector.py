import json
import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16000
MAX_DURATION_SEC = 60

_EFFNET_OUTPUT = "PartitionedCall:1"
_CLASSIFIER_INPUT = "model/Placeholder"
_CLASSIFIER_OUTPUT = "model/Softmax"


class VocalDetector:
    def __init__(self, models_dir: str) -> None:
        # Deferred import: clear error if essentia-tensorflow not installed
        import essentia.standard as es  # noqa: PLC0415

        base = Path(models_dir)
        effnet_pb = base / "discogs-effnet-bs64-1.pb"
        classifier_pb = base / "voice_instrumental-discogs-effnet-1.pb"
        metadata_json = base / "voice_instrumental-discogs-effnet-1.json"

        for p in (effnet_pb, classifier_pb, metadata_json):
            if not p.exists():
                raise RuntimeError(
                    f"Model file not found: {p}\nRun scripts/download_models.sh first."
                )

        with open(metadata_json) as f:
            metadata = json.load(f)
        self._classes: list[str] = metadata["classes"]

        self._embedder = es.TensorflowPredictEffnetDiscogs(
            graphFilename=str(effnet_pb),
            output=_EFFNET_OUTPUT,
        )
        self._classifier = es.TensorflowPredict2D(
            graphFilename=str(classifier_pb),
            input=_CLASSIFIER_INPUT,
            output=_CLASSIFIER_OUTPUT,
        )
        self._es = es

    def analyze(self, audio_bytes: bytes, filename: str) -> dict:
        suffix = Path(filename).suffix.lower() or ".bin"

        with tempfile.TemporaryDirectory() as tmp:
            input_path = os.path.join(tmp, f"input{suffix}")
            wav_path = os.path.join(tmp, "audio.wav")

            with open(input_path, "wb") as f:
                f.write(audio_bytes)

            # Convert to 16 kHz mono WAV, trim to MAX_DURATION_SEC
            subprocess.run(
                [
                    "ffmpeg", "-y",
                    "-i", input_path,
                    "-ac", "1",
                    "-ar", str(SAMPLE_RATE),
                    "-t", str(MAX_DURATION_SEC),
                    wav_path,
                ],
                check=True,
                capture_output=True,
            )

            audio = self._es.MonoLoader(filename=wav_path, sampleRate=SAMPLE_RATE)()

        embeddings = self._embedder(audio)
        predictions = self._classifier(embeddings)

        # Average across frames; handle both (N, C) and (C,) shapes
        mean_preds = (
            np.mean(predictions, axis=0) if predictions.ndim > 1 else predictions
        )

        scores = {
            cls: float(np.clip(mean_preds[i], 0.0, 1.0))
            for i, cls in enumerate(self._classes)
        }

        instr = scores.get("instrumental", 0.0)
        voice = scores.get("voice", 0.0)

        return {
            "is_instrumental": instr > voice,
            "confidence": float(max(instr, voice)),
            "scores": {"instrumental": instr, "voice": voice},
        }
