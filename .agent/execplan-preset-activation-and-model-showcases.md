# Preset Activation and Installed-model Showcases

This ExecPlan tracks the correction of performance-preset activation and the
one-time enrichment of the currently connected OnePlus 13 model library.

## Goals

- Make the effective performance strategy visible and controllable.
- Treat `持续性能` as the recommended automatic baseline; never fall back to
  the low-quality compatibility preset while a supported built-in exists.
- Let users activate immutable built-ins or custom presets without making
  built-ins editable or deletable.
- Make the activation switch disable manual/model overrides while preserving
  them for a later re-enable.
- Fill only missing installed-model descriptions with concise Chinese style
  and use-case metadata, preserving existing source/runtime/rating evidence.
- Add one same-name prompt-library entry per installed generation model. Each
  prompt depicts an adult woman in NSFW content and is tailored to the model's
  characteristic visual language. Upscalers are excluded.

## Architecture Decisions

- Presence of the `DEFAULT` binding is the master override switch. When absent,
  model-specific bindings are dormant and resolution selects the recommended
  built-in. This avoids a parallel preference that could drift from Room.
- `持续性能` is the product default because it is the validated high-throughput
  profile for repeated/background generation on the target device. The
  compatibility preset remains an internal last-resort only if built-ins are
  missing or corrupt.
- A user selecting a preset is an explicit product action, not an unattended
  optimizer decision. Qualification records remain audit evidence but no
  longer block a user-selected binding.
- Current device enrichment is performed transactionally and idempotently:
  metadata writes are atomic, prompt titles are unique per installed model,
  existing user templates are not overwritten, and the database is backed up
  before mutation.

## Progress

- [x] Audit preset resolution, Room bindings, installed model metadata, and
  prompt storage.
- [x] Implement recommended-default resolution, activation state, and UI.
- [x] Add regression and Compose coverage.
- [x] Research missing model styles and prepare model-specific prompt data.
- [x] Back up and enrich the connected device.
- [x] Build, install, and verify UI, metadata, and prompt counts.

## Validation

Run:

    ./gradlew app:testDebugUnitTest
    ./gradlew app:lintDebug
    ./gradlew app:assembleDebug app:assembleDebugAndroidTest

Then install without clearing data, verify the preset console states, inspect
the public model metadata files, and query prompt titles from Room. Temporary
database copies and UI captures must be moved to Trash.

## Outcome

- With no `DEFAULT` binding, both local and API request admission now resolve
  to `持续性能`. Enabling the override creates the binding; disabling it deletes
  only `DEFAULT`, leaving model-specific choices dormant and recoverable.
- The physical preset page showed `当前生效：持续性能`, switched to `均衡`,
  persisted the expected built-in ID, then returned to automatic sustained
  mode with zero `DEFAULT` rows.
- All 77 installed generation models now expose Chinese style/use-case
  descriptions. Existing runtime compatibility and native attestation fields
  were preserved byte-semantically while missing metadata was enriched.
- The prompt library contains the original user template plus 77 model-named
  adult-woman NSFW showcase templates. Every new template has an explicit
  adult-only positive constraint and underage exclusions in its negative
  prompt.
- Ktlint, detekt, JVM tests, Lint, debug/test APK builds, and all four focused
  physical-device Compose tests pass. The test package was uninstalled after
  validation, leaving only `io.github.ddq.visiondream` on the device.
