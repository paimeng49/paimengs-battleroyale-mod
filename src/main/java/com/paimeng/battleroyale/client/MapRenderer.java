package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.MapManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.List;

/**
 * C3-1：客户端地图渲染。
 *
 * <p>数据源只有一个：{@link ClientMapCache}（C2 从服务端按 Tile 收到的 ARGB 底图）。
 * 本类不读 {@code MapThumbnailGenerator}，不画毒圈、标点、队友。
 *
 * <p><b>方向约定：完整复用旧代码（MapHudRenderer 142~145 行 / FullscreenMapScreen 102~105 行）
 * 已经修好的换算公式，禁止改动</b>：
 * <pre>
 *   px = (worldX - originX) / step     // 底图像素列，屏幕 X 方向
 *   pz = (worldZ - originZ) / step     // 底图像素行，屏幕 Y 方向（向下）
 * </pre>
 * 也就是「X 增大向右、Z 增大向下」，地图保持上北下南：玩家往北走（Z 减小）时
 * pz 减小，箭头在地图上向上移动。箭头角度同样沿用 {@code yaw + 180}（MC 的 yaw=0 指南）。
 */
@Environment(EnvType.CLIENT)
public final class MapRenderer {
    /** 没有 Tile 数据的区域：深灰（与 C1 未生成色一致）。 */
    private static final int MISSING_COLOR = 0xFF1A1A1A;
    private static final int ARROW_OUTLINE = 0xFF000000;
    private static final int ARROW_FILL = 0xFFFFFFFF;
    /** 物资点标记：金色 2x2 小方块 + 1 像素黑色描边（深浅底色上都醒目）。 */
    private static final int LOOT_FILL = 0xFFFFD24A;
    private static final int LOOT_OUTLINE = 0xFF000000;
    // [BR-FLIGHT] 航线：白色粗线 + 黄色起点实心块 / 终点空心块。
    private static final int FLIGHT_LINE = 0xE6FFFFFF;
    private static final int FLIGHT_MARK = 0xFFFFD24A;
    /** 航线逐像素步进的上限：极端缩放时也不会超过这个 stamp 数（防卡顿）。 */
    private static final int MAX_FLIGHT_STEPS = 4096;
    // ---- [BR-ZONE] 模块四：毒圈图层（HUD 小地图 + 全屏地图共用同一套画法） ----
    /** 蓝圈（当前边界）：亮蓝半透，不盖住底图。 */
    private static final int ZONE_BLUE = 0xE64C9AFF;
    /** 白圈（下个安全区）：纯白半透。 */
    private static final int ZONE_WHITE = 0xE6FFFFFF;
    /** 圈外玩家的“进圈指引”虚线：白色（比白圈更亮一档，斜着走最显眼）。 */
    private static final int ZONE_GUIDE = 0xF2FFFFFF;
    /** 虚线一个“划”的长度（屏幕像素）：划 3 停 3。 */
    private static final int ZONE_DASH_ON = 3;
    private static final int ZONE_DASH_OFF = 3;
    /** 圆环逐像素判定的预算：外接正方形像素数超过它就整体放大扫描步距（stamp 首尾相接，圈仍连续）。 */
    private static final long ZONE_SCAN_BUDGET = 1_000_000L;
    /** 虚线逐像素步进的 stamp 上限：超远指引自动放大步长（步长始终按屏幕像素算，不按方块）。 */
    private static final int ZONE_GUIDE_MAX_STEPS = 2048;
    /** 圆环线宽下限（屏幕像素）：缩得再小也保持 2px，远看是一条连贯的线。 */
    private static final double ZONE_LINE_MIN_WIDTH = 2.0;
    /** 圆环线宽上限（屏幕像素）：大圈不必无限加粗。 */
    private static final double ZONE_LINE_MAX_WIDTH = 5.0;
    /** HUD 小地图：边长 100、距屏幕边缘 8（沿用旧版尺寸）。 */
    private static final int HUD_SIZE = 100;
    private static final int HUD_EDGE = 8;
    /** HUD 视野：玩家周围 100x100 个世界方块。 */
    private static final int HUD_VIEW_BLOCKS = 100;
    // ---- Tile 查找缓存（渲染只在客户端线程执行，无需同步） ----
    private static String lookedUpMapId = "";
    private static ClientMapCache.Meta lookedUpMeta;

    private MapRenderer() {
    }

    /** 底图元数据是否已就绪（至少收到过一个带数据的 Tile）。 */
    public static boolean hasBaseMap() {
        ClientMapCache.Meta meta = currentMeta();
        return meta != null && meta.step() > 0 && meta.imageWidth() > 0 && meta.imageHeight() > 0;
    }

    /** 底图宽 / 高（像素）。未就绪时为 0。 */
    public static int baseWidth() {
        ClientMapCache.Meta meta = currentMeta();
        return meta == null ? 0 : meta.imageWidth();
    }

    public static int baseHeight() {
        ClientMapCache.Meta meta = currentMeta();
        return meta == null ? 0 : meta.imageHeight();
    }

    /** 全屏地图适配缩放：整幅底图按比例装进给定区域（保持宽高比）。 */
    public static double fitZoom(int regionWidth, int regionHeight) {
        ClientMapCache.Meta meta = currentMeta();
        if (meta == null || meta.imageWidth() <= 0 || meta.imageHeight() <= 0) {
            return 1.0;
        }
        return Math.max(0.02, Math.min((double) regionWidth / meta.imageWidth(),
                (double) regionHeight / meta.imageHeight()));
    }

    // ==================== HUD 小地图 ====================

