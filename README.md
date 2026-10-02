# CIE NFC POC

Standalone Android proof-of-concept for local NFC inspection of CIE-compatible electronic documents.

## Privacy boundary
- No android.permission.INTERNET.
- No files, database, SharedPreferences, analytics, crash reporting, or PII logs.
- CAN/MRZ secrets exist only in memory for the active session.
- DG2/DG3/DG4 are never read.
- The copyable report contains metadata only, never personal values.

## Scope
The POC reports what the card declares and what can actually be read. It does not infer CIE capabilities from generic ICAO/JMRTD support and does not claim authenticity unless the SOD signature chain is verified to a trusted CSCA.

## Build
GitHub Actions builds a debug APK. Download the cie-nfc-poc-debug-apk artifact from the latest Android debug APK workflow run.

## Dependencies
JMRTD 0.8.8 (LGPL), SCUBA Android 0.0.26 (LGPL), Bouncy Castle 1.85.2 (Bouncy Castle Licence).
