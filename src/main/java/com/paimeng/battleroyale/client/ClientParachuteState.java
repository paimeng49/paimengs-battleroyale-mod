package com.paimeng.battleroyale.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端收到的服务端权威开伞状态（{@code battleroyale:parachute_state}），按玩家
 * UUID 保存；并在每客户端 tick 把开伞玩家的<b>包围盒临时放大</b>——降落伞伞盖
 * 比玩家 0.6x1.8 的碰撞箱大得多（约 2.5 格宽、头顶 3 格高），原版按玩家包围盒做
 * 视锥剔除，抬头/平视远处跳伞者时伞盖容易被整帧剔掉（[BR-PARA-CULL]）。
 */
public final class ClientParachuteState {
    private static final Map<UUID, Boolean> STATES = new HashMap<>();
    /** 上一 tick 被放大过包围盒的玩家：状态关闭/实体消失时要还原一次。 */
    private static final Set<UUID> EXPANDED = new HashSet<>();
    /** 伞盖横向半径（格）。 */
    private static final double SIDE = 1.8D;
    /** 放大后包围盒：脚底下探 0.2、头顶上伸 4.4（覆盖伞盖 + 悬摆余量）。 */
    private static final double DOWN = 0.2D;
    private static final double UP = 4.4D;

    private ClientParachuteState() {
    }

    public static void set(UUID id, boolean parachuting) {
        if (id != null) {
            STATES.put(id, parachuting);
        }
    }

    public static boolean isParachuting(UUID id) {
        return id != null && Boolean.TRUE.equals(STATES.get(id));
    }

    /**
     * 每客户端 tick（END_CLIENT_TICK）调用：给开伞中的玩家实体（含自己）撑大
     * 包围盒躲视锥剔除；状态关闭或实体被移除时把包围盒缩回原版尺寸。
     */
    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            EXPANDED.clear();
            return;
        }
        Set<UUID> done = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Player)) {
                continue;
            }
            UUID id = entity.getUUID();
            done.add(id);
            if (isParachuting(id)) {
                AABB box = entity.getBoundingBox();
                double cx = (box.minX + box.maxX) * 0.5;
                double cz = (box.minZ + box.maxZ) * 0.5;
                entity.setBoundingBox(new AABB(cx - SIDE, box.minY - DOWN, cz - SIDE,
                        cx + SIDE, box.minY + UP, cz + SIDE));
                EXPANDED.add(id);
            } else if (EXPANDED.remove(id)) {
                AABB box = entity.getBoundingBox();
                double cx = (box.minX + box.maxX) * 0.5;
                double cz = (box.minZ + box.maxZ) * 0.5;
                entity.setBoundingBox(new AABB(cx - 0.3, box.minY, cz - 0.3,
                        cx + 0.3, box.minY + 1.8, cz + 0.3));
            }
        }
        EXPANDED.removeIf(id -> !done.contains(id));
    }

    public static void clear() {
        STATES.clear();
        EXPANDED.clear();
    }
}
