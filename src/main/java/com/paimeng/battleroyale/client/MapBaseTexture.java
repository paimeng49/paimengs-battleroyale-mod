package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * [BR-MAP-R122] 整幅底图 → 一张 {@link DynamicTexture}，绘制只用
 * {@link net.minecraft.client.gui.GuiGraphics#blit} 一次提交（问题二修复核心）。
 *
 * <p>旧做法是每帧把 Tile 像素逐格画成 {@code POSITION_COLOR} 四边形：批次大、依赖
 * scissor/着色器现场状态，1.20.1 下表现为"日志显示 Tile 已缓存，底图却全黑"。
 * 现在改成 1.20.1 标准动态纹理管线：
 * <ol>
 *   <li>{@link ClientMapCache} 每收一块 Tile 就调 {@link #onTileCached}——像素直接
 *       写进整图 {@link NativeImage}（未下载区域预填 {@code 0xFF1A1A1A} 深灰，
 *       永远不会是"全黑"）；</li>
 *   <li>纹理经 {@code TextureManager.register(String family, DynamicTexture)} 注册，
 *       拿到 1.20.1 规范的 {@link ResourceLocation}；</li>
 *   <li>渲染线程通过 {@link #currentTexture} 取纹理 id（脏时按 250ms 节流重传 GPU），
 *       {@code MapRenderer} 用 {@code graphics.blit(...)} 配精确 UV 一次画完。</li>
 * </ol>
 *
 * <p>字节序坑（按 1.20.1 原版 {@code NativeImage#setColor} 字节码核实）：它按小端
 * 原样写入整数，内存中的 RGBA 要求传入 {@code 0xAABBGGRR}；服务端色板又只有
 * {@code 0xRRGGBB}，所以本类所有像素都先过 {@link #argbToRgba}，同时补齐 Alpha。
 */
@Environment(EnvType.CLIENT)
public final class MapBaseTexture {

    /** 纹理边长上限：超过就不建整图（异常 meta 的保险，正常 BR 地图远小于此）。 */
    private static final int MAX_DIMENSION = 4096;
    /** 脏位重传节流：全图批量下载期间每 250ms 最多重传一次整图。 */
    private static final long UPLOAD_INTERVAL_MS = 250L;
    /** 未缓存区域填色：与 MapRenderer 的 MISSING_COLOR 一致的深灰（不是黑）。 */
    private static final int MISSING_ARGB = 0xFF1A1A1A;

    private static String boundMapId = null;
    private static int boundWidth;
    private static int boundHeight;
    private static NativeImage image;
    private static DynamicTexture texture;
    private static ResourceLocation location;
    private static boolean dirty;
    private static long lastUploadMs;

    private MapBaseTexture() {
    }

    /**
     * 一块 Tile 进入 {@link ClientMapCache} 缓存后调用（客户端主线程）。
     * 只做 CPU 侧像素拷贝，GPU 上传延迟到渲染线程节流执行。
     */
    public static void onTileCached(String mapId, ClientMapCache.Meta meta, int tileX, int tileZ, int[][] colors) {
        if (mapId == null || meta == null || colors == null || colors.length == 0) {
            return;
        }
        int width = meta.imageWidth();
        int height = meta.imageHeight();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return;
        }
        ensureImage(mapId, width, height);
        if (image == null) {
            return;
        }
        int baseX = tileX * meta.tileSize();
        int baseZ = tileZ * meta.tileSize();
        for (int z = 0; z < colors.length; z++) {
            int py = baseZ + z;
            if (py < 0 || py >= boundHeight) {
                continue;
            }
            int[] row = colors[z];
            if (row == null) {
                continue;
            }
            int count = Math.min(row.length, boundWidth - baseX);
            for (int x = 0; x < count; x++) {
                int px = baseX + x;
                if (px < 0 || px >= boundWidth) {
                    continue;
                }
                image.setPixelRGBA(px, py, argbToRgba(row[x]));
            }
        }
        dirty = true;
    }

    /**
     * 渲染线程用：返回可直接喂给 {@code GuiGraphics.blit} 的纹理 id；
     * {@code null} = 整图还没建好 / 尺寸对不上（此时调用方保持深灰底色，不画底图）。
     *
     * @param meta 当前底图元数据（宽高必须与绑定的整图一致，防止跨地图串图）
     */
    public static ResourceLocation currentTexture(ClientMapCache.Meta meta) {
        if (meta == null || texture == null || location == null) {
            return null;
        }
        if (meta.imageWidth() != boundWidth || meta.imageHeight() != boundHeight) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (dirty && now - lastUploadMs >= UPLOAD_INTERVAL_MS) {
            try {
                texture.upload();
            } catch (RuntimeException e) {
                // GL 上传失败不重试到刷屏：保持 dirty，下个节流窗口再来。
                BattleRoyaleMod.LOGGER.warn("[BR-MAP] 底图纹理上传失败，稍后重试", e);
                return null;
            }
            dirty = false;
            lastUploadMs = now;
        }
        return location;
    }

    /** 换图 / 断线 / 缓存清空时调用：释放纹理与像素内存。 */
    public static void reset() {
        TextureManager textureManager = textureManagerOrNull();
        if (location != null && textureManager != null) {
            try {
                textureManager.release(location);
            } catch (RuntimeException ignored) {
                // 释放失败：本对象引用即将置空，旧 id 由 TextureManager 兜底回收。
            }
        }
        if (texture != null) {
            try {
                texture.close();   // DynamicTexture 连同持有的 NativeImage 一起释放
            } catch (RuntimeException ignored) {
                // 同上
            }
        } else if (image != null) {
            try {
                image.close();
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
        boundMapId = null;
        boundWidth = 0;
        boundHeight = 0;
        image = null;
        texture = null;
        location = null;
        dirty = false;
        lastUploadMs = 0L;
    }

    // ==================== 内部 ====================

    /** 绑定 / 重建整图（mapId 或尺寸变化时）。 */
    private static void ensureImage(String mapId, int width, int height) {
        if (image != null && mapId.equals(boundMapId) && width == boundWidth && height == boundHeight) {
            return;
        }
        reset();
        TextureManager textureManager = textureManagerOrNull();
        if (textureManager == null) {
            return;   // 客户端还没就绪（理论上不会发生：Tile 包只会进 Playing 阶段）
        }
        try {
            NativeImage newImage = new NativeImage(NativeImage.Format.RGBA, width, height, true);
            // 整图预填深灰：还没下载的 Tile 区域显示"未探测"灰，而不是黑。
            newImage.fillRect(0, 0, width, height, argbToRgba(MISSING_ARGB));
            DynamicTexture newTexture = new DynamicTexture(newImage);
            ResourceLocation newLocation = textureManager.register(familyOf(mapId), newTexture);
            boundMapId = mapId;
            boundWidth = width;
            boundHeight = height;
            image = newImage;
            texture = newTexture;
            location = newLocation;
            dirty = true;
            lastUploadMs = 0L;   // 首帧立即上传
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("[BR-MAP] 底图纹理创建失败：{}x{}", width, height, e);
            image = null;
            texture = null;
            location = null;
        }
    }

    /** register(String, DynamicTexture) 的 family：小写 + 白名单字符，避免非法路径。 */
    private static String familyOf(String mapId) {
        String cleaned = mapId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "default";
        }
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48);
        }
        return "br_map_" + cleaned;
    }

    private static TextureManager textureManagerOrNull() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.getTextureManager();
    }

    /**
     * Java 颜色（0xAARRGGBB 或无 alpha 的 0xRRGGBB）→ 1.20.1
     * {@code NativeImage.Format.RGBA} 所需的小端整数。
     *
     * <p>1.20.1 的 {@code NativeImage#setColor} 用小端原样写入整数，
     * 所以要传 {@code 0xAABBGGRR}，内存中才是 RGBA；同时服务端色板
     * 只发 0xRRGGBB，必须在这里补成完全不透明。旧的转换会把 RRGGBB
     * 错排成 RRGGBBAA，导致红色通道被当成 alpha，整张地图出现红粉滤镜。
     */
    private static int argbToRgba(int color) {
        int alpha = color & 0xFF000000;
        if (alpha == 0) {
            alpha = 0xFF000000;
        }
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        return alpha | (blue << 16) | (green << 8) | red;
    }
}
