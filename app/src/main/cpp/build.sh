#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_main_dir="$(cd "$script_dir/.." && pwd)"
qnn_sdk_root="${QNN_SDK_ROOT:-$HOME/Library/Android/qairt/2.48.40.260702}"
android_sdk_root="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
android_ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-$android_sdk_root/ndk/28.2.13676358}}"
cmake_bin="${CMAKE_BIN:-$android_sdk_root/cmake/3.31.4/bin/cmake}"

required_files=(
  "sdk.yaml"
  "include/QNN/QnnInterface.h"
  "lib/aarch64-android/libQnnHtp.so"
  "lib/aarch64-android/libQnnSystem.so"
  "lib/aarch64-android/libQnnHtpV79Stub.so"
  "lib/hexagon-v79/unsigned/libQnnHtpV79Skel.so"
)
for relative_path in "${required_files[@]}"; do
  if [[ ! -f "$qnn_sdk_root/$relative_path" ]]; then
    echo "Missing QAIRT file: $qnn_sdk_root/$relative_path" >&2
    exit 1
  fi
done
if [[ ! -x "$cmake_bin" ]]; then
  echo "CMake not found: $cmake_bin" >&2
  exit 1
fi
if [[ ! -f "$android_ndk_root/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found: $android_ndk_root" >&2
  exit 1
fi
if ! grep -q '^version: 2\.48\.40$' "$qnn_sdk_root/sdk.yaml" ||
   ! grep -q '^build_id: 260702151143$' "$qnn_sdk_root/sdk.yaml"; then
  echo "QAIRT 2.48.40 build 260702151143 is required" >&2
  exit 1
fi

export QNN_SDK_ROOT="$qnn_sdk_root"
export ANDROID_NDK_ROOT="$android_ndk_root"
if command -v rustup >/dev/null 2>&1; then
  rust_toolchain_bin="$(dirname "$(rustup which cargo)")"
  export PATH="$rust_toolchain_bin:$PATH"
fi

cd "$script_dir"
"$cmake_bin" --preset android-release --fresh -DCMAKE_POLICY_VERSION_MINIMUM=3.5
"$cmake_bin" --build --preset android-release

core_source="$script_dir/build/android/bin/arm64-v8a/libstable_diffusion_core.so"
qnn_source="$script_dir/build/android/qnnlibs"
core_target="$app_main_dir/jniLibs/arm64-v8a/libstable_diffusion_core.so"
qnn_target="$app_main_dir/assets/qnnlibs"
legal_target="$app_main_dir/assets/legal"
manifest_target="$app_main_dir/assets/qairt-runtime-manifest.json"

mkdir -p "$(dirname "$core_target")" "$qnn_target" "$legal_target"
install -m 0755 "$core_source" "$core_target"
for runtime_file in "$qnn_source"/*.so; do
  install -m 0644 "$runtime_file" "$qnn_target/$(basename "$runtime_file")"
done
install -m 0644 "$qnn_sdk_root/NOTICE.txt" "$legal_target/QAIRT_NOTICE.txt"

python3 - "$qnn_sdk_root/sdk.yaml" "$core_target" "$qnn_target" "$manifest_target" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import sys

sdk_yaml, core_path, qnn_dir, manifest_path = map(Path, sys.argv[1:])
sdk_text = sdk_yaml.read_text(encoding="utf-8")

def sdk_value(name: str) -> str:
    match = re.search(rf"^{re.escape(name)}:\s*(.+)$", sdk_text, re.MULTILINE)
    if not match:
        raise SystemExit(f"Missing {name} in {sdk_yaml}")
    return match.group(1).strip()

def artifact(path: Path) -> dict[str, object]:
    return {
        "name": path.name,
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }

manifest = {
    "schemaVersion": 1,
    "qairt": {
        "version": sdk_value("version"),
        "buildId": sdk_value("build_id"),
        "qnnBackendApiVersion": sdk_value("qnn_backend_api_version"),
    },
    "precompiledCore": artifact(core_path),
    "packagedRuntime": [
        artifact(path) for path in sorted(qnn_dir.glob("*.so"))
    ],
    "distribution": {
        "precompiledCore": "tracked-in-repository",
        "qualcommRuntime": "packaged-in-application-only",
        "sdkArchive": "not-distributed",
    },
}
manifest_path.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

echo "Built QAIRT 2.48 core: $core_target"
shasum -a 256 "$core_target"
