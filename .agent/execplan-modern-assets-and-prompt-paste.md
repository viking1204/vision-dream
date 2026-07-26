# Modern Asset Browser and Prompt Transfer

This ExecPlan tracks the third Vision Dream feature batch: compact visual
tokens, multiple asset layouts, explicit reveal/preview gestures, prompt-pair
copy/paste, and same-device API diagnostics.

## Goals

- Offer three mainstream asset layouts: a two-column waterfall preserving
  image aspect ratios, a compact three-column grid, and a metadata-rich list.
- Replace the asset filter action with a layout selector. Keep the shared
  filter implementation available to model-run history, but remove it from the
  global asset page.
- Add a top-level "show all images" checkbox. Individual image-center taps
  toggle reveal/hide; a separate top-right fullscreen affordance opens preview.
- Make concealed placeholders icon-only and visually quiet. Fullscreen preview
  displays the image without conceal controls.
- Surface model name, positive prompt, and negative prompt in asset list/detail
  views. Copy both prompts as one typed clipboard payload that generation,
  chat, and prompt-manager inputs can recognize and split on paste.
- Replace the oversized expressive feel with a compact theme type/shape scale
  and tighter screen spacing while preserving 48dp semantic touch targets.
- Log only method, path, and status for the local OpenAI gateway (never API
  keys, prompts, or image bodies) so caller compatibility failures can be
  diagnosed from the device.

## Architecture Decisions

- Persist the chosen asset layout in app preferences; reveal state remains
  session-local and defaults to hidden whenever the asset page is reopened.
- Use stable asset IDs for every lazy list/grid key. Waterfall items derive
  their aspect ratio from stored width/height; list and grid modes use bounded
  thumbnails to avoid decoding full-size images.
- Evolve `RevealableImage` into a statelessly configurable molecule: caller
  supplies a reset key, initial reveal state, long-click selection, and optional
  fullscreen action. The component owns only per-item reveal state.
- Reuse the existing versioned `ParamShare` JSON contract with only
  `PROMPT`/`NEGATIVE_PROMPT` selected. Paste interception recognizes a complete
  payload and updates both fields atomically; normal text paste stays unchanged.
- Compact visual changes stay in theme tokens and reusable screen components;
  do not scale `LocalDensity` or shrink touch targets below accessibility
  minimums.

## Progress

- [x] Inspect the current asset grid, conceal component, parameter dialog,
  clipboard parameter contract, and theme tokens.
- [x] Implement layout mode persistence and waterfall/grid/list renderers.
- [x] Implement global/individual reveal behavior and fullscreen affordances.
- [x] Add prompt-pair copy and atomic paste handling across prompt entry points.
- [x] Apply compact theme and high-traffic screen spacing.
- [x] Capture a fresh failing request from the other on-device app and resolve
  the exact compatibility gap.
- [x] Run ktlint, detekt, 123 JVM tests, lint, and APK/test-APK builds.
- [x] Run physical-device visual/interaction QA after ADB reconnects.

## Implementation Notes

- The app now accepts bounded HTTP/1.1 chunked request bodies in addition to
  fixed `Content-Length` bodies. This removes a common Android-client
  compatibility failure while retaining the 20 MiB request and 40 MiB
  in-flight budgets.
- Primary model-list and model-run app bars now use compact pinned top bars;
  typography and shape tokens were reduced globally without changing density
  or accessibility touch targets.
- Manual ZIP imports were folded into transactional publication and gained
  path, duplicate-entry, entry-count, and expanded-byte defenses during the
  same review.
- OnePlus can freeze an optimized foreground service even while its
  notification remains visible. The API settings now link to the vendor
  background-control page, explain the required "Allow all background
  activity" choice, and hold a lifecycle-paired partial wake lock.
- Tavo 0.92.1 successfully validates the loopback endpoint through
  `GET /v1/models`. An existing chat continued using its previously selected
  image provider; a new chat created after making Vision Dream the default
  issued `POST /v1/images/generations` to the local service and received 200.

## Verification

- Switching among waterfall, list, and three-column grid preserves the same
  paging data and stable item selection.
- Global show/hide resets every visible item; center tap toggles one item; the
  fullscreen icon opens an unconcealed overlay without also hiding the card.
- List rows and detail dialogs show model and both prompt fields. A copied pair
  pasted into generation/chat/prompt-manager positive input fills both fields,
  while arbitrary pasted text remains ordinary prompt text.
- Typography and containers are visibly denser on the PJZ110 while icon
  buttons retain at least 48dp touch semantics.
- Gateway logs prove whether a caller reached the server and record the HTTP
  status without exposing secrets or prompt content.
- On the PJZ110, all seven explicit instrumentation tests pass. The API stays
  responsive while Tavo is foreground, automatically loads
  `AsianMix-qnn2.28-8gen2`, stores the remote result as an asset, and exposes
  its model and prompt metadata.
- `response_format=url` returns a raw 512x512 PNG (`Content-Type: image/png`)
  with HTTP 200. The model-list `unload` action releases the loaded model
  without changing its usage-ranked position; the next request reloads it.
