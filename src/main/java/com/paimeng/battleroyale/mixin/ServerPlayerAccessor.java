package com.paimeng.battleroyale.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * [r127] 读写 {@code ServerPlayer.isChangingDimension}——机上玩家免“移动校验”
 * 回弹的<b>唯一正确开关</b>。
 *
 * <p><b>名字说明（官方映射实况）</b>：这个标志在 Yarn 叫
 * {@code inTeleportationState}，但 Mojang 官方映射 1.20.1 的名字是
 * {@code isChangingDimension}（历史遗留命名，语义早已扩成“传送进行中”，
 * 不止跨维度）。映射链已逐环核实：yarn {@code field_13985 / method_14208 /
 * method_14240} → notch {@code cE / K / L}（类 {@code aig}）→
 * official {@code isChangingDimension() / hasChangedDimension()}
 * （fabric-loom {@code intermediary-v2.tiny} 与 Forge 缓存
 * {@code client_mappings.txt} 双向对得上）。
 *
 * <p>移植第二步曾断言 1.20.1 删除了该字段、改用
 * {@code ServerGamePacketListenerImpl.awaitingPositionFromClient} 顶替。对照
 * 1.20.1 字节码逐一核实后确认两件事：
 * <ol>
 *   <li>该标志在 1.20.1 <b>依然存在</b>（1.20.5 传送重构才删除），
 *       {@code handleMovePlayer} 的“moved too quickly”（100 格阈值）与
 *       “moved wrongly”（0.0625 容差橡皮筋）两处判定都被它的取反包住——它为
 *       true 时只跳过距离校验，位置与 <b>Yaw / Pitch 照常按客户端上报更新</b>；</li>
 *   <li>{@code awaitingPositionFromClient} 非空时 {@code handleMovePlayer} <b>整包直接
 *       return</b>（包含转动包），并且每 20 tick 用服务端冻结的 yaw 补发一次
 *       teleport——这正是“飞机上视角锁死正东、鼠标转不动”的根源。它只该由原版
 *       传送握手（teleportTo → 客户端确认）短暂持有，模组绝不能每 tick 强写。</li>
 * </ol>
 *
 * <p>因此 [BR-MTR] 豁免对改为：{@code abilities.mayfly=true}（防 floating too long 踢线）
 * + 本字段 {@code =true}（防 moved too quickly / moved wrongly 橡皮筋），全程不再触碰
 * {@code awaitingPositionFromClient}。登机传送后原版的 teleport-confirm 会经
 * {@code hasChangedDimension()} 把本字段翻回 false，所以
 * {@code FlightManager#reaffirmFlightExemption} 每 tick 重申——重申只影响上述两个
 * 跳过分支，不吞任何客户端输入。
 */
@Mixin(ServerPlayer.class)
public interface ServerPlayerAccessor {
    /** yarn: {@code isInTeleportationState()}；official: {@code isChangingDimension()}。 */
    @Accessor("isChangingDimension")
    boolean battleroyale$getInTeleportationState();

    /** yarn: {@code inTeleportationState} 字段的写入端；official: {@code isChangingDimension}。 */
    @Accessor("isChangingDimension")
    void battleroyale$setInTeleportationState(boolean value);
}
