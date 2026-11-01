package com.paimeng.battleroyale.game;

/**
 * 大逃杀游戏阶段。纯 Java 枚举，不依赖 Minecraft，升级到高版本时原样可用。
 *
 * <pre>
 * WAITING --/paimengmods br start--> PREPARING --倒计时结束--> RUNNING --胜者产生/超时/停止--> ENDED --结算展示结束--> WAITING
 * WAITING --热身倒计时结束--> FLIGHT（航线阶段）
 * </pre>
 */
public enum GamePhase {

    /** 大厅：等人加入，可开下一局。 */
    WAITING("等待中"),

    /** 准备：已开局，倒计时中等所有人到位。 */
    PREPARING("准备中"),

    /** 航线阶段：热身倒计时结束、玩家已拉回出生岛并解除无敌后的阶段。 */
    FLIGHT("航线阶段"),

    /** 进行中：对局运行，毒圈 / 撤离 / 跳伞等玩法逻辑挂在这个阶段。 */
    RUNNING("进行中"),

    /** 已结束：展示结果，稍后自动回到 WAITING。 */
    ENDED("已结束");

    private final String displayName;

    GamePhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 大厅阶段（仅 WAITING）允许玩家进出名单。 */
    public boolean allowsRosterChange() {
        // [BR-MATCHTEAM] 一旦进入 PREPARING，MatchTeam 已依据当前大厅队伍分配完成，
        // 名单锁死；此后再 join 会造成"分配后新增玩家无队可归"，故只允许 WAITING 变更。
        return this == WAITING;
    }

    /** 是否算“一局还没结束”的状态（用于 /paimengmods br stop 的判断）。 */
    public boolean isActiveMatch() {
        return this == PREPARING || this == FLIGHT || this == RUNNING;
    }
}
