package com.pixelfloor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 平面像素画界面。
 *
 * <p>左栏：原图 + 铺好后的俯视效果 + 图片列表（可点选）。
 * 右栏：路径、画幅尺寸、色彩算法、地面处理、开始铺设。
 *
 * <p>布局尺寸全部按当前 GUI 逻辑空间动态计算，纵向另有三档密度自适应。
 * 这是从上一个模组踩出来的经验：Minecraft 自动 GUI 缩放会把逻辑空间压到
 * 400~600 宽，写死像素必然重叠。
 */
public class PixelFloorScreen extends Screen {

    private static final int PREVIEW_MAX = 320;
    private static final int BG = 0xC0101010;
    private static final int PANEL = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFF;
    private static final int TEXT_DIM = 0xFFA0A0A0;
    private static final int ACCENT = 0xFF7FD4FF;

    private static final Component TITLE = Component.literal("平面像素画生成器");
    private static final Component LBL_SOURCE = Component.literal("原图");
    private static final Component LBL_FLOOR = Component.literal("铺好后俯视效果");
    private static final Component LBL_CONVERTING = Component.literal("转换中…");
    private static final Component NO_IMAGE =
        Component.literal("先选一张图片：点左上角目录，或在右栏粘贴路径");

    private final List<Path> imageFiles = new ArrayList<>();
    private List<ImageFinder.Folder> folders = List.of();
    private ImageFinder.Folder currentFolder;
    private int selectedIndex = -1;
    private Path selectedFile;
    private int listScroll;

    // 列表几何，由 render() 写入，mouseClicked() 复用
    private int listTop;
    private int listRowH = 10;
    private int listRows;
    private int listStart;
    private int listLeft;
    private int listWidth;

    private FloorImage result;
    private String status = "先选一张图片";
    private boolean statusIsError = false;
    private boolean converting = false;

    private DynamicTexture srcTex;
    private DynamicTexture dstTex;
    private ResourceLocation srcTexId;
    private ResourceLocation dstTexId;
    private int srcTexW;
    private int srcTexH;
    private int dstTexW;
    private int dstTexH;

    // 布局
    private int panelTop;
    private int panelBottom;
    private int leftPanelX;
    private int leftPanelW;
    private int rightPanelX;
    private int rightPanelW;
    private int gap;
    private int widgetW;
    private int widgetH;
    private int rowH;
    private int contentBottom;
    private boolean compactUi;
    /** 空间极紧（例如 854x480 窗口配自动 GUI 缩放 = 逻辑 427x240）时的最窄档。 */
    private boolean terseUi;

    // 参数
    private int blockWidth = Config.blockWidth;
    private boolean autoHeight = Config.autoHeight;
    private int blockHeight = Config.blockHeight;
    private boolean crop = Config.crop;
    private boolean enhance = Config.enhance;
    private int colorMode = Config.colorMode;
    private boolean levelUnder = Config.levelUnder;
    private int groundY;
    /** 铺设速度：每 tick 发多少条指令。值越大铺得越快，但太大会给服务端和帧率压力。 */
    private int commandsPerTick = Config.commandsPerTick;

    private EditBox widthBox;
    private EditBox heightBox;
    private EditBox pathBox;
    private Button browseButton;
    private Button loadPathButton;
    private Button prevButton;
    private Button nextButton;
    private Button autoHeightButton;
    private Button colorModeButton;
    private Button speedButton;
    private Button cropButton;
    private Button enhanceButton;
    private Button levelButton;
    private Button paletteButton;
    private Button buildButton;

    private long lastConvertRequest;

    public PixelFloorScreen() {
        super(TITLE);
    }

    // ---------------------------------------------------------------- 布局

