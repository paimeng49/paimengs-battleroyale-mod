package com.paimeng.battleroyale.team;

/**
 * 标点的类型（需求写死的两种，消息文案不许自定义）：
 *
 * <ul>
 *   <li>{@link #WAYPOINT}：V 键<b>单击</b>标记一处地点。队伍消息固定为
 *       {@code 玩家名：我标记了一处地点}，绿色高亮 + 经验拾取音效；
 *       世界渲染与发起者自己的小地图标记<b>永久保留</b>（队友的世界渲染 10 秒后消失）。</li>
 *   <li>{@link #ENEMY}：V 键<b>双击</b>标记敌人。队伍消息固定为
 *       {@code 玩家名：发现敌人}，绿色高亮 + 经验拾取音效；
 *       世界渲染与小地图标记都是 10 秒后消失。</li>
 * </ul>
 *
 * <p>网络包里用 {@link #id()}（VarInt）传输，收端用 {@link #fromId(int)} 还原；
 * 未知 id 返回 null，由调用方丢弃脏包。
 */
public enum PingType {

    WAYPOINT(0, "我标记了一处地点"),
    ENEMY(1, "发现敌人");

    private final int id;
    private final String fixedText;

    PingType(int id, String fixedText) {
        this.id = id;
        this.fixedText = fixedText;
    }

    /** 网络传输用的稳定编号（别改，改了新旧客户端会互认不出）。 */
    public int id() {
        return id;
    }

    /** 写死在队伍消息里的固定文案（需求指定，不读配置）。 */
    public String fixedText() {
        return fixedText;
    }

    /** id → 类型；未知 id（脏包 / 新版本加的枚举）返回 null。 */
    public static PingType fromId(int id) {
        for (PingType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
