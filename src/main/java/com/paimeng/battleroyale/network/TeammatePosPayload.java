package com.paimeng.battleroyale.network;

import java.util.List;
import java.util.UUID;

/**
 * [BR-MINIMAP] 队友坐标数据包（服务端 → 单个队伍的每名在线成员，含已淘汰的观战者）。
 *
 * <p>小地图上的队友图标需要队友的<b>实时世界坐标</b>：原版实体追踪只在
 * 视距范围内把队友送到客户端，地图上相距几百米的队友在客户端根本没有实体，
 * 所以坐标必须由服务端周期性定向同步（每队只发自己队，绝不全图广播）。
 *
 * <p>每条 = 队友 UUID + 座号 + X/Z + 存活状态：座号供图标按 1~4 号位固定色上色，
 * 状态（{@link TeamSyncPayload#STATUS_ALIVE} 等）供倒地闪烁 / 淘汰隐藏判定。
 * 数据自足，不再依赖客户端本地快照的时效性。
 *
 * <p>空列表 = 当前没有队友坐标可画（本局销毁 / 回大厅），客户端收到即清缓存。
 */
public record TeammatePosPayload(List<Entry> entries) {

    /** 一名队友：UUID + 局内座号（1 号位起）+ 世界 X/Z + 存活状态码。 */
    public record Entry(UUID playerId, int seat, double x, double z, int status) {
    }

    public static TeammatePosPayload empty() {
        return new TeammatePosPayload(List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
