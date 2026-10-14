# -*- coding: utf-8 -*-
"""检查游戏本体 jar 里有哪些方块贴图，用于生成准确的调色板。

比官方资源包仓库可靠：这就是用户实际在玩的版本，而且里面必然包含全部贴图。
"""

import os
import re
import zipfile

JARS = [
    r"F:\EFI\PCL 正式版 2.13.1.1\.minecraft\versions\1.21.4-Forge_54.1.18\1.21.4-Forge_54.1.18.jar",
    r"F:\mctool\woolphoto-forge\.gradle\mavenizer\repo\net\minecraft\client-extra\1.21.4-20241203.143248\client-extra-1.21.4-20241203.143248.jar",
]

for jar in JARS:
    if not os.path.isfile(jar):
        print(f"[无] {jar}")
        continue
    print("=" * 70)
    print(f"[有] {os.path.basename(jar)}  {os.path.getsize(jar)/1024/1024:.1f} MB")
    try:
        z = zipfile.ZipFile(jar)
    except Exception as exc:  # noqa: BLE001
        print("   打不开:", exc)
        continue

    pngs = [n for n in z.namelist() if n.startswith("assets/minecraft/textures/block/")
            and n.endswith(".png")]
    print(f"   textures/block 下 PNG: {len(pngs)} 个")

    groups = {
        "地毯": r"carpet",
        "混凝土": r"concrete",
        "陶瓦": r"terracotta",
        "羊毛": r"wool",
        "玻璃": r"glass",
    }
    for label, pat in groups.items():
        hits = sorted(n.split("/")[-1] for n in pngs if re.search(pat, n, re.I))
        # 去掉非彩色的通用贴图（如 terracotta.png、glass.png 本身）
        print(f"  --- {label}: {len(hits)} 个 ---")
        for h in hits[:8]:
            print("       ", h)
        if len(hits) > 8:
            print(f"        …另有 {len(hits)-8} 个")
    print()
