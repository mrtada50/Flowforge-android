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
Workflow Studio now has two modes: **Stack** (default) — an OpenBullet-style vertical list of colour-coded blocks
(green=HTTP request, blue=branch/check, amber=parser, purple=other) with add/duplicate/delete/move/save/export icons,
an inline Block Info panel for the selected block's fields, and a Debugger panel that runs the workflow once and shows
BOT STATUS + VARIABLES — and **Canvas**, the original free-form node graph for branches, loops and manual wiring.
Execution engine, HTTP engine (allowlist, limits, cookies, one optional proxy), Parser engine, Request Lab, Parser Studio,
Data Manager, Proxy Manager, Runner (single/dataset runs, hard limits, pause/resume/stop, foreground service), Results
(search, filters, paging, CSV export) and Analytics.

Safety limits: only hosts in Allowed domains are contacted, max 8 workers, max 20 items/second, max 100,000 items, no proxy rotation.
Not yet: WorkManager scheduling, instrumented UI tests, module split.
