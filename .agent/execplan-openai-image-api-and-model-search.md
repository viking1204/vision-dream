# OpenAI Image Gateway and Model Discovery ExecPlan

This plan records the first gateway/model-search batch. The later
`.agent/execplan-prompt-assets-chat-and-privacy.md` supersedes its queue,
response-format, asset, and privacy decisions; the current behavior is also
reflected below.

## Goal

Turn the Android device into an explicitly enabled, authenticated LAN image
inference gateway, and let users discover and install compatible models from
the currently configured Hugging Face-compatible source.

The finished app must:

- install alongside the upstream app as `io.github.ddq.visiondream`,
  display as Vision Dream, and restart its release line at version 1.0 (code 1);
- produce one unfiltered application variant only;
- expose installed models through `GET /v1/models` and the `/models` alias;
- implement OpenAI-compatible `POST /v1/images/generations` and
  `POST /v1/images/edits`;
- provide the documented vision-dream extension
  `POST /v1/images/upscales` because OpenAI has no standard upscale endpoint;
- switch to the requested installed model inside a single model-aware priority
  execution domain;
- allow one running inference plus a configurable number of waiting requests,
  rejecting overflow with HTTP 429;
- keep in-app loading and generation usable while the gateway is enabled,
  while preventing local and API inference from overlapping;
- keep the native backend bound to loopback while the gateway is active;
- keep every installed model in the stable public repository
  `/storage/emulated/0/VisionDream/models`, with no private-storage fallback;
- search the configured Hugging Face-compatible source, hide results that
  cannot be safely imported, warn for already-installed results, then download
  and import supported artifacts.

## Current Architecture

`BackendService` is already a desired-state reconciler for one native process.
The process serves `/generate`, `/upscale`, and `/health` on port 8081.
`RemoteHostService` and `RemoteHostServer` implement a separate device-link
protocol on port 8808. That control server accepts only small JSON bodies and
its `/models` response is not OpenAI-compatible, so changing it would break the
existing controller.

Upstream model files lived in `filesDir/models/<modelId>`. That location is
package-private and disappears on uninstall. Some built-in download and custom
import paths also constructed it independently, which made a partial path
change unsafe.

## Design

### Gateway

Add a separate foreground `OpenAiApiService` on port 8809. It owns:

1. a bounded HTTP server with body/header limits and bearer-token
   authentication;
2. a single-worker `BoundedSerialExecutor`, whose bounded queue counts waiting
   requests only and prefers requests for the currently loaded model;
3. a runtime coordinator that resolves only validated installed model IDs,
   requests the matching `BackendService` configuration, waits for matching
   state plus `/health`, and then calls the loopback native endpoint.

All generations, edits, and upscales enter the same executor. Requests for the
currently loaded model may overtake another model, equal-priority requests are
FIFO, and a three-overtake limit prevents starvation. `n` is limited to 1.
`b64_json` is the compatible default; `url` returns the image body directly and
`binary` is its explicit alias.

An in-process inference arbiter makes API admission and in-app generation
admission atomic. Accepted API requests retain reservations while queued, so
the model cannot be switched by the app before their turn. This closes the
check-then-act race left by independent service-running flags.

The gateway uses a generated bearer token, explicit user start/stop, request
size and pixel limits, and no permissive CORS headers. It cannot run
simultaneously with device-link host mode. While it is active,
`BackendService` ignores legacy LAN binding preferences and binds only to
loopback.

### Installed Catalog and Inference

Add an installed-model validator keyed by backend type. Required runtime files
must be present and non-empty; completion markers alone are insufficient.
Upscaler IDs resolve to server-side paths and client-supplied file paths are
never accepted.

Generation/edit inputs are normalized into the native JSON contract. Multipart
edits accept one `image` or `image[]` and an optional `mask`. Upscale accepts
one image and an installed upscaler ID. Native SSE completion is converted to
the OpenAI `created/data[].b64_json` response.

### Search and Installation

Use `<configured-base-url>/api/models` with keyword, `text-to-image`, detail,
and result limits. A pure compatibility evaluator accepts:

- a root-level Local Dream ZIP whose artifact name and model metadata identify
  a supported package; or
- a root-level single-file SD 1.5 checkpoint with explicit SD 1.5 base-model
  metadata.

Generic Diffusers repositories, SDXL checkpoints that require unsupported
conversion, inpainting-only checkpoints, nested shards, and ambiguous
repositories are filtered out.

Downloads use private scratch files, while extraction and conversion use a
staging directory beside the public repository. ZIP contents or converted
output are validated before a same-filesystem rename publishes the model. The target
is checked both before download and before commit. An existing installation is
returned as `AlreadyInstalled`; the UI shows a prompt and does not overwrite
it. Model IDs are normalized to a strict safe character set.

### Public Model Repository

