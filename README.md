# Medication Observation Note (med-observation-note)

English | [日本語](README.ja.md)

A small, patient-side tool for people on medication treatment: casual conversation with an AI turns into a daily log of medication and condition, and **only what you choose** becomes a summary handed to your attending physician.

This is not a medical device. It does not diagnose and does not decide whether to increase or decrease medication; it only prepares the material a patient brings to the doctor (an aid to the clinical interview).

## What matters in this repository is not the code but the design and operations

The UI and logic live in a single file, and any modern AI can rebuild it quickly. What is worth publishing is the following design policy for protecting patient privacy.

### 1. All records belong to the person alone
- The diary lives in a space only the owner can read and write. Not even collaborators or administrators can see it.
- Full conversation transcripts are not stored. What ends up in the diary is only a short summary of the facts the person actually raised in that conversation.
- AI speculation, diagnosis, and personality judgments are not recorded.

### 2. Only what the person selects leaves the record
- The person marks each day's entry as shared with the doctor or not.
- The physician-facing summary is built only from the days marked as shared.
- Before sending, the person always sees exactly what the doctor will see (nothing is sent automatically).

### 3. The AI's role is limited to listening and organizing
- No medical decisions about doses or stopping medication. On those topics it encourages consulting the attending physician.
- Summaries state only what is in the record, adding no suggestions or diagnoses.
- If suicidal thoughts or similar appear, respond without judgment and point to the attending physician or public support services.

### 4. Guarded through operations
- Diagnosis names and drug names never appear in the app's name, description, or shared-link previews.
- Sharing is always an explicit act by the person; links are private by default.
- When integrating with a medical institution, follow the Act on the Protection of Personal Information (sensitive personal information) and the guidelines on the secure management of medical information systems.

## Running it

`index.html` is written to run as a claude.ai Artifact and uses these two runtime features.

| Feature | Purpose | If you replace it |
|---|---|---|
| `claude.use("db")` | Storing the diary (`entries/<日付>`, owner-only read/write) | On-device IndexedDB, or storage encrypted with the person's own key |
| `claude.use("sample")` | Conversation replies, diary extraction, physician-facing summary | Any LLM API. With a local LLM the records never leave the device |

In environments where neither is available, conversation and summaries are disabled and only the manually entered diary works.

When publishing as an Artifact, declare these permissions.

```json
{
  "db": { "rules": [{ "path": "entries", "read": "owner", "write": "owner" }] },
  "sample": {}
}
```

## Android app

A native version (Kotlin + Jetpack Compose) is in `android/`. APKs are available from [Releases](https://github.com/johnknash2025/med-observation-note/releases).

- **Diary entries from conversation** run on-device with Gemma 4 E2B ([LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)). Neither the records nor the conversations leave the device.
- **The detailed visit summary** can be delegated to a self-hosted inference server (any OpenAI-compatible API such as Ollama, vLLM, or llama.cpp). From outside, access is assumed to go through a VPN such as Tailscale, and unencrypted http is allowed only on the home LAN and Tailscale addresses.
- The diary is excluded from cloud backup and device-to-device transfer via `allowBackup=false` and the backup exclusion rules.

Builds run on GitHub Actions (`.github/workflows/android.yml`). Tagging `v*` attaches a signed APK to the Release.

**Installation**: on a device running Android 12 (API 31) or later, download and open the `.apk` of the latest release from [Releases](https://github.com/johnknash2025/med-observation-note/releases). If you are asked to allow installing apps from unknown sources, grant that permission to the browser (or file app) you used.

If you fork and distribute it yourself, register these repository secrets.

| Secret | Contents |
|---|---|
| `SIGNING_KEYSTORE_B64` | The signing keystore (.jks) encoded as base64 |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

## Data shape

```json
{
  "date": "2026-10-19",
  "share": true,
  "meds": ["薬名と量"],
  "did": "できたこと",
  "feel": "体感・気分",
  "compare": "以前との比較",
  "sleep": "睡眠",
  "creativity": "発想・創造性",
  "concern": "副作用・困りごと"
}
```

## Roadmap

- A mode that works entirely with a local LLM
- On-device encryption of records, plus sharing only the part given to the doctor via temporary links
- Recording the physician's reactions at each visit to improve the summary format

## License

MIT
