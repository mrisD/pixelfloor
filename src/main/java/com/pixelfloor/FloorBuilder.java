package com.pixelfloor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把平面像素画铺到地面上。
 *
 * <p>核心思路：沿 XZ 平面平铺，而不是立成一面墙。这样高度只有 1 格，
 * 完全不受世界高度（384 格）限制，分辨率只取决于水平距离——而水平方向
 * 每个轴有 3000 万格，等于没有限制。
 *
 * <p>坐标映射：
 * <pre>
 *   图片 col (0 = 最左)  ->  世界 X 递增（从西到东）
 *   图片 row (0 = 最上)  ->  世界 Z，row 0 在玩家这一侧，逐行远离玩家
 * </pre>
 * 因为玩家面朝南时北方在屏幕上方，把 row 0 放在玩家近端，从玩家视角看
 * 图片上边缘正好在远处（屏幕上方），画面就是正的。
 *
 * <p>地面处理：向下找到第一个实心面再往上铺一格；铺完在图片下方垫一层石头，
 * 避免图片悬空、或被挖穿后露出下面。
 */
public final class FloorBuilder {

    private static final int MAX_COMMAND_LEN = 250;
    private static final int MAX_COORD = 30_000_000;

    private static final Deque<String> PENDING = new ArrayDeque<>();
    private static final List<String> LAST_BATCH = new ArrayList<>();
    private static int commandsPerTick = 6;

    private FloorBuilder() {
    }

    public static void setCommandsPerTick(int n) {
        commandsPerTick = Math.max(1, Math.min(128, n));
    }

    public static int getCommandsPerTick() {
        return commandsPerTick;
    }

    public static boolean isBuilding() {
        return !PENDING.isEmpty();
    }

    public static int remaining() {
        return PENDING.size();
    }

    public static void cancel() {
        PENDING.clear();
    }

