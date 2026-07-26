# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android application. Kotlin and Jetpack Compose code is
under `app/src/main/java/io/github/xororz/localdream/`: `ui/` contains screens
and components, `data/` owns models and persistence, `service/` contains
foreground work, `openai/` implements the compatible image API, and
`modelcatalog/` handles repository discovery and installation. Native Stable
Diffusion code and CMake files live in `app/src/main/cpp/`; keep vendored code
isolated under `3rdparty/`. Android resources are in `app/src/main/res/`, bundled
runtime assets in `app/src/main/assets/`, JVM tests in `app/src/test/`, and
device tests in `app/src/androidTest/`.

## Build, Test, and Development Commands

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew assembleDebug               # build the debug APK
./gradlew testDebugUnitTest            # run JVM tests
./gradlew connectedDebugAndroidTest    # run emulator/device tests
./gradlew ktlintCheck detekt lintDebug # run static checks
```

For native changes, run `cd app/src/main/cpp && ./build.sh`. Do not commit SDK
paths, keystores, generated `jniLibs`, or build output.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, `PascalCase` for classes and composables, and
`camelCase` for functions and properties. Keep packages aligned with ownership;
use established suffixes such as `*Screen`, `*Service`, `*Repository`, and
`*Manager`. Run ktlint before review and keep detekt clean. For C++, preserve
the surrounding style and avoid formatting vendored sources.

## Testing Guidelines

Name test classes after the subject, for example `BoundedSerialExecutorTest`,
and describe observable behavior in test methods. Add regression coverage for
parsers, queue boundaries, model validation, and failure paths. Use
instrumentation tests for Android service lifecycle, storage, or Compose UI
behavior.

## Commit & Pull Request Guidelines

Use concise Conventional Commit-style subjects, such as
`feat: 增加模型搜索` or `fix: 拒绝超额队列请求`. Keep commits focused. Pull
requests must describe user impact, tests run, linked issues, and screenshots
for visible UI changes. Call out model-format, ABI, permission, and
network-security effects explicitly.
