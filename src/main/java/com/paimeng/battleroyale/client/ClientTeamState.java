package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.network.TeamSyncPayload;

import java.util.List;
import java.util.UUID;

/**
 * 客户端这一侧的队伍数据（只读缓存），对标 {@link ClientZoneState} 的做法。
 *
 * <p>数据来源：服务端 {@code TeamManager} 在队伍创建 / 有人加入 / 有人离开时
 * 当场发来的 {@link TeamSyncPayload}，由 {@code BattleRoyaleModClient} 收包写入；
 * 消费方：{@link TeamHudRenderer}（屏幕左上角的队友列表）。
 *
 * <p>"没有队伍"的几种情况都归一为一份空快照：从没入过队、离队（服务端单发一份
 * 空快照）、断线退服（本地 DISCONNECT 钩子 clear()）。
 *
 * <p>整份引用一次性替换 + volatile：收包线程赋值后渲染线程立即可见，
 * 不存在"读到一半列表被换掉"的撕裂问题（payload 本身是不可变的）。
 */
public final class ClientTeamState {

    private static final TeamSyncPayload NO_TEAM = TeamSyncPayload.empty();

    private static volatile TeamSyncPayload snapshot = NO_TEAM;

    private ClientTeamState() {
    }

    // ------------------------------------------------------------------
    // 写入（只由收包逻辑调用）
    // ------------------------------------------------------------------

    /** 收到服务端快照：整份替换；空快照等价于 clear()。 */
    public static void apply(TeamSyncPayload payload) {
        snapshot = (payload == null || payload.isEmpty()) ? NO_TEAM : payload;
    }

    /** 断线 / 退出世界：本地没有队伍了，HUD 什么都不画。 */
    public static void clear() {
        snapshot = NO_TEAM;
    }

    // ------------------------------------------------------------------
    // 读取（渲染线程）
    // ------------------------------------------------------------------

    /** 有没有队伍。false = HUD 一行都不画（需求：没队伍不显示任何内容）。 */
    public static boolean hasTeam() {
        return !snapshot.isEmpty();
    }

    public static int teamNumber() {
        return snapshot.teamNumber();
    }

    /** [BR-MATCHTEAM] 当前快照是不是本局临时队伍（true 时 HUD 表头显示 "#N队"）。 */
    public static boolean isMatch() {
        return snapshot.match();
    }

    /** [BR-MATCHTEAM 第四步] 本局快照是否来自被拆分的预组队（HUD 显示拆分提示）。 */
    public static boolean isSplit() {
        return snapshot.match() && snapshot.split();
    }

    /** 按玩家 UUID 查询其所在队伍编号；找不到时返回 0。 */
    public static int teamNumberOf(UUID playerId) {
        if (playerId == null || snapshot.isEmpty()) {
            return 0;
        }
        if (playerId.equals(snapshot.leaderId())
                || snapshot.members().stream().anyMatch(member -> member.playerId().equals(playerId))) {
            return snapshot.teamNumber();
        }
        return 0;
    }

    public static UUID leaderId() {
        return snapshot.leaderId();
    }

    /** 成员列表，已按队内编号升序。 */
    public static List<TeamSyncPayload.Member> members() {
        return snapshot.members();
    }
}