    /**
     * 渲染 HUD 小地图（右上角）：玩家固定在中心，底图跟着玩家移动。
     *
     * <p>[r128 统一矩阵坐标系] HUD 与全屏地图改用同一条矩阵链：底图、航线、毒圈、
     * 物资点、玩家箭头<b>全部画在「底图像素坐标系」里</b>，缩放与玩家居中由矩阵统一施加：
     * <pre>
     *   translate(盒中心)                     ← 玩家像素锚点固定在盒中心
     *   scale(zoom)                           ← 1 底图像素 = zoom 屏幕像素
     *   translate(-centerPx, -centerPz)       ← 玩家所在底图像素落到原点
     * </pre>
     * 旧版这里底图走整数取整的 blit、矢量层走另一套小数屏幕坐标，是「两个坐标系」——
     * 玩家移动时标记相对底图滑位（乱飞）。现在底图与所有图层过同一个矩阵，
     * 锚定关系由矩阵保证恒等；屏幕恒定尺寸（标记 / 线宽 / 文字 / 箭头）
     * 用 {@code 1/zoom} 反缩放，与全屏地图完全同款（unitScale = zoom）。
     */
    public static void renderMinimap(GuiGraphics graphics, Player player) {
        if (graphics == null || player == null) {
            return;
        }
        PoseStack matrices = graphics.pose();
        Minecraft client = Minecraft.getInstance();
        int left = client.getWindow().getGuiScaledWidth() - HUD_SIZE - HUD_EDGE;
        int top = HUD_EDGE;
        GuiDraw.fill(matrices, left, top, left + HUD_SIZE, top + HUD_SIZE, MISSING_COLOR);

        ClientMapCache.Meta meta = currentMeta();
        if (meta == null || meta.step() <= 0 || meta.imageWidth() <= 0 || meta.imageHeight() <= 0) {
            return;
        }
        // 1 个底图像素 = step 个世界方块；HUD 把 1 个世界方块画成 1 个屏幕像素，
        // 所以 zoom = step（底图像素 → 屏幕像素）。
        double zoom = (double) HUD_SIZE / HUD_VIEW_BLOCKS * meta.step();
        double centerPx = (player.getX() - meta.originX()) / (double) Math.max(1, meta.step());
        double centerPz = (player.getZ() - meta.originZ()) / (double) Math.max(1, meta.step());
        if (!(zoom > 0.0) || !Double.isFinite(centerPx) || !Double.isFinite(centerPz)) {
            return;   // 垃圾变换绝不进矩阵（同全屏地图 r122 防线）
        }
        // 全局一层 scissor（HUD 盒，物理像素，与矩阵无关）：各层不再自己开闭 scissor。
        int guiScale = (int) Math.max(1.0D, client.getWindow().getGuiScale());
        RenderSystem.enableScissor(left * guiScale,
                (client.getWindow().getGuiScaledHeight() - (top + HUD_SIZE)) * guiScale,
                HUD_SIZE * guiScale, HUD_SIZE * guiScale);
        matrices.pushPose();
        try {
            matrices.translate(left + HUD_SIZE / 2.0, top + HUD_SIZE / 2.0, 0.0);
            matrices.scale((float) zoom, (float) zoom, 1.0f);
            matrices.translate(-centerPx, -centerPz, 0.0);

            // 底图：整幅纹理一次 blit，本地坐标 (0,0) 起、1 纹理像素 = 1 本地单位——
            // 与全屏地图逐字节同一条路径，不再有任何整数取整的第二坐标系。
            ResourceLocation texture = MapBaseTexture.currentTexture(meta);
            if (texture != null) {
                graphics.blit(texture, 0, 0, 0.0F, 0.0F,
                        meta.imageWidth(), meta.imageHeight(),
                        meta.imageWidth(), meta.imageHeight());
            }

            // HUD 盒可视范围换算回底图像素，仅供各层做“整段剔除”预判。
            int clip = (int) Math.ceil(HUD_SIZE / 2.0 / zoom) + 1;
            int clipLeft = (int) Math.floor(centerPx) - clip;
            int clipTop = (int) Math.floor(centerPz) - clip;
            int clipSize = clip * 2;

            // 航线层：底图之上、物资点之下（不跳伞也能在小地图看到飞行线）。
            drawFlightLine(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipSize, clipSize);
            // [BR-ZONE] 模块四：毒圈层（蓝圈 / 白圈 / 圈外虚线），维度不匹配自动整层不画。
            drawZoneOverlay(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipSize, clipSize);
            // 物资点画在底图之上、玩家箭头之下（箭头永远最上层不被遮挡）。
            drawLootPoints(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipSize, clipSize);
            // [BR-MINIMAP] 队友图标层：视野内精确位置、视野外按方位角贴边。
            double hudHalf = HUD_SIZE / 2.0 / zoom;
            drawTeammateIcons(matrices, meta, centerPx, centerPz, hudHalf, hudHalf, zoom);
            // 玩家箭头：锚点 = 玩家所在底图像素（矩阵保证贴着玩家地图位置），尺寸屏幕恒定。
            drawArrowAnchored(matrices, centerPx, centerPz, player.getYRot(), zoom);
        } finally {
            matrices.popPose();
            RenderSystem.disableScissor();
        }
    }

    // ==================== 全屏地图 ====================

