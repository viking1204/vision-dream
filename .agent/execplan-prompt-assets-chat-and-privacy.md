# Prompt, Asset, Chat, Privacy, and Model Metadata Expansion

This ExecPlan is a living implementation record for the second Vision Dream
feature batch. It covers prompt templates, unified generated assets, model-aware
API scheduling, NSFW metadata, directory-based repository downloads, concealed
image presentation, biometric app locking, and a chat-style generation screen.

## Goals

- Replace FIFO-only API scheduling with dynamic model affinity: requests for
  the currently loaded model may overtake, equal-priority requests remain FIFO,
  and a bounded-overtake rule prevents starvation.
- Rename the model action to the literal `unload`.
- Keep `b64_json` compatible and make `response_format=url` return the generated
  image bytes directly as a documented Vision Dream extension; accept
  `response_format=binary` as the explicit alias.
- Upgrade generation history into a unified asset repository that records local,
  connected-device, chat, OpenAI API, and upscale results.
- Add reusable prompt templates and allow prompt selection from generation
  screens and asset metadata.
- Persist NSFW model metadata, infer it conservatively from repository metadata,
  and ask for it during manual import.
- Install repositories that expose a complete supported model as loose files,
  not only ZIP/checkpoint artifacts.
- Conceal every generated image by default and require an explicit reveal.
- Gate only the activity UI with strong biometric authentication. Foreground
  generation and the OpenAI API service must continue independently.
- Add a separate chat-style image generation destination using installed models
  and the existing native inference pipeline.

## Architecture Decisions

- Retain `generation_history` and the existing image directory, then wrap them
  as assets. This preserves existing user data and avoids a duplicate gallery.
- Add Room prompt-template storage rather than encoding templates in
  preferences; templates require querying, editing, ordering, and deduplication.
- Represent generated images internally as encoded bytes plus MIME type. The
  same bytes are persisted and returned, avoiding a decode/re-encode cycle.
- Use a monitored bounded list for model affinity, not a mutable-comparator
  priority heap. At dequeue time, prefer the oldest request matching the loaded
  model, but allow no more than three overtakes of the queue head.
- Store model metadata in `.vision-dream-model.json` inside each model directory.
  Keep it separate from runtime `config.json`.
- A repository directory is compatible only when one root/prefix contains the
  complete required file set for a single supported backend. Downloads stage
  every declared file and publish the validated directory atomically.
- Biometric state lives only in `MainActivity`; services never consult it.
- Reuse current Material theme/components and stable keyed lazy lists. New
  screen-level state is persisted in Room/DataStore, while transient reveal and
  dialog state remains local to Compose.

## Progress

- [x] Audit existing history, gateway, model catalog/download, settings, and
  navigation paths.
- [x] Add Room migration, prompt repository, asset metadata, encoded asset
  storage, and deletion consistency.
- [x] Add model-affinity scheduling, raw image responses, and API asset capture.
- [x] Add NSFW metadata/import UI and loose repository download support.
- [x] Add prompt manager/picker, asset UI changes, revealable image component,
  biometric gate, and chat generation page.
- [x] Update strings and documentation.
- [x] Run unit tests, formatting, static analysis, lint, and APK build.
- [x] Complete physical-device verification on a OnePlus PJZ110. Prompt
  CRUD/picking, chat generation, asset conceal/reveal/details/batch deletion,
  legacy and new NSFW labels, public model discovery, queue-capacity editing,
  package/storage permissions, IPv4 loopback, authenticated models/raw/b64
  generation, concurrent admission, model-affinity order, idle API plus in-app
  loading, unload/ranking, and three instrumentation tests pass. The biometric
  prompt no longer crashes, enabling is committed only after authentication,
  background API generation continues while the prompt is visible, and the
  switch is left off at the user's request.

## Verification

- Scheduler tests cover loaded-model preference, FIFO within a model, three
  overtakes maximum, model changes, capacity, shutdown, and inference-lease
  release.
- Room/repository tests cover migration, prompt CRUD/deduplication, all asset
  origins, encoded file preservation, and single/batch deletion.
- Catalog tests cover ZIP, checkpoint, complete loose layouts, nested layouts,
  incomplete layouts, traversal rejection, and NSFW signals.
- API tests cover `b64_json`, direct PNG/JPEG bytes, installed models, asset
  metadata, and queue rejection.
- Compose/device checks cover default concealment, prompt selection, asset
  details/deletion, `unload`, biometric relock without stopping services, and
  chat generation.
- A device-only chat failure exposed a main-thread health probe: the native
  service was healthy while Compose waited until timeout. Health I/O now runs
  on `Dispatchers.IO`; a cold chat request loaded the selected model and
  produced a concealed 512x512 asset in nine seconds.
- Legacy model folders without `.vision-dream-model.json` use a conservative
  name fallback. Embedded `NSFW` is recognized, utility names are excluded, and
  any persisted user/repository rating takes precedence.
- Physical-device checks use package `io.github.ddq.visiondream` version `1.0`
  on Android 16. The OEM package installer rejects the test APK with `-99` if
  its confirmation UI times out behind the secure lock screen; this is an
  environment gate, not a test assertion failure.

## Notes

- OpenAI's current Image API documents JSON image responses; returning raw bytes
  for `response_format=url` is intentionally a Vision Dream extension requested
  by the user. The compatible default remains `b64_json`.
- NSFW inference is metadata-based and therefore advisory. It must never inspect
  or classify downloaded weights or generated pixels.
