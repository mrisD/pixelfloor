package com.pixelfloor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 平面像素画用的方块调色板（64 色）。
 *
 * <p>颜色不写死在代码里，而是读 jar 内的 {@code palette_floor.json}——
 * 那份文件由 {@code tools/fetch_floor_palette.py} 从<b>游戏本体的方块贴图</b>算出平均色。
 * 这样做的好处：
 * <ul>
 *   <li>颜色来自真实贴图，不是凭"标准染料色"估的，能消掉肉眼可见的偏差；</li>
 *   <li>工具和模组共用同一份数据，不会出现两边不一致；</li>
 *   <li>以后想换调色板（比如加带釉陶瓦）只要重新跑工具，不用改 Java 代码。</li>
 * </ul>
 */
public final class Palette {

    /** 一种候选方块。 */
    public static final class Entry {
        public final String kind;
        public final String color;
        public final String blockId;
        public final String zhName;
        /** 半透明方块（染色玻璃）的颜色会受下方方块影响，选材时需要区别对待。 */
        public final boolean translucent;
        public final int r, g, b;
        final double labL, labA, labB;
        /** 是否被用户禁用。 */
        public boolean enabled = true;

        Entry(String kind, String color, String blockId, String zhName,
              boolean translucent, int r, int g, int b) {
            this.kind = kind;
            this.color = color;
            this.blockId = blockId;
            this.zhName = zhName;
            this.translucent = translucent;
            this.r = r;
            this.g = g;
            this.b = b;
            double[] lab = ColorMath.rgbToLab(r, g, b);
            this.labL = lab[0];
            this.labA = lab[1];
            this.labB = lab[2];
        }

        @Override
        public String toString() {
            return zhName;
        }
    }

    public static final int MODE_CIEDE2000 = 0;
    public static final int MODE_CIE76 = 1;
    public static final int MODE_RGB = 2;

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded;

    // 5 位/通道的最近色缓存（32768 格），避免逐像素重复算 CIEDE2000
    private static final int CACHE_BITS = 5;
    private static final int CACHE_SIZE = 1 << (CACHE_BITS * 3);
    private static final short[] CACHE_2000 = new short[CACHE_SIZE];
    private static final short[] CACHE_76 = new short[CACHE_SIZE];
    private static final short[] CACHE_RGB = new short[CACHE_SIZE];
    private static final boolean[] VALID_2000 = new boolean[CACHE_SIZE];
    private static final boolean[] VALID_76 = new boolean[CACHE_SIZE];
    private static final boolean[] VALID_RGB = new boolean[CACHE_SIZE];

    private Palette() {
    }

    /** 从 jar 内资源加载调色板；只加载一次。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        String resource = "/palette_floor.json";
        try (InputStream in = Palette.class.getResourceAsStream(resource)) {
            if (in == null) {
                PixelFloor.LOGGER.error("找不到调色板资源 {}，模组无法工作", resource);
                return;
            }
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray blocks = root.getAsJsonArray("blocks");
            for (JsonElement el : blocks) {
                JsonObject o = el.getAsJsonObject();
                JsonArray rgb = o.getAsJsonArray("rgb");
                ENTRIES.add(new Entry(
                    o.get("kind").getAsString(),
                    o.get("color").getAsString(),
                    o.get("block").getAsString(),
                    o.get("zh").getAsString(),
                    o.has("translucent") && o.get("translucent").getAsBoolean(),
                    rgb.get(0).getAsInt(), rgb.get(1).getAsInt(), rgb.get(2).getAsInt()
                ));
            }
            PixelFloor.LOGGER.info("调色板加载完成，共 {} 种方块", ENTRIES.size());
        } catch (Exception e) {
            PixelFloor.LOGGER.error("读取调色板失败", e);
        }
    }

    public static List<Entry> all() {
        return Collections.unmodifiableList(ENTRIES);
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static Entry get(int i) {
        return ENTRIES.get(i);
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_CIE76: return "CIE76（Lab 距离，快）";
            case MODE_RGB: return "RGB 距离（最快，最不准）";
            default: return "CIEDE2000（推荐，最接近人眼）";
        }
    }

    /** 找与给定颜色最接近、且未被禁用的方块下标；找不到返回 -1。 */
    public static int nearest(int r, int g, int b, int mode) {
        int key = cacheKey(r, g, b);
        short[] cache = cacheArray(mode);
        boolean[] valid = validArray(mode);
        if (valid[key]) {
            return cache[key];
        }
        int best = computeNearest(r, g, b, mode);
        cache[key] = (short) best;
        valid[key] = true;
        return best;
    }

    private static int computeNearest(int r, int g, int b, int mode) {
        double[] lab = mode == MODE_RGB ? null : ColorMath.rgbToLab(r, g, b);
        int bestIndex = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            if (!e.enabled) {
                continue;
            }
            double d;
            switch (mode) {
                case MODE_CIE76:
                    d = ColorMath.deltaE76(lab, e.labL, e.labA, e.labB);
                    break;
                case MODE_RGB:
                    double dr = r - e.r;
                    double dg = g - e.g;
                    double db = b - e.b;
                    d = dr * dr + dg * dg + db * db;
                    break;
                case MODE_CIEDE2000:
                default:
                    d = ColorMath.deltaE2000(lab[0], lab[1], lab[2],
                        e.labL, e.labA, e.labB);
                    break;
            }
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * 开关变动后必须清缓存：缓存是按"全部可用"算的，一旦有方块被禁用，
     * 旧结果就可能指向被禁用的方块。
     */
    public static void invalidateCache() {
        java.util.Arrays.fill(VALID_2000, false);
        java.util.Arrays.fill(VALID_76, false);
        java.util.Arrays.fill(VALID_RGB, false);
    }

    private static int cacheKey(int r, int g, int b) {
        int shift = 8 - CACHE_BITS;
        return ((r >> shift) << (CACHE_BITS * 2)) | ((g >> shift) << CACHE_BITS) | (b >> shift);
    }

    private static short[] cacheArray(int mode) {
        switch (mode) {
            case MODE_CIE76: return CACHE_76;
            case MODE_RGB: return CACHE_RGB;
            default: return CACHE_2000;
        }
    }

    private static boolean[] validArray(int mode) {
        switch (mode) {
            case MODE_CIE76: return VALID_76;
            case MODE_RGB: return VALID_RGB;
            default: return VALID_2000;
        }
    }
}
