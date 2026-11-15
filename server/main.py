"""PST Google Cloud Translation gateway; credentials stay server-side."""
import json
import os
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from google.cloud import translate_v2 as translate
from google.oauth2 import service_account

app = FastAPI()
ACCESS_TOKEN = os.getenv("PST_GATEWAY_TOKEN", "")

def google_client():
    raw = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON", "")
    if raw:
        credentials = service_account.Credentials.from_service_account_info(json.loads(raw))
        return translate.Client(credentials=credentials, project=credentials.project_id)
    return translate.Client()

class TranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    previous_english: list[str] = Field(default_factory=list, max_length=8)
    previous_turkish: list[str] = Field(default_factory=list, max_length=8)

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/translate")
def translate_text(req: TranslationRequest, authorization: str | None = Header(default=None)):
    if not ACCESS_TOKEN or authorization != f"Bearer {ACCESS_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")
    try:
        response = google_client().translate(
            req.text, source_language="en", target_language="tr", format_="text"
        )
        result = response.get("translatedText", "").strip()
        if not result:
            raise ValueError("Empty translation")
        return {"translation": result}
    except Exception:
        raise HTTPException(status_code=502, detail="Google Cloud Translation unavailable")
