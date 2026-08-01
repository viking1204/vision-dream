#!/usr/bin/env bash
# Non-destructive Android install helper: snapshot first, then adb install -r.
set -euo pipefail

PACKAGE="io.github.ddq.visiondream"
SERIAL=""
APK="app/build/outputs/apk/debug/VisionDream_armv8a_1.0.apk"
BACKUP_ROOT="${VISION_DREAM_DEVICE_BACKUP_DIR:-$HOME/.vision-dream-device-backups}"

usage() {
  cat <<USAGE
用法：
  $0 [--serial SERIAL] [--apk APK] [--snapshot-only]

行为：
  1. 将 APP 的 databases、shared_prefs、files/history 备份到本机；
  2. 仅执行 adb install -r -d，不执行 uninstall 或 connected Android tests；
  3. 输出 manifest.json 路径；它是运行破坏性仪器测试时的 Gradle 凭据。
USAGE
}

SNAPSHOT_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --snapshot-only) SNAPSHOT_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

"${ADB[@]}" get-state >/dev/null
if [[ "$SNAPSHOT_ONLY" -eq 0 && ! -f "$APK" ]]; then
  echo "APK 不存在：$APK" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
snapshot_dir="$BACKUP_ROOT/$timestamp"
umask 077
mkdir -p "$snapshot_dir"

package_present=0
if "${ADB[@]}" shell pm path "$PACKAGE" 2>/dev/null | grep -q '^package:'; then
  package_present=1
  for file in databases/local_dream.db databases/local_dream.db-wal databases/local_dream.db-shm; do
    name="${file//\//_}"
    if "${ADB[@]}" shell "run-as $PACKAGE test -f $file" 2>/dev/null; then
      "${ADB[@]}" exec-out run-as "$PACKAGE" cat "$file" > "$snapshot_dir/$name"
    fi
  done

  # Preserve private assets and preferences as independent archives; tar's
  # exit code is checked so an empty/partial archive is never called a backup.
  for directory in files/history shared_prefs; do
    archive="$snapshot_dir/${directory//\//_}.tar"
    if "${ADB[@]}" shell "run-as $PACKAGE test -d $directory" 2>/dev/null; then
      "${ADB[@]}" exec-out run-as "$PACKAGE" tar -C . -cf - "$directory" > "$archive"
      tar -tf "$archive" >/dev/null
    fi
  done
fi

python3 - "$snapshot_dir/manifest.json" "$PACKAGE" "$SERIAL" "$package_present" <<'PY'
import hashlib
import json
import pathlib
import sys
from datetime import datetime, timezone

manifest = pathlib.Path(sys.argv[1])
target = manifest.parent
files = []
for path in sorted(target.iterdir()):
    if path.name == manifest.name or not path.is_file():
        continue
    files.append({
        "name": path.name,
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    })
manifest.write_text(json.dumps({
    "createdAt": datetime.now(timezone.utc).isoformat(),
    "package": sys.argv[2],
    "serial": sys.argv[3] or None,
    "packagePresentBeforeSnapshot": sys.argv[4] == "1",
    "files": files,
}, ensure_ascii=False, indent=2) + "\n")
PY

echo "快照完成：$snapshot_dir/manifest.json"
if [[ "$SNAPSHOT_ONLY" -eq 1 ]]; then
  exit 0
fi

"${ADB[@]}" install -r -d "$APK"
"${ADB[@]}" shell pm path "$PACKAGE" >/dev/null
echo "覆盖安装完成；未执行卸载或 connected Android tests。"
