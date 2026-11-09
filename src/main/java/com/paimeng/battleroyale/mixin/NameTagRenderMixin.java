package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.client.ClientDownedState;
import com.paimeng.battleroyale.client.ClientMatchRoster;
import com.paimeng.battleroyale.client.ClientTeamState;
import com.paimeng.battleroyale.client.ClientTeammatePosState;
import com.paimeng.battleroyale.client.TeamHudRenderer;
import net.minecraft.client.Minecraft;
import com.paimeng.battleroyale.network.DownedSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * [BR-NAMETAG] 头顶名字标签的队伍编号装饰（客户端渲染注入）。
 *
 * <p>本工程的 Fabric API（0.92.2+1.20.1）没有 {@code RenderNameTagEvent}
 * （已核对 fabric-rendering-v1 3.0.8 的公开 API 清单），所以改注入原版渲染的
 * 唯一咽喉点 {@link EntityRenderer#renderNameTag(Entity, Component, PoseStack,
 * MultiBufferSource, int)}——所有实体（含玩家与掉落物）的名字标签都从这一个
 * 方法画出去。{@code @ModifyVariable} 在 HEAD 替换 Component 参数，
 * 原版排版 / 距离淡出 / 潜行压矮等全部行为原样保留。
 *
 * <p>规则（与需求逐条对应）：
 * <ul>
 *   <li>只动 {@link Player}：掉落物、生物的名字标签原样经过本注入点
 *       ——拾取提示（看向物品时的名字）不受任何影响；</li>
 *   <li>查 {@link ClientMatchRoster}：座号 0（大厅阶段 / 未参赛者 / NPC）
 *       直接放行，名字维持原样——[BR-NOLOBBYNUMBER] 的渲染侧执行点；</li>
 *   <li>只装饰当前 MatchTeam 的队友；敌队玩家即便存在于全量花名册，也绝不能显示
 *       {@code [N号]} 前缀或队伍色；</li>
 *   <li>格式 {@code [1号] 玩家名}：{@code [N号]} 按座号固定色上色
 *       （1 黄 / 2 橙 / 3 蓝 / 4 绿，复用 {@link TeamHudRenderer#colorOf}），
 *       名字部分保留原版颜色；</li>
 *   <li>队友倒地（{@link ClientDownedState} 实时击倒同步）：名字部分压成
 *       深红（原版 DARK_RED = #AA0000，与队伍 HUD 的倒地色一致），
 *       座号前缀保留队伍色，方便远处一眼认出谁倒了。</li>
 * </ul>
 *
 * <p>阶段门闩不用查状态机：花名册包只在开局分配后广播、销毁时清空，
 * 「查得到座号」本身就等价于“本局进行中”。
 *
 * <p><b>性能</b>：渲染每帧每个可见玩家一次；座号查表 O(1)，未命中直接返回原
 * 引用（大厅零分配零拷贝），命中才新建两个轻量 Component——无 GL 状态改动、
 * 无纹理绑定、无循环，不构成卡顿源。
 */
@Environment(EnvType.CLIENT)
@Mixin(EntityRenderer.class)
public abstract class NameTagRenderMixin {

    /**
     * renderNameTag 的 Component 参数（ordinal 0：该方法唯一 Component 类型入参）。
     * handler 在值参数后追加目标方法入参（Mixin 官方文档认可写法），
     * 这里只需要第一个入参 entity，追加一个即可。
     */
    @ModifyVariable(method = "renderNameTag(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/network/chat/Component;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), ordinal = 0)
    private Component battleroyale$decorateNameTag(Component name, Entity entity) {
        // 只装饰玩家；掉落物名字（拾取提示）、生物名字一律原样放行。
        if (!(entity instanceof Player) || name == null) {
            return name;
        }
        Minecraft client = Minecraft.getInstance();
        Player localPlayer = client.player;
        // 仅给本地玩家当前 MatchTeam 的队友装饰编号。敌方玩家（即便在花名册里）、
        // 无队玩家以及玩家自己，都保持原版名字。
        if (localPlayer == null || entity.getUUID().equals(localPlayer.getUUID())
                || !battleroyale$isVisibleTeammate(entity.getUUID())) {
            return name;
        }
        int seat = ClientMatchRoster.seatOf(entity.getUUID());
        if (seat <= 0) {
            // 大厅阶段 / 未参赛者：维持原样，绝不显示局内编号。
            return name;
        }
        int seatColor = TeamHudRenderer.colorOf(seat) & 0x00FFFFFF;
        Component body = name;
        DownedSyncPayload downed = ClientDownedState.get(entity.getUUID());
        if (downed != null && downed.downed()) {
            // 倒地：名字压成深红（座号前缀保留队伍色）。
            body = name.copy().withStyle(ChatFormatting.DARK_RED);
        }
        MutableComponent prefix = Component.literal("[" + seat + "号]")
                .withStyle(style -> style.withColor(TextColor.fromRgb(seatColor)));
        return prefix.append(Component.literal(" ")).append(body);
    }

    private static boolean battleroyale$isVisibleTeammate(java.util.UUID target) {
        if (ClientTeamState.isMatch()) {
            for (var member : ClientTeamState.members()) {
                if (member.playerId().equals(target)) {
                    return true;
                }
            }
            // 当前队伍快照明确不是目标所在的队，不能误标成队友。
            return false;
        }
        // 兼容 TeamSync 尚未到达但服务端坐标同步已经建立的短暂窗口。
        return ClientTeammatePosState.contains(target);
    }
}
