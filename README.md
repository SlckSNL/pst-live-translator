# PST Live Translator — Azure AI Translator F0

Android live English-to-Turkish meeting translation. Android SpeechRecognizer or Vosk recognizes English; Azure AI Translator translates finalized English; on-device ML Kit remains the default and automatic fallback.

## Azure F0 setup
1. Open [Azure Portal](https://portal.azure.com/), sign in with a Microsoft account, and create a **Translator** resource under Azure AI services. Select the **F0 (Free)** pricing tier if offered for your subscription/region. F0 availability and quotas are subject to Azure terms; Microsoft 365 E3/E5/Copilot licenses do not grant Translator API access automatically.
2. From the Translator resource's **Keys and Endpoint** page, copy **Key 1** and the **Region/Location**. Do not put this key in Android or GitHub.
3. Deploy the repository using [Render Blueprints](https://dashboard.render.com/select-repo?type=blueprint). Set these **secret environment variables**:
   - `AZURE_TRANSLATOR_KEY`: Azure Translator resource key.
   - `AZURE_TRANSLATOR_REGION`: resource region (e.g. `westeurope`), required for regional/multi-service resources.
   - `PST_GATEWAY_TOKEN`: separate, long random password for Android-to-gateway authentication.
   - Optional `AZURE_TRANSLATOR_ENDPOINT`: custom Azure Translator endpoint; default is `https://api.cognitive.microsofttranslator.com`.
4. Copy Render's public HTTPS URL and append `/translate`. In Android check **AZURE AI TRANSLATOR F0**, enter this HTTPS URL and **PST_GATEWAY_TOKEN** (NOT the Azure resource key). Only the URL is saved by the app; the gateway token is not persisted.
5. Speak an English test sentence. **Azure Translator çeviri hazır** confirms successful cloud response. **Yerel yedek çeviri hazır** means Azure was unreachable and ML Kit handled it.

## Security, reliability and privacy
- Keep Azure key server-side; never commit secrets or send them in chat. Restrict access, monitor quota and set budget alerts.
- HTTPS and a bearer token protect the gateway, but for production add rate limiting, secret rotation and appropriate monitoring.
- Render's free service can sleep, causing slow first responses. Live meetings may require an always-on plan.
- English text is sent to the gateway and Azure when cloud translation is enabled. Obtain appropriate consent and follow confidentiality policies.
- Azure AI Translator is a **text translation** service, not a conversational LLM. Previous English/Turkish segments in the Android request are accepted for API compatibility but not used by Azure text translation.
- This implementation retains existing English recognition and ML Kit preview. The cloud path translates finalized chunks; it does not stream partial Azure translations.

## Build
GitHub Actions > Build Android APK > artifact `PST-Live-Translator-debug-APK`.

## Verification
Android build and actual Azure F0 connection must be tested. This repo does not provision an Azure resource or a live HTTPS server automatically.