    private void computeLayout() {
        int margin = Math.max(6, Math.min(16, width / 60));
        int colGap = Math.max(8, Math.min(16, width / 70));

        leftPanelX = margin;
        panelTop = 26;
        panelBottom = height - 26;

        int usable = width - margin * 2 - colGap;
        leftPanelW = Math.max(140, (int) (usable * 0.56));
        rightPanelX = leftPanelX + leftPanelW + colGap;
        rightPanelW = Math.max(120, width - margin - rightPanelX);
        widgetW = rightPanelW;

        int budget = panelBottom - panelTop;
        int[][] presets = {{20, 4}, {18, 3}, {16, 2}};
        compactUi = false;
        terseUi = false;

        // 每档实测需要的行数（含各行类型差异）：
        //   完整档 11 行：路径/对话框/翻页 + 宽高 + 高度自动/算法/速度 + 增强/地面/裁剪/调色板 + 铺设
        //   紧凑档  9 行：路径/对话框/翻页 + 宽高 + 高度自动/算法/速度 + 增强/地面 + 铺设
        //   精简档  5 行：路径 + 高度自动 + 算法 + 速度 + 铺设（省掉对话框、翻页、尺寸输入、所有附加项）
        int[][] rowPlan = {{3, 1, 4, 1, 2}, {3, 1, 3, 1, 1}, {1, 0, 3, 0, 1}};

        for (int i = 0; i < presets.length; i++) {
            widgetH = presets[i][0];
            gap = presets[i][1];
            rowH = widgetH + gap;
            compactUi = i == presets.length - 1;
            int[] plan = rowPlan[i];
            int bottom = panelTop
                + 18 + rowH * plan[0]          // 路径 + 对话框 + 翻页
                + 18 + rowH * plan[1]          // 宽高输入框
                + rowH * plan[2]               // 开关类
                + rowH * plan[3]               // 附加项
                + 22;                          // 铺设按钮
            if (bottom - panelTop <= budget) {
                contentBottom = bottom;
                terseUi = false;
                return;
            }
        }

        // 连紧凑档都放不下：进入精简档。这通常意味着 GUI 缩放偏大
        // （854x480 自动缩放会得到 427x240 的逻辑空间，纵向只有 214 像素）。
        compactUi = true;
        terseUi = true;
        widgetH = 16;
        gap = 2;
        rowH = widgetH + gap;
        contentBottom = panelTop + 18 + rowH * 5 + 8;
        if (contentBottom > panelBottom) {
            // 极端情况兜底，保证按钮不会跑到屏幕外
            contentBottom = panelBottom;
        }
    }

