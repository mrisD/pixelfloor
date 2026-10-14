# -*- coding: utf-8 -*-
"""从游戏本体 jar 里读方块贴图，算出每种方块的平均色，生成平面像素画调色板。

为什么从 jar 而不是官方资源包仓库：
  1. jar 里就是用户实际在玩的版本，颜色绝对准确；
  2. 官方资源包仓库把可染色方块存成灰阶 + 运行时染色，取平均色会得到灰色，是错的；
  3. jar 里包含全部逐色贴图，不用猜文件名。

调色板构成（各 16 色，共 64 色）：
    羊毛 wool            不透明，便宜
    混凝土 concrete      不透明，饱和度高
    陶瓦 terracotta      不透明，低饱和，专门覆盖"灰扑扑"的中间色
    染色玻璃 stained_glass  半透明，会受下方方块影响，放在浅色底上时颜色才准

刻意不用带釉陶瓦（*_glazed_terracotta）：它的贴图是带箭头花纹的方块，
颜色只占一部分面积，取平均色会严重失真。

注意 Java 版没有 16 色地毯（只有 pale_moss_carpet），所以"用地毯做调色板"
在 Java 版是行不通的。
"""

from __future__ import annotations

import json
import os
import pathlib
import zipfile

from PIL import Image

JARS = [
    r"F:\EFI\PCL 正式版 2.13.1.1\.minecraft\versions\1.21.4-Forge_54.1.18\1.21.4-Forge_54.1.18.jar",
    r"F:\mctool\woolphoto-forge\.gradle\mavenizer\repo\net\minecraft\client-extra\1.21.4-20241203.143248\client-extra-1.21.4-20241203.143248.jar",
]

COLORS = [
    ("white", "白色"), ("orange", "橙色"), ("magenta", "品红色"),
    ("light_blue", "淡蓝色"), ("yellow", "黄色"), ("lime", "黄绿色"),
    ("pink", "粉红色"), ("gray", "灰色"), ("light_gray", "淡灰色"),
    ("cyan", "青色"), ("purple", "紫色"), ("blue", "蓝色"),
    ("brown", "棕色"), ("green", "绿色"), ("red", "红色"), ("black", "黑色"),
]

# 种类 -> (贴图名模板, Java 方块名模板, 中文后缀, 是否半透明)
KINDS = [
    ("wool",       "{c}_wool",          "{c}_wool",          "羊毛",     False),
    ("concrete",   "{c}_concrete",      "{c}_concrete",      "混凝土",   False),
    ("terracotta", "{c}_terracotta",    "{c}_terracotta",    "陶瓦",     False),
    ("glass",      "{c}_stained_glass", "{c}_stained_glass", "染色玻璃", True),
]

PREFIX = "assets/minecraft/textures/block/"


def pick_jar():
    for jar in JARS:
        if os.path.isfile(jar):
            return jar
    return None


def average_rgb(img: Image.Image):
    """平均色。半透明贴图只统计不透明像素，否则会被透明部分拉暗。"""
    px = list(img.getdata())
    opaque = [p[:3] for p in px if len(p) < 4 or p[3] >= 250]
    if not opaque:
        opaque = [p[:3] for p in px if len(p) < 4 or p[3] > 0]
    if not opaque:
        return (128, 128, 128)
    n = len(opaque)
    return (round(sum(p[0] for p in opaque) / n),
            round(sum(p[1] for p in opaque) / n),
            round(sum(p[2] for p in opaque) / n))


def main() -> int:
    jar = pick_jar()
    if jar is None:
        print("找不到游戏 jar，无法取色")
        return 1
    print(f"取色来源: {os.path.basename(jar)}")
    z = zipfile.ZipFile(jar)
    names = set(z.namelist())

    out = pathlib.Path(__file__).resolve().parent.parent / "palette_floor.json"
    entries = []
    missing = []

    for kind, tpl, block_tpl, zh_suffix, translucent in KINDS:
        print(f"=== {zh_suffix} ===")
        for color, zh_color in COLORS:
            entry_path = PREFIX + tpl.format(c=color) + ".png"
            if entry_path not in names:
                missing.append(entry_path)
                print(f"  {color:<11} 缺失")
                continue
            with z.open(entry_path) as fh:
                img = Image.open(fh).convert("RGBA")
            rgb = average_rgb(img)
            entries.append({
                "kind": kind,
                "color": color,
                "block": f"minecraft:{block_tpl.format(c=color)}",
                "zh": zh_color + zh_suffix,
                "zh_kind": zh_suffix,
                "translucent": translucent,
                "rgb": list(rgb),
                "texture": entry_path,
            })
            print(f"  {color:<11} avg={str(rgb):<18} -> {entry_path.split('/')[-1]}")

    out.write_text(json.dumps({
        "_comment": "由 tools/fetch_floor_palette.py 生成；颜色取自游戏本体 jar 的方块贴图平均色",
        "jar": os.path.basename(jar),
        "count": len(entries),
        "blocks": entries,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print()
    print(f"共 {len(entries)} 种方块，缺失 {len(missing)} 个")
    for m in missing:
        print("   缺:", m)
    print(f"已写入 {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
