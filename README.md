# FlowForge Android

Visual HTTP workflow engine for **authorized testing only** (domain allowlist enforced).
All builds, tests and APKs are produced by **GitHub Actions** (`.github/workflows/build-apk.yml`). No local Gradle/SDK needed.

## Push from Termux
```
pkg install git
cd FlowForge
git init -b main && git add . && git commit -m "Phase 0/1 foundation"
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```
Then open the repo -> Actions -> Build APK -> download `flowforge-debug-apk` from Artifacts.

## Release signing (optional)
Add repository secrets: `KEYSTORE_BASE64` (base64 of your .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the release APK is produced unsigned.

## Status
Implemented: CI, Compose + navigation, EN/AR + RTL, Room schema (v2 + migration), Workflow Studio (visual canvas: add/drag/connect/rename/duplicate/delete,
undo/redo, autosave, validation, JSON export/import), execution engine, HTTP engine (allowlist, limits, cookies, redirects), Parser engine,
Request Lab, Parser Studio, Runner (in-app), Results (filter + paging), domain allowlist setting.
Not yet: Data Manager, Proxy Manager, foreground service/WorkManager, analytics charts, instrumented UI tests.