    @Override
    protected void init() {
        // 目录列表（不依赖任何系统对话框）
        folders = ImageFinder.commonFolders();
        if (currentFolder == null && !folders.isEmpty()) {
            currentFolder = folders.get(0);
            for (ImageFinder.Folder f : folders) {
                if (!ImageFinder.listImages(f.path()).isEmpty()) {
                    currentFolder = f;
                    break;
                }
            }
        }
        if (selectedFile == null && !Config.lastImage.isEmpty()) {
            Path p = Path.of(Config.lastImage);
            if (Files.isRegularFile(p)) {
                selectedFile = p;
            }
        }
        refreshFileList();

        computeLayout();

        // --- 顶部目录条 ---
        int fx = leftPanelX;
        int fy = panelTop;
        int fh = Math.max(14, Math.min(18, widgetH - 4));
        for (ImageFinder.Folder folder : folders) {
            int labelW = font.width(folder.label()) + 8;
            if (fx + labelW > leftPanelX + leftPanelW && fx > leftPanelX) {
                break;
            }
            final ImageFinder.Folder target = folder;
            addRenderableWidget(Button.builder(Component.literal(folder.label()),
                b -> switchFolder(target)).bounds(fx, fy, labelW, fh).build());
            fx += labelW + 3;
        }

        int x = rightPanelX;
        int w = widgetW;
        int y = panelTop;

        // 精简档里空间只够放路径输入框本身，所以"载入"按钮并排、宽度压窄
        int pathBoxW = terseUi ? Math.max(40, w - 34) : Math.max(50, w - 54);
        pathBox = new EditBox(font, x, y, pathBoxW, 18,
            Component.literal("图片路径"));
        // 注意顺序：EditBox 的 maxLength 默认只有 32 字符，而图片路径经常更长。
        // 必须在 setResponder 之前放宽，否则 responder 收到的是被截断的值。
        pathBox.setMaxLength(400);
        pathBox.setHint(Component.literal(terseUi ? "粘贴图片路径" : "也可粘贴图片路径"));
        pathBox.setResponder(s -> {
            Path p = ImageFinder.parseUserPath(s);
            if (p != null && !p.equals(selectedFile)) {
                loadImage(p, false);
            }
        });
        if (selectedFile != null) {
            pathBox.setValue(selectedFile.toAbsolutePath().toString());
        }
        if (terseUi) {
            // 精简档没有单独的系统对话框按钮行，把它挂成路径框的提示，
            // 中键/悬停即可知道还有这条备用路径
            pathBox.setTooltip(Tooltip.create(Component.literal(
                "直接粘贴图片完整路径，回车即载入。\n左栏目录按钮可直接点选图片。")));
        }
        addRenderableWidget(pathBox);

        loadPathButton = Button.builder(Component.literal("载入"), b -> {
            Path p = ImageFinder.parseUserPath(pathBox.getValue());
            if (p == null) {
                setStatus("路径找不到图片文件，检查是否写全（含扩展名）", true);
            } else {
                loadImage(p, true);
            }
        }).bounds(x + pathBoxW + 4, y, terseUi ? 30 : 50, 18).build();
        addRenderableWidget(loadPathButton);
        y += 18 + gap + 2;

        if (!terseUi) {
            browseButton = Button.builder(
                Component.literal("系统文件对话框（可能不可用）"), b -> openFileDialog()
            ).bounds(x, y, w, widgetH).build();
            addRenderableWidget(browseButton);
            y += rowH + 2;

            int arrowW = Math.max(18, Math.min(24, w / 4));
            prevButton = Button.builder(Component.literal("◀"), b -> stepSelection(-1))
                .bounds(x, y, arrowW, widgetH).build();
            nextButton = Button.builder(Component.literal("▶"), b -> stepSelection(1))
                .bounds(x + w - arrowW, y, arrowW, widgetH).build();
            addRenderableWidget(prevButton);
            addRenderableWidget(nextButton);
            y += rowH + 2;
        }

        // 尺寸输入框：精简档放不下，改用上次保存的宽度（下面的速度/算法仍可调）
        if (!terseUi) {
            int boxGap = 6;
            int halfW = (w - boxGap) / 2;
            widthBox = new EditBox(font, x, y, halfW, 18, Component.literal("宽度"));
            widthBox.setValue(String.valueOf(blockWidth));
            widthBox.setResponder(s -> {
                Integer v = parseInt(s);
                if (v != null && v >= 8 && v <= 2048) {
                    blockWidth = v;
                    scheduleConvert();
                }
            });
            addRenderableWidget(widthBox);

            heightBox = new EditBox(font, x + halfW + boxGap, y, halfW, 18,
                Component.literal("高度"));
            heightBox.setValue(String.valueOf(blockHeight));
            heightBox.setResponder(s -> {
                Integer v = parseInt(s);
                if (v != null && v >= 8 && v <= 2048) {
                    blockHeight = v;
                    scheduleConvert();
                }
            });
            addRenderableWidget(heightBox);
            y += 18 + gap + 2;
        }

        autoHeightButton = Button.builder(autoHeightLabel(), b -> {
            autoHeight = !autoHeight;
            b.setMessage(autoHeightLabel());
            if (heightBox != null) {
                heightBox.setEditable(!autoHeight);
                heightBox.setVisible(!autoHeight);
            }
            scheduleConvert();
        }).bounds(x, y, w, widgetH).build();
        addRenderableWidget(autoHeightButton);
        if (heightBox != null) {
            heightBox.setEditable(!autoHeight);
            heightBox.setVisible(!autoHeight);
        }
        y += rowH;

        colorModeButton = Button.builder(Component.literal(modeLabel()), b -> {
            colorMode = (colorMode + 1) % 3;
            Palette.invalidateCache();
            b.setMessage(Component.literal(modeLabel()));
            scheduleConvert();
        }).bounds(x, y, w, widgetH).build();
        addRenderableWidget(colorModeButton);
        y += rowH;

        // --- 铺设速度 ---
        // 放在紧凑模式之外，因为它是"铺得动不动"的关键项，空间再紧也要留着
        speedButton = Button.builder(speedLabel(), b -> {
            commandsPerTick = nextSpeed(commandsPerTick);
            FloorBuilder.setCommandsPerTick(commandsPerTick);
            b.setMessage(speedLabel());
            b.setTooltip(Tooltip.create(Component.literal(speedTooltip())));
        }).bounds(x, y, w, widgetH).build();
        speedButton.setTooltip(Tooltip.create(Component.literal(speedTooltip())));
        addRenderableWidget(speedButton);
        y += rowH;

        if (!compactUi) {
            enhanceButton = Button.builder(enhanceLabel(), b -> {
                enhance = !enhance;
                b.setMessage(enhanceLabel());
                scheduleConvert();
            }).bounds(x, y, w, widgetH).build();
            addRenderableWidget(enhanceButton);
            y += rowH;

            levelButton = Button.builder(levelLabel(), b -> {
                levelUnder = !levelUnder;
                b.setMessage(levelLabel());
            }).bounds(x, y, w, widgetH).build();
            addRenderableWidget(levelButton);
            y += rowH;

            cropButton = Button.builder(cropLabel(), b -> {
                crop = !crop;
                b.setMessage(cropLabel());
                scheduleConvert();
            }).bounds(x, y, w, widgetH).build();
            addRenderableWidget(cropButton);
            y += rowH;

            paletteButton = Button.builder(
                Component.literal("调色板：64 色（点击查看）"), b -> showPalette())
                .bounds(x, y, w, widgetH).build();
            addRenderableWidget(paletteButton);
            y += rowH + 4;
        }

        buildButton = Button.builder(Component.literal("▶ 开始铺设"), b -> doBuild())
            .bounds(x, y, w, 22).build();
        addRenderableWidget(buildButton);
        buildButton.active = result != null;
        contentBottom = y + 22;

        if (terseUi) {
            setStatus("界面空间很紧（当前 " + width + "x" + height + " 逻辑像素）："
                + "已隐藏尺寸输入、翻页、系统对话框等项，"
                + "画幅沿用上次保存的 " + blockWidth + " 宽。"
                + "想看到完整界面，把窗口拉大或把 GUI 缩放调到 1。", false);
        } else if (compactUi) {
            setStatus("界面空间紧张：已隐藏裁剪与调色板按钮（用默认值）。"
                + "想看到全部选项，把窗口拉大或把 GUI 缩放调小。", false);
        }
        if (result == null && selectedFile != null) {
            scheduleConvert();
        }
    }

