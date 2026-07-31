# Built-in performance presets

## Goal

Ship usable OnePlus-oriented presets on every installation while preserving
user control over custom presets. Built-ins must be selectable and bindable but
never editable or deletable. Custom presets retain create, edit, bind, export,
import, and delete operations.

## Scope

1. Add an `isBuiltIn` persistence flag and a non-destructive Room v7→v8
   migration.
2. Seed idempotent built-ins: `省内存`, `均衡`, and `极致性能`; the historical
   compatibility fallback also becomes built-in.
3. Enforce immutability in repository and Room deletion—not merely in Compose
   visibility—then label immutable cards in the UI.
4. Add regression tests for migration/seed behavior and repository rejection.
5. Build, install on PJZ110, verify the cards, and measure each built-in via
   explicit OpenAI `preset_id`; then run five background API generations.

## Acceptance

- Existing databases retain user presets and history.
- Every build contains exactly the three named built-ins plus compatibility
  fallback, with no duplicate rows after repeated opens.
- `update`/`delete` rejects a built-in; custom presets remain mutable.
- Explicit API calls can run each preset and the background service remains
  healthy across five sequential generations.

## Status

- [x] Room v7→v8 migration and idempotent built-in seed.
- [x] Domain, persistence, MCP projection, and UI immutability enforcement.
- [x] JVM regression coverage and debug APK build.
- [x] PJZ110 installation, three-preset DMD2/non-DMD2 comparison, and
  background API stability verification.
