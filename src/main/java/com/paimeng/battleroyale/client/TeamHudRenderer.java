package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.util.BrText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;

import java.util.List;
import java.util.UUID;

/**
 * 队友编号 HUD：屏幕<b>左上角</b>画一列"编号 名字"，只在客户端存在
 * （挂在 {@code HudRenderCallback} 上，见 {@code BattleRoyaleModClient}）。
 *
 * <p>规则（都来自需求）：
 * <ul>
 *   <li>数据只读 {@link ClientTeamState}；没有队伍 → 一行都不画；</li>
 *   <li>每行 = 队员编号 + 空格 + 名字，编号用<b>该成员编号固定对应</b>的标志色：
 *       1 黄、2 橙、3 蓝、4 绿；取色用的是成员条目自带的<b>队内编号</b>
 *       （{@code member.number()}），不是队伍编号；</li>
 *   <li>队内编号超过 4（异常情况）回退到白色；</li>
 *   <li>表头一行"队伍 N"；队长在行尾加 ★，自己这行的名字高亮成绿色
 *       ——这两个是锦上添花，不影响"编号 名字"的主体格式。</li>
 * </ul>
 *
 * <p>绘制用原版静态工具 {@link DrawableHelper#drawTextWithShadow}，
 * 不开任何 GL 状态，画完即走。
 *
 * <p><b>升级预留</b>：1.21+ DrawableHelper 系列改成 GuiRender 上下文，
 * 到时把两处 drawTextWithShadow 换成对应新入口；取色映射不动。
 */
@Environment(EnvType.CLIENT)
public final class TeamHudRenderer {

    /** 左边距 / 上边距（和原版 HUD 的惯例一致，留 4 像素）。 */
    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    /** 行距在字高基础上再加 1 像素，看着不挤。 */
    private static final int LINE_GAP = 1;

    /**
     * 队内编号 → 标志色（ARGB，固定映射，不做循环取色）。
     * 需求指定：1 黄、2 橙、3 蓝、4 绿。
     */
    private static final int NUMBER_1_YELLOW = 0xFFFFFF00;
    private static final int NUMBER_2_ORANGE = 0xFFFFA500;
    private static final int NUMBER_3_BLUE = 0xFF00BFFF;
    private static final int NUMBER_4_GREEN = 0xFF00FF00;
    /** 编号超出 1-4（异常情况）的兜底色：白色。 */
    private static final int NUMBER_FALLBACK_WHITE = 0xFFFFFFFF;

    private static final int NAME_WHITE = 0xFFE0E0E0;     // 队友名字：亮白
    private static final int NAME_SELF = 0xFF55FF55;      // 自己这行的名字用绿色
    private static final int HEADER = 0xFFAAAAAA;         // 表头灰色
    /** [BR-MATCHTEAM 第四步] 预组队拆分提示的金色。 */
    private static final int SPLIT_MARK = 0xFFFFD700;

    private TeamHudRenderer() {
    }

    /**
     * 队内编号对应的固定标志色。
     *
     * @param memberNumber <b>队内编号</b>（取自成员条目 {@code member.number()}，
     *                     不是队伍编号）；1-4 有专属色，其余（含 0 / 负数等异常）一律白色
     */
    public static int colorOf(int memberNumber) {
        return switch (memberNumber) {
            case 1 -> NUMBER_1_YELLOW;
            case 2 -> NUMBER_2_ORANGE;
            case 3 -> NUMBER_3_BLUE;
            case 4 -> NUMBER_4_GREEN;
            default -> NUMBER_FALLBACK_WHITE;
        };
    }

    /** 挂在 {@code HudRenderCallback.EVENT} 上，每帧一次。 */
    public static void render(GuiGraphics graphics, float tickDelta) {
        PoseStack matrices = graphics.pose();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.options.hideGui) {
            return;
        }
        if (!ClientTeamState.hasTeam()) {
            return;                         // 需求：没有队伍不显示任何内容
        }

        Font text = client.font;
        List<com.paimeng.battleroyale.network.TeamSyncPayload.Member> members =
                ClientTeamState.members();
        UUID leaderId = ClientTeamState.leaderId();
        UUID selfId = player.getUUID();

        // 左上角顶部若被状态 HUD（剩余/击杀）占用，队友列表整体下移避让；
        // 没有统计时 occupiedHeight=0，行为与旧版完全一致。
        int y = MARGIN_Y + StatsHudRenderer.occupiedHeight(text);
        // [BR-MATCHTEAM 模块三] 表头：局内 MatchTeam 显示 "#N队"，大厅队沿用 "队伍 N"。
        String header = ClientTeamState.isMatch()
                ? "#" + ClientTeamState.teamNumber() + "队"
                : "队伍 " + ClientTeamState.teamNumber();
        GuiDraw.drawTextWithShadow(matrices, text,
                BrText.mutable(header).withStyle(ClientTeamState.isMatch() ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                MARGIN_X, y, ClientTeamState.isMatch() ? colorOf(1) : HEADER);
        y += text.lineHeight + LINE_GAP;

        for (com.paimeng.battleroyale.network.TeamSyncPayload.Member member : members) {
            // 1) 编号：按该成员自己的队内编号取固定标志色（不用队伍编号）
            String numberText = Integer.toString(member.number());
            GuiDraw.drawTextWithShadow(matrices, text, BrText.of(numberText),
                    MARGIN_X, y, colorOf(member.number()));

            // 2) 名字：自己绿色，队友白色；队长行尾加 ★
            boolean self = member.playerId().equals(selfId);
            String namePart = " " + member.name()
                    + (member.playerId().equals(leaderId) ? " ★" : "")
                    + (self ? "（我）" : "");
            GuiDraw.drawTextWithShadow(matrices, text, BrText.of(namePart),
                    MARGIN_X + text.width(numberText + " "), y, self ? NAME_SELF : NAME_WHITE);

            // [BR-MATCHTEAM 第四步] 经历过预组队拆分的成员行尾加标记
            if (member.split()) {
                int nameEnd = MARGIN_X + text.width(numberText + " " + namePart);
                GuiDraw.drawTextWithShadow(matrices, text, BrText.of(" ✂"),
                        nameEnd, y, SPLIT_MARK);
            }

            y += text.lineHeight + LINE_GAP;
        }

        // [BR-MATCHTEAM 第四步] 自己被拆分过：列表底部给一行明确提示
        if (ClientTeamState.isMatch() && selfSplit(members, selfId)) {
            GuiDraw.drawTextWithShadow(matrices, text,
                    BrText.of("由于地图限制，您的预组队已被拆分"),
                    MARGIN_X, y + LINE_GAP, SPLIT_MARK);
        }
    }

    /** 自己这一行是否带拆分标记。 */
    private static boolean selfSplit(
            List<com.paimeng.battleroyale.network.TeamSyncPayload.Member> members, UUID selfId) {
        for (com.paimeng.battleroyale.network.TeamSyncPayload.Member member : members) {
            if (member.playerId().equals(selfId)) {
                return member.split();
            }
        }
        return false;
    }
}
