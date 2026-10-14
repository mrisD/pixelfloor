# -*- coding: utf-8 -*-
"""验算 pixelfloor 界面的自适应布局（含新增的"速度"一行）。

复刻 PixelFloorScreen.computeLayout 的完整逻辑，检查每种 GUI 逻辑尺寸下
控件是否越界、是否被面板切掉。用户当前窗口是 854x480。
"""

PRESETS = [(20, 4), (18, 3), (16, 2)]
# 各档行数规划 {路径/对话框/翻页, 宽高, 开关, 附加项}
ROW_PLAN = [(3, 1, 4, 1), (3, 1, 3, 1), (1, 0, 3, 0)]
TERSE_ROWS = 5


def layout(width, height):
    margin = max(6, min(16, width // 60))
    col_gap = max(8, min(16, width // 70))
    panel_top = 26
    panel_bottom = height - 26
    usable = width - margin * 2 - col_gap
    left_w = max(140, int(usable * 0.56))
    right_x = margin + left_w + col_gap
    right_w = max(120, width - margin - right_x)
    budget = panel_bottom - panel_top

    for i, (wh, gap) in enumerate(PRESETS):
        row_h = wh + gap
        compact = i == len(PRESETS) - 1
        a, b, c, dd = ROW_PLAN[i]
        bottom = (panel_top + 18 + row_h * a + 18 + row_h * b
                  + row_h * c + row_h * dd + 22)
        if bottom - panel_top <= budget:
            return dict(preset=i + 1, compact=compact, terse=False,
                        widget_h=wh, gap=gap, right_x=right_x, right_w=right_w,
                        left_w=left_w, content_bottom=bottom,
                        panel_bottom=panel_bottom, panel_top=panel_top,
                        margin=margin)

    wh, gap = 16, 2
    row_h = wh + gap
    bottom = panel_top + 18 + row_h * TERSE_ROWS + 8
    if bottom > panel_bottom:
        bottom = panel_bottom
    return dict(preset=4, compact=True, terse=True, widget_h=wh, gap=gap,
                right_x=right_x, right_w=right_w, left_w=left_w,
                content_bottom=bottom, panel_bottom=panel_bottom,
                panel_top=panel_top, margin=margin)


CASES = [
    (854, 480, "用户当前窗口 854x480 (缩放1x)"),
    (427, 240, "用户窗口 缩放2x"),
    (285, 160, "用户窗口 缩放3x"),
    (1536, 864, "全屏 1536x864 缩放1x"),
    (768, 432, "全屏 缩放2x"),
    (512, 288, "全屏 缩放3x"),
    (384, 216, "全屏 缩放4x"),
    (1280, 720, "720p 1x"),
    (640, 360, "720p 2x"),
    (1920, 1080, "1080p 1x"),
    (960, 540, "1080p 2x"),
    (480, 270, "1080p 4x"),
    (320, 240, "最小窗口"),
]

print(f"{'场景':<26}{'GUI':>10}{'档':>3}{'窄档':>5}{'控件底':>7}{'面板底':>7}{'余量':>7}{'结论':>10}")
print("-" * 84)
problems = 0
for w, h, label in CASES:
    L = layout(w, h)
    issues = []
    if L["right_x"] + L["right_w"] > w:
        issues.append("右栏越界")
    if L["right_x"] < L["margin"] + L["left_w"]:
        issues.append("两栏重叠")
    if L["content_bottom"] > L["panel_bottom"]:
        issues.append("控件超出")
    if L["left_w"] < 60:
        issues.append("左栏过窄")
    verdict = "OK" if not issues else " ".join(issues)
    if issues:
        problems += 1
    slack = L["panel_bottom"] - L["content_bottom"]
    tag = "精简" if L["terse"] else ("紧凑" if L["compact"] else "完整")
    print(f"{label:<26}{w}x{h:<6}{L['preset']:>3}{tag:>5}"
          f"{L['content_bottom']:>7}{L['panel_bottom']:>7}{slack:>7}{verdict:>10}")

print()
print(f"有问题的场景: {problems} / {len(CASES)}")
