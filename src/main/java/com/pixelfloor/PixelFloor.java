package com.pixelfloor;

import java.io.File;
import java.nio.file.Path;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PixelFloor · 平面像素画生成器。
 *
 * <p>把一张图片用 64 色方块（羊毛 / 混凝土 / 陶瓦 / 染色玻璃各 16 色）铺在<b>地面</b>上。
 *
 * <p>为什么要平铺而不是立成墙：立墙时画幅高度受世界高度限制（主世界只有 384 格，
 * 实际可用更少），所以分辨率上不去。平铺到 XZ 平面后高度只占 1 格，
 * 分辨率只取决于水平距离——每个轴 3000 万格，等于没有限制。
 *
 * <p>用法：{@code /pixelfloor} 打开界面。
 */
@Mod(PixelFloor.MODID)
public class PixelFloor {

    public static final String MODID = "pixelfloor";
    public static final Logger LOGGER = LoggerFactory.getLogger("pixelfloor");

    private static FloorImage lastImage;
    private static FloorBuilder.Placement lastPlacement;

    public PixelFloor() {
        Palette.load();
        Config.init();
        FloorBuilder.setCommandsPerTick(Config.commandsPerTick);
        LOGGER.info("平面像素画生成器已加载，调色板 {} 色。用 /pixelfloor 打开界面。",
            Palette.size());

        if (FMLEnvironment.dist.isClient()) {
            // @EventBusSubscriber 对内部类不自动生效，必须手动注册
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        }

        // 色差算法自检：写错一处整个匹配都会偏，启动时验一遍
        double worst = ColorMath.selfTest();
        if (worst > 1e-4) {
            LOGGER.error("CIEDE2000 自检未通过，最大偏差 {}，颜色匹配可能不准", worst);
        } else {
            LOGGER.info("CIEDE2000 自检通过（对照 34 组标准数据，最大偏差 {}）", worst);
        }
    }

    public static FloorImage getLastImage() {
        return lastImage;
    }

    public static FloorBuilder.Placement getLastPlacement() {
        return lastPlacement;
    }

    public static void setLastImage(FloorImage image) {
        lastImage = image;
    }

    /** 打开界面。 */
    public static void openScreen() {
        Minecraft.getInstance().setScreen(new PixelFloorScreen());
    }

    /**
     * 开始铺设。先算位置、检查越界，再把指令放进待发队列。
     *
     * @return 提示文本
     */
    public static Component startBuild(FloorImage image) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return Component.literal("§c不在游戏里，无法铺设。");
        }
        if (FloorBuilder.isBuilding()) {
            return Component.literal("§e上一幅还在铺，请稍等或输入 /pixelfloor cancel 取消。");
        }
        FloorBuilder.Placement placement = FloorBuilder.computePlacement(mc.player, image);
        if (placement.outOfWorld()) {
            return Component.literal("§c放置范围超出世界边界，请换个位置或缩小尺寸。");
        }
        lastPlacement = placement;

        String base = Config.levelUnder ? Config.baseBlock : null;
        int commands = FloorBuilder.start(image, placement, base, Config.levelUnder);
        return Component.literal("§a开始铺设：" + placement.describe()
            + "，共 §e" + commands + " §a条指令");
    }

    /** 客户端事件。 */
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {

        @SubscribeEvent
        public static void onRegisterCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(
                Commands.literal("pixelfloor")
                    .executes(ctx -> {
                        openScreen();
                        return 1;
                    })
                    .then(Commands.literal("open").executes(ctx -> {
                        openScreen();
                        return 1;
                    }))
                    .then(Commands.literal("cancel").executes(ctx -> {
                        FloorBuilder.cancel();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("§e已取消剩余指令。"), false);
                        return 1;
                    }))
                    .then(Commands.literal("palette").executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§b调色板（" + Palette.size() + " 色）§r\n");
                        String lastKind = "";
                        for (Palette.Entry e : Palette.all()) {
                            if (!e.kind.equals(lastKind)) {
                                lastKind = e.kind;
                                sb.append("§7").append(e.zhName.substring(
                                    Math.max(0, e.zhName.length() - 3))).append("：§r");
                            }
                            sb.append(e.color).append(' ');
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                        return 1;
                    }))
                    .then(Commands.literal("build").executes(ctx -> {
                        if (lastImage == null) {
                            ctx.getSource().sendFailure(
                                Component.literal("还没有图片，先用 /pixelfloor 选一张。"));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> startBuild(lastImage), false);
                        return 1;
                    }))
                    .then(Commands.literal("load")
                        .then(Commands.argument("文件", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String path = StringArgumentType.getString(ctx, "文件");
                                File file = new File(path);
                                if (!file.isFile()) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("找不到文件：" + path));
                                    return 0;
                                }
                                try {
                                    FloorImage img = FloorImage.load(file, currentOptions());
                                    lastImage = img;
                                    Config.lastImage = file.getAbsolutePath();
                                    Config.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                        String.format("§a已载入 %d x %d 格，平均色差 ΔE %.2f，用色 %d 种。输入 /pixelfloor build 开始铺设。",
                                            img.cols, img.rows, img.meanDeltaE, img.distinctBlocks())), false);
                                    return 1;
                                } catch (Exception e) {
                                    LOGGER.error("载入图片失败: {}", file, e);
                                    ctx.getSource().sendFailure(
                                        Component.literal("转换失败：" + e.getMessage()));
                                    return 0;
                                }
                            })))
                    .then(Commands.literal("info").executes(ctx -> {
                        if (lastImage == null) {
                            ctx.getSource().sendFailure(Component.literal("还没有载入图片。"));
                            return 0;
                        }
                        FloorImage img = lastImage;
                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "§b平面像素画§r %d x %d 格（共 %d 块）\n"
                                + "§7平均色差§r ΔE %.2f  §7局部色调§r ΔE %.2f\n§7用色§r %d 种：%s",
                            img.cols, img.rows, img.cols * img.rows,
                            img.meanDeltaE, img.blockDeltaE,
                            img.distinctBlocks(), img.usageSummary(6))), false);
                        return 1;
                    }))
            );
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                FloorBuilder.tick();
            }
        }

        /** 用当前设置生成转换参数。 */
        public static FloorImage.Options currentOptions() {
            FloorImage.Options o = new FloorImage.Options();
            o.blockWidth = Config.blockWidth;
            o.blockHeight = Config.blockHeight;
            o.autoHeight = Config.autoHeight;
            o.crop = Config.crop;
            o.enhance = Config.enhance;
            o.colorMode = Config.colorMode;
            return o;
        }
    }

    /** 供界面调用：保存并应用设置。 */
    public static void applySettings(int blockWidth, boolean autoHeight, int blockHeight,
                                     boolean crop, boolean enhance, int colorMode,
                                     String baseBlock, boolean levelUnder, int commandsPerTick) {
        Config.blockWidth = blockWidth;
        Config.autoHeight = autoHeight;
        Config.blockHeight = blockHeight;
        Config.crop = crop;
        Config.enhance = enhance;
        Config.colorMode = colorMode;
        Config.baseBlock = baseBlock;
        Config.levelUnder = levelUnder;
        Config.commandsPerTick = commandsPerTick;
        FloorBuilder.setCommandsPerTick(commandsPerTick);
        Config.save();
    }

    /** 供界面调用：记住选中的图片。 */
    public static void rememberImage(Path path) {
        Config.lastImage = path == null ? "" : path.toString();
        Config.save();
    }
}