    // ---------------------------------------------------------------- 文件

    private void refreshFileList() {
        List<Path> listed = ImageFinder.listImages(
            currentFolder == null ? null : currentFolder.path());
        Path own = Config.getImagesDir();
        if (own != null && (currentFolder == null || !own.equals(currentFolder.path()))) {
            for (Path p : ImageFinder.listImages(own)) {
                if (!listed.contains(p)) {
                    listed.add(p);
                }
            }
        }
        imageFiles.clear();
        imageFiles.addAll(listed);
    }

    private void switchFolder(ImageFinder.Folder folder) {
        currentFolder = folder;
        listScroll = 0;
        refreshFileList();
        if (selectedFile != null) {
            selectedIndex = imageFiles.indexOf(selectedFile);
        }
        setStatus("已切换到「" + folder.label() + "」，共 " + imageFiles.size() + " 张图片", false);
    }

    private void stepSelection(int delta) {
        if (imageFiles.isEmpty()) {
            return;
        }
        if (selectedIndex < 0) {
            selectedIndex = 0;
        } else {
            selectedIndex = (selectedIndex + delta + imageFiles.size()) % imageFiles.size();
        }
        selectFile(imageFiles.get(selectedIndex));
    }

    private void selectFile(Path path) {
        selectedFile = path;
        updateButtons();
        scheduleConvert();
    }

