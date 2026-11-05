package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.network.MatchRosterPayload;

import java.util.Map;
import java.util.UUID;

/**
 * [BR-NAMETAG] 客户端花名册缓存（只读）：本局<b>所有参赛者</b>的「UUID → 座号」。
 *
 * <p>数据来源：服务端 {@code MatchTeamManager} 开局广播、比赛期间每 10 秒重播的
 * {@link MatchRosterPayload}（battleroyale:match_roster 通道），由
 * {@code BattleRoyaleModClient} 收包写入；消费方：{@code NameTagRenderMixin}
 * （头顶名字拼 [N号] 前缀）。
 *
 * <p>为什么不能只查 {@link ClientTeamState}：那份快照只有自己队伍，
 * NameTag 要给视野里<b>任何一名玩家</b>（含别的队）拼座号，必须拿得到全量表。
 *
 * <p>与 {@link ClientTeamState} 同款线程约定：整份不可变 Map 引用一次性替换 +
 * volatile，收包线程赋值、渲染线程读取，不存在读到一半被改的问题。
 */
public final class ClientMatchRoster {

    private static final Map<UUID, Integer> NO_ROSTER = Map.of();

    private static volatile Map<UUID, Integer> seatByPlayer = NO_ROSTER;

    // [r143][BR-NAMETAG] 实机排障日志：花名册“收到第一份 / 被清空”各打一行，
    // 持续有效时每 30 秒补一行心跳。apply 可能跑在网络线程，状态用锁保护。
    private static final Object LOG_LOCK = new Object();
    private static boolean hadRoster = false;
    private static long lastLogMillis = 0L;

    private ClientMatchRoster() {
    }

    /** 收到服务端花名册：整份替换；空包 / 未开局等价于 clear()。 */
    public static void apply(MatchRosterPayload payload) {
        if (payload == null || !payload.active() || payload.entries().isEmpty()) {
            seatByPlayer = NO_ROSTER;
            logState(false, 0);
            return;
        }
        Map<UUID, Integer> next = new java.util.HashMap<>(payload.entries().size() * 2);
        for (MatchRosterPayload.Entry entry : payload.entries()) {
            next.put(entry.playerId(), entry.seat());
        }
        seatByPlayer = Map.copyOf(next);
        logState(true, payload.entries().size());
    }

    /**
     * [r143] 收包状态日志：空→非空（收到第一份花名册）与非空→空各一行；
     * 持续有效时按 30 秒节流心跳，确认“包一直通着”（小地图队友层的间接佐证）。
     */
    private static void logState(boolean hasRoster, int count) {
        synchronized (LOG_LOCK) {
            long now = System.currentTimeMillis();
            if (hasRoster && !hadRoster) {
                BattleRoyaleMod.LOGGER.info("[BR-NAMETAG][客户端] 收到本局花名册：{} 人（NameTag 座号生效）", count);
                lastLogMillis = now;
            } else if (!hasRoster && hadRoster) {
                BattleRoyaleMod.LOGGER.info("[BR-NAMETAG][客户端] 花名册已清空（空包 / 未开局）");
            } else if (hasRoster && now - lastLogMillis >= 30_000L) {
                BattleRoyaleMod.LOGGER.info("[BR-NAMETAG][客户端] 花名册持续有效：{} 人", count);
                lastLogMillis = now;
            }
            hadRoster = hasRoster;
        }
    }

    /** 断线 / 退出世界：没有本局概念，NameTag 维持原样。 */
    public static void clear() {
        seatByPlayer = NO_ROSTER;
        synchronized (LOG_LOCK) {
            if (hadRoster) {
                BattleRoyaleMod.LOGGER.info("[BR-NAMETAG][客户端] 本地清空花名册（断线/退图）");
                hadRoster = false;
            }
        }
    }

    /**
     * 查某玩家的局内座号；0 = 查无此人（大厅阶段 / NPC / 未参赛者），
     * 调用方据此原样保留 vanilla 名字。
     */
    public static int seatOf(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        Integer seat = seatByPlayer.get(playerId);
        return seat == null ? 0 : seat;
    }
}