`ModelStorage` is the only authority for installed-model and staging paths. On
Android 11 and newer the app requires explicit all-files access, then uses
`/storage/emulated/0/VisionDream/models`. Android 9 and 10 use the legacy
runtime write permission. Missing access is a blocking state: callers fail
closed instead of creating a second private repository.

The launcher rechecks access whenever the activity resumes from system
settings. Before showing model UI it creates the public repository and copies
models left by an earlier Vision Dream build through a same-volume staging
directory. Existing public entries are not overwritten, and private sources
are retained as recovery copies. Download/conversion staging is also on the
public volume so final publication remains an atomic rename.

## Verification

- Pure JVM tests cover OpenAI route parsing/error envelopes, multipart parsing,
  model-aware priority/overflow semantics, model-ID normalization, Hugging
  Face response filtering, and installed-file validation.
- `./gradlew testDebugUnitTest`
- `./gradlew ktlintCheck detekt lintDebug`
- `./gradlew assembleDebug`
- Native compilation or targeted C++ build validation after changing the
  generation/upscale mutex.
- Manual/runtime verification on an Android device is required for foreground
  service lifetime, native model switching, and real Hugging Face downloads.

## Progress

- [x] Inspect latest `origin/master`, project rules, build setup, device-link,
  backend lifecycle, native endpoints, download/import paths, and official
  OpenAI image/model contracts.
- [x] Protect the dirty, outdated checkout by creating this latest-based
  worktree and branch.
- [x] Implement gateway contracts, HTTP transport, authentication, queue, and
  runtime coordinator.
- [x] Add service settings and Remote screen controls.
- [x] Add installed-model validation and serialize native upscale with
  generation.
- [x] Implement compatible model search, transactional installation, duplicate
  warning, and model-list UI.
- [x] Add parser, queue, model-ID, compatibility, and response-envelope tests.
- [x] Pass the final serial build/static-check run after all review fixes.
- [x] Review and harden security, service lifecycle, backend ownership,
  cancellation, and partial-install behavior.
- [x] Centralize installed models and installation staging in the public
  repository, add permission gating, and copy legacy Vision Dream models.
- [x] Replace the launcher/adaptive icon with the new Vision Dream eye,
  aperture, and star identity.
- [x] Rebuild, install, and verify permission denial/grant plus public storage
  creation on the attached Android device.
- [x] Allow queue capacity editing from 0–10, including an empty transient
  text-field value.
- [x] Share the loopback backend safely between an idle gateway and in-app
  model loading/generation.
- [x] Show the loaded model, persist MRU ranking, and provide a scoped unload
  action from the model list.
- [x] Rebuild, install, and verify same-device loopback, model loading,
  unloading, ranking, queue limits, and output-format errors on hardware.

## Decision Log

- 2026-07-23: Use a new port/service instead of changing device-link port 8808;
  its `/models` schema is already consumed by `RemoteApiClient`.
- 2026-07-23: `/v1/images/upscales` is a documented vision-dream extension,
  not an OpenAI-standard route.
- 2026-07-23: Queue capacity means waiting requests; one running request is
  additional. Generation, edit, and upscale share the queue.
- 2026-07-23: Default to mandatory generated bearer authentication and
  loopback-only native serving.
- 2026-07-23: Reject ambiguous search results. A false negative is preferable
  to downloading a model the app cannot import.
- 2026-07-23: Run the gateway and device-link host mode as mutually exclusive
  `connectedDevice` foreground services. Keep native port 8081 loopback-only
  while the authenticated gateway owns the backend.
- 2026-07-23: Publish downloaded models only by a same-filesystem rename from
  validated staging, never by incrementally populating the installed path.
- 2026-07-23: Keep API credentials device-local by excluding their preference
  file from cloud backup and device transfer.
- 2026-07-23: Change the install identity to
  `io.github.ddq.visiondream`, app label to Vision Dream, and version to
  1.0 (code 1). Keep the Kotlin namespace unchanged because `applicationId`
  provides Android install isolation without a risky source-tree rename.
- 2026-07-23: Remove the `filter` product flavor, its 11 MiB safety-checker
  asset, filter-only reporting path, and conditional runtime wiring. Vision
  Dream now has one base build variant and one APK per build type.
- 2026-07-23: Reject uploaded images before queue admission when decoded
  dimensions exceed edge or pixel limits, and repeat the upper bound in native
  decoding so direct loopback callers cannot trigger decompression OOM.
- 2026-07-23: Keep the requested LAN endpoint on HTTP, but treat bearer
  authentication only as caller authentication, not transport encryption.
  Show an explicit in-app warning and require a TLS reverse proxy before
  crossing an untrusted network.
- 2026-07-23: Use the fixed public path
  `/storage/emulated/0/VisionDream/models` with
  `MANAGE_EXTERNAL_STORAGE`. The native QNN/MNN runtime needs real paths, so a
  Storage Access Framework URI tree would add a fragile mirroring layer.
