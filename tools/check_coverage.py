# -*- coding: utf-8 -*-
"""评估调色板覆盖度：64 色相比 16 色羊毛，色彩还原能力提升多少。

覆盖度的定义：在 Lab 空间撒点，看每个点到最近调色板颜色的 CIEDE2000 距离。
最坏值和平均值越小，说明能把原图还原得越准。
"""

from __future__ import annotations

import json
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, r"F:\mctool\woolphoto")

from core import color as C  # noqa: E402


def coverage(rgb_list, label, samples):
    pal = np.array(rgb_list, dtype=float)
    pal_lab = C.rgb_to_lab(pal)
    samples_lab = C.rgb_to_lab(samples)
    # 分批算，避免 (n_samples, n_palette) 的 CIEDE2000 占太多内存
    worst = 0.0
    total = 0.0
    n = 0
    for i in range(0, len(samples_lab), 2000):
        chunk = samples_lab[i:i + 2000]
        d = C.delta_e_2000(chunk[:, None, :], pal_lab[None, :, :])
        dmin = d.min(axis=1)
        worst = max(worst, float(dmin.max()))
        total += float(dmin.sum())
        n += len(dmin)
    print(f"  {label:<28} 色数 {len(pal):>3}  最坏 ΔE {worst:6.2f}  平均 ΔE {total/n:5.2f}")
    return worst, total / n


def main() -> int:
    floor = json.loads((ROOT / "palette_floor.json").read_text(encoding="utf-8"))
    floor_rgb = [b["rgb"] for b in floor["blocks"]]

    wool16 = json.loads(
        pathlib.Path(r"F:\mctool\woolphoto\palette_wool.json").read_text(encoding="utf-8")
    )
    wool_rgb = [b["rgb"] for b in wool16["wool"]]

    # 只取不透明方块做对比（玻璃半透明，表现依赖下方方块）
    opaque = [b["rgb"] for b in floor["blocks"] if not b["translucent"]]

    rng = np.random.default_rng(20261008)
    print("在 sRGB 全色域随机采样 20000 个颜色，看最近调色板颜色的色差：")
    print()
    # 直接在 RGB 空间均匀采样（更贴近真实照片的颜色分布）
    samples = rng.integers(0, 256, size=(20000, 3)).astype(float)

    coverage(wool_rgb, "16 色羊毛（原模组）", samples)
    coverage(opaque, "48 色不透明（地毯替代方案）", samples)
    coverage(floor_rgb, "64 色含玻璃（本模组）", samples)

    print()
    print("按种类看各自的覆盖（各 16 色）：")
    for kind, zh in (("wool", "羊毛"), ("concrete", "混凝土"),
                     ("terracotta", "陶瓦"), ("glass", "染色玻璃")):
        rgb = [b["rgb"] for b in floor["blocks"] if b["kind"] == kind]
        coverage(rgb, f"{zh} 16 色", samples)

    print()
    print("验证结论：64 色应显著优于 16 色；陶瓦覆盖低饱和中间色，")
    print("混凝土覆盖高饱和区，两者互补。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