    /**
     * [r127 统一矩阵坐标系] 渲染全屏地图：底图、航线、毒圈、物资点、玩家箭头
     * <b>全部塞进同一个 PoseStack</b>，图层内部只允许使用「底图像素坐标」
     * （像素 (0,0) = 本地图层原点），缩放与拖拽由这一条矩阵链统一施加：
     * <pre>
     *   translate(区域中心 + pan)   ← 拖拽偏移（屏幕像素，与底图同一平移量）
     *   scale(zoom)                 ← 滚轮缩放（以区域中心为锚）
     *   translate(-imageW/2, -imageH/2) ← 底图左上角落到原点，底图居中于区域
     * </pre>
     * 任何图层都不许再自行乘 zoom 或引用鼠标坐标——锚定关系由矩阵保证恒等。
     * 屏幕恒定尺寸的标记（点/箭头/文字/线宽）用 {@code 1/zoom} 反缩放保持可读，
     * 但坐标一律走矩阵。裁剪用一层全局 scissor（物理像素，与矩阵无关）。
     */
    public static void renderFullscreenMap(GuiGraphics graphics, Player player,
                                           int regionLeft, int regionTop, int regionRight, int regionBottom,
                                           double zoom, double panX, double panY) {
        if (graphics == null) {
            return;
        }
        ClientMapCache.Meta meta = currentMeta();
        if (meta == null || meta.imageWidth() <= 0 || meta.imageHeight() <= 0) {
            return;
        }
        if (!(zoom > 0.0) || !Double.isFinite(zoom) || !Double.isFinite(panX) || !Double.isFinite(panY)) {
            return;   // 垃圾变换绝不进矩阵（历史上 OOM 放大器，同 r122 防线）
        }
        PoseStack matrices = graphics.pose();
        int regionWidth = regionRight - regionLeft;
        int regionHeight = regionBottom - regionTop;
        double centerX = regionLeft + regionWidth / 2.0;
        double centerY = regionTop + regionHeight / 2.0;

        Minecraft client = Minecraft.getInstance();
        int guiScale = (int) Math.max(1.0D, client.getWindow().getGuiScale());
        RenderSystem.enableScissor(regionLeft * guiScale,
                (client.getWindow().getGuiScaledHeight() - regionBottom) * guiScale,
                regionWidth * guiScale, regionHeight * guiScale);
        matrices.pushPose();
        try {
            matrices.translate(centerX + panX, centerY + panY, 0.0);
            matrices.scale((float) zoom, (float) zoom, 1.0f);
            matrices.translate(-meta.imageWidth() / 2.0, -meta.imageHeight() / 2.0, 0.0);

            // 底图：整幅纹理一次 blit，本地坐标 (0,0) 起、1 纹理像素 = 1 本地单位。
            ResourceLocation texture = MapBaseTexture.currentTexture(meta);
            if (texture != null) {
                graphics.blit(texture, 0, 0, 0.0F, 0.0F,
                        meta.imageWidth(), meta.imageHeight(),
                        meta.imageWidth(), meta.imageHeight());
            }

            // 区域矩形换算回本地地图像素，仅供各层做“整段剔除”预判。
            int clipLeft = (int) Math.floor((regionLeft - centerX - panX) / zoom + meta.imageWidth() / 2.0);
            int clipTop = (int) Math.floor((regionTop - centerY - panY) / zoom + meta.imageHeight() / 2.0);
            int clipRight = (int) Math.ceil((regionRight - centerX - panX) / zoom + meta.imageWidth() / 2.0);
            int clipBottom = (int) Math.ceil((regionBottom - centerY - panY) / zoom + meta.imageHeight() / 2.0);

            // 航线层：底图之上、物资点之下。
            drawFlightLine(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipRight - clipLeft, clipBottom - clipTop);
            // [BR-ZONE] 模块四：毒圈层与 HUD 小地图同一套画法（全屏地图上更好定位两个圈）。
            drawZoneOverlay(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipRight - clipLeft, clipBottom - clipTop);
            // 物资点层：底图之上、玩家箭头之下。
            drawLootPoints(matrices, meta, 0.0, 0.0, 1.0, zoom,
                    clipLeft, clipTop, clipRight - clipLeft, clipBottom - clipTop);
            // [BR-MINIMAP] 队友图标层：全屏地图同样画，视野外贴当前可视区边缘。
            drawTeammateIcons(matrices, meta,
                    (clipLeft + clipRight) / 2.0, (clipTop + clipBottom) / 2.0,
                    (clipRight - clipLeft) / 2.0, (clipBottom - clipTop) / 2.0, zoom);

            if (player != null) {
                double step = Math.max(1, meta.step());
                double px = (player.getX() - meta.originX()) / step;
                double pz = (player.getZ() - meta.originZ()) / step;
                drawArrowAnchored(matrices, px, pz, player.getYRot(), zoom);
            }
        } finally {
            matrices.popPose();
            RenderSystem.disableScissor();
        }
    }

    // ==================== 底图与矢量层共用几何工具 ====================

    /** [r127] 尺寸改 float：统一矩阵（非整数缩放）下不产生 int 截断跳位。 */
    private static void putQuad(BufferBuilder buffer, Matrix4f matrix, float x, float y,
                                float size, float sizeY, int color) {
        float alpha = (color >>> 24) / 255.0F;
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float x2 = x + size;
        float y2 = y + sizeY;
        buffer.vertex(matrix, x, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y2, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x2, y, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x, y, 0.0F).color(red, green, blue, alpha).endVertex();
    }

    /** 当前对局地图的底图元数据（origin / step / 像素尺寸）。 */
    private static ClientMapCache.Meta currentMeta() {
        String mapId = effectiveMapId();
        if (mapId.isEmpty()) {
            lookedUpMapId = "";
            lookedUpMeta = null;
            return null;
        }
        if (!mapId.equals(lookedUpMapId) || lookedUpMeta == null) {
            lookedUpMapId = mapId;
            lookedUpMeta = ClientMapCache.meta(mapId);
        }
        return lookedUpMeta;
    }

    /**
     * 当前底图认定的权威地图 ID：物资点通道的 mapId 优先，
     * 缺失 / 还没同步 / 查不到元数据时回退 Tile 包记录的 mapId——
     * 两条通道的 id 理论上同源，但任何一次错位都不该让底图“凭空变黑”，
     * 也不该连累队友图标层（[r143] 从 currentMeta 抽出共用，闸门与底图同源）。
     */
    private static String effectiveMapId() {
        String mapId = ClientLootPointsState.mapId();
        if (mapId == null || mapId.isEmpty() || ClientMapCache.meta(mapId) == null) {
            String gameMapId = ClientMapCache.cachedMapId();
            if (gameMapId != null && !gameMapId.isEmpty()) {
                mapId = gameMapId;
            }
        }
        return mapId == null ? "" : mapId;
    }

    /**
     * [r143][BR-MINIMAP] 队友图标层的独立可见性闸门。
     *
     * <p>旧版这里复用了物资点闸 {@code ClientLootPointsState.visibleIn}——
     * 那道闸要求“该地图收到过至少一个物资点”才放行，导致<b>没配置物资点的地图
     * 上队友图标整层被误杀</b>（实机症状：小地图正常、坐标包正常、就是没图标）。
     * 队友坐标只依赖服务端 teammate_pos 包 + 维度隔离，与物资点毫无关系，
     * 从此单独判定：
     * <ul>
     *   <li>当前维度必须是本模组的比赛维度（battleroyale_game 命名空间）——
     *       大厅 / 主世界 / 别的维度一律不画（淘汰回大厅、br leave 都不串图）；</li>
     *   <li>能解析出当前地图 ID 时，维度路径必须与它一致（一服多图不串队）；
     *       解析不出（Tile 兜底也失败）但已在比赛维度里时选择信任服务端发包。</li>
     * </ul>
     */
    private static boolean teammateLayerVisible(ResourceKey<Level> dim) {
        if (dim == null) {
            return false;
        }
        ResourceLocation value = dim.location();
        if (!GameWorldManager.DIMENSION_NAMESPACE.equals(value.getNamespace())) {
            return false;
        }
        String mapId = effectiveMapId();
        return mapId.isEmpty()
                || MapManager.safeId(mapId).toLowerCase().equals(value.getPath());
    }

    // ==================== 物资点 ====================