    /** 从路径载入。接收 Path 而不是字符串，避免经过文本框被截断。 */
    private void loadImage(Path path, boolean importCopy) {
        Path actual = path;
        if (importCopy) {
            try {
                actual = Config.importImage(path);
                refreshFileList();
                selectedIndex = imageFiles.indexOf(actual);
            } catch (Exception e) {
                PixelFloor.LOGGER.warn("复制图片失败，改用原文件: {}", e.toString());
                actual = path;
            }
        }
        PixelFloor.rememberImage(actual);
        selectFile(actual);
        if (pathBox != null) {
            pathBox.setValue(actual.toAbsolutePath().toString());
        }
        setStatus("已选中：" + actual.getFileName() + "，正在转换…", false);
    }

    /**
     * 系统文件对话框（可选功能）。
     *
     * <p>实测在某些环境里 AWT 对话框在游戏进程内显示不出来，所以主路径改用
     * 游戏内目录点选 + 路径输入框。这里保留是为了碰运气，并且用 catch(Throwable)
     * 兜住——不然后台线程静默死掉，日志里什么都看不到，极难排查。
     */
    private void openFileDialog() {
        PixelFloor.LOGGER.info("打开系统文件对话框…");
        Thread t = new Thread(() -> {
            try {
                java.awt.FileDialog dialog = new java.awt.FileDialog(
                    (java.awt.Frame) null, "选择一张图片", java.awt.FileDialog.LOAD);
                // Windows 上 setFilenameFilter 会被忽略，必须用 setFile 通配符
                dialog.setFile("*.png;*.jpg;*.jpeg;*.bmp;*.gif");
                dialog.setMultipleMode(false);
                dialog.setLocationRelativeTo(null);
                if (currentFolder != null) {
                    dialog.setDirectory(currentFolder.path().toString());
                }
                dialog.setVisible(true);
                String dir = dialog.getDirectory();
                String file = dialog.getFile();
                if (dir == null || file == null || file.isEmpty()) {
                    PixelFloor.LOGGER.info("用户取消了文件选择");
                    return;
                }
                Path chosen = Path.of(dir, file);
                Minecraft.getInstance().execute(() -> loadImage(chosen, true));
            } catch (Throwable e) {
                PixelFloor.LOGGER.error("系统文件对话框不可用，请改用左栏目录点选或路径输入框", e);
                Minecraft.getInstance().execute(() -> setStatus(
                    "系统文件对话框在这台机器上不可用。请用左栏目录点选，或粘贴路径到输入框。", true));
            }
        }, "pixelfloor-file-dialog");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 转换

    private void scheduleConvert() {
        if (selectedFile == null) {
            return;
        }
        lastConvertRequest = System.currentTimeMillis();
        long token = lastConvertRequest;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(180);
            } catch (InterruptedException e) {
                return;
            }
            if (token != lastConvertRequest) {
                return;
            }
            doConvertNow(token);
        }, "pixelfloor-convert");
        t.setDaemon(true);
        t.start();
    }

    private void doConvertNow(long token) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            converting = true;
            updateButtons();
        });

        FloorImage.Options o = new FloorImage.Options();
        o.blockWidth = blockWidth;
        o.blockHeight = blockHeight;
        o.autoHeight = autoHeight;
        o.crop = crop;
        o.enhance = enhance;
        o.colorMode = colorMode;

        try {
            FloorImage converted = FloorImage.load(selectedFile.toFile(), o);
            if (token != lastConvertRequest) {
                return;
            }
            mc.execute(() -> {
                result = converted;
                converting = false;
                PixelFloor.setLastImage(converted);
                groundY = mc.player == null ? 64 : FloorBuilder.findGroundY(mc.player);
                uploadTextures(converted);
                setStatus(String.format(
                    "%d x %d = %d 块 · 平均色差 ΔE %.2f · 局部色调 ΔE %.2f · 用色 %d 种",
                    converted.cols, converted.rows, converted.cols * converted.rows,
                    converted.meanDeltaE, converted.blockDeltaE, converted.distinctBlocks()),
                    false);
                updateButtons();
            });
        } catch (Exception e) {
            PixelFloor.LOGGER.error("转换失败", e);
            mc.execute(() -> {
                converting = false;
                setStatus("转换失败：" + e.getMessage(), true);
                updateButtons();
            });
        }
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusIsError = error;
    }

    private void updateButtons() {
        if (buildButton != null) {
            buildButton.active = result != null && !FloorBuilder.isBuilding();
        }
    }

    private void showPalette() {
        StringBuilder sb = new StringBuilder("调色板 " + Palette.size() + " 色：");
        String last = "";
        for (Palette.Entry e : Palette.all()) {
            if (!e.kind.equals(last)) {
                last = e.kind;
                sb.append("\n§7").append(e.zhName.substring(Math.max(0, e.zhName.length() - 3)))
                  .append("：§r");
            }
            sb.append(e.color).append(' ');
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(sb.toString()), false);
        }
    }

    // ---------------------------------------------------------------- 贴图

    private void uploadTextures(FloorImage image) {
        releaseTextures();
        Minecraft mc = Minecraft.getInstance();
        srcTexW = image.sourceImage.getWidth();
        srcTexH = image.sourceImage.getHeight();
        srcTex = toDynamicTexture(image.sourceImage);
        srcTexId = ResourceLocation.fromNamespaceAndPath(PixelFloor.MODID, "runtime/source");
        mc.getTextureManager().register(srcTexId, srcTex);

        BufferedImage prev = scaleNearest(image.toPreviewImage(), PREVIEW_MAX, PREVIEW_MAX);
        dstTexW = prev.getWidth();
        dstTexH = prev.getHeight();
        dstTex = toDynamicTexture(prev);
        dstTexId = ResourceLocation.fromNamespaceAndPath(PixelFloor.MODID, "runtime/floor");
        mc.getTextureManager().register(dstTexId, dstTex);
    }

    private void releaseTextures() {
        Minecraft mc = Minecraft.getInstance();
        if (srcTexId != null) {
            mc.getTextureManager().release(srcTexId);
            srcTexId = null;
        }
        if (dstTexId != null) {
            mc.getTextureManager().release(dstTexId);
            dstTexId = null;
        }
        srcTex = null;
        dstTex = null;
    }

    private static BufferedImage scaleNearest(BufferedImage src, int maxW, int maxH) {
        double s = Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight());
        if (s <= 0) {
            s = 1;
        }
        int w = Math.max(1, (int) Math.round(src.getWidth() * s));
        int h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static DynamicTexture toDynamicTexture(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                // 1.21.4 的 NativeImage 只有 setPixel，颜色按 0xAABBGGRR（ABGR）打包
                ni.setPixel(x, y, (0xFF << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return new DynamicTexture(ni);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int leftX = leftPanelX;
        int leftW = leftPanelW;
        graphics.drawCenteredString(font, TITLE, width / 2, 8, ACCENT);

        int bodyTop = panelTop + 20;
        int boxGap = Math.max(6, leftW / 20);
        int boxW = (leftW - boxGap) / 2;
        int listReserve = Math.min(90, Math.max(40, height / 6));
        int boxH = Math.max(40, Math.min(400, panelBottom - bodyTop - listReserve - 26));
        int boxTop = bodyTop + 12;

        graphics.drawString(font, LBL_SOURCE, leftX, bodyTop + 1, TEXT_DIM, false);
        graphics.drawString(font, LBL_FLOOR, leftX + boxW + boxGap, bodyTop + 1, TEXT_DIM, false);
        drawPanel(graphics, leftX, boxTop, boxW, boxH);
        drawPanel(graphics, leftX + boxW + boxGap, boxTop, boxW, boxH);

        if (srcTexId != null && srcTexW > 0) {
            drawFitted(graphics, srcTexId, srcTexW, srcTexH, leftX + 2, boxTop + 2,
                boxW - 4, boxH - 4);
        } else {
            graphics.drawCenteredString(font, NO_IMAGE, leftX + boxW / 2,
                boxTop + boxH / 2, TEXT_DIM);
        }
        if (dstTexId != null && dstTexW > 0) {
            drawFitted(graphics, dstTexId, dstTexW, dstTexH,
                leftX + boxW + boxGap + 2, boxTop + 2, boxW - 4, boxH - 4);
        } else if (converting) {
            graphics.drawCenteredString(font, LBL_CONVERTING,
                leftX + boxW + boxGap + boxW / 2, boxTop + boxH / 2, TEXT_DIM);
        }

        // 实时地面高度与耗时预估
        if (result != null) {
            graphics.drawString(font,
                Component.literal("将铺在 Y=" + groundY + "，占地 " + result.cols
                    + " x " + result.rows + " 格"),
                leftX, boxTop + boxH + 2, ACCENT, false);
            int cmds = estimateCommands(result);
            double secs = cmds / (double) (commandsPerTick * 20);
            String eta = secs >= 90
                ? String.format("%.1f 分钟", secs / 60)
                : String.format("%.0f 秒", secs);
            graphics.drawString(font,
                Component.literal("预计 " + cmds + " 条指令，约 " + eta
                    + "（速度 " + commandsPerTick + " 条/tick）"),
                leftX, boxTop + boxH + 2 + font.lineHeight, 0xFFFFD070, false);
        }

        // --- 图片列表 ---
        // 上方有"地面高度"和"预计耗时"两行文字（见上面 result != null 的分支），
        // 所以起始位置要留出其中较高的一行，避免压字
        int infoLines = result != null ? 2 : 0;
        int listY = boxTop + boxH + 4 + Math.max(1, infoLines) * (font.lineHeight + 1);
        graphics.drawString(font,
            Component.literal("点名字即可选中（共 " + imageFiles.size() + " 张）"),
            leftX, listY, TEXT_DIM, false);
        listY += font.lineHeight + 1;
        int rowHeight = font.lineHeight + 1;
        int maxRows = Math.max(1, (panelBottom - 12 - listY) / rowHeight);
        int start = Math.min(listScroll, Math.max(0, imageFiles.size() - maxRows));

        listTop = listY;
        listRowH = rowHeight;
        listRows = maxRows;
        listStart = start;
        listLeft = leftX;
        listWidth = leftW;

        for (int i = 0; i < maxRows && start + i < imageFiles.size(); i++) {
            int idx = start + i;
            Path p = imageFiles.get(idx);
            boolean sel = idx == selectedIndex;
            boolean hover = mouseX >= leftX && mouseX <= leftX + leftW
                && mouseY >= listY && mouseY < listY + rowHeight;
            if (sel) {
                graphics.fill(leftX - 2, listY - 1, leftX + leftW, listY + rowHeight - 2, PANEL);
            } else if (hover) {
                graphics.fill(leftX - 2, listY - 1, leftX + leftW, listY + rowHeight - 2,
                    0x20FFFFFF);
            }
            String name = font.plainSubstrByWidth(
                p.getFileName().toString(), Math.max(20, leftW - 6));
            graphics.drawString(font, Component.literal(name), leftX, listY,
                sel ? ACCENT : (hover ? 0xFFFFFF80 : TEXT), false);
            listY += rowHeight;
        }

        // --- 状态栏 ---
        int statusY = height - font.lineHeight - 4;
        graphics.drawString(font,
            Component.literal(font.plainSubstrByWidth(status, width - leftX * 2)),
            leftX, statusY, statusIsError ? 0xFF8080 : TEXT, false);
        if (FloorBuilder.isBuilding()) {
            graphics.drawString(font, Component.literal(
                    "铺设中… 剩余 " + FloorBuilder.remaining() + " 条指令"),
                leftX, statusY - font.lineHeight - 1, ACCENT, false);
        }
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, BG);
        graphics.renderOutline(x, y, w, h, PANEL);
    }

    private void drawFitted(GuiGraphics graphics, ResourceLocation tex,
                            int texW, int texH, int x, int y, int w, int h) {
        float scale = Math.min(w / (float) texW, h / (float) texH);
        int dw = Math.max(1, (int) (texW * scale));
        int dh = Math.max(1, (int) (texH * scale));
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        // 1.21.4 的 blit 第一个参数是"贴图 -> 渲染类型"的函数
        graphics.blit(RenderType::guiTextured, tex, dx, dy, 0.0F, 0.0F,
            dw, dh, texW, texH);
    }

    // ---------------------------------------------------------------- 事件

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listLeft && mouseX <= listLeft + listWidth
            && mouseY >= listTop) {
            int row = (int) ((mouseY - listTop) / Math.max(1, listRowH));
            if (row >= 0 && row < listRows) {
                int idx = listStart + row;
                if (idx >= 0 && idx < imageFiles.size()) {
                    selectedIndex = idx;
                    Path p = imageFiles.get(idx);
                    PixelFloor.rememberImage(p);
                    selectFile(p);
                    setStatus("已选中：" + p.getFileName(), false);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < rightPanelX) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void doBuild() {
        if (result == null) {
            return;
        }
        PixelFloor.applySettings(blockWidth, autoHeight, blockHeight, crop, enhance,
            colorMode, Config.baseBlock, levelUnder, commandsPerTick);
        Component msg = PixelFloor.startBuild(result);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(msg, false);
        }
        onClose();
    }

    @Override
    public void onClose() {
        PixelFloor.applySettings(blockWidth, autoHeight, blockHeight, crop, enhance,
            colorMode, Config.baseBlock, levelUnder, commandsPerTick);
        releaseTextures();
        super.onClose();
    }

    @Override
    public void removed() {
        releaseTextures();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component autoHeightLabel() {
        return Component.literal("高度自动：" + (autoHeight ? "开" : "关"));
    }

    private Component cropLabel() {
        return Component.literal("居中裁剪：" + (crop ? "开（不变形）" : "关（拉伸）"));
    }

    private Component enhanceLabel() {
        return Component.literal("增强对比：" + (enhance ? "开" : "关"));
    }

    private Component levelLabel() {
        return Component.literal("地下垫实：" + (levelUnder ? "开（防悬空）" : "关"));
    }

    private String modeLabel() {
        return "算法：" + Palette.modeName(colorMode).split("（")[0];
    }

    /** 可选的速度档位：每 tick 发多少条指令。 */
    private static final int[] SPEEDS = {6, 12, 24, 48};

    private static int nextSpeed(int current) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == current) {
                return SPEEDS[(i + 1) % SPEEDS.length];
            }
        }
        return 12; // 配置里是个非标准值时的兜底
    }

    private Component speedLabel() {
        return Component.literal("速度：" + commandsPerTick + " 条/tick"
            + (commandsPerTick <= 6 ? "（稳）" : commandsPerTick >= 48 ? "（最快）" : ""));
    }

    private String speedTooltip() {
        int perSec = commandsPerTick * 20;
        StringBuilder sb = new StringBuilder();
        sb.append("每 tick 发 ").append(commandsPerTick)
          .append(" 条指令，约 ").append(perSec).append(" 条/秒\n");
        if (result != null) {
            int cmds = estimateCommands(result);
            sb.append("当前画幅预计 ").append(cmds).append(" 条，")
              .append(String.format("约 %.0f 秒", cmds / (double) perSec)).append('\n');
        }
        sb.append("\n档位：6 → 12 → 24 → 48\n");
        sb.append("发太快的话，方块更新会挤在同一刻，容易卡顿；")
          .append("联机时还可能被反作弊判定为刷屏。12~24 是速度与稳定的平衡点。");
        return sb.toString();
    }

    /**
     * 估算铺设需要多少条指令：只做行内同色合并，和 FloorBuilder 的口径一致。
     *
     * <p>垫底部分每行一条，所以还要加上行数。
     */
    private static int estimateCommands(FloorImage img) {
        int cmds = 0;
        for (int row = 0; row < img.rows; row++) {
            int col = 0;
            while (col < img.cols) {
                int v = img.blockIndex[row][col];
                int run = 1;
                while (col + run < img.cols && img.blockIndex[row][col + run] == v) {
                    run++;
                }
                cmds++;
                col += run;
            }
        }
        if (Config.levelUnder) {
            cmds += img.rows;
        }
        return cmds;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
