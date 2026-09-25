#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""构建 Komikku 扩展仓库的产物目录（repo 分支内容）。

用法：
    python tools/make_repo.py                 # 用默认配置
    python tools/make_repo.py --config x.json # 用自定义配置

产物结构（App 端按这个硬拼 URL，见 NetworkLegacyExtension.kt:32）：
    <repoBase>/repo.json       ← 仓库元信息 + 签名指纹（自动信任用）
    <repoBase>/index.min.json  ← 扩展清单（JSON 数组）
    <repoBase>/apk/<apk 字段>   ← apkUrl = "$storeBaseUrl/apk/$apk"
    <repoBase>/icon/<pkg>.png  ← iconUrl = "$storeBaseUrl/icon/$pkg.png"

字段依据（逐行核对自本机源码）：
  - NetworkLegacyExtension      : name/pkg/apk/lang/code/version/nsfw/sources
  - NetworkLegacyExtensionRepo  : meta{name,shortName,website,signingKeyFingerprint}
  - ExtensionStoreService:38-41 : 填 .../index.min.json 会自动改拉 .../repo.json
  - ExtensionPlugin.kt:328-333  : source id 算法（computeSourceId）
"""

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "repo-dist"          # 产物目录（repo 分支的内容）

# ── 仓库元信息 ────────────────────────────────────────────────
# ★ 签名指纹：填自己的 ⇒ 该指纹下所有扩展永久免手点信任
#   debug keystore 实测值（apksigner verify --print-certs）
#   若改用 release 签名，务必替换成对应的指纹
SIGNING_KEY = "c002a9a7b3562f5d897df90e4632799cfc4c18f7fd0f542a3aa3a251488d5848"

REPO_META = {
    "name": "Komikku 扩展源",
    "shortName": "komikku-ext",
    "website": "https://github.com/az1001010086-ux/komikku-extensions",
}

# ── 扩展清单：每加一个扩展，在这里加一条 ──────────────────────
# apk_path 相对于仓库根；构建后它会出现在 extensions/<lang>/<mod>/build/outputs/apk/release/
EXTENSIONS = [
    {
        "pkg": "eu.kanade.tachiyomi.extension.zh.kxmanhua",
        "source_name": "开心看漫画",          # 图源页主名
        "lang": "zh",
        "code": 106001,                     # ★ 必须与 APK 的 versionCode 一致
        "version": "1.6.1",                 # ★ 必须与 APK 的 versionName 一致
        "nsfw": 1,                          # 站点为成人向 ⇒ 标记 NSFW（0=SAFE 1=NSFW）
        "base_url": "https://kxmanhua.com",
        "apk": "extensions/zh/kxmanhua/build/outputs/apk/release/"
               "tachiyomi-zh.kxmanhua-v1.6.1.apk",
        "icon": "extensions/zh/kxmanhua/res/mipmap-xxxhdpi/ic_launcher.png",
    },
    {
        "pkg": "eu.kanade.tachiyomi.extension.zh.kmh",
        "source_name": "K漫画",              # 图源页主名
        "lang": "zh",
        "code": 106001,                     # ★ 必须与 APK 的 versionCode 一致
        "version": "1.6.1",                 # ★ 必须与 APK 的 versionName 一致
        "nsfw": 1,                          # 站点为成人向 ⇒ 标记 NSFW（0=SAFE 1=NSFW）
        "base_url": "https://kmh001.com",
        "apk": "extensions/zh/kmh/build/outputs/apk/release/"
               "tachiyomi-zh.kmh-v1.6.1.apk",
        "icon": "extensions/zh/kmh/res/mipmap-xxxhdpi/ic_launcher.png",
    },
    {
        "pkg": "eu.kanade.tachiyomi.extension.zh.tutorialdemo",
        "source_name": "教程示例",          # 图源页主名（可中文）
        "lang": "zh",
        "code": 106001,                     # ★ 必须与 APK 的 versionCode 一致
        "version": "1.6.1",                 # ★ 必须与 APK 的 versionName 一致
        "nsfw": 0,
        "base_url": "https://example.com",
        "apk": "extensions/zh/tutorialdemo/build/outputs/apk/release/"
               "tachiyomi-zh.tutorialdemo-v1.6.1.apk",
        "icon": "extensions/zh/tutorialdemo/res/mipmap-xxxhdpi/ic_launcher.png",
    },
]


def compute_source_id(name: str, lang: str, version_id: int = 1) -> int:
    """复刻 ExtensionPlugin.kt:328-333 的 computeSourceId()。"""
    key = f"{name.lower()}/{lang}/{version_id}"
    b = hashlib.md5(key.encode("utf-8")).digest()
    v = 0
    for i in range(8):
        v = ((v << 8) | (b[i] & 0xFF)) & 0xFFFFFFFFFFFFFFFF
    return v & 0x7FFFFFFFFFFFFFFF


def read_apk_meta(apk: Path) -> dict:
    """用 aapt2 读 APK 的 package / versionCode / versionName。失败返回 {}。"""
    aapt2 = shutil.which("aapt2") or shutil.which("aapt")
    if not aapt2:
        return {}
    try:
        out = subprocess.run(
            [aapt2, "dump", "badging", str(apk)],
            capture_output=True, text=True, timeout=30,
        ).stdout
    except Exception:
        return {}
    meta = {}
    first = out.splitlines()[0] if out else ""
    # package: name='xxx' versionCode='106001' versionName='1.6.1'
    for token in first.split():
        if token.startswith("name="):
            meta["pkg"] = token.split("=", 1)[1].strip("'")
        elif token.startswith("versionCode="):
            meta["code"] = int(token.split("=", 1)[1].strip("'"))
        elif token.startswith("versionName="):
            meta["version"] = token.split("=", 1)[1].strip("'")
    return meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify-apk", action="store_true",
                    help="用 aapt2 校验 index 里的 code/version 与 APK 实际值一致")
    args = ap.parse_args()

    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "apk").mkdir(parents=True)
    (OUT / "icon").mkdir(parents=True)

    index = []
    problems = []

    for e in EXTENSIONS:
        apk_src = ROOT / e["apk"]
        icon_src = ROOT / e["icon"]
        if not apk_src.is_file():
            problems.append(f"APK 不存在：{apk_src}")
            continue
        if not icon_src.is_file():
            problems.append(f"图标不存在：{icon_src}")
            continue

        # 可选：交叉校验 APK 元数据与 index 声明是否一致
        if args.verify_apk:
            actual = read_apk_meta(apk_src)
            if actual:
                if actual.get("code") != e["code"]:
                    problems.append(
                        f"{e['pkg']}: code 不符 index={e['code']} apk={actual.get('code')}")
                if actual.get("version") != e["version"]:
                    problems.append(
                        f"{e['pkg']}: version 不符 index={e['version']} apk={actual.get('version')}")
                if actual.get("pkg") != e["pkg"]:
                    problems.append(
                        f"{e['pkg']}: pkg 不符 apk={actual.get('pkg')}")

        apk_name = apk_src.name
        shutil.copy2(apk_src, OUT / "apk" / apk_name)
        shutil.copy2(icon_src, OUT / "icon" / f"{e['pkg']}.png")

        index.append({
            # App 端 substringAfter("Tachiyomi: ") 后才是显示名
            "name": f"Tachiyomi: {e['source_name']}",
            "pkg": e["pkg"],
            "apk": apk_name,
            "lang": e["lang"],
            "code": e["code"],
            "version": e["version"],
            "nsfw": e["nsfw"],
            "sources": [{
                "id": compute_source_id(e["source_name"], e["lang"]),
                "lang": e["lang"],
                "name": e["source_name"],
                "baseUrl": e["base_url"],
            }],
        })

    if problems:
        print("!! 存在问题：", file=sys.stderr)
        for p in problems:
            print(f"   - {p}", file=sys.stderr)
        return 1

    # 用 write_bytes 显式写 LF —— 若用 write_text，Windows 上会落成 CRLF，
    # 与线上（git 归一化成 LF）字节不一致，徒增排查成本。
    (OUT / "index.min.json").write_bytes(
        json.dumps(index, ensure_ascii=False, indent=2).encode("utf-8"))

    # ⚠️ index_v2 若填值，App 会当完整 URL 再 fetch（ExtensionStoreService.kt:58-59），
    #    填相对路径 ⇒ OkHttp 报 "no scheme was found"。legacy 仓库必须填 null。
    # ⚠️ 字段无默认值，即使 explicitNulls=false 也必须显式写出这个 key。
    (OUT / "repo.json").write_bytes(
        json.dumps({"index_v2": None, "meta": {
            **REPO_META, "signingKeyFingerprint": SIGNING_KEY,
        }}, ensure_ascii=False, indent=2).encode("utf-8"))

    print(f"产物已生成：{OUT}")
    for p in sorted(OUT.rglob("*")):
        if p.is_file():
            print(f"  {p.relative_to(OUT)}  ({p.stat().st_size} B)")
    print(f"\n共 {len(index)} 个扩展")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