    /**
     * 找地面高度：从玩家所在格开始向下找第一个实心方块，返回它上面那一格的 Y。
     *
     * <p>跳过水，因为站在水上放图没有意义；找不到就返回玩家自身高度。
     */
    public static int findGroundY(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return (int) Math.floor(player.getY());
        }
        int x = (int) Math.floor(player.getX());
        int z = (int) Math.floor(player.getZ());
        int startY = (int) Math.floor(player.getY());
        // 1.21.4 里这个方法是 getMinY()（旧版本叫 getMinBuildHeight）
        int minY = mc.level.getMinY();
        for (int y = startY; y >= minY; y--) {
            BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
            if (state.isAir() || state.getBlock() == Blocks.WATER) {
                continue;
            }
            // 第一个实心方块的上面一格
            return y + 1;
        }
        return startY;
    }

    /**
     * 计算整幅画的放置范围。
     *
     * <p>以玩家所站位置为"图片上边缘（row 0）的中心"，图片向面朝方向展开。
     *
     * <p>方向推导（和立墙那套不同，已用投影探针逐朝向验算）：
     * 把世界方向投影到屏幕基底 (右, 上) 上，要求
     * "col 增大"投影到右方、"row 增大"投影到上方。
     * <pre>
     *   屏幕上方 = 面朝方向 f（玩家低头看图时，远处就是屏幕上方）
     *   屏幕右方 = cross(视线方向, 上方)，视线方向 = (0,-1,0)
     *            = cross((0,-1,0), (fx,0,fz)) = (-fz, 0, fx)
     * </pre>
     * 代入面朝南 f=(0,0,1)：屏幕右方 = (-1,0,0) = 西，
     * 而 {@code Direction.SOUTH.getCounterClockWise()} 正好是 west。
     * 所以 colDir 必须用 <b>getCounterClockWise</b>——
     * 用 getClockWise 会得到 east，画面左右镜像
     * （这一点和立墙模组相反，别照抄）。
     */
    public static Placement computePlacement(Player player, FloorImage image) {
        Direction facing = player.getDirection();
        Direction colDir = facing.getCounterClockWise();
        // 行方向就是面朝方向：row 增大 = 远离玩家 = 屏幕上方向
        Direction rowDir = facing;

        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int groundY = findGroundY(player);

        // 图片上边缘(row 0)的中心放在玩家脚下一格前方，
        // 这样整幅图铺在玩家正前方而不是脚下
        int nearX = px + rowDir.getStepX();
        int nearZ = pz + rowDir.getStepZ();

        // 横向居中：把第 0 列往"列的负方向"挪半个宽度
        int halfWidth = image.cols / 2;
        int originX = nearX - colDir.getStepX() * halfWidth;
        int originZ = nearZ - colDir.getStepZ() * halfWidth;

        return new Placement(originX, groundY, originZ, nearX, nearZ,
            colDir.getStepX(), colDir.getStepZ(),
            rowDir.getStepX(), rowDir.getStepZ(),
            image.cols, image.rows);
    }

    /** 放置范围。 */
    public static final class Placement {
        public final int originX, groundY, originZ;
        /** 图片上边缘中心的坐标，用于描述给玩家。 */
        public final int nearX, nearZ;
        /** 图片列增大对应的世界方向。 */
        public final int colStepX, colStepZ;
        /** 图片行增大（远离玩家）对应的世界方向。 */
        public final int rowStepX, rowStepZ;
        public final int cols, rows;

        Placement(int ox, int gy, int oz, int nx, int nz,
                  int colStepX, int colStepZ, int rowStepX, int rowStepZ,
                  int cols, int rows) {
            this.originX = ox;
            this.groundY = gy;
            this.originZ = oz;
            this.nearX = nx;
            this.nearZ = nz;
            this.colStepX = colStepX;
            this.colStepZ = colStepZ;
            this.rowStepX = rowStepX;
            this.rowStepZ = rowStepZ;
            this.cols = cols;
            this.rows = rows;
        }

        /** 某个格子对应的世界坐标。 */
        public BlockPos posOf(int col, int row) {
            // 列：沿 right 方向展开；行：沿面朝方向逐行远离玩家
            // 注意 originX 已经包含了横向居中的偏移
            int x = originX + colStepX * col + rowStepX * row;
            int z = originZ + colStepZ * col + rowStepZ * row;
            return new BlockPos(x, groundY, z);
        }

        /** 是否超出世界范围。 */
        public boolean outOfWorld() {
            BlockPos a = posOf(0, 0);
            BlockPos b = posOf(cols - 1, rows - 1);
            int maxY = groundY;
            if (maxY < -64 || maxY > 320) {
                return true;
            }
            return Math.abs(a.getX()) > MAX_COORD || Math.abs(a.getZ()) > MAX_COORD
                || Math.abs(b.getX()) > MAX_COORD || Math.abs(b.getZ()) > MAX_COORD;
        }

        private String sideName() {
            if (rowStepZ > 0) {
                return "南边";
            }
            if (rowStepZ < 0) {
                return "北边";
            }
            if (rowStepX > 0) {
                return "东边";
            }
            return "西边";
        }

        public String describe() {
            return String.format("%d x %d 格，铺在地面（Y=%d）上、向%s展开，左上角在 (%d, %d, %d)",
                cols, rows, groundY, sideName(), posOf(0, 0).getX(), groundY, posOf(0, 0).getZ());
        }
    }

    /**
     * 生成放置指令：逐行合并同色方块成 /fill，并在图片下方垫一层石头。
     *
     * @param levelUnder true 时在图片正下方铺一层石头，防止悬空
     * @return 指令条数
     */
    public static int start(FloorImage image, Placement p, String baseBlock, boolean levelUnder) {
        PENDING.clear();
        LAST_BATCH.clear();

        // 1) 先垫底：整个矩形下方一层，用便宜的石材，避免地形高低差造成悬空
        if (levelUnder && baseBlock != null && !baseBlock.isEmpty()) {
            for (int row = 0; row < image.rows; row++) {
                BlockPos a = p.posOf(0, row);
                BlockPos b = p.posOf(image.cols - 1, row);
                emitFill(new BlockPos(a.getX(), p.groundY - 1, a.getZ()),
                         new BlockPos(b.getX(), p.groundY - 1, b.getZ()), baseBlock);
            }
        }

        // 2) 再铺图像本身，逐行合并同色
        for (int row = 0; row < image.rows; row++) {
            int col = 0;
            while (col < image.cols) {
                int idx = image.blockIndex[row][col];
                int run = 1;
                while (col + run < image.cols && image.blockIndex[row][col + run] == idx) {
                    run++;
                }
                String blockId = Palette.get(idx).blockId;
                emitRun(p, row, col, run, blockId);
                col += run;
            }
        }

        PENDING.addAll(LAST_BATCH);
        return LAST_BATCH.size();
    }

    /** 把一段连续同色方块拆成若干条合法长度的 /fill。 */
    private static void emitRun(Placement p, int row, int startCol, int len, String blockId) {
        int done = 0;
        while (done < len) {
            int chunk = len - done;
            while (chunk > 1) {
                BlockPos a = p.posOf(startCol + done, row);
                BlockPos b = p.posOf(startCol + done + chunk - 1, row);
                String cmd = fillCommand(a, b, blockId);
                if (cmd.length() <= MAX_COMMAND_LEN) {
                    LAST_BATCH.add(cmd);
                    break;
                }
                chunk = Math.max(1, chunk / 2);
            }
            if (chunk == 1) {
                BlockPos a = p.posOf(startCol + done, row);
                LAST_BATCH.add(String.format("setblock %d %d %d %s",
                    a.getX(), a.getY(), a.getZ(), blockId));
            }
            done += chunk;
        }
    }

    private static void emitFill(BlockPos a, BlockPos b, String blockId) {
        String cmd = fillCommand(a, b, blockId);
        if (cmd.length() <= MAX_COMMAND_LEN) {
            LAST_BATCH.add(cmd);
            return;
        }
        // 太长就按列对半分
        if (a.getX() != b.getX()) {
            int mid = (a.getX() + b.getX()) / 2;
            emitFill(a, new BlockPos(mid, a.getY(), a.getZ()), blockId);
            emitFill(new BlockPos(mid + 1, b.getY(), b.getZ()), b, blockId);
        } else {
            int mid = (a.getZ() + b.getZ()) / 2;
            emitFill(a, new BlockPos(a.getX(), a.getY(), mid), blockId);
            emitFill(new BlockPos(b.getX(), b.getY(), mid + 1), b, blockId);
        }
    }

    private static String fillCommand(BlockPos a, BlockPos b, String blockId) {
        return "fill " + a.getX() + " " + a.getY() + " " + a.getZ()
            + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " " + blockId;
    }

    /** 由客户端 tick 调用：每 tick 发一小批指令。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (PENDING.isEmpty() || mc.getConnection() == null || mc.player == null) {
            return;
        }
        int sent = 0;
        while (!PENDING.isEmpty() && sent < commandsPerTick) {
            String cmd = PENDING.poll();
            try {
                mc.getConnection().sendCommand(cmd);
            } catch (Exception e) {
                PixelFloor.LOGGER.warn("指令发送失败: {}", cmd, e);
            }
            sent++;
        }
        if (PENDING.isEmpty()) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§a平面像素画铺设完成！"), false);
        } else if (PENDING.size() % 100 == 0) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "§7铺设中… 还剩 §e" + PENDING.size() + " §7条指令"), true);
        }
    }

    /**
     * 统计目标区域里已经有多少非空气方块，放置前提醒玩家会不会覆盖建筑。
     * 按采样估算，避免几十万次方块查询卡住界面。
     */
    public static int countOccupied(Placement p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return -1;
        }
        int stepRow = Math.max(1, p.rows / 32);
        int stepCol = Math.max(1, p.cols / 32);
        int occupied = 0;
        int sampled = 0;
        for (int row = 0; row < p.rows; row += stepRow) {
            for (int col = 0; col < p.cols; col += stepCol) {
                BlockState state = mc.level.getBlockState(p.posOf(col, row));
                sampled++;
                if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                    occupied++;
                }
            }
        }
        if (sampled == 0) {
            return 0;
        }
        return (int) Math.round(occupied * (p.cols * (double) p.rows) / sampled);
    }
}
