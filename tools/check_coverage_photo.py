# -*- coding: utf-8 -*-
"""用真实照片的像素评估调色板覆盖度。

为什么不能只看随机采样：均匀随机采样里高饱和颜色占比过高，
而真实照片绝大部分像素是低饱和的（肤色、岩石、木头、植被、天空），
正好是陶瓦擅长覆盖的区间。用真实像素才能说明实际提升。
"""

from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, r"F:\mctool\woolphoto")

from core import color as C  # noqa: E402

PHOTOS = [
    r"F:\mctool\woolphoto\演示\示例照片_山谷.jpg",
    r"F:\mctool\woolphoto\演示\测试图_色卡渐变.png",
]


def load_pixels(path, max_px=20000):
    img = Image.open(path).convert("RGB")
    arr = np.asarray(img).reshape(-1, 3).astype(float)
    if len(arr) > max_px:
        idx = np.random.default_rng(7).choice(len(arr), max_px, replace=False)
        arr = arr[idx]
    return arr


def evaluate(rgb_list, samples):
    pal_lab = C.rgb_to_lab(np.array(rgb_list, dtype=float))
    s_lab = C.rgb_to_lab(samples)
    best = None
    for i in range(0, len(s_lab), 2000):
        d = C.delta_e_2000(s_lab[i:i + 2000, None, :], pal_lab[None, :, :])
        dmin = d.min(axis=1)
        best = dmin if best is None else np.concatenate([best, dmin])
    return float(best.max()), float(best.mean())


def main() -> int:
    floor = json.loads((ROOT / "palette_floor.json").read_text(encoding="utf-8"))
    floor_rgb = [b["rgb"] for b in floor["blocks"]]
    opaque = [b["rgb"] for b in floor["blocks"] if not b["translucent"]]

    wool16 = json.loads(
        pathlib.Path(r"F:\mctool\woolphoto\palette_wool.json").read_text(encoding="utf-8")
    )
    wool_rgb = [b["rgb"] for b in wool16["wool"]]

    import os
    for photo in PHOTOS:
        if not os.path.isfile(photo):
            continue
        px = load_pixels(photo)
        print("=" * 74)
        print(f"图片: {os.path.basename(photo)}  （采样 {len(px)} 像素）")
        for label, pal in (("16 色羊毛（原模组）", wool_rgb),
                           ("48 色不透明", opaque),
                           ("64 色含玻璃（本模组）", floor_rgb)):
            worst, mean = evaluate(pal, px)
            print(f"  {label:<24} 最坏 ΔE {worst:6.2f}   平均 ΔE {mean:5.2f}")

    print()
    print("按种类单独看（真实照片像素上）：")
    px = load_pixels(PHOTOS[0])
    for kind, zh in (("wool", "羊毛"), ("concrete", "混凝土"),
                     ("terracotta", "陶瓦"), ("glass", "染色玻璃")):
        rgb = [b["rgb"] for b in floor["blocks"] if b["kind"] == kind]
        worst, mean = evaluate(rgb, px)
        print(f"  {zh + ' 16 色':<16} 最坏 ΔE {worst:6.2f}   平均 ΔE {mean:5.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
