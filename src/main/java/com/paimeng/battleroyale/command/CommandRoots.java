package com.paimeng.battleroyale.command;

/**
 * 全模组指令命名空间的唯一来源：所有指令都挂在 {@link #ROOT} 这一个根指令下。
 *
 * <p>为什么要统一一个根：原版 / 别的模组也可能用 {@code /br}、{@code /qc}
 * 这种两三个字母的短指令，撞名之后谁的注册先到就用谁的，指令会莫名其妙失灵。
 * 挂到 {@code /paimengmods} 下就不可能跟别人撞了。
 *
 * <p>分支分布（服务端一次注册同一棵指令树）：
 * <ul>
 *   <li>{@code /paimengmods br ...} —— 大逃杀与队伍指令，见 {@link BrCommand}；</li>
 *   <li>{@code /paimengmods qc ...} —— 服务端快捷消息指令，见 {@link QuickChatCommand}。</li>
 * </ul>
 *
 * <p>标点系统没有指令：V 键单击 / 双击直接触发（见 {@code client.PingClient}），
 * 原先的 {@code /paimengmods ping} 分支已按需求删除。
 *
 * <p>服务端只注册一次 {@code paimengmods} 根节点，下面同时挂着 br 与 qc 两支；
 * 客户端不再注册同名根节点，避免 1.18.2 的客户端命令树合并冲突。
 *
 * <p>本类没有任何 Minecraft 依赖，服务端 / 客户端都能直接用。
 */
public final class CommandRoots {

    /** 根指令名（不带斜杠）：{@code /paimengmods}。 */
    public static final String ROOT = "paimengmods";

    /** 大逃杀 + 队伍分支：{@code /paimengmods br}，服务端注册。 */
    public static final String BR = "br";

    /** 快捷消息分支：{@code /paimengmods qc}，服务端注册并校验预设消息。 */
    public static final String QC = "qc";

    /** 给玩家看的完整前缀：{@code /paimengmods br}，提示文案用。 */
    public static final String BR_PREFIX = "/" + ROOT + " " + BR;

    /** 给玩家看的完整前缀：{@code /paimengmods qc}，提示文案与点击填入用。 */
    public static final String QC_PREFIX = "/" + ROOT + " " + QC;

    private CommandRoots() {
    }
}
