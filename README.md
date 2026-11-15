# PST Live Translator — Google Cloud Translation

English speech recognition (Android or Vosk) and Turkish live translation.

## Translation modes
- **Google ML Kit**: on-device, free, default and automatic fallback.
- **Google Cloud Translation Basic v2 (NMT)**: optional cloud translation via a user-deployed HTTPS gateway. This is **not Gemini or a context-aware LLM**. The server translates the current English text; previous segments are currently ignored by Google Cloud Basic.

## Setup
1. In [Google Cloud Console](https://console.cloud.google.com/), create/select a project, attach billing, enable **Cloud Translation API**.
2. Create a dedicated service account with only the required Cloud Translation permissions. Generate a JSON credential if necessary; treat it as a secret. Prefer managed identity/secret management when available.
3. Deploy the repository's `render.yaml` Blueprint on Render. Configure secret `GOOGLE_SERVICE_ACCOUNT_JSON` with the entire service-account JSON and `PST_GATEWAY_TOKEN` with a long random independent token. **Never commit either secret.**
4. Copy the deployed HTTPS base URL and append `/translate`. In the Android app enable **GOOGLE CLOUD TRANSLATION**, enter the HTTPS URL and the gateway token.
5. Test a short English sentence. The status should say **Google Cloud çeviri hazır**. If it says **Yerel yedek**, the cloud request failed and ML Kit was used.

Google Cloud API charges may apply; set billing budgets and quotas. A Render free instance can sleep and cause latency. Meeting speech and translated text are sent to the gateway and Google Cloud when enabled; assess consent and confidentiality.

## Build
GitHub Actions > Build Android APK > `PST-Live-Translator-debug-APK`.

## Limitations
Physical-device and translation-quality tests pending. Single microphone cannot separate overlapping voices. Cloud Translation Basic does not provide conversational context handling. The Android app requires a deployed gateway for Google Cloud mode.
