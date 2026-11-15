# PST Live Translator V3.4

Android live English-to-Turkish meeting translator. Two modes:
- Offline ML Kit (default, no gateway).
- Optional contextual AI translation through a **user-deployed HTTPS backend**. English recognition remains on-device/Android.

## Server setup
Install Python 3.11+, then `pip install -r server/requirements.txt`.
Set environment variables `OPENAI_API_KEY` and `PST_GATEWAY_TOKEN` to different long secret values.
Optionally set `PST_TRANSLATION_MODEL` (default `gpt-4.1-mini`).
Start `uvicorn server.main:app --host 127.0.0.1 --port 8000`.
Deploy behind HTTPS with authentication and rate limiting. A localhost server is **not reachable from the Android phone** unless network access/tunneling is configured.

In the app enable AI translation, enter the public HTTPS URL ending in `/translate`, and the **gateway token** (not the OpenAI API key). The gateway token is kept in memory, not saved by the app. If the cloud request fails or times out, ML Kit is used automatically.

## Privacy / costs
The English utterance and up to eight prior English/Turkish segments are sent to the configured server and then to the AI provider. Obtain appropriate meeting consent and assess confidentiality before enabling. Provider usage incurs charges. Do not expose OpenAI API keys in Android apps or commit secrets to GitHub.

## Build
GitHub Actions > Build Android APK > artifact `PST-Live-Translator-debug-APK`.

## Limitations
Not yet verified on a physical device. Cloud translation requires a deployed gateway; the APK alone does not activate AI translation. Single-phone microphone cannot reliably separate simultaneous speakers.