    /**
     * [BR-CRASH-AND-LOAD] 物资点标记 + 名字。
     *
     * <p>崩溃修复：本层不再自己 {@code buffer.begin()}。上一版在这里 begin 之后，
     * 如果所有点都在裁剪区外（出生岛附近没有物资点时必然如此）就跳过了
     * {@code Tesselator.draw()}，共享 BufferBuilder 停在 "building" 状态，
     * 紧接着画玩家箭头再 begin 就抛 {@code IllegalStateException: Already building!}。
     * 现在标记用 {@link DrawableHelper#fill} 自包含提交、文字用 {@link Font#draw}，
     * 全程不碰 buffer.begin()，从根源杜绝状态泄漏。
     *
     * <p>数据只读 {@link ClientLootPointsState} 本地缓存（服务端进图时推一次，不刷包）；
     * 坐标换算与底图 / 玩家箭头同一套公式：{@code px = (worldX - originX) / step}，
     * 再乘 zoom 落到屏幕。整盒（含名字行）完全在裁剪区外的点直接跳过，
     * 压边的点由 scissor 裁掉。
     */
    /**
     * [BR-CRASH-AND-LOAD] 物资点标记 + 名字。
     *
     * <p>[r127] 图层参数全面“单位化”：位置 = {@code originX + 地图像素 × scale}
     * （小地图：scale=zoom、画在屏幕坐标系；全屏地图：origin=(0,0)、scale=1，
     * 画在统一矩阵的地图像素坐标系，缩放/拖拽由矩阵负责）；所有屏幕恒定尺寸
     * （标记、线宽、文字）除以 {@code unitScale} 换算回绘制单位——小地图
     * {@code unitScale=1} 行为与旧版逐像素一致，全屏地图由此保证标记尺寸恒定
     * 而锚点永远贴着底图像素。裁剪由调用方一层全局 scissor 负责，本层只做剔除。
     *
     * <p>崩溃修复（保留）：全程不碰共享 buffer.begin()，标记用自包含
     * {@link GuiDraw#fillF}、文字用 {@link GuiDraw#drawTextWithShadow}，
     * 从根源杜绝 {@code Already building!}。
     */
    private static void drawLootPoints(PoseStack matrices, ClientMapCache.Meta meta, double originX,
                                       double originY, double scale, double unitScale,
                                       int clipLeft, int clipTop,
                                       int clipWidth, int clipHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !ClientLootPointsState.visibleIn(client.level.dimension())) {
            return;
        }
        List<LootPoint> points = ClientLootPointsState.points();
        if (points.isEmpty() || scale <= 0.0 || unitScale <= 0.0) {
            return;
        }
        double inv = 1.0 / unitScale;
        int clipRight = clipLeft + clipWidth;
        int clipBottom = clipTop + clipHeight;
        double step = Math.max(1, meta.step());
        Font text = client.font;
        for (LootPoint point : points) {
            double px = (point.x() - meta.originX()) / step;
            double pz = (point.z() - meta.originZ()) / step;
            double x = originX + px * scale;
            double y = originY + pz * scale;
            float labelScale = labelScale(point.fontSize());
            double labelHeight = Math.max(1.0D, text.lineHeight * labelScale) * inv;
            // 标记盒（x-2 ~ x+3）连同下方名字行都完全在裁剪区外：跳过不画。
            if (x + 3 * inv <= clipLeft || x - 3 * inv >= clipRight
                    || y + 4 * inv + labelHeight <= clipTop || y - 3 * inv >= clipBottom) {
                continue;
            }
            // 金色 2x2 + 1 像素黑色描边（屏幕恒定尺寸，随 unitScale 换算）。
            GuiDraw.fillF(matrices, (float) (x - 2 * inv), (float) (y - 2 * inv),
                    (float) (x + 3 * inv), (float) (y + 3 * inv), LOOT_OUTLINE);
            GuiDraw.fillF(matrices, (float) (x - inv), (float) (y - inv),
                    (float) (x + 2 * inv), (float) (y + 2 * inv), LOOT_FILL);
            drawLootLabel(matrices, text, point.name(), x, y + 3 * inv, labelScale, inv);
        }
    }

    /** 字号换算：LootPoint.fontSize 按 9 像素原版字高折算成缩放倍率，夹在 0.5x ~ 2x。 */
    private static float labelScale(int fontSize) {
        return Mth.clamp(fontSize / 9.0F, 0.5F, 2.0F);
    }

    /**
     * 物资点名字：黑色阴影 + 白色正文（先画偏移 1 像素的黑字，再画白字），
     * 以标记水平居中、贴着标记下沿。Font.draw 自己管理顶点缓冲，
     * 不干扰外层任何 Buffer 状态。
     *
     * <p>[r127] {@code sizeScale} = labelScale × inv（inv = 1/unitScale）：
     * 字体在内层反缩放矩阵里按“屏幕恒定像素高”渲染，锚点坐标仍走统一矩阵。
     */
    private static void drawLootLabel(PoseStack matrices, Font text, String name,
                                      double centerX, double topY, float labelScale, double inv) {
        if (name == null || name.isEmpty()) {
            return;
        }
        matrices.pushPose();
        try {
            matrices.translate(centerX, topY, 0.0);
            float sizeScale = (float) (labelScale * inv);
            matrices.scale(sizeScale, sizeScale, 1.0F);
            float x = -text.width(name) / 2.0F;
            GuiDraw.drawTextWithShadow(matrices, text, name, x + 1.0F, 1.0F, 0xFF000000);
            GuiDraw.drawTextWithShadow(matrices, text, name, x, 0.0F, 0xFFFFFFFF);
        } finally {
            matrices.popPose();
        }
    }

    // ==================== [BR-MINIMAP] 队友图标 ====================

    /** 图标黑色描边（与物资点同风格）。 */
    private static final int TEAMMATE_OUTLINE = 0xFF000000;
    /** 倒地图标闪烁：亮红半拍。 */
    private static final int TEAMMATE_DOWNED_BRIGHT = 0xFFFF4444;
    /** 倒地图标闪烁：暗红半拍（0xAA0000 同队伍 HUD 倒地色）。 */
    private static final int TEAMMATE_DOWNED_DIM = 0xFF8B1A1A;
    /** 贴边图标距地图可视区边缘的留白（屏幕像素）。 */
    private static final double TEAMMATE_EDGE_MARGIN_PX = 5.0;

    /**
     * 画本队队友图标（HUD 小地图与全屏地图共用）：
     * <ul>
     *   <li>坐标读 {@link ClientTeammatePosState}（服务端每 0.25 秒定向同步 +
     *       渲染侧线性插值），世界→底图像素换算与物资点同一条公式，
     *       锚定关系由外层统一矩阵保证，绝不再开第二坐标系；</li>
     *   <li>视野内在精确位置、视野外按<b>方位角投影</b>贴可视区边缘
     *       （方向相对中心恒真，PUBG 式贴边）；</li>
     *   <li>颜色按座号 1~4 固定色（复用 {@link TeamHudRenderer#colorOf}）；
     *       倒地每 300ms 亮/暗红闪烁；已淘汰不画；自己不画（箭头就是自己）；</li>
     *   <li>观战（已淘汰）本人看队友视角时同样生效——图标只依赖坐标包 +
     *       维度隔离闸，不依赖本人存活状态。</li>
     * </ul>
     * 每帧至多 4 个 5 像素方块（含 1 次描边），零 buffer.begin、纯自包含
     * {@link GuiDraw#fillF}，与物资点层同款渲染安全写法，不构成卡顿源。
     */
    private static void drawTeammateIcons(PoseStack matrices, ClientMapCache.Meta meta,
                                          double centerX, double centerY,
                                          double halfWidth, double halfHeight, double unitScale) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || unitScale <= 0.0 || halfWidth <= 0.0 || halfHeight <= 0.0) {
            return;
        }
        // 维度隔离闸（[r143] 独立于物资点层）：只在比赛维度画队友图标，
        // 回大厅后服务端空包清缓存 + 这道闸双重保险；地图 ID 能解析时还要求
        // 维度与当前底图同图，一服多图不串队。
        if (!teammateLayerVisible(client.level.dimension())) {
            return;
        }
        java.util.Map<java.util.UUID, ClientTeammatePosState.Placement> placements =
                ClientTeammatePosState.interpolatedPositions();
        if (placements.isEmpty()) {
            return;
        }
        double step = Math.max(1, meta.step());
        double inv = 1.0 / unitScale;
        double margin = TEAMMATE_EDGE_MARGIN_PX * inv;
        double availX = halfWidth - margin;
        double availY = halfHeight - margin;
        if (availX <= 0.0 || availY <= 0.0) {
            return; // 可视区被缩到放不下图标：整层跳过（不画贴边也不报错）。
        }
        java.util.UUID selfId = client.player.getUUID();
        // 闪烁半拍：每帧算一次，全体倒地队友同步呼吸，不会各闪各的。
        boolean blinkBright = ((System.currentTimeMillis() / 300L) & 1L) == 0L;
        for (java.util.Map.Entry<java.util.UUID, ClientTeammatePosState.Placement> entry
                : placements.entrySet()) {
            if (entry.getKey().equals(selfId)) {
                continue;
            }
            ClientTeammatePosState.Placement p = entry.getValue();
            if (p.status() == com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_ELIMINATED) {
                continue; // 需求：观战时显示的是“存活队友”，淘汰的不占图标。
            }
            double px = (p.x() - meta.originX()) / step;
            double pz = (p.z() - meta.originZ()) / step;
            double dx = px - centerX;
            double dy = pz - centerY;
            if (Math.abs(dx) > availX || Math.abs(dy) > availY) {
                // 按方位角把点压回边缘：缩放系数取两轴各自可容纳比例的最小值。
                double t = Math.min(availX / Math.max(Math.abs(dx), 1e-9),
                        availY / Math.max(Math.abs(dy), 1e-9));
                dx *= t;
                dy *= t;
            }
            int color;
            if (p.status() == com.paimeng.battleroyale.network.TeamSyncPayload.STATUS_DOWNED) {
                color = blinkBright ? TEAMMATE_DOWNED_BRIGHT : TEAMMATE_DOWNED_DIM;
            } else {
                color = TeamHudRenderer.colorOf(p.seat());
            }
            double x = centerX + dx;
            double y = centerY + dy;
            // 3 屏幕像素彩色点 + 1 像素黑描边（5 像素盒），尺寸恒定随 inv 换算。
            GuiDraw.fillF(matrices, (float) (x - 2.5 * inv), (float) (y - 2.5 * inv),
                    (float) (x + 2.5 * inv), (float) (y + 2.5 * inv), TEAMMATE_OUTLINE);
            GuiDraw.fillF(matrices, (float) (x - 1.5 * inv), (float) (y - 1.5 * inv),
                    (float) (x + 1.5 * inv), (float) (y + 1.5 * inv), color);
        }
    }

    // ==================== 航线（[BR-FLIGHT]） ====================

    /**
     * 画服务端同步的航线 A→B：直线段，白色 3 像素粗，
     * 起点黄色实心块、终点黄色空心框（一眼分清飞机从哪飞到哪）。
     *
     * <p><b>渲染安全</b>：全程只用自包含的 {@link DrawableHelper#fill}（内部 begin/draw 成对闭合），
     * 绝不碰共享 {@code BufferBuilder}——沿用物资点层验证过的写法，
     * 从根源杜绝 {@code Already building!}。步进 = 主轴像素数（上限 {@value #MAX_FLIGHT_STEPS}），
     * 每步 stamp 一个 3x3 方块；包围盒与裁剪区不相交时整层直接跳过（等待期零开销）。
     *
     * <p>坐标换算与底图 / 物资点 / 箭头同一套世界→像素公式：
     * {@code px = (worldX - originX) / step}，再乘 zoom 落屏。
     */
    /**
     * 画服务端同步的航线 A→B：直线段，白色 3 屏幕像素粗，
     * 起点黄色实心块、终点黄色空心框（一眼分清飞机从哪飞到哪）。
     *
     * <p>[r127] 与物资点层同款“单位化”：位置 = origin + 地图像素 × scale
     * （全屏地图在统一矩阵内 scale=1），stamp 宽 / 步进间隔 / 端点块尺寸按
     * 1/unitScale 换算保持屏幕恒定；裁剪由调用方全局 scissor 负责，
     * 本层只做剔除与 Liang–Barsky 步进上限。全程只用自包含 {@link GuiDraw#fillF}，
     * 绝不碰共享 {@code BufferBuilder}（{@code Already building!} 历史教训）。
     */
    private static void drawFlightLine(PoseStack matrices, ClientMapCache.Meta meta, double originX,
                                       double originY, double scale, double unitScale,
                                       int clipLeft, int clipTop, int clipWidth, int clipHeight) {
        Minecraft client = Minecraft.getInstance();
        if (!ClientFlightState.isActive() || client.level == null || scale <= 0.0 || unitScale <= 0.0
                || !ClientFlightState.visibleIn(client.level.dimension())) {
            return;
        }
        double inv = 1.0 / unitScale;
        double step = Math.max(1, meta.step());
        double x0 = originX + (ClientFlightState.startX() - meta.originX()) / step * scale;
        double y0 = originY + (ClientFlightState.startZ() - meta.originZ()) / step * scale;
        double x1 = originX + (ClientFlightState.endX() - meta.originX()) / step * scale;
        double y1 = originY + (ClientFlightState.endZ() - meta.originZ()) / step * scale;
        int clipRight = clipLeft + clipWidth;
        int clipBottom = clipTop + clipHeight;
        double margin = 4.0 * inv;
        // 包围盒先做整段剔除：航线不在视野里就一次 fill 都不发。
        if (Math.max(x0, x1) + margin < clipLeft || Math.min(x0, x1) - margin > clipRight
                || Math.max(y0, y1) + margin < clipTop || Math.min(y0, y1) - margin > clipBottom) {
            return;
        }
        // Liang–Barsky 参数裁剪：只步进裁剪区里的那一小段。
        double dxAll = x1 - x0;
        double dyAll = y1 - y0;
        double tMin = 0.0;
        double tMax = 1.0;
        double[] p = {-dxAll, dxAll, -dyAll, dyAll};
        double[] q = {x0 - (clipLeft - margin), (clipRight + margin) - x0,
                y0 - (clipTop - margin), (clipBottom + margin) - y0};
        boolean inside = true;
        for (int i = 0; i < 4 && inside; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                inside = q[i] >= 0.0;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0.0) {
                    tMin = Math.max(tMin, t);
                } else {
                    tMax = Math.min(tMax, t);
                }
                inside = tMin <= tMax;
            }
        }
        if (!inside || tMax <= tMin) {
            return;
        }
        x0 += dxAll * tMin;
        y0 += dyAll * tMin;
        x1 += dxAll * tMax;
        y1 += dyAll * tMax;
        // 步进数按“屏幕像素”计（约每 1 屏幕像素一个 stamp），上限防卡顿。
        int steps = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) * unitScale);
        steps = Math.max(1, Math.min(steps, MAX_FLIGHT_STEPS));
        double dx = (x1 - x0) / steps;
        double dy = (y1 - y0) / steps;
        // 线身：每步一个 3×3 屏幕像素 stamp。
        float half = 1.5f * (float) inv;
        double px = x0;
        double py = y0;
        for (int i = 0; i <= steps; i++) {
            GuiDraw.fillF(matrices, (float) px - half, (float) py - half,
                    (float) px + half, (float) py + half, FLIGHT_LINE);
            px += dx;
            py += dy;
        }
        // 起点实心块 + 终点空心框（各 1 / 4 次 fill，标出飞行方向）。
        float h = 3.5f * (float) inv;    // 半宽（屏幕 3.5 像素）
        float t = (float) inv;           // 空心框线厚（屏幕 1 像素）
        GuiDraw.fillF(matrices, (float) x0 - h, (float) y0 - h, (float) x0 + h, (float) y0 + h, FLIGHT_MARK);
        GuiDraw.fillF(matrices, (float) x1 - h, (float) y1 - h, (float) x1 + h, (float) y1 - h + t, FLIGHT_MARK);
        GuiDraw.fillF(matrices, (float) x1 - h, (float) y1 + h - t, (float) x1 + h, (float) y1 + h, FLIGHT_MARK);
        GuiDraw.fillF(matrices, (float) x1 - h, (float) y1 - h + t, (float) x1 - h + t, (float) y1 + h - t, FLIGHT_MARK);
        GuiDraw.fillF(matrices, (float) x1 + h - t, (float) y1 - h + t, (float) x1 + h, (float) y1 + h - t, FLIGHT_MARK);
    }

    // ==================== [BR-ZONE] 毒圈图层（模块四） ====================

    /**
     * 画毒圈：蓝圈（实时边界）、白圈（下个安全区），玩家在<b>白圈</b>外时
     * 画一条从玩家指向白圈中心的白色虚线（进圈指引，与蓝圈无关）。
     *
     * <p><b>维度隔离（模块六）</b>：入口先过 {@link ClientZoneState#visibleIn}，
     * 主城 / 别的地图维度直接返回，什么都不画。
     *
     * <p><b>画法：像素扫描，不再按角度取点。</b>圆按「外接正方形逐像素距离判定」画成
     * 连续环带（见 {@link #drawZoneCircle}），虚线按「屏幕像素步进」画
     * （见 {@link #drawZoneGuide}）——旧版每 ~1.5 像素取一个点、分段封顶 128，
     * 半径一大点与点之间就断开成离散珠子，从根上不存在了。
     *
     * <p><b>渲染安全</b>：三层共用一次 {@code buffer.begin/draw} 批量提交（比逐像素
     * {@code fill} 少几千次 draw call），严格 {@code try { begin; ... } finally { draw(); }}
     * 包裹，与底图层同款写法，绝不把共享 BufferBuilder 留在 building 状态；
     * 扫描范围先和裁剪区求交、像素判定数有硬预算（{@value #ZONE_SCAN_BUDGET}），每帧开销有上界。
     */
    /**
     * 画毒圈：蓝圈（实时边界）、白圈（下个安全区），玩家在<b>白圈</b>外时
     * 画一条从玩家指向白圈中心的白色虚线（进圈指引，与蓝圈无关）。
     *
     * <p><b>维度隔离（模块六）</b>：入口先过 {@link ClientZoneState#visibleIn}，
     * 主城 / 别的地图维度直接返回，什么都不画。
     *
     * <p><b>画法：像素扫描，不再按角度取点。</b>扫描预算与线宽容差全部按
     * “屏幕像素”计算（{@code unitScale} = 1 绘制单位有多少屏幕像素），落笔时
     * 换算回绘制单位——小地图（屏幕坐标系）行为与旧版逐像素一致，全屏地图
     * （统一矩阵的地图像素坐标系）环宽/虚线保持屏幕恒定，锚点由矩阵保证。
     *
     * <p><b>渲染安全</b>：三层共用一次 {@code buffer.begin/draw} 批量提交，严格
     * {@code try { begin; ... } finally { draw(); }} 包裹；裁剪由调用方全局 scissor
     * 负责（r127 起本层不再自己开闭 scissor）。
     */
    private static void drawZoneOverlay(PoseStack matrices, ClientMapCache.Meta meta, double originX,
                                        double originY, double scale, double unitScale,
                                        int clipLeft, int clipTop, int clipWidth, int clipHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || scale <= 0.0 || unitScale <= 0.0
                || !ClientZoneState.visibleIn(client.level.dimension())) {
            return;
        }
        double step = Math.max(1, meta.step());
        double pxPerBlock = scale / step;
        double inv = 1.0 / unitScale;
        int clipRight = clipLeft + clipWidth;
        int clipBottom = clipTop + clipHeight;
        Matrix4f matrix = matrices.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        try {
            // 蓝圈：实时边界（服务端同步的收缩起点圆，客户端按进度 lerp 后的值）。
            drawZoneCircle(buffer, matrix, meta, originX, originY, pxPerBlock, inv, unitScale,
                    ClientZoneState.liveCenterX(), ClientZoneState.liveCenterZ(),
                    ClientZoneState.liveRadius(), ZONE_BLUE,
                    clipLeft, clipTop, clipRight, clipBottom);
            // 白圈：下个安全区（等待阶段就能看到要往哪跑）。
            drawZoneCircle(buffer, matrix, meta, originX, originY, pxPerBlock, inv, unitScale,
                    ClientZoneState.whiteCenterX(), ClientZoneState.whiteCenterZ(),
                    ClientZoneState.whiteRadius(), ZONE_WHITE,
                    clipLeft, clipTop, clipRight, clipBottom);
            // 进圈指引：玩家在白圈（下一个安全区）外就画线，不管他在不在蓝圈里
            // （r113 修正——旧判定误用了实时蓝圈，玩家在蓝圈内但白圈外时漏画）。
            if (ClientZoneState.isOutsideWhite(client.player.getX(), client.player.getZ())) {
                drawZoneGuide(buffer, matrix, meta, originX, originY, pxPerBlock, inv, client.player);
            }
        } finally {
            // 哪怕扫描中途出异常，共享 buffer 也必须闭合（历史崩溃点，见类注释）。
            // 裁剪由调用方统一 scissor 负责，本层不碰 scissor 开闭。
            if (buffer.building()) BufferUploader.drawWithShader(buffer.end());
        }
    }

    /**
     * 像素扫描画圆（连续环带）。[r127] 几何预算按屏幕像素，落笔按绘制单位
     * （inv = 1/unitScale 换算）：外接正方形先与裁剪区求交、判定总数有硬预算、
     * 半带宽不小于扫描步距，环带首尾相接绝不断线。
     */
    private static void drawZoneCircle(BufferBuilder buffer, Matrix4f matrix, ClientMapCache.Meta meta,
                                       double originX, double originY, double pxPerBlock,
                                       double inv, double unitScale,
                                       double worldX, double worldZ, double worldRadius, int color,
                                       int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (worldRadius <= 0.0 || pxPerBlock <= 0.0) {
            return;
        }
        // 世界→绘制单位与底图同一套公式：pos = origin + (world - originInWorld) * pxPerBlock。
        double cx = originX + (worldX - meta.originX()) * pxPerBlock;
        double cy = originY + (worldZ - meta.originZ()) * pxPerBlock;
        double radiusDraw = worldRadius * pxPerBlock;           // 绘制单位
        double radiusScreen = radiusDraw * unitScale;           // 屏幕像素（尺寸预算按它算）
        if (radiusScreen < 0.5) {
            return;
        }
        // 动态容差：线宽随屏幕半径缓慢增大但封顶（屏幕像素），换算回绘制单位；
        // 半带宽不得小于扫描步距，否则环带盖不住相邻采样点。
        double lineWidthScreen = Math.max(ZONE_LINE_MIN_WIDTH, Math.min(ZONE_LINE_MAX_WIDTH, radiusScreen / 60.0));
        // 外接正方形 ∩ 裁剪区（四边各留“半线宽 + 1 绘制单位”富余）。
        double halfLineWidthDraw = lineWidthScreen * 0.5 * inv;
        int margin = Math.max(1, (int) Math.ceil(halfLineWidthDraw) + 1);
        int minX = Math.max((int) Math.floor(cx - radiusDraw) - margin, clipLeft);
        int maxX = Math.min((int) Math.ceil(cx + radiusDraw) + margin, clipRight - 1);
        int minY = Math.max((int) Math.floor(cy - radiusDraw) - margin, clipTop);
        int maxY = Math.min((int) Math.ceil(cy + radiusDraw) + margin, clipBottom - 1);
        if (minX > maxX || minY > maxY) {
            return; // 整圆都在视野外：一个像素都不扫。
        }
        // 扫描预算按屏幕像素面积，与旧版逐像素一致（unitScale=1 时公式原样退化）。
        int stepScreen = scanStep((int) Math.ceil((maxX - minX + 1) * unitScale),
                (int) Math.ceil((maxY - minY + 1) * unitScale));
        // 采样步距（绘制单位）= 屏幕步距换算：小地图恒 ≥1 像素；全屏地图高 zoom
        // 时可以到亚像素（1 屏幕像素 = 1/zoom 个地图像素），环带才够圆。
        double step = Math.max(stepScreen * inv, 1e-6);
        // 半带宽 = 线宽一半（屏幕口径同旧版），且不得小于扫描步距，
        // 否则步距放大后 stamp 盖不住相邻采样点，环带会断成虚点。
        double halfBand = Math.max(halfLineWidthDraw, step);
        double outerR = radiusDraw + halfBand;
        double outerSq = outerR * outerR;
        double innerR = Math.max(0.0, radiusDraw - halfBand);
        double innerSq = innerR * innerR;
        // [r130 阶段零] 超大圈视距剔除：矩形到圆心的最远角距离仍小于环带内缘
        // （矩形四个角是距离最大值点，逐角取最大即全矩形上界）→ 环带整体在视野外，
        // 一个像素都不扫。开局蓝圈罩住全图时，小地图 / 全屏地图上只见白圈。
        double farDx = Math.max(Math.abs(clipLeft - cx), Math.abs((clipRight - 1) - cx));
        double farDy = Math.max(Math.abs(clipTop - cy), Math.abs((clipBottom - 1) - cy));
        if (farDx * farDx + farDy * farDy < innerSq) {
            return;
        }
        for (double y = minY; y <= maxY; y += step) {
            double dy = y + step * 0.5 - cy;
            double dySq = dy * dy;
            if (dySq > outerSq) {
                continue; // 这一行整行在环带外（外接正方形的上下角区），跳过内层。
            }
            for (double x = minX; x <= maxX; x += step) {
                double dx = x + step * 0.5 - cx;
                double distSq = dx * dx + dySq;
                if (distSq >= innerSq && distSq <= outerSq) {
                    putQuad(buffer, matrix, (float) x, (float) y, (float) step, (float) step, color);
                }
            }
        }
    }

    /**
     * 圈外指引虚线：玩家当前位置 → 白圈中心，沿<b>屏幕像素</b>步进「划 3 停 3」。
     * [r127] 划线/步长/宽度按屏幕像素预算、乘 inv 换算回绘制单位后落笔。
     */
    private static void drawZoneGuide(BufferBuilder buffer, Matrix4f matrix, ClientMapCache.Meta meta,
                                      double originX, double originY, double pxPerBlock,
                                      double inv,
                                      net.minecraft.client.player.LocalPlayer player) {
        double fromX = originX + (player.getX() - meta.originX()) * pxPerBlock;
        double fromY = originY + (player.getZ() - meta.originZ()) * pxPerBlock;
        double toX = originX + (ClientZoneState.whiteCenterX() - meta.originX()) * pxPerBlock;
        double toY = originY + (ClientZoneState.whiteCenterZ() - meta.originZ()) * pxPerBlock;
        double dx = toX - fromX;
        double dy = toY - fromY;
        double lengthDraw = Math.hypot(dx, dy);
        if (lengthDraw < 1e-9) {
            return;
        }
        double ux = dx / lengthDraw;
        double uy = dy / lengthDraw;
        // 步距按屏幕像素：默认 1px 一步逐像素走；超远指引按比例放大，stamp 跟着放大。
        double lengthScreen = lengthDraw * (1.0 / inv); // = lengthDraw / inv
        if (lengthScreen < 2.0) {
            return; // 屏幕上线长不足 2 像素：不画（同旧版阈值）
        }
        int stepPx = Math.max(1, (int) Math.ceil(lengthScreen / ZONE_GUIDE_MAX_STEPS));
        double step = Math.max(stepPx * inv, 1e-9);
        int count = (int) Math.ceil(lengthDraw / step) + 1;
        double width = Math.max(2, stepPx) * inv;
        double period = (ZONE_DASH_ON + ZONE_DASH_OFF) * inv;
        double dashOn = ZONE_DASH_ON * inv;
        for (int i = 0; i < count; i++) {
            double t = (double) i * step;
            if (t % period >= dashOn) {
                continue; // 3 像素一划、3 像素一停
            }
            double x = fromX + ux * t - width * 0.5;
            double y = fromY + uy * t - width * 0.5;
            putQuad(buffer, matrix, (float) x, (float) y, (float) width, (float) width, ZONE_GUIDE);
        }
    }

    /**
     * 扫描步距：外接正方形像素数在预算内用 1（严格逐像素，圆绝对连续）；
     * 超出预算时按 {@code step² ≈ 面积 / 预算} 放大，保证每帧判定次数有硬上界。
     * 参数按<b>屏幕像素</b>计，返回值同为屏幕像素步距。
     */
    private static int scanStep(int boxWidth, int boxHeight) {
        long area = (long) Math.max(1, boxWidth) * Math.max(1, boxHeight);
        int step = 1;
        while ((area + (long) step * step - 1) / ((long) step * step) > ZONE_SCAN_BUDGET) {
            step++;
        }
        return step;
    }

    // ==================== 玩家箭头 ====================

    /**
     * [BR-ARROW-AND-SPEED] 白色玩家箭头：恢复 BufferBuilder + TRIANGLES 直接提交，
     * 硬件光栅化得到平滑的斜边三角形（不再用 1x1 fill 逐像素拼马赛克）。
     *
     * <p>上一版为了躲 {@code Already building!} 才改成逐像素栅格化；泄漏根源已在
     * 各矢量层统一的 {@code try/finally}（共享 BufferBuilder 必被关闭）
     * 和物资点层的全自包含 {@code fill} 处根治，这里可以放心重新 begin。
     * 本方法仍严格 {@code try { buffer.begin(...); ... } finally { draw(); }} 包裹：
     * 哪怕顶点提交中途出异常，共享 buffer 也绝不留在 building 状态。
     *
     * <p>旋转与旧公式逐点一致（角度 {@code yaw + 180}，绕屏幕 Z 轴，
     * 屏幕坐标 Y 向下）：{@code sx = x + lx*cos - ly*sin}，{@code sy = y + lx*sin + ly*cos}。
     */
    private static void drawArrow(PoseStack matrices, double x, double y, float yaw) {
        double radians = Math.toRadians(yaw + 180.0F);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        Matrix4f matrix = matrices.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            // 先大三角黑描边、后小三角白实心（同一批次里后画的覆盖先画的）。
            putRotatedTriangle(buffer, matrix, x, y, cos, sin,
                    0.0F, -6.0F, -5.0F, 5.0F, 5.0F, 5.0F, ARROW_OUTLINE);
            putRotatedTriangle(buffer, matrix, x, y, cos, sin,
                    0.0F, -5.0F, -4.0F, 4.0F, 4.0F, 4.0F, ARROW_FILL);
        } finally {
            if (buffer.building()) BufferUploader.drawWithShader(buffer.end());
        }
    }

    /**
     * [r127] 全屏地图箭头：锚点 (x, y) 是<b>地图像素</b>（统一矩阵的本地坐标），
     * 内外两层同一矩阵——拖拽/缩放时箭头与底图像素严格绑定；三角形几何在内层
     * 反缩放（1/unitScale）里按屏幕像素画，尺寸恒定、位置随矩阵锚定。
     * 裁剪由调用方全局 scissor 负责，不再单独开闭。
     */
    private static void drawArrowAnchored(PoseStack matrices, double x, double y, float yaw, double unitScale) {
        if (unitScale <= 0.0 || !Double.isFinite(x) || !Double.isFinite(y)) {
            return;
        }
        matrices.pushPose();
        try {
            matrices.translate(x, y, 0.0);
            float inv = (float) (1.0 / unitScale);
            matrices.scale(inv, inv, 1.0F);
            drawArrow(matrices, 0.0, 0.0, yaw);
        } finally {
            matrices.popPose();
        }
    }

    /**
     * 提交一个三角形（3 个 TRIANGLES 顶点）：把箭头局部坐标 (lx, ly) 绕屏幕中心
     * (centerX, centerY) 旋转 (cos, sin) 后换算成屏幕坐标，颜色展开成 0~1 分量。
     */
    private static void putRotatedTriangle(BufferBuilder buffer, Matrix4f matrix,
                                           double centerX, double centerY, float cos, float sin,
                                           float ax, float ay, float bx, float by, float cx, float cy,
                                           int color) {
        float alpha = (color >>> 24) / 255.0F;
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        putRotatedVertex(buffer, matrix, centerX, centerY, cos, sin, ax, ay, red, green, blue, alpha);
        putRotatedVertex(buffer, matrix, centerX, centerY, cos, sin, bx, by, red, green, blue, alpha);
        putRotatedVertex(buffer, matrix, centerX, centerY, cos, sin, cx, cy, red, green, blue, alpha);
    }

    private static void putRotatedVertex(BufferBuilder buffer, Matrix4f matrix,
                                         double centerX, double centerY, float cos, float sin,
                                         float lx, float ly, float red, float green, float blue, float alpha) {
        float sx = (float) (centerX + lx * cos - ly * sin);
        float sy = (float) (centerY + lx * sin + ly * cos);
        buffer.vertex(matrix, sx, sy, 0.0F).color(red, green, blue, alpha).endVertex();
    }

}
