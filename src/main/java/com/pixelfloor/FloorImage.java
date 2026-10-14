package com.pixelfloor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 一张图片转换成的"平面像素画"数据。
 *
 * <p>坐标约定：{@code blockIndex[row][col]}，row=0 是图片最上面一行。
 * 放置时 row 映射到世界 Z（row 越大 Z 越大，也就是越靠南），col 映射到世界 X。
 * 这样从正上方俯视（北在上）时，图片是正的：北 = 图片上边缘 = row 0。
 *
 * <p>这里刻意把每个方块的近似色一起存下来：界面上的颜色开关如果变化，
 * 只需要重新做一次最近色匹配就能刷新预览，不用重新解码原图、重新缩放，
 * 响应快很多。
 */
public final class FloorImage {

    /** 方块索引，[row][col]。 */
    public final int[][] blockIndex;
    /** 每个格子实际渲染出来的颜色（0xRRGGBB），用于预览，避免每帧查调色板。 */
    public final int[][] renderRgb;
    public final int cols;
    public final int rows;
    /** 缩放后的原图，用于并排对照。 */
    public final BufferedImage sourceImage;
    public final double meanDeltaE;
    public final double maxDeltaE;
    /** 8x8 局部平均色的色差，比逐像素更接近"远看像不像"。 */
    public final double blockDeltaE;
    public final Map<String, Integer> usage;

    private FloorImage(int[][] blockIndex, int[][] renderRgb, BufferedImage sourceImage,
                       double meanDeltaE, double maxDeltaE, double blockDeltaE,
                       Map<String, Integer> usage) {
        this.blockIndex = blockIndex;
        this.renderRgb = renderRgb;
        this.rows = blockIndex.length;
        this.cols = this.rows == 0 ? 0 : blockIndex[0].length;
        this.sourceImage = sourceImage;
        this.meanDeltaE = meanDeltaE;
        this.maxDeltaE = maxDeltaE;
        this.blockDeltaE = blockDeltaE;
        this.usage = usage;
    }

    /** 转换参数。 */
    public static final class Options {
        /** 目标宽度（方块数）。平铺在地面，所以宽度只受水平距离限制。 */
        public int blockWidth = 128;
        public int blockHeight = 0;
        public boolean autoHeight = true;
        public boolean crop = false;
        public int rotate = 0;
        public boolean flipH = false;
        public boolean enhance = false;
        public int colorMode = Palette.MODE_CIEDE2000;
    }

