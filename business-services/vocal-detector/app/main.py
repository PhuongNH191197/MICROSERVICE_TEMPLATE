import asyncio
import os
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Security, UploadFile
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.detector import VocalDetector
from app.schemas import DetectionResponse

MODELS_DIR = os.getenv("MODELS_DIR", "/models")
MAX_FILE_BYTES = 50 * 1024 * 1024  # 50 MB
ALLOWED_SUFFIXES = {".mp3", ".wav", ".flac", ".m4a"}
_SWAGGER_ENABLED = os.getenv("SWAGGER_ENABLED", "true").lower() == "true"

bearer_scheme = HTTPBearer(auto_error=False)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.detector = VocalDetector(MODELS_DIR)
    yield


app = FastAPI(
    title="Vocal Detector",
    description="Detect whether an audio file contains vocals using MTG Essentia models.",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if _SWAGGER_ENABLED else None,
    redoc_url="/redoc" if _SWAGGER_ENABLED else None,
    openapi_url="/openapi.json" if _SWAGGER_ENABLED else None,
)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/detect", response_model=DetectionResponse)
async def detect(
    file: UploadFile = File(...),
    _token: HTTPAuthorizationCredentials | None = Security(bearer_scheme),
):
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported format '{suffix}'. Allowed: {sorted(ALLOWED_SUFFIXES)}",
        )

    content = await file.read()
    if len(content) > MAX_FILE_BYTES:
        raise HTTPException(status_code=413, detail="File exceeds 50 MB limit.")

    loop = asyncio.get_event_loop()
    try:
        result = await loop.run_in_executor(
            None,
            app.state.detector.analyze,
            content,
            file.filename,
        )
    except subprocess.CalledProcessError as exc:
        raise HTTPException(status_code=422, detail="Audio decoding failed.") from exc

    return JSONResponse(content=result)
