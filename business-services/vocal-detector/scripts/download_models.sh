#!/usr/bin/env bash
set -euo pipefail

DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models"
mkdir -p "$DEST"

BASE="https://essentia.upf.edu/models"

echo "Downloading discogs-effnet embedding model..."
curl -L --progress-bar \
  "$BASE/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb" \
  -o "$DEST/discogs-effnet-bs64-1.pb"

echo "Downloading voice/instrumental classifier..."
curl -L --progress-bar \
  "$BASE/classification-heads/voice_instrumental/voice_instrumental-discogs-effnet-1.pb" \
  -o "$DEST/voice_instrumental-discogs-effnet-1.pb"

echo "Downloading classifier metadata..."
curl -L --progress-bar \
  "$BASE/classification-heads/voice_instrumental/voice_instrumental-discogs-effnet-1.json" \
  -o "$DEST/voice_instrumental-discogs-effnet-1.json"

echo ""
echo "Done. Models saved to: $DEST"
