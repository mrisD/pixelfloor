# -*- coding: utf-8 -*-
"""用真实的调色板和图片，算出铺设会产生多少条 /fill，进而估算真实耗时。

为什么要算而不是估：/fill 是按"同行连续同色"合并的，条数完全取决于图片内容。
照片渐变多 -> 合并多 -> 条数少；噪点多 -> 条数多。用真实图片算才准。
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(r"F:\mctool\pixelfloor-forge")
sys.path.insert(0, r"F:\mctool\woolphoto")

from core import color as C  # noqa: E402


def load_palette():
    data = json.loads((ROOT / "palette_floor.json").read_text(encoding="utf-8"))
    rgb = np.array([b["rgb"] for b in data["blocks"]], dtype=float)
    return rgb, C.rgb_to_lab(rgb), data["blocks"]


def quantize(img: Image.Image, pal_rgb, pal_lab, batch=4000):
    """逐像素找最近色，返回索引数组。分批算避免内存爆掉。"""
    arr = np.asarray(img.convert("RGB"), dtype=float)
    h, w = arr.shape[:2]
    flat = arr.reshape(-1, 3)
    lab = C.rgb_to_lab(flat)
    out = np.empty(len(flat), dtype=np.int32)
    for i in range(0, len(lab), batch):
        d = C.delta_e_2000(lab[i:i + batch, None, :], pal_lab[None, :, :])
        out[i:i + batch] = d.argmin(axis=1)
    return out.reshape(h, w)


def count_commands(idx):
    """复刻 FloorBuilder 的合并逻辑：逐行把连续同色合并成一条 fill。"""
    rows, cols = idx.shape
    cmds = 0
    blocks_per_cmd = []
    for row in range(rows):
        c = 0
        while c < cols:
            run = 1
            while c + run < cols and idx[row, c + run] == idx[row, c]:
                run += 1
            # 单条 fill 有长度上限（250 字符），按最坏情况估算拆分
            # 坐标最长约 24 字符 + 方块名 26 字符 -> 约 60 字符一条，一般不会拆
            cmds += 1
            blocks_per_cmd.append(run)
            c += run
    return cmds, blocks_per_cmd


def main() -> int:
    pal_rgb, pal_lab, blocks = load_palette()
    print(f"调色板: {len(blocks)} 色")

    # 用户配置里的图片和宽度
    settings = pathlib.Path(
        r"F:\EFI\PCL 正式版 2.13.1.1\.minecraft\versions\1.21.4-Forge_54.1.18"
        r"\config\pixelfloor\settings.properties")
    img_path = r"F:\mctool\woolphoto\演示\示例照片_山谷.jpg"
    width = 300
    if settings.is_file():
        text = settings.read_text(encoding="utf-8")
        m = re.search(r"lastImage=(.+)", text)
        if m:
            raw = m.group(1).replace("\\\\", "\\").replace("\\:", ":").replace("\\=", "=")
            if pathlib.Path(raw).is_file():
                img_path = raw
        m = re.search(r"blockWidth=(\d+)", text)
        if m:
            width = int(m.group(1))
    print(f"图片: {img_path}")
    print(f"宽度: {width} 方块")

    img = Image.open(img_path)
    ratio = img.height / img.width
    height = max(1, round(width * ratio))
    small = img.convert("RGB").resize((width, height), Image.LANCZOS)
    print(f"画幅: {width} x {height} = {width * height} 块")
    print()

    idx = quantize(small, pal_rgb, pal_lab)
    cmds, runs = count_commands(idx)
    avg_run = sum(runs) / len(runs)
    max_run = max(runs)

    # 垫底：每行一条 fill（宽度方向合并），共 height 条
    level_cmds = height

    print("=== 指令统计 ===")
    print(f"  图像部分: {cmds} 条 fill（平均每条覆盖 {avg_run:.1f} 块，最长 {max_run} 块）")
    print(f"  垫底部分: {level_cmds} 条 fill（每行一条）")
    total = cmds + level_cmds
    print(f"  合计    : {total} 条")
    print()

    # 速度换算
    print("=== 耗时换算（每 tick 6 条，20 tick/秒 = 120 条/秒）===")
    for rate in (6, 12, 24, 48):
        tps = rate * 20
        secs = total / tps
        print(f"  每 tick {rate:>3} 条 -> {tps:>4} 条/秒 -> {secs:6.1f} 秒"
              + ("   (当前设置)" if rate == 6 else ""))

    print()
    print("=== 实际数据量 ===")
    print(f"  方块总数（含垫底）: {width * height + width * height} 块")
    print(f"  其中图像 {width * height} 块 + 垫底 {width * height} 块")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
