# Prevent OEM background freezing of the image API

## Goal

Keep the user-enabled OpenAI-compatible server responsive while another app on
the same Android device is in the foreground.

## Evidence

- On OnePlus PJZ110, Tavo and Vision Dream retain an `ESTABLISHED` loopback
  socket while API requests stop receiving responses.
- Vision Dream's accept and pre-header threads enter `do_freezer_trap` within
  seconds of switching to Tavo.
- Bringing Vision Dream to the foreground immediately restores `/health`.
- Android reports the process as an FGS and `isFrozen=false`; the app is already
  set to "完全允许后台行为".
- The service's partial wake lock is present only in ColorOS's restored/proxied
  wake-lock list, so neither the standard Doze allowlist nor a wake lock prevents
  the OEM freeze.

## Plan

- [x] Reproduce the background freeze without restarting either app.
- [x] Rule out the HTTP queue, response writer, standard app freezer, battery
  optimization, and missing wake lock.
- [x] Test the API as a `specialUse` foreground service, which accurately
  describes a user-started local inference server not covered by another FGS
  category.
- [x] Build and install without clearing app data.
- [x] Verify the process is still frozen and `/health` still times out after
  switching to Tavo; roll back the unproven manifest change.
- [x] Verify that ColorOS's package-level `no_frozen` list does not exempt this
  UID from the observed freezer.
- [x] Run a minimal isolated-process experiment; both the UI and `:gateway`
  foreground-service processes are frozen together because the policy is
  UID-scoped. Roll back process isolation.
- [x] Verify that locking Vision Dream in the ColorOS recent-apps screen
  prevents the UID freeze.
- [x] Install the final single-process build and verify three health probes plus
  two consecutive 1024x1024 generation requests while another app is in the
  foreground.

## Rollback

`specialUse` did not prevent the OEM freeze, so the previous manifest type was
restored. Do not retain an unproven foreground-service declaration.

## Result

ColorOS recent-task locking is the only tested device control that prevents the
UID from entering `do_freezer_trap`. With the final single-process build locked,
the gateway stayed runnable for at least 40 seconds in the background, three
health probes returned immediately, and two binary PNG generations completed
in 27.91 and 25.82 seconds. Standard battery allowlisting, ColorOS's
`no_frozen` setting, a partial wake lock, `specialUse`, and a separate service
process did not prevent this device's UID-scoped freezer.

## Reopened after Tavo URL verification

A later real Tavo request reproduced `do_freezer_trap` even though the recent
task still showed as locked. Bringing Vision Dream to the foreground let the
same request finish, return URL JSON, and serve the temporary PNG successfully.
The lock-screen workaround is therefore not reliable enough.

- [x] Inspect the PJZ110 vendor framework and identify
  `OplusHansFreezeManager.keepBackgroundRunning`.
- [x] Test a type-safe, lifecycle-paired compatibility guard using compile-only
  vendor signatures. The device rejected it with error code 1 because
  `com.oplus.permission.safe.POWER` is `signature|privileged`; roll it back.
- [x] Run two consecutive Tavo generations while Vision Dream remains in the
  background. Both defaulted to URL JSON, completed 28 inference steps, returned
  POST 200, and served Tavo's follow-up image GET with 200.
