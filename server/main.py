"""PST V3.4 contextual translation gateway. pip install fastapi uvicorn openai"""
import os
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from openai import AsyncOpenAI

app = FastAPI()
client = AsyncOpenAI()
ACCESS_TOKEN = os.getenv("PST_GATEWAY_TOKEN", "")
MODEL = os.getenv("PST_TRANSLATION_MODEL", "gpt-4.1-mini")

class TranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    previous_english: list[str] = Field(default_factory=list, max_length=8)
    previous_turkish: list[str] = Field(default_factory=list, max_length=8)

@app.post("/translate")
async def translate(req: TranslationRequest, authorization: str | None = Header(default=None)):
    if not ACCESS_TOKEN or authorization != f"Bearer {ACCESS_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")
    history = "\n".join(
        f"EN: {en}\nTR: {tr}" for en, tr in zip(
            req.previous_english[-8:], req.previous_turkish[-8:]
        )
    )
    response = await client.responses.create(
        model=MODEL,
        instructions=(
            "You are a professional live interpreter for an English-to-Turkish "
            "industrial manufacturing business meeting. Translate ONLY the new "
            "English utterance to natural, accurate Turkish. Use the previous "
            "conversation for context, never retranslate previous utterances. "
            "Preserve numbers, units, dates, names, negation, uncertainty and "
            "commercial commitments. Do not invent facts. Return translation only."
        ),
        input=f"Previous context (reference only):\n{history}\n\nNEW ENGLISH:\n{req.text}",
        max_output_tokens=800,
    )
    result = response.output_text.strip()
    if not result:
        raise HTTPException(status_code=502, detail="Empty translation")
    return {"translation": result}
