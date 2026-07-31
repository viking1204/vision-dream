#!/usr/bin/env python3
"""Safely import Vision Dream QNN model archives from an Android Download folder."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


DOWNLOAD_ROOT = "/sdcard/Download"
MODEL_ROOT = "/sdcard/VisionDream/models"
STAGING_ROOT = "/sdcard/VisionDream/.staging"
ARCHIVE_SUFFIX = "_qnn2.28_8gen3.zip"

SDXL_REQUIRED = {
    "SDXL",
    "tokenizer.json",
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight",
    "pos_emb.bin",
    "pos_emb_2.bin",
    "token_emb.bin",
    "token_emb_2.bin",
    "unet.bin",
    "vae_decoder.bin",
    "vae_encoder.bin",
}
ANIMA_REQUIRED = {
    "ANIMA",
    "tokenizer.json",
    "tokenizer_t5.json",
    "clip.bin",
    "token_emb.bin",
    "unet_part1.bin",
    "unet_part2.bin",
    "vae_decoder.bin",
    "vae_encoder.bin",
}


@dataclass(frozen=True)
class ModelInfo:
    display_name: str
    description: str
    content_rating: str = "unknown"


MODEL_INFO = {
    "anikawa_v4": ModelInfo("Anikawa V4", "风格：清新日系二次元、细腻角色插画；适合：角色立绘、轻小说插图、校园与日常场景。"),
    "anima_base_v1_turbo": ModelInfo("Anima Base V1 Turbo", "风格：通用 Anima 快速基底、画面中性；适合：快速参数探索、通用角色和后续风格测试。"),
    "animagine_v4": ModelInfo("Animagine V4", "风格：高完成度日系动漫、细节丰富；适合：角色立绘、复杂构图、海报和精致插画。"),
    "animayume_v1_turbo": ModelInfo("AnimaYume V1 Turbo", "风格：梦幻柔和的二次元插画；适合：幻想角色、柔光场景、快速剧情配图。"),
    "chenkinnoob_v0.5": ModelInfo("Chenkin Noob V0.5", "风格：通用二次元、角色细节均衡；适合：人物立绘、动作场景和常规动漫插图。"),
    "chenkinnoob_v0.5_dmd2": ModelInfo("Chenkin Noob V0.5 DMD2", "风格：通用二次元快速版；适合：聊天 RP 配图、连续剧情图和低步数快速预览。"),
    "counterfeit_v2.5": ModelInfo("Counterfeit V2.5", "风格：经典日系动漫、赛璐璐与插画融合；适合：角色头像、立绘和同人场景。"),
    "cyberrealistic_v3_turbo": ModelInfo("CyberRealistic V3 Turbo", "风格：写实摄影、电影感光影；适合：真人肖像、现代生活、写实 RP 角色和快速预览。"),
    "dreamshaper": ModelInfo("DreamShaper", "风格：半写实、幻想与概念艺术融合；适合：奇幻人物、电影概念图、环境和叙事场景。"),
    "epic_realism": ModelInfo("Epic Realism", "风格：高对比写实摄影、自然皮肤；适合：人像、生活照、时尚和电影剧照。"),
    "furrytoonmix_v3": ModelInfo("Furry Toon Mix V3", "风格：兽人、卡通和动漫融合；适合：Furry 角色、表情立绘、轻松剧情和双人互动。"),
    "gonzalomo_v7": ModelInfo("Gonzalomo V7", "风格：高质感写实摄影；适合：人物肖像、时尚、棚拍和电影感场景。"),
    "gonzalomo_v7_dmd2": ModelInfo("Gonzalomo V7 DMD2", "风格：写实摄影快速版；适合：写实 RP 头像、连续剧情配图和低步数预览。"),
    "illustrij_v21": ModelInfo("IllustriJ V21", "风格：细腻二次元插画、丰富材质与光影；适合：角色海报、幻想场景和精修立绘。"),
    "illustrious_v16": ModelInfo("Illustrious V1.6", "风格：通用高质量二次元；适合：人物立绘、动作、多人互动和动漫场景。"),
    "illustrious_v16_dmd2": ModelInfo("Illustrious V1.6 DMD2", "风格：通用二次元快速版；适合：聊天 RP、批量角色草图和低步数连续生成。"),
    "illustrious_v17_dmd2": ModelInfo("Illustrious V1.7 DMD2", "风格：新版通用二次元快速版；适合：连续剧情图、角色变化和低步数预览。"),
    "juggernaut": ModelInfo("Juggernaut XL", "风格：电影级写实、强质感与景深；适合：人物摄影、影视概念、建筑和环境画面。"),
    "juggernaut_dmd2": ModelInfo("Juggernaut XL DMD2", "风格：电影写实快速版；适合：写实 RP、分镜预览和连续场景生成。"),
    "lemonsugarmix_v3": ModelInfo("Lemon Sugar Mix V3", "风格：甜美可爱、明亮柔和的二次元；适合：萌系角色、日常服饰、头像和轻松场景。"),
    "miaomiao_harem_v2": ModelInfo("Miaomiao Harem V2", "风格：二次元多人角色与关系场景；适合：群像、双人互动、剧情式 RP 和室内场景。"),
    "miaomiao_realskin_v1.4": ModelInfo("Miaomiao RealSkin V1.4", "风格：写实皮肤、亚洲人像与写真感；适合：人物特写、Cosplay、时尚和室内人像。"),
    "miaomiao_v1.4_turbo": ModelInfo("Miaomiao V1.4 Turbo", "风格：人物向半写实快速版；适合：聊天 RP 头像、连续人物图和快速构图。"),
    "noobai_vpred": ModelInfo("NoobAI V-Pred", "风格：细节丰富的二次元 V-Pred 路线；适合：高质量角色、复杂服饰、动作和插画。"),
    "nova_orange_rex_v1": ModelInfo("Nova Orange Rex V1", "风格：鲜明高饱和的二次元与幻想插画；适合：强光影角色、奇幻服饰和海报。"),
    "novaanime_v18": ModelInfo("Nova Anime V1.8", "风格：通用动漫、角色表现稳定；适合：立绘、头像、动作和剧情插图。"),
    "novaanime_v19": ModelInfo("Nova Anime V1.9", "风格：高质量通用动漫、细节和构图均衡；适合：角色海报、动作和复杂剧情场景。"),
    "novaanime_v19_dmd2": ModelInfo("Nova Anime V1.9 DMD2", "风格：通用动漫快速版；适合：聊天 RP、连续剧情和低步数批量预览。"),
    "novaanime_v2.5_turbo": ModelInfo("Nova Anime V2.5 Turbo", "风格：新版动漫快速路线；适合：快速角色设计、聊天配图和批量构图。"),
    "novaanime_v3_turbo": ModelInfo("Nova Anime V3 Turbo", "风格：新版高效率动漫插画；适合：连续 RP、生图聊天、角色和动作场景。"),
    "novafurry_v18": ModelInfo("Nova Furry V1.8", "风格：精细兽人、动漫与半写实融合；适合：Furry 角色、服装设计、互动和剧情插画。"),
    "perfect_deliberate_v9": ModelInfo("Perfect Deliberate V9", "风格：可控半写实、插画与摄影融合；适合：人物、概念设计、产品和叙事场景。"),
    "perfection_realistic_v8": ModelInfo("Perfection Realistic V8", "风格：自然写实摄影、细腻皮肤和光影；适合：肖像、生活照、时尚和室外场景。"),
    "ponydiffusion_v6xl": ModelInfo("Pony Diffusion V6 XL", "风格：Pony 系动漫、西式卡通与角色表现；适合：多角色互动、Furry、同人和剧情画面。"),
    "pppanimix_v20": ModelInfo("PPP AniMix V20", "风格：综合型日系动漫、色彩鲜明；适合：人物立绘、头像、服饰和常规动漫场景。"),
    "prefect_illustrious_v8": ModelInfo("Prefect Illustrious V8", "风格：精修 Illustrious 二次元、细节华丽；适合：角色海报、复杂服装和幻想场景。"),
    "raehoshi_v10": ModelInfo("Raehoshi V10", "风格：柔和精致的日系角色插画；适合：人物立绘、情绪场景、室内和日常剧情。"),
    "raehoshi_v10_dmd2": ModelInfo("Raehoshi V10 DMD2", "风格：柔和日系快速版；适合：聊天 RP、表情变化和低步数连续配图。"),
    "realvis_xl_v5": ModelInfo("RealVis XL V5", "风格：自然写实摄影、电影感色彩；适合：真人肖像、旅行、生活和商业摄影。"),
    "realvis_xl_v5_dmd2": ModelInfo("RealVis XL V5 DMD2", "风格：自然写实快速版；适合：写实聊天角色、分镜和低步数连续生成。"),
    "reed_xxx_v14": ModelInfo("Reed XXX V14", "风格：成人向写实与半写实人物；适合：仅限成人角色及成人剧情画面。", "nsfw"),
    "rin_animepopcute_v4": ModelInfo("Rin Anime Pop Cute V4", "风格：流行萌系、明快可爱的二次元；适合：头像、偶像、日常服饰和轻松场景。"),
    "rin_animepopcute_v4_dmd2": ModelInfo("Rin Anime Pop Cute V4 DMD2", "风格：流行萌系快速版；适合：聊天头像、表情图和低步数连续生成。"),
    "rin_featherfall_v4": ModelInfo("Rin Featherfall V4", "风格：轻盈梦幻、柔光幻想二次元；适合：精灵、羽毛、童话场景和氛围插画。"),
    "rin_flanime_v1_turbo": ModelInfo("Rin Flanime V1 Turbo", "风格：鲜明平涂动漫快速版；适合：动画分镜、角色草图和聊天剧情配图。"),
    "rin_flanime_v4": ModelInfo("Rin Flanime V4", "风格：清晰线稿与鲜明平涂二次元；适合：动画风角色、动作、校园和日常场景。"),
    "rin_flanime_v4_dmd2": ModelInfo("Rin Flanime V4 DMD2", "风格：平涂动漫快速版；适合：连续分镜、动作预览和低步数聊天配图。"),
    "sam_anima_realistic_v2.3_turbo": ModelInfo("SAM Anima Realistic V2.3 Turbo", "风格：Anima 写实人物快速路线；适合：真人感角色、时尚、室内外人像和快速预览。"),
    "sdxl_base": ModelInfo("SDXL Base", "风格：中性通用 SDXL 基底；适合：参数基准、提示词测试、通用人物和场景原型。"),
    "wai_anima_v1_turbo": ModelInfo("WAI Anima V1 Turbo", "风格：高效率二次元 Anima；适合：动漫角色、连续 RP、快速构图和批量预览。"),
}


class ImportFailure(RuntimeError):
    pass


def adb(serial: str, *args: str, timeout: int = 900, capture: bool = True) -> str:
    result = subprocess.run(
        ["adb", "-s", serial, *args],
        check=False,
        text=True,
        capture_output=capture,
        timeout=timeout,
    )
    if result.returncode != 0:
        message = (result.stderr or result.stdout or "adb command failed").strip()
        raise ImportFailure(message)
    return result.stdout if capture else ""


def shell(serial: str, command: str, timeout: int = 900) -> str:
    return adb(serial, "shell", command, timeout=timeout)


def q(value: str) -> str:
    return shlex.quote(value)


def list_archives(serial: str) -> list[str]:
    output = shell(
        serial,
        f"find {q(DOWNLOAD_ROOT)} -maxdepth 1 -type f "
        f"-name {q('*' + ARCHIVE_SUFFIX)} -print | sort",
    )
    return [line for line in output.splitlines() if line]


def parse_archive(serial: str, archive: str) -> tuple[str, str, set[str]]:
    listing = shell(serial, f"unzip -l {q(archive)}", timeout=120)
    entries: list[str] = []
    for line in listing.splitlines():
        match = re.match(r"^\s*\d+\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$", line)
        if match:
            entries.append(match.group(1))
    if not entries:
        raise ImportFailure("压缩包没有可读取条目")
    for entry in entries:
        path = PurePosixPath(entry)
        if entry.startswith(("/", "\\")) or "\\" in entry or ".." in path.parts:
            raise ImportFailure(f"压缩包包含不安全路径：{entry}")

    markers = [entry for entry in entries if PurePosixPath(entry).name in {"SDXL", "ANIMA"}]
    if len(markers) != 1:
        raise ImportFailure("压缩包必须包含且仅包含一个 SDXL 或 ANIMA 标记")
    marker = PurePosixPath(markers[0])
    model_type = marker.name
    prefix = str(marker.parent)
    files = {
        str(PurePosixPath(entry).relative_to(marker.parent))
        for entry in entries
        if PurePosixPath(entry).is_relative_to(marker.parent)
    }
    required = SDXL_REQUIRED if model_type == "SDXL" else ANIMA_REQUIRED
    missing = sorted(required - files)
    if missing:
        raise ImportFailure("模型文件不完整：" + ", ".join(missing))
    return model_type, prefix, files


def installed_fingerprints(serial: str) -> dict[str, str]:
    command = f"""
