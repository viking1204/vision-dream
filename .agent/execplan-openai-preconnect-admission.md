# OpenAI Gateway Preconnect Admission

## Problem

Tavo may keep a TCP connection to port 8809 open without sending HTTP bytes,
then reuse it much later for image generation. A fixed pre-header deadline
closes the server side first; Tavo subsequently writes to the stale socket and
appears to remain in its queue. Extending the deadline only postpones the same
failure, while letting idle sockets occupy all HTTP workers creates a bounded
denial-of-service condition.

## Design

- Accept sockets into a bounded pre-header pool without submitting them to the
  HTTP worker executor.
- Use one lightweight dispatcher to detect the first available byte.
- Start the absolute header-read deadline only when bytes are available, then
  reset the shorter body deadline after complete headers.
- Keep idle preconnections until the peer closes or the pool reaches capacity.
  At capacity, evict the oldest idle connection so a stale client cannot block
  new callers indefinitely.
- Continue to bound parsed-request workers, request/body sizes, and inference
  admission independently.

## Progress

- [x] Capture the failure without restarting either app.
- [x] Prove the gateway listener accepts an independent request while Tavo is
  stuck.
- [x] Observe Tavo's established idle socket outlive the server side and later
  accumulate unsent bytes.
- [x] Implement bounded pre-header admission and lifecycle cleanup.
- [x] Add delayed-send, capacity-eviction, and deadline tests.
- [x] Build, install without clearing app data, and verify Tavo generation
  immediately and after an idle preconnection.

## Verification

- JVM tests cover oldest-idle eviction and first-byte readiness.
- Existing absolute body-deadline tests continue to pass.
- `testDebugUnitTest`, `assembleDebug`, and `git diff --check` pass.
- On the PJZ110, hold a connection idle without assigning a worker, then send
  successfully on that socket; repeat Tavo generation to confirm no immediate
  stale-socket hang.

## Runtime Result

- A loopback connection remained idle in the pre-header pool without creating
  an HTTP worker, then received a complete request and returned a response on
  that same socket.
- After clearing Tavo's already-broken pre-fix socket, two real 1024x1024 Tavo
  generations reached the gateway immediately and returned HTTP 200 in 45 and
  38 seconds.
