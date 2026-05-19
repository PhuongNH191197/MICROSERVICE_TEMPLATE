from pydantic import BaseModel, Field


class Scores(BaseModel):
    instrumental: float = Field(..., ge=0.0, le=1.0)
    voice: float = Field(..., ge=0.0, le=1.0)


class DetectionResponse(BaseModel):
    is_instrumental: bool
    confidence: float = Field(..., ge=0.0, le=1.0)
    scores: Scores
