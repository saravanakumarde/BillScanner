# Bill Scanner

A personal Android app for scanning shopping receipts (English or German),
extracting the data automatically, and browsing your spending by bill, week,
month, category, or store — fully offline, no cloud services, no API keys.

Built for: Xiaomi 13T (Android 13/14, HyperOS) — but works on any Android 8+
device.

---

## What it does

1. **Scan** — take a photo of a receipt with the in-app camera.
2. The photo is saved straight to your phone's **Gallery**
   (`Pictures/BillScanner/`), never hidden in app-private storage.
3. **On-device OCR** (Google ML Kit, Latin script — covers German umlauts
   and ß as well as English) reads the text from the photo. No internet
   connection or API key is used for this step.
4. A **rule-based parser** picks out the store name, date, total amount, and
   individual line items from the raw text, using regex patterns tuned for
   German (`Summe`, `Gesamt`, `31.12.2025`, comma-decimals) and English
   (`Total`, `12/31/2025`, dot-decimals) receipts. It also recognizes ~40
   common German and English supermarket/drugstore chains by name.
5. Known German words (product names, receipt jargon) are translated to
   English using a **bundled offline dictionary** (see
   `data/dict/GermanEnglishDictionary.kt`) — free, no network required. The
   original German text is always kept and shown alongside the English
   translation, since the dictionary won't cover everything.
6. You **review and correct** the extracted data (store, date, total,
   category, items) before saving — auto-extraction is a first draft, not
   final.
7. A **category** is auto-guessed from the store/items (e.g. Rewe → Groceries,
   dm → Pharmacy) but you can change it, and add your own categories anytime.
8. Browse everything: **All bills**, **This week**, **This month**,
   **By category**, **By store** — each tappable through to full bill detail.

Everything is stored locally in an on-device SQLite database (via Room).
Nothing leaves your phone.

---

## Requirements

- Android Studio (recent version — Koala/2024.1 or newer recommended)
- JDK 17 (Android Studio usually bundles this)
- An Android device or emulator running Android 8.0 (API 26) or newer

## How to build the APK

1. **Open the project**
   Android Studio → `File > Open` → select the `BillScanner` folder (the one
   containing `settings.gradle.kts`).

2. **Let Gradle sync**
   Android Studio will automatically download the required Gradle version and
   dependencies (Room, CameraX, ML Kit, AndroidX, Material, MPAndroidChart)
   the first time you open the project. This needs an internet connection
   once; after that, builds work offline. This can take a few minutes the
   first time.

3. **Build a debug APK to test on your phone**
   - Menu: `Build > Build Bundle(s) / APK(s) > Build APK(s)`
   - Or terminal inside the project: `./gradlew assembleDebug`
   - Output: `app/build/outputs/apk/debug/app-debug.apk`
   - Copy this to your Xiaomi 13T (e.g. via USB, or `adb install app-debug.apk`)
     and install it (you'll need to allow "install unknown apps" for
     whichever app you used to transfer it).

4. **Build a signed release APK** (recommended for daily use — smaller,
   optimized, and installable without a debug warning)
   - `Build > Generate Signed Bundle / APK`
   - Choose **APK**
   - Create a new keystore the first time (Android Studio walks you through
     this — save the `.jks` file and its passwords somewhere safe; you'll
     need the *same* keystore for every future update if you want to install
     over the existing app without uninstalling first)
   - Choose the `release` build variant
   - Output: `app/release/app-release.apk`

5. **Install on your Xiaomi 13T**
   - Easiest: `adb install app/release/app-release.apk` with the phone
     connected via USB and USB debugging enabled (Settings > About phone >
     tap "MIUI/HyperOS version" 7 times to unlock Developer options, then
     enable USB debugging).
   - Or copy the APK to the phone and open it from a file manager; HyperOS
     will prompt to allow installation from that source.

On first launch, grant the **Camera** permission when prompted (needed to
scan bills). On Android 9 and below, a storage-write permission may also be
requested (needed only on very old Android versions to save into the
Gallery; Android 10+ doesn't need it).

---

## Extending the German↔English dictionary

Open `app/src/main/java/com/billscanner/app/data/dict/GermanEnglishDictionary.kt`
and add entries to the `DICTIONARY` map, e.g.:

```kotlin
"gurken" to "Cucumbers",
```

Rebuild and the new term will be recognized on future scans (existing saved
bills keep whatever was recognized at scan time — original text is always
preserved so nothing is lost).

## Extending the receipt parser

If a particular store's receipt format isn't parsed well (e.g. total not
found, items missed), the rules live in
`app/src/main/java/com/billscanner/app/parser/ReceiptParser.kt`. It's plain
Kotlin regex — no ML/AI involved — so it's fully inspectable and tweakable.
Whatever it gets wrong, you can always fix on the Review screen before
saving, and the raw OCR text is stored with every bill for reference.

## Known limitations (by design, to keep this 100% free/offline)

- OCR accuracy depends on photo quality — good lighting and a flat receipt
  help a lot. Crumpled thermal-paper receipts are the hardest case.
- The offline dictionary covers common supermarket/drugstore terms, not
  every possible product name. Untranslated German text is shown as-is.
- Currency is assumed to be EUR unless a different symbol is detected on the
  receipt (this app is DACH-region-focused, matching your use case).
- No cloud backup/sync — your data lives only on this device's local
  database. Consider occasionally backing up your phone as usual (this app
  doesn't need any special backup handling beyond that).

## Project structure

```
app/src/main/java/com/billscanner/app/
  data/db/         Room entities, DAOs, database
  data/dict/        Offline German→English dictionary + translator
  ocr/              ML Kit wrapper + image pre-processing
  parser/           Receipt parsing rules + category auto-matching
  ui/               Activities: Main, Scan, Review, List, Detail, Stats, Category
  util/             Gallery storage, currency/date formatting
```
