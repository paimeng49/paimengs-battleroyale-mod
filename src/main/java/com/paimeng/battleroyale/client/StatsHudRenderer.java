package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.ChatFormatting;

/**
 * 本局状态 HUD：屏幕<b>左上角</b>两行 "剩余：N 人" 和 "击杀：M 人"，
 * 画在 {@link TeamHudRenderer} 队友列表的<b>上方</b>。
 *
 * <p>数据来源：{@link ClientStatsState}（服务端 StatsManager 广播）。
 * 本地没有统计（未开局 / 已结算 / 断线）时一行都不画，队伍列表回到顶部。
 *
 * <p>与队友列表的错位：{@link #occupiedHeight(Font)} 告诉
 * TeamHudRenderer 顶部被占用了多少像素，队伍 HUD 从这之下开始画。
 */
@Environment(EnvType.CLIENT)
public final class StatsHudRenderer {

    /** 边距与行距和 TeamHudRenderer 保持一致。 */
    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    private static final int LINE_GAP = 1;

    private static final int LABEL = 0xFFAAAAAA;      // 灰色，和队伍表头同色系
    private static final int REMAINING = 0xFFE0E0E0;  // 亮白
    private static final int KILLS = 0xFFFF5555;      // 击杀数红色
    private static final int TEAM_COLOR = 0xFF55FFFF; // [BR-MATCHTEAM] 存活队伍数青色

    private StatsHudRenderer() {
    }

    /** 挂在 {@code HudRenderCallback.EVENT} 上，每帧一次；先于队友列表画在顶部。 */
    public static void render(GuiGraphics graphics, float tickDelta) {
        PoseStack matrices = graphics.pose();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.options.hideGui) {
            return;
        }
        // [BR-HUD-REJOIN] 维度隔离：这两行只属于吃鸡对局，主世界等其它维度一律不画
        // （哪怕服务端统计快照还挂在本地缓存里）。
        if (!inGameDimension()) {
            return;
        }
        if (!ClientStatsState.hasStats()) {
            return;
        }

        Font text = client.font;
        int y = MARGIN_Y;
        GuiDraw.drawTextWithShadow(matrices, text,
                BrText.mutable("剩余：").withStyle(ChatFormatting.GRAY)
                        .append(BrText.of(Integer.toString(ClientStatsState.remainingPlayers()))),
                MARGIN_X, y, REMAINING);
        y += text.lineHeight + LINE_GAP;
        // [BR-MATCHTEAM 模块四] 存活队伍数：只在服务端下发 ≥0 时画（-1 = 大厅 / 旧包）。
        int teams = ClientStatsState.remainingTeams();
        if (teams >= 0) {
            GuiDraw.drawTextWithShadow(matrices, text,
                    BrText.mutable("队伍：").withStyle(ChatFormatting.GRAY)
                            .append(BrText.of(Integer.toString(teams))),
                    MARGIN_X, y, TEAM_COLOR);
            y += text.lineHeight + LINE_GAP;
        }
        GuiDraw.drawTextWithShadow(matrices, text,
                BrText.mutable("击杀：").withStyle(ChatFormatting.GRAY)
                        .append(BrText.of(Integer.toString(ClientStatsState.kills()))),
                MARGIN_X, y, KILLS);
    }

    /**
     * 顶部被本 HUD 占用的像素高度（含底部行距），供 TeamHudRenderer 下移避让。
     * 无统计可画时返回 0，队友列表回到屏幕顶部。
     * [BR-HUD-REJOIN] 非游戏维度同样返回 0：这里不画了，不能让队友列表空让两行。
     */
    public static int occupiedHeight(Font text) {
        if (!inGameDimension() || !ClientStatsState.hasStats()) {
            return 0;
        }
        // 剩余 / 击杀 两行恒定，队伍行只在服务端下发 ≥0 时多占一行。
        int lines = 2 + (ClientStatsState.remainingTeams() >= 0 ? 1 : 0);
        return lines * (text.lineHeight + LINE_GAP);
    }

    /**
     * 当前是否身处吃鸡游戏维度（{@code battleroyale_game:*} 命名空间）。
     * 判定直接复用服务端同一份 {@link GameWorldManager#isGameWorld}
     * （纯 ResourceKey 命名空间比较，客户端安全，ClientMapCache 已有先例）。
     */
    private static boolean inGameDimension() {
        ClientLevel world = Minecraft.getInstance().level;
        return world != null && GameWorldManager.isGameWorld(world.dimension());
    }
}
