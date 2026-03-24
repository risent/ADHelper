# Repository Guidelines

## Project Structure & Module Organization
`app/` contains the Android helper app. Kotlin sources live in `app/src/main/java/com/adhelper/helper/`, resources in `app/src/main/res/`, and the manifest in `app/src/main/AndroidManifest.xml`. `host/` contains desktop-side Python utilities such as `helper_client.py` and `xiaoka_crawler.py`. Top-level Gradle files (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`) define the Android build; `docs/` holds design notes and architecture decisions.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root.

- `./gradlew assembleDebug`: builds the debug APK.
- `./gradlew installDebug`: installs the app on a connected device or emulator.
- `./gradlew lint`: runs Android lint checks.
- `./gradlew test`: runs JVM unit tests if present.
- `./gradlew connectedAndroidTest`: runs instrumentation tests on a device.
- `python3 host/helper_client.py --pretty health`: checks the forwarded helper endpoint.
- `adb forward tcp:7912 tcp:7912`: exposes the device HTTP server to local scripts.

This project targets JDK 17, Android `compileSdk 34`, and `minSdk 30`.

## Coding Style & Naming Conventions
Follow the existing Kotlin and Python style in the repo: 4-space indentation, concise functions, and small helper methods. Use `UpperCamelCase` for Kotlin classes, `lowerCamelCase` for methods and properties, and `snake_case` for Python functions, variables, and CLI flags. Keep Android resource names lowercase with underscores, for example `activity_main.xml` and `accessibility_service_config.xml`. Preserve the current Kotlin DSL formatting in `*.gradle.kts` files.

## Testing Guidelines
There is no committed test suite yet, so new work should add coverage where practical. Put JVM tests under `app/src/test/` and instrumentation tests under `app/src/androidTest/`. Name Kotlin test files after the target class, for example `CommandHttpServerTest.kt`. For host utilities, prefer small deterministic tests around request formatting and CLI behavior.

## Commit & Pull Request Guidelines
Recent commits use short imperative subjects such as `Add host control CLI and usage guide`. Keep commit titles focused and descriptive. Pull requests should explain the user-visible change, list validation steps, and note any device setup required. Include screenshots only when UI text or layout changes in `MainActivity` or related resources.

## Configuration & Device Notes
The helper depends on Accessibility access and the foreground runtime service. Before validating host commands, install the app, start the helper from `MainActivity`, enable `AD Helper Accessibility`, and confirm `adb forward` is active.
