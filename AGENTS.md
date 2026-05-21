# Repository Guidelines

## Project Structure & Module Organization
SMSforwarder is a single-module Android app under `app/`. Source lives in `app/src/main/java` (add Kotlin packages under `dev.zaplatin.smsforwarder`). UI resources belong in `app/src/main/res`, and `app/src/main/AndroidManifest.xml` wires services, receivers, and permissions. Shared Gradle config sits in root `build.gradle.kts` and the version catalog `gradle/libs.versions.toml`. Host-side unit tests live in `app/src/test`, instrumentation in `app/src/androidTest`; keep assets grouped with the feature they serve (e.g., `res/xml/forwarding_rules.xml`). Background jobs (SMS forwarding and battery monitoring) use WorkManager; app-wide initialization belongs in `SmsForwarderApp`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` – compile the debug APK; run before pushing to ensure it installs.
- `./gradlew installDebug` – deploy to the attached/emulated device for manual verification.
- `./gradlew testDebugUnitTest` – executes JVM tests in `app/src/test`.
- `./gradlew connectedDebugAndroidTest` – runs Espresso/device tests from `app/src/androidTest`.
- `./gradlew lint` – Android lint plus Kotlin analysis; required to keep CI green.
Run commands from the repo root and add `--stacktrace` when diagnosing build issues.

## Coding Style & Naming Conventions
Write Kotlin with 4-space indentation, explicit visibility modifiers, and trailing commas in multiline builders. Favor Jetpack components, coroutines, and dependency-injection-ready constructors so SMS logic stays testable. Prefix ViewModel, Service, and Worker classes descriptively (e.g., `ForwardingWorker`). Resource identifiers stay lowercase snake_case (`activity_main.xml`, `color_primary`). Follow Android Studio's formatter, keep XML attributes ordered (id, layout, style, bindings), and update `gradle/libs.versions.toml` when bumping dependencies.

## Background Work Guidelines
Use WorkManager for periodic tasks; remember the minimum periodic interval is 15 minutes. Keep battery alert state in app-private storage (SharedPreferences) and reset thresholds when the charge returns above the configured limit. Log WorkManager scheduling and execution steps with `OperationLogger` for field diagnostics.

## Testing Guidelines
Mirror production packages under `app/src/test` and suffix files with `Test`; instrumentation suites end with `InstrumentedTest`. Exercise SMS parsing, permission handling, and forwarding intents through Mockito/fakes before adding UI tests. Add regression tests when changing backup rules or manifest declarations. Aim to cover critical flows, and never submit code without `testDebugUnitTest` succeeding locally.

## Commit & Pull Request Guidelines
Commits use a short imperative subject prefixed by the area, e.g., `app: add auto-forward worker`. Body lines explain the motivation and list any TODO follow-ups. Pull requests summarize behavior changes, list verification evidence (`testDebugUnitTest`, device model), link related issues, and attach screenshots/logs for UI or permission dialogs. Keep PRs scoped to one feature or fix to simplify reviews.

## Security & Configuration Tips
Do not hardcode phone numbers or tokens; read them from encrypted storage or developer-only build configs. Keep device-specific settings in `local.properties` and never commit secrets. Review `backup_rules.xml` and `data_extraction_rules.xml` when touching persistence so no SMS data leaks during backup/restore.
