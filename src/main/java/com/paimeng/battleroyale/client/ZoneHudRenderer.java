package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.zone.ZonePhase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * 模块五：小地图正下方的缩圈进度条（纯 HUD，不碰任何物理 / 同步 / 按键）。
 *
 * <p>形态（需求原文示例）：{@code 00:30 ----|--/--}
 * <ul>
 *   <li><b>倒计时数字</b>：WAITING = 正常白色 {@code 00:mm}；SHRINKING / DONE =
 *       黄色 §e{@code 00:00}（倒计时归零后锁定）；</li>
 *   <li><b>进度条几何</b>：最左 = 当前蓝圈边缘（玩家距白圈中心 = 蓝圈半径的位置），
 *       最右 = 白圈中心；</li>
 *   <li>{@code |}：毒圈推进前沿，索引 = 缩圈总进度 0~1 映射；{@code |} 和它左侧的
 *       {@code -} 渲染为蓝色 §9；</li>
 *   <li>{@code /}：玩家位置投影（按玩家到白圈中心的距离算索引）；已在白圈内
 *       直接顶到最右；字符本身黄色 §e；</li>
 *   <li>剩余的 {@code -}（{@code |} 右侧）：白色 §f。</li>
 * </ul>
 * 全部用 {@code Component.literal(...).setStyle(...)} 拼一个 Component 组件一次画出，
 * 不画像素条、不引新字体。
 *
 * <p><b>模块六（维度隔离）</b>：{@link ClientZoneState#visibleIn} 不通过（主城 /
 * 别的地图 / 没数据）直接 return，连进度条本身都不画。
 */
@Environment(EnvType.CLIENT)
public final class ZoneHudRenderer {

    /**
     * 进度条的字符数（不含倒计时前缀）。
     * [r122-OOM 防护] 这是编译期常量：整行文本 ≤ 5（时钟）+ 1（空格）+ BAR_LEN + 2
     * （玩家恰好在前沿时 | 和 / 两字符）——字符数有绝对上界，不存在拼出超长文本、
     * 进而把字体顶点缓冲撑爆的路径。需求上限 20，这里取 14 不变。
     */
    private static final int BAR_LEN = 14;

    private static final Style BLUE = Style.EMPTY.withColor(ChatFormatting.BLUE);
    private static final Style YELLOW = Style.EMPTY.withColor(ChatFormatting.YELLOW);
    private static final Style WHITE = Style.EMPTY.withColor(ChatFormatting.WHITE);

    private ZoneHudRenderer() {
    }

    /**
     * 渲染入口：画在小地图正下方。
     *
     * @param minimapLeft 小地图左上角 X（屏幕逻辑像素）
     * @param minimapTop  小地图左上角 Y
     * @param minimapSize 小地图边长
     */
    public static void render(PoseStack matrices, int minimapLeft, int minimapTop, int minimapSize) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.options.hideGui) {
            return;
        }
        // 维度隔离闸：主城 / 别的地图 / 毒圈未激活 → 什么都不画（含这条进度条）。
        if (!ClientZoneState.visibleIn(client.level.dimension())) {
            return;
        }
        Component bar = buildBarText(client.player);
        int textWidth = client.font.width(bar);
        int x = minimapLeft + Math.max(0, (minimapSize - textWidth) / 2);
        int y = minimapTop + minimapSize + 3;
        // 背景压暗：文字在底图 / 天空上都看得清。
        GuiDraw.fill(matrices, x - 2, y - 1, x + textWidth + 2, y + 10, 0x80101010);
        GuiDraw.drawTextWithShadow(matrices, client.font, bar, x, y, 0xFFFFFFFF);
    }

    /** 组装「倒计时 + 进度条」整行的 Component 组件（需求：Component.literal().setStyle() 拼接）。 */
    static Component buildBarText(LocalPlayer player) {
        ZonePhase phase = ClientZoneState.phase();
        MutableComponent line = Component.literal("");

        // ---- 倒计时数字：WAITING 白色正常显示；SHRINKING / DONE 黄色 00:00 ----
        String clock;
        Style clockStyle;
        if (phase == ZonePhase.WAITING) {
            clock = formatClock(ClientZoneState.remainingSeconds());
            clockStyle = WHITE;
        } else {
            clock = "00:00";
            clockStyle = YELLOW;
        }
        line.append(Component.literal(clock).setStyle(clockStyle));
        line.append(Component.literal(" ").setStyle(WHITE));

        // ---- 几何索引（[r122-OOM 防护]：所有网络来的 double 一律先"洗"再用，
        // NaN / ±Infinity / 越界值全部钳回安全区间；两个索引钳回 [0, last]。
        // 循环本身以编译期常量 BAR_LEN 为上界，不可能死循环、不可能拼出超长文本）----
        // 左端 = <b>实时</b>蓝圈边缘（等待期间 = 收缩起点圆；缩圈期间 = lerp 后的当前圈，
        // [r130 阶段零] 开局蓝圈罩住全图时分母极大，dist/blueRadius 自然趋 0，
        // / 稳停最右端 = "全图安全"的正确语义；≥1 的下限保证任何半径都不除零）。
        // 右端 = 白圈中心（距离 0）。
        double blueRadius = finiteOr(ClientZoneState.liveRadius(), 1.0);
        if (blueRadius < 1.0) {
            blueRadius = 1.0;
        }
        double whiteRadius = finiteOr(ClientZoneState.whiteRadius(), 0.0);
        if (whiteRadius < 0.0) {
            whiteRadius = 0.0;
        }
        double whiteX = finiteOr(ClientZoneState.whiteCenterX(), player.getX());
        double whiteZ = finiteOr(ClientZoneState.whiteCenterZ(), player.getZ());
        double progress = finiteOr(ClientZoneState.progress(), 0.0);
        if (progress < 0.0) {
            progress = 0.0;
        } else if (progress > 1.0) {
            progress = 1.0;
        }
        double dist = Math.hypot(player.getX() - whiteX, player.getZ() - whiteZ);
        if (!Double.isFinite(dist)) {
            dist = blueRadius;   // 玩家坐标本身就烂掉（传送穿邦）：按"圈外最左"处理
        }

        int last = BAR_LEN - 1;
        int frontIdx = clampIndex((int) Math.round(progress * last), last);
        int playerIdx;
        if (dist <= whiteRadius) {
            playerIdx = last;            // 已在白圈：/ 直接顶到最右侧
        } else if (dist >= blueRadius) {
            playerIdx = 0;               // 蓝圈边缘之外：最左端
        } else {
            playerIdx = clampIndex((int) Math.round((1.0 - dist / blueRadius) * last), last);
        }

        // ---- 逐格拼字符 + 颜色（规则见类注释）----
        for (int i = 0; i <= last; i++) {
            if (i == playerIdx && i == frontIdx) {
                // 玩家正好站在毒圈前沿：先画蓝 | 再叠黄 /（两字符，信息不丢）。
                line.append(Component.literal("|").setStyle(BLUE));
                line.append(Component.literal("/").setStyle(YELLOW));
                continue;
            }
            if (i == playerIdx) {
                line.append(Component.literal("/").setStyle(YELLOW));
            } else if (i == frontIdx) {
                line.append(Component.literal("|").setStyle(BLUE));
            } else if (i < frontIdx) {
                line.append(Component.literal("-").setStyle(BLUE));   // | 左侧的 -：蓝
            } else {
                line.append(Component.literal("-").setStyle(WHITE));   // 剩余的 -：白
            }
        }
        return line;
    }

    /** 秒 → mm:ss。 */
    private static String formatClock(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    /** [r122-OOM 防护] NaN / ±Infinity 一律换成兜底值再参与算术。 */
    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    /** [r122-OOM 防护] 进度条索引钳回 [0, last]：Math.round(NaN)=0 之类的意外永远出不了界。 */
    private static int clampIndex(int value, int last) {
        return Math.max(0, Math.min(last, value));
    }
}