- 2026-07-23: Never fall back to `filesDir/models` after the public-storage
  switch. One visible repository is easier to reason about and prevents models
  from appearing or disappearing based on permission state.
- 2026-07-23: Copy legacy Vision Dream private models without deleting their
  sources or overwriting public entries. The original Local Dream package
  remains inaccessible because Android isolates its non-debuggable data.
- 2026-07-23: Brand the launcher with a centered eye/aperture/star emblem on a
  deep navy adaptive background, plus a simplified monochrome system icon.
- 2026-07-23: Keep inference serial even for the same model. The single native
  pipeline uses a process-wide generation mutex; removing it would introduce
  unsupported NPU/runtime concurrency rather than useful parallelism.
- 2026-07-23: Use one atomic inference arbiter across UI and API admission;
  separate busy flags leave a model-switch race between their checks.
- 2026-07-23: Treat model use order as persisted MRU state. Unload releases the
  runtime without deleting rank, so the list remains a visible use ranking.
- 2026-07-24: Supersede the earlier `b64_json`-only decision. Per product
  requirement, `response_format=url` now streams the image body directly
  without creating a public artifact URL; `binary` is the explicit alias.
- 2026-07-24: Supersede FIFO scheduling with loaded-model affinity, stable FIFO
  within equal priority, and a three-overtake starvation bound.

## Verification Notes

- The coexistence and model-ranking follow-up passes `ktlintCheck`, `detekt`,
  `testDebugUnitTest`, `lintDebug`, and `assembleDebug`. All 65 JVM tests pass,
  including atomic App/API inference admission and queued-reservation coverage.
- The rebuilt 139 MiB debug APK is
  `app/build/outputs/apk/debug/VisionDream_armv8a_1.0.apk`, SHA-256
  `7b4c819a7e53970d1ccb2c11963e57c4d3e3d267374e6b0130e25566d4270723`.
  `aapt` confirms package `io.github.ddq.visiondream`, version code 1, and
  version name 1.0.
- A clean serial run of `ktlintCheck`, `detekt`, `testDebugUnitTest`,
  `lintDebug`, and `assembleDebug` passed after removing product flavors. All
  58 JVM tests passed without failures or skips after adding public-storage
  migration coverage, and Android lint reported 0 errors.
- The sole debug APK confirms package `io.github.ddq.visiondream`, version code
  1, version name 1.0, and label Vision Dream.
- The final APK was installed on the physical PJZ110 running Android 16. With
  all-files access disabled, the app showed the blocking permission screen and
  the exact public path. Its action opened the app-specific system settings.
  After enabling access, the app returned to the model list and created
  `/storage/emulated/0/VisionDream/models`.
- The existing private `AsianMix-qnn2.28-8gen2` installation was copied to the
  public repository without deleting its source: both trees contained 23 files
  and reported 1,555,832 KiB. A force-stop/relaunch retained permission, listed
  the migrated model as downloaded, and produced no fatal application log.
- Android system settings rendered the new eye/aperture/star launcher icon,
  Vision Dream label, version 1.0, and enabled all-files switch as expected.
- On PJZ110 (Android 16), the API screen accepted an empty transient queue
  value and then `10`; after restart it reported `0/10`. A device-specific
  IPv6-only wildcard bind was fixed by explicitly listening on IPv4. Same-device
  `127.0.0.1:8809` now rejects unauthenticated calls immediately, returns all
  installed models when authenticated, and returns a 512x512 PNG body for
  `response_format=url`.
- Two same-model generation callers were admitted concurrently: while one was
  running, `/health` reported `active=true` and `queued=1`; both later returned
  HTTP 200 serially. A three-request hardware run completed loaded-model work
  in A/C/B order while B required a switch, confirming affinity without
  enabling parallel native inference.
- With the API service enabled but idle, loading a local model reached its
  prompt screen without hanging. The model list showed the loaded state and an
  unload action; after unload, the model remained first by MRU rank. Cleanup
  stopped the API service, unloaded the model, and confirmed ports 8081 and
  8809 were no longer listening.
- The native build script cannot run on this macOS host: it requires the
  Qualcomm AI Runtime SDK at `/data/qairt/2.39.0.250926` and a Linux Android
  toolchain preset. The C++ change is limited to taking the existing process
  mutex in the upscale handler.
- The follow-up JVM regression run passed with 65 tests, including the new
  queue/MRU and atomic app-versus-API inference-admission coverage. Ktlint
  passed. `assembleDebug` produced a 139 MiB APK whose manifest confirms
  package `io.github.ddq.visiondream`, version `1.0` (code `1`), and label
  `Vision Dream`. Detekt and Android Lint could not complete in this session
  because their first-run analyzer dependencies remained stalled on remote
  Maven reads; they need a later cached rerun.
