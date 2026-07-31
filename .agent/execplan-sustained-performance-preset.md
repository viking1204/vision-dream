# Sustained performance preset

## Goal

Separate single-image peak throughput from repeated/background generation.
The sustained preset must preserve NPU performance without leaving the native
runtime vulnerable to the ColorOS ION memory jail observed with the current
extreme preset.

## Scope

1. Reproduce consecutive OpenAI API generation with an existing low-memory
   preset on the connected OnePlus 13.
2. If stable, add an immutable `持续性能` built-in with a stable ID and a
   product-facing explanation distinct from `极致性能`.
3. Keep `极致性能` as the explicit single-image peak profile.
4. Update database seed/migration coverage and Compose semantics.
5. Build, install, and repeat the background API smoke test.

## Acceptance

- Built-ins clearly distinguish single-image peak from sustained operation.
- Existing databases idempotently receive the new built-in.
- Two consecutive non-DMD2 API generations finish without an orphaned native
  process or ColorOS `ion_limit` jail.
- Stopping the API during a generation tears down the native backend.

## Status

- [x] Validate low-memory sustained baseline on OnePlus 13.
- [x] Add immutable sustained built-in and UI copy.
- [x] Update tests and build.
- [x] Install and verify background generation.

## Evidence note

The low-memory/power-saver baseline completed two consecutive non-DMD2
requests and released native RSS back to about 70 MB after each request, but
ColorOS recorded one transient `ion_limit` event at roughly 2.8 GB ION.

After installing the dedicated low-memory/HTP-performance preset, two more
background requests completed in 20 s and 18 s. Native RSS returned to roughly
69 MB after both, no `ion_limit` event appeared in the cleared acceptance log,
and stopping the API removed both foreground services and the native process.
