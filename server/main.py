"""Authenticated Azure AI Translator F0 gateway. Azure secrets never reach the APK."""
import os
import uuid
import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI()
ACCESS_TOKEN = os.getenv("PST_GATEWAY_TOKEN", "")
AZURE_KEY = os.getenv("AZURE_TRANSLATOR_KEY", "")
AZURE_REGION = os.getenv("AZURE_TRANSLATOR_REGION", "")
AZURE_ENDPOINT = os.getenv("AZURE_TRANSLATOR_ENDPOINT", "https://api.cognitive.microsofttranslator.com").rstrip("/")

class TranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    previous_english: list[str] = Field(default_factory=list, max_length=8)
    previous_turkish: list[str] = Field(default_factory=list, max_length=8)

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/translate")
async def translate_text(req: TranslationRequest, authorization: str | None = Header(default=None)):
    if not ACCESS_TOKEN or authorization != f"Bearer {ACCESS_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")
    if not AZURE_KEY:
        raise HTTPException(status_code=503, detail="Azure Translator is not configured")
    headers = {
        "Ocp-Apim-Subscription-Key": AZURE_KEY,
        "Content-Type": "application/json",
        "X-ClientTraceId": str(uuid.uuid4()),
    }
    if AZURE_REGION:
        headers["Ocp-Apim-Subscription-Region"] = AZURE_REGION
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            response = await client.post(
                f"{AZURE_ENDPOINT}/translate",
                params={"api-version": "3.0", "from": "en", "to": "tr"},
                headers=headers,
                json=[{"text": req.text}],
            )
            response.raise_for_status()
            result = response.json()[0]["translations"][0]["text"].strip()
            if not result:
                raise ValueError("Empty translation")
            return {"translation": result}
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(status_code=502, detail="Azure Translator unavailable")