for d in {q(MODEL_ROOT)}/*; do
  [ -d "$d" ] || continue
  if [ -f "$d/unet.bin" ]; then
    h=$(sha256sum "$d/unet.bin"); h=${{h%% *}}
    echo "SDXL:$h|$d"
  elif [ -f "$d/unet_part1.bin" ] && [ -f "$d/unet_part2.bin" ]; then
    h1=$(sha256sum "$d/unet_part1.bin"); h1=${{h1%% *}}
    h2=$(sha256sum "$d/unet_part2.bin"); h2=${{h2%% *}}
    echo "ANIMA:$h1:$h2|$d"
  fi
done
"""
    output = shell(serial, command, timeout=1800)
    values: dict[str, str] = {}
    for line in output.splitlines():
        fingerprint, separator, model_dir = line.partition("|")
        if separator:
            values[fingerprint] = model_dir
    return values


def extracted_fingerprint(serial: str, model_type: str, model_dir: str) -> str:
    if model_type == "SDXL":
        output = shell(serial, f"sha256sum {q(model_dir + '/unet.bin')}", timeout=600)
        return "SDXL:" + output.split()[0]
    output = shell(
        serial,
        f"sha256sum {q(model_dir + '/unet_part1.bin')} {q(model_dir + '/unet_part2.bin')}",
        timeout=900,
    )
    hashes = [line.split()[0] for line in output.splitlines() if line.strip()]
    if len(hashes) != 2:
        raise ImportFailure("无法计算 Anima 模型摘要")
    return "ANIMA:" + ":".join(hashes)


def metadata_for(model_id: str, archive: str) -> dict[str, object]:
    info = MODEL_INFO.get(
        model_id,
        ModelInfo(
            model_id,
            "风格：通用本地生成模型；适合：人物、场景和提示词探索，建议先用少量样本确认具体风格。",
        ),
    )
    metadata: dict[str, object] = {
        "schema_version": 4,
        "content_rating": info.content_rating,
        "display_name": info.display_name,
        "description": info.description,
        "source": {
            "repository_id": f"local-download/{model_id}",
            "artifact_kind": "zip",
        },
    }
    if info.content_rating == "nsfw":
        metadata["rating_source"] = "repository_name"
        metadata["rating_evidence"] = [f"archive:{PurePosixPath(archive).name}"]
    return metadata


def push_metadata(serial: str, model_dir: str, metadata: dict[str, object]) -> None:
    payload = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=True) as temp:
        temp.write(payload)
        temp.flush()
        remote_temp = model_dir + "/.vision-dream-model.json.tmp"
        adb(serial, "push", temp.name, remote_temp, timeout=120)
        shell(
            serial,
            f"mv {q(remote_temp)} {q(model_dir + '/.vision-dream-model.json')}",
            timeout=60,
        )


def safe_cleanup_staging(serial: str, staging: str) -> None:
    expected_prefix = STAGING_ROOT + "/manual-import-"
    if not staging.startswith(expected_prefix):
        raise ImportFailure("拒绝清理非任务暂存目录")
    shell(serial, f"rm -rf {q(staging)}", timeout=300)


def import_archive(
    serial: str,
    archive: str,
    fingerprints: dict[str, str],
) -> tuple[str, str]:
    archive_name = PurePosixPath(archive).name
    if not archive_name.endswith(ARCHIVE_SUFFIX):
        raise ImportFailure("不是 Vision Dream QNN 模型包")
    model_id = archive_name[: -len(ARCHIVE_SUFFIX)]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", model_id):
        raise ImportFailure("模型 ID 包含不安全字符")

    target = f"{MODEL_ROOT}/{model_id}"
    target_exists = shell(serial, f"if [ -e {q(target)} ]; then echo yes; fi").strip()
    if target_exists:
        return "SKIPPED", f"目标目录已存在：{target}"

    model_type, prefix, _ = parse_archive(serial, archive)
    stamp = f"{int(time.time())}-{hashlib.sha256(archive.encode()).hexdigest()[:8]}"
    staging = f"{STAGING_ROOT}/manual-import-{model_id}-{stamp}"
    extracted = f"{staging}/{prefix}"
    try:
        shell(serial, f"mkdir -p {q(staging)}", timeout=60)
        shell(serial, f"unzip -q {q(archive)} -d {q(staging)}", timeout=3600)
        shell(serial, f"test -d {q(extracted)}", timeout=60)
        fingerprint = extracted_fingerprint(serial, model_type, extracted)
        duplicate = fingerprints.get(fingerprint)
        if duplicate:
            return "SKIPPED", f"内容与已安装模型重复：{duplicate}"

        push_metadata(serial, extracted, metadata_for(model_id, archive))
        shell(serial, f"test ! -e {q(target)} && mv {q(extracted)} {q(target)}", timeout=300)
        marker = "SDXL" if model_type == "SDXL" else "ANIMA"
        shell(
            serial,
            f"test -f {q(target + '/' + marker)} && "
            f"test -s {q(target + '/.vision-dream-model.json')}",
            timeout=60,
        )
        fingerprints[fingerprint] = target
        shell(serial, f"rm -f {q(archive)} && test ! -e {q(archive)}", timeout=120)
        return "IMPORTED", f"{target}；已删除 {archive}"
    finally:
        safe_cleanup_staging(serial, staging)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()

    model = shell(args.serial, "getprop ro.product.model").strip()
    soc = shell(args.serial, "getprop ro.soc.model").strip()
    if (model, soc) != ("PJZ110", "SM8750"):
        raise SystemExit(f"拒绝操作非目标设备：{model}/{soc}")

    archives = list_archives(args.serial)
    if args.limit is not None:
        archives = archives[: args.limit]
    print(json.dumps({"event": "inventory", "archives": len(archives)}, ensure_ascii=False), flush=True)

    fingerprints = installed_fingerprints(args.serial)
    print(
        json.dumps(
            {"event": "installed_fingerprints", "count": len(fingerprints)},
            ensure_ascii=False,
        ),
        flush=True,
    )

    summary = {"IMPORTED": 0, "SKIPPED": 0, "FAILED": 0}
    for index, archive in enumerate(archives, start=1):
        print(
            json.dumps(
                {"event": "started", "index": index, "total": len(archives), "archive": archive},
                ensure_ascii=False,
            ),
            flush=True,
        )
        try:
            status, detail = import_archive(args.serial, archive, fingerprints)
        except Exception as error:  # Continue with the next independent archive.
            status, detail = "FAILED", str(error)
        summary[status] += 1
        print(
            json.dumps(
                {
                    "event": "finished",
                    "index": index,
                    "total": len(archives),
                    "archive": archive,
                    "status": status,
                    "detail": detail,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    print(json.dumps({"event": "summary", **summary}, ensure_ascii=False), flush=True)
    return 0 if summary["FAILED"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
