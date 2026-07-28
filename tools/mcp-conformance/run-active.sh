#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. The primary command is run-active-suite.mjs, matching
# the delivery plan and requiring explicit device/endpoint/token evidence.
# The official 0.1.11 CLI has no authorization-header option; the Node runner
# injects this one-time grant without storing it in source, npm config, or results.
: "${MCP_CONFORMANCE_TOKEN:?Create a loopback MCP grant in the app and export its token as MCP_CONFORMANCE_TOKEN.}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the OnePlus 13 serial.}"

target_url="${MCP_CONFORMANCE_TARGET_URL:-http://127.0.0.1:3003/mcp}"
node ./run-active-suite.mjs \
  --serial "$ANDROID_SERIAL" \
  --base-url "$target_url" \
  --token "$MCP_CONFORMANCE_TOKEN"
