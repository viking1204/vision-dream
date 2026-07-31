# Human-friendly performance preset editor

## Goal

Replace the developer-oriented JSON editor with a structured Compose control
surface. Users must be able to inspect immutable built-ins, clone one as a
custom preset, and create, edit, or delete custom presets without knowing the
storage schema.

## Scope

1. Add a single domain serializer for strict v2 engine configuration.
2. Replace selector, model-id, and JSON text fields with named switches and
   bounded choices for CLIP threads, HTP power mode, and HTP partitioning.
3. Generate internal selectors automatically.
4. Add “create from this preset” to built-in detail and cards.
5. Keep raw JSON available only in read-only detail/export surfaces.
6. Add regression tests, build, install, and verify on the connected device.

## Acceptance

- Built-ins remain immutable and fully inspectable.
- A built-in can seed a new editable custom preset.
- New/edit dialogs expose no editable JSON or selector fields.
- Every saved configuration parses as strict v2.
- Existing custom presets remain editable without losing their selector.
- The debug APK builds and the installed screen passes a device smoke test.

## Status

- [x] Domain serializer and tests.
- [x] Structured Compose editor and built-in clone flow.
- [x] Compose regression coverage.
- [x] Build, install, and device smoke test.
