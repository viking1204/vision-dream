# Modern Visual Redesign

This ExecPlan corrects the earlier compact-theme pass. That pass reduced type,
spacing, and radius tokens, but it did not materially redesign the screen
composition.

## Outcome

- Give creation, asset, model, and service surfaces one coherent visual system.
- Replace large empty regions and stacked full-width controls with clear
  sections, compact action rows, tonal surfaces, and balanced whitespace.
- Preserve every existing interaction, prompt-pair paste path, reveal rule,
  model action, and background-service setting.
- Keep icon-button touch targets at least 48dp and use semantic Material 3
  colors, typography, and shapes.

## Design Decisions

- Treat the generation composer as a persistent studio dock: model and tools
  are a compact header, the negative prompt is secondary, and send is a square
  primary action beside the positive prompt.
- Put empty states inside a bounded tonal canvas instead of vertically centering
  one line in an otherwise blank screen.
- Use a reusable section heading/status treatment across service settings and
  metadata-heavy pages.
- Keep existing theme presets and dynamic color. The redesign changes hierarchy
  and surfaces, not user color preferences.

## Progress

- [x] Inspect current theme tokens and the four primary screens.
- [x] Establish shared Material 3 surface, section-header, and status treatments
  through theme tokens and the existing screen components.
- [x] Redesign the chat generation canvas and composer.
- [x] Redesign the asset toolbar and asset cards.
- [x] Align model-list and background-service visual hierarchy.
- [x] Run formatting, static analysis, tests, lint, and APK builds.
- [x] Install without clearing app data and visually verify on the connected
  PJZ110.

## Verification

- Chat generation no longer opens to an oversized blank body or three stacked
  full-width form rows.
- Asset layout, global reveal, individual reveal, fullscreen, metadata, copy,
  and selection interactions remain available in every layout.
- Loaded-model status, `unload`, API start/stop, queue input, and API key actions
  remain reachable with the same behavior.
- Physical-device screenshots show balanced density in both empty and populated
  states; no clipped controls or inaccessible touch targets are introduced.

## Runtime Notes

- The first physical-device pass exposed an IME regression that static checks
  could not detect: the new studio dock was obscured by the keyboard. Adding
  `imePadding()` to the dock keeps the model picker, both prompt fields, and
  send action visible while typing.
- The final APK was installed with `adb install -r`; app data, installed
  models, and generated assets were preserved.