    public static FloorImage load(File file, Options options) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("无法识别的图片格式（支持 PNG / JPG / BMP / GIF）");
        }
        return convert(img, options);
    }

    public static FloorImage convert(BufferedImage original, Options options) {
        BufferedImage img = toRgb(original);
        img = applyTransform(img, options.rotate, options.flipH);
        if (options.enhance) {
            img = enhance(img);
        }

        int cols = Math.max(1, options.blockWidth);
        int rows;
        if (options.autoHeight) {
            rows = Math.max(1, (int) Math.round(
                (double) cols * img.getHeight() / img.getWidth()));
        } else {
            rows = Math.max(1, options.blockHeight);
        }
        if (options.crop) {
            img = centerCrop(img, (double) cols / rows);
        }
        BufferedImage scaled = scale(img, cols, rows);

        int[][] index = new int[rows][cols];
        int[][] render = new int[rows][cols];
        Map<String, Integer> usage = new LinkedHashMap<>();
        double sum = 0;
        double max = 0;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int p = scaled.getRGB(col, row) & 0xFFFFFF;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;

                int idx = Palette.nearest(r, g, b, options.colorMode);
                if (idx < 0) {
                    idx = 0; // 所有方块都被禁用时的兜底
                }
                index[row][col] = idx;
                Palette.Entry e = Palette.get(idx);
                render[row][col] = (e.r << 16) | (e.g << 8) | e.b;
                usage.merge(e.zhName, 1, Integer::sum);

                double[] lab = ColorMath.rgbToLab(r, g, b);
                double d = ColorMath.deltaE2000(lab[0], lab[1], lab[2],
                    e.labL, e.labA, e.labB);
                sum += d;
                if (d > max) {
                    max = d;
                }
            }
        }

        return new FloorImage(index, render, scaled,
            sum / (cols * (double) rows), max,
            blockMeanDeltaE(scaled, render, 8), sortDesc(usage));
    }

    /**
     * 只重做"最近色匹配"，复用已经缩放好的原图。
     *
     * <p>用户切换颜色开关时需要这个：源图没变，只是候选方块变了。
     */
    public FloorImage requantize(int colorMode) {
        BufferedImage scaled = sourceImage;
        int[][] index = new int[rows][cols];
        int[][] render = new int[rows][cols];
        Map<String, Integer> usage = new LinkedHashMap<>();
        double sum = 0;
        double max = 0;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int p = scaled.getRGB(col, row) & 0xFFFFFF;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int idx = Palette.nearest(r, g, b, colorMode);
                if (idx < 0) {
                    idx = 0;
                }
                index[row][col] = idx;
                Palette.Entry e = Palette.get(idx);
                render[row][col] = (e.r << 16) | (e.g << 8) | e.b;
                usage.merge(e.zhName, 1, Integer::sum);
                double[] lab = ColorMath.rgbToLab(r, g, b);
                double d = ColorMath.deltaE2000(lab[0], lab[1], lab[2],
                    e.labL, e.labA, e.labB);
                sum += d;
                if (d > max) {
                    max = d;
                }
            }
        }
        return new FloorImage(index, render, scaled,
            sum / (cols * (double) rows), max,
            blockMeanDeltaE(scaled, render, 8), sortDesc(usage));
    }

    /** 生成预览图（1 方块 = 1 像素），直接来自已算好的颜色，不查表。 */
    public BufferedImage toPreviewImage() {
        BufferedImage out = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                out.setRGB(col, row, renderRgb[row][col]);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private static double blockMeanDeltaE(BufferedImage src, int[][] render, int block) {
        int w = src.getWidth();
        int h = src.getHeight();
        int step = Math.max(1, Math.min(block, Math.min(w, h)));
        double total = 0;
        int count = 0;
        for (int by = 0; by < h; by += step) {
            for (int bx = 0; bx < w; bx += step) {
                long ar = 0, ag = 0, ab = 0, br = 0, bg = 0, bb = 0;
                int n = 0;
                for (int y = by; y < Math.min(h, by + step); y++) {
                    for (int x = bx; x < Math.min(w, bx + step); x++) {
                        int p = src.getRGB(x, y);
                        ar += (p >> 16) & 0xFF;
                        ag += (p >> 8) & 0xFF;
                        ab += p & 0xFF;
                        int q = render[y][x];
                        br += (q >> 16) & 0xFF;
                        bg += (q >> 8) & 0xFF;
                        bb += q & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                double[] l1 = ColorMath.rgbToLab((int) (ar / n), (int) (ag / n), (int) (ab / n));
                double[] l2 = ColorMath.rgbToLab((int) (br / n), (int) (bg / n), (int) (bb / n));
                total += ColorMath.deltaE2000(l1[0], l1[1], l1[2], l2[0], l2[1], l2[2]);
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private static Map<String, Integer> sortDesc(Map<String, Integer> map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        map.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // 透明像素合成到白底：像素画没有透明概念，得给个合理底色
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage applyTransform(BufferedImage src, int rotate, boolean flipH) {
        BufferedImage img = src;
        if (flipH) {
            BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(img, img.getWidth(), 0, 0, img.getHeight(),
                0, 0, img.getWidth(), img.getHeight(), null);
            g.dispose();
            img = out;
        }
        int deg = ((rotate % 360) + 360) % 360;
        if (deg == 0) {
            return img;
        }
        boolean swap = deg == 90 || deg == 270;
        int w = swap ? img.getHeight() : img.getWidth();
        int h = swap ? img.getWidth() : img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.rotate(Math.toRadians(deg), w / 2.0, h / 2.0);
        g.drawImage(img, (w - img.getWidth()) / 2, (h - img.getHeight()) / 2, null);
        g.dispose();
        return out;
    }

    private static BufferedImage centerCrop(BufferedImage src, double targetRatio) {
        double srcRatio = src.getWidth() / (double) src.getHeight();
        if (Math.abs(srcRatio - targetRatio) < 1e-6) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (srcRatio > targetRatio) {
            w = Math.max(1, (int) Math.round(h * targetRatio));
        } else {
            h = Math.max(1, (int) Math.round(w / targetRatio));
        }
        return src.getSubimage((src.getWidth() - w) / 2, (src.getHeight() - h) / 2, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage enhance(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] hist = new int[256];
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            hist[(int) (0.299 * r + 0.587 * g + 0.114 * b)]++;
        }
        int total = pixels.length;
        int lowCut = total / 100;
        int highCut = total - lowCut;
        int low = 0;
        int high = 255;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= lowCut) {
                low = i;
                break;
            }
        }
        acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= highCut) {
                high = i;
                break;
            }
        }
        double range = Math.max(1, high - low);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = clamp((int) Math.round((((p >> 16) & 0xFF) - low) * 255.0 / range));
            int g = clamp((int) Math.round((((p >> 8) & 0xFF) - low) * 255.0 / range));
            int b = clamp((int) Math.round(((p & 0xFF) - low) * 255.0 / range));
            int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            r = clamp((int) Math.round(lum + (r - lum) * 1.12));
            g = clamp((int) Math.round(lum + (g - lum) * 1.12));
            b = clamp((int) Math.round(lum + (b - lum) * 1.12));
            pixels[i] = (r << 16) | (g << 8) | b;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 用色统计的可读形式。 */
    public String usageSummary(int topN) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int total = cols * rows;
        for (Map.Entry<String, Integer> e : usage.entrySet()) {
            if (n++ >= topN) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(e.getKey()).append(' ')
              .append(String.format("%.0f%%", e.getValue() * 100.0 / total));
        }
        return sb.toString();
    }

    public int distinctBlocks() {
        return usage.size();
    }

    /** 用到的方块 ID 列表（放置与统计用）。 */
    public List<String> usedBlockIds() {
        List<String> out = new ArrayList<>();
        for (int[] row : blockIndex) {
            for (int idx : row) {
                String id = Palette.get(idx).blockId;
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
