# Vision Dream Studio Shell Redesign

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`,
`Decision Log`, and `Outcomes & Retrospective` current while implementation
continues.

## Purpose / Big Picture

Vision Dream currently opens on model administration and exposes product
features through screen-local toolbars and overflow menus. The redesign makes
creation the product center: users land on a workbench, start generation from
an explicit studio, review protected assets, manage ranked models, and observe
the background API from five stable top-level destinations.

The target is defined in the editable Figma file
`Vision Dream — Studio Redesign` (`6p5e8HZouURMxJ7jeC3kbS`). The implementation
must preserve existing model, generation, history, prompt, download, and API
behavior while replacing the navigation and visual hierarchy.

## Progress

- [x] Audit current navigation and the five primary feature screens.
- [x] Create and render-check Figma designs for Workbench, Create, Assets,
  Models, Services, plus a token overview.
- [x] Add a creation-first navigation model and shared bottom navigation.
- [x] Add semantic studio color, shape, and reusable surface tokens.
- [x] Implement the Workbench with live model/API/asset state.
- [x] Convert Create, Assets, Models, and Services into top-level destinations.
- [x] Remove product navigation from the model overflow menu while preserving
  technical management actions.
- [x] Preserve Material navigation semantics and add a navigation-structure test.
- [x] Install and visually verify on an Android runtime.

## Surprises & Discoveries

- The app already uses Material 3 Expressive motion and compact typography, so
  the core problem is information architecture rather than another theme tweak.
- Every primary screen owns a `Scaffold`. A second root `Scaffold` would create
  nested insets, so the shared navigation bar must be provided as a screen slot
  until screens are migrated to a single shell template.
- Model administration is a monolithic screen. This pass keeps its domain
  behavior intact and changes its top-level composition before extracting
  management dialogs in a later focused refactor.

## Decision Log

- Use five top-level destinations: Workbench, Create, Assets, Models, Services.
  Prompt Manager, Upscale, model execution, and storage access remain nested.
- Keep generated imagery concealed by default. Workbench previews never bypass
  the existing reveal interaction.
- Reuse existing state owners (`BackendService`, `OpenAiApiService`,
  `HistoryManager`, and `InstalledModelCatalog`) rather than adding a parallel
  UI state layer during the navigation rewrite.
- Use a dark creative-studio palette with lavender primary, cyan connectivity,
  coral destructive/NSFW accents, and green running/loaded states.
- Add `Vision` as the new-install preset and disable dynamic color by default;
  explicitly persisted user choices remain authoritative.
- A successful connection from the top-level Services screen navigates to
  Models instead of popping to Workbench, so the newly available remote catalog
  is immediately visible.

## Implementation Plan

Create `ui/design/VisionStudio.kt` for shared spacing, section labels, status
pills, cards, and the bottom navigation. Add `Screen.Workbench` and a
top-level-destination model in `navigation/Navigation.kt`. Change
`MainActivity.AppContent` to start at Workbench and use single-top,
save/restore-state navigation for the five tabs.

Implement `StudioHomeScreen.kt` as a live dashboard. Add optional `topLevel`
and `bottomBar` parameters to the four existing primary screens so they drop
back arrows at the root while retaining their nested-screen behavior.

Apply the Figma hierarchy to each screen: task-first hero and progressive
settings in Create; compact global privacy/layout controls in Assets; a loaded
model hero and usage-ranked list in Models; and API status, queue metrics, and
diagnostics-first sections in Services.

## Validation

Run:

    ./gradlew app:testDebugUnitTest
    ./gradlew app:lintDebug
    ./gradlew app:assembleDebug

Then install the debug APK, launch each top-level destination, verify back-stack
restoration, 48dp touch targets, content descriptions, default asset concealment,
loaded-model status, and API service controls. Any temporary screenshots or UI
dumps created for validation must be moved to Trash before completion.

## Outcomes & Retrospective

The Figma workbench frame was fetched through structured design context and a
rendered screenshot before implementation. Local unit tests, Lint, and APK
assembly pass, including regression coverage for the top-level route set and
new-install theme defaults.

The final APK was installed on a OnePlus 6 running Android 15. Cold launch,
all five top-level destinations, back-stack behavior, concealed asset defaults,
the three asset layout choices, and queue-capacity editing from 3 to 10 and
back to 3 were verified through the real UI. Starting the foreground API
service exposed both `/v1/models` and `/models`; each returned HTTP 200 with the
same empty installed-model list, and stopping the service closed the forwarded
port. The crash log remained empty. This device has no installed generation
model, so actual model loading and image inference are outside this runtime
pass. All temporary screenshots and UI dumps were moved to Trash.
