package com.paimeng.battleroyale.item;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * [r149 MEDICAL 模块二] 医疗物品的限时移速加成（能量饮料 +10%、止痛药 +20%）。
 *
 * <p>原版药水效果体系表达不了「10% / 20%」这种百分比（SPEED 效果一档就是 +20%，
 * 也没有半档），所以直接挂一条 MULTIPLY_TOTAL 属性修饰符，到期由服务端 tick 摘除：
 * <ul>
 *   <li>全体玩家共用同一个修饰符 UUID——属性实例本来就按玩家隔离，互不干扰；
 *       重复饮用时「先摘旧、再挂新」，等效刷新时长；</li>
 *   <li>修饰符走 {@code addPermanentModifier}，会随玩家存档持久化——因此
 *       登录 / 断线都做一次兜底清理，防止「下线把 buff 存进档案」「重连带旧 buff」；</li>
 *   <li>玩家死亡重生后属性容器重建，残留自动消失，无需额外清理入口。</li>
 * </ul>
 */
public final class SpeedBoostManager {

    /** 全队共用的修饰符 ID（固定 UUID，不与任何原版修饰符冲突）。 */
    public static final UUID MODIFIER_ID =
            UUID.fromString("7f2a9c31-58d4-4b6e-9a05-1d3f7c08e24b");
    private static final String MODIFIER_NAME = "battleroyale.medical_speed";

    /** 每玩家的加成记录：playerId -> 起始 tick / 持续 tick（服务端主线程访问，无并发）。 */
    private static final class Boost {
        int startTick;
        final int durationTicks;

        Boost(int startTick, int durationTicks) {
            this.startTick = startTick;
            this.durationTicks = durationTicks;
        }
    }

    private static final Map<UUID, Boost> BOOSTS = new HashMap<>();
    private static int currentTick;

    private SpeedBoostManager() {
    }

    /** 施加 / 刷新移速加成（服务端主线程调用）。amount 为倍率：0.10 = +10%。 */
    public static void apply(ServerPlayer player, double amount, int durationTicks) {
        remove(player);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.addPermanentModifier(new AttributeModifier(
                MODIFIER_ID, MODIFIER_NAME, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
        BOOSTS.put(player.getUUID(), new Boost(currentTick, durationTicks));
    }

    /** 摘除加成与跟踪记录（没有时静默）。 */
    public static void remove(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(MODIFIER_ID);
        }
        BOOSTS.remove(player.getUUID());
    }

    /**
     * tick 入口：到期摘除。挂在 Fabric END_SERVER_TICK 上
     * （与 GameManager / ZoneManager 同一服务端主线程，无锁）。
     */
    public static void tick(MinecraftServer server) {
        currentTick = server.getTickCount();
        if (BOOSTS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Boost>> it = BOOSTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Boost> entry = it.next();
            Boost boost = entry.getValue();
            if (currentTick - boost.startTick < boost.durationTicks) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(MODIFIER_ID);
                }
            }
        }
    }

    /** 登录兜底：不在跟踪表里的玩家，清掉存档可能带来的残留修饰符。 */
    public static void ensureCleanJoin(ServerPlayer player) {
        if (!BOOSTS.containsKey(player.getUUID())) {
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.removeModifier(MODIFIER_ID);
            }
        }
    }

    /** 断线兜底：从跟踪表摘除（属性实例随玩家对象一起消失）。 */
    public static void handleDisconnect(UUID playerId) {
        BOOSTS.remove(playerId);
    }
}
