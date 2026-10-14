package com.pixelfloor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 模组设置：界面里改的选项会自动存下来，下次打开还是这套参数。
 *
 * <p>同时管理"用过的图片"目录 {@code config/pixelfloor/images/}，
 * 原图被移动或删除后模组这边仍能重新生成。
 */
public final class Config {

    private static final Properties PROPS = new Properties();
    private static Path dir;
    private static Path imagesDir;
    private static Path propsFile;

    /** 画幅宽度（方块数）。平铺在地面，所以能开得比立墙大得多。 */
    public static int blockWidth = 128;
    public static boolean autoHeight = true;
    public static int blockHeight = 128;
    public static boolean crop = false;
    public static boolean enhance = false;
    public static int colorMode = Palette.MODE_CIEDE2000;
    /** 垫底方块；空字符串表示不垫。 */
    public static String baseBlock = "minecraft:stone";
    /** 是否在图片下方垫一层，防止悬空。 */
    public static boolean levelUnder = true;
    public static int commandsPerTick = 6;
    public static String lastImage = "";

    private Config() {
    }

    public static void init() {
        dir = FMLPaths.CONFIGDIR.get().resolve("pixelfloor");
        imagesDir = dir.resolve("images");
        propsFile = dir.resolve("settings.properties");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            PixelFloor.LOGGER.warn("无法创建配置目录: {}", imagesDir, e);
        }
        load();
    }

    public static Path getImagesDir() {
        return imagesDir;
    }

    private static void load() {
        if (propsFile == null || !Files.isRegularFile(propsFile)) {
            return;
        }
        try (var in = Files.newInputStream(propsFile)) {
            PROPS.load(in);
            blockWidth = intOf("blockWidth", blockWidth);
            autoHeight = boolOf("autoHeight", autoHeight);
            blockHeight = intOf("blockHeight", blockHeight);
            crop = boolOf("crop", crop);
            enhance = boolOf("enhance", enhance);
            colorMode = intOf("colorMode", colorMode);
            baseBlock = PROPS.getProperty("baseBlock", baseBlock);
            levelUnder = boolOf("levelUnder", levelUnder);
            commandsPerTick = intOf("commandsPerTick", commandsPerTick);
            lastImage = PROPS.getProperty("lastImage", "");
        } catch (IOException e) {
            PixelFloor.LOGGER.warn("读取设置失败，使用默认值", e);
        }
    }

    public static void save() {
        if (propsFile == null) {
            return;
        }
        PROPS.setProperty("blockWidth", String.valueOf(blockWidth));
        PROPS.setProperty("autoHeight", String.valueOf(autoHeight));
        PROPS.setProperty("blockHeight", String.valueOf(blockHeight));
        PROPS.setProperty("crop", String.valueOf(crop));
        PROPS.setProperty("enhance", String.valueOf(enhance));
        PROPS.setProperty("colorMode", String.valueOf(colorMode));
        PROPS.setProperty("baseBlock", baseBlock == null ? "" : baseBlock);
        PROPS.setProperty("levelUnder", String.valueOf(levelUnder));
        PROPS.setProperty("commandsPerTick", String.valueOf(commandsPerTick));
        PROPS.setProperty("lastImage", lastImage == null ? "" : lastImage);
        try {
            Files.createDirectories(dir);
            try (var out = Files.newOutputStream(propsFile)) {
                PROPS.store(out, "pixelfloor settings");
            }
        } catch (IOException e) {
            PixelFloor.LOGGER.warn("保存设置失败", e);
        }
    }

    private static int intOf(String key, int def) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolOf(String key, boolean def) {
        return Boolean.parseBoolean(PROPS.getProperty(key, String.valueOf(def)).trim());
    }

    /** 把用户选的图片复制进模组目录，返回副本路径；重名自动加后缀。 */
    public static Path importImage(Path source) throws IOException {
        Files.createDirectories(imagesDir);
        String base = source.getFileName().toString();
        String stem = base;
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            ext = base.substring(dot);
        }
        String safe = stem.replaceAll("[^A-Za-z0-9_\\-]", "_").toLowerCase(Locale.ROOT);
        if (safe.isEmpty()) {
            safe = "image";
        }
        Path target = imagesDir.resolve(safe + ext.toLowerCase(Locale.ROOT));
        int n = 1;
        while (Files.exists(target)) {
            if (Files.isSameFile(source, target)) {
                return target;
            }
            target = imagesDir.resolve(safe + "_" + n + ext.toLowerCase(Locale.ROOT));
            n++;
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }
}
