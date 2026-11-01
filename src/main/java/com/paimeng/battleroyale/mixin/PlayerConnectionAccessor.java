package com.paimeng.battleroyale.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读写 {@code ServerGamePacketListenerImpl.awaitingPositionFromClient}。
 *
 * <p>[r127] <b>本字段不再是机上豁免通道</b>，只保留作诊断 / 兼容兜底：
 * 移植第二步曾以为 1.20.1 删除了 {@code ServerPlayer.inTeleportationState}、
 * 用 awaiting 顶替，并让 FlightManager 每 tick 强写它。对照官方字节码核实：
 * {@code handleMovePlayer} 在 {@code awaitingPositionFromClient != null} 时
 * <b>整包丢弃移动包（含 Yaw/Pitch）</b>，且每 20 tick 用冻结的服务端视角补发
 * teleport——这就是“飞机上视角锁死正东”的直接根因。
 *
 * <p>正确的豁免开关是 {@link ServerPlayerAccessor}（实体侧
 * {@code inTeleportationState}：只跳过 moved too quickly / moved wrongly 两处
 * 距离校验，位置和视角照常更新）。awaiting 字段交还给原版传送握手
 * （teleport 包 → 客户端 confirm）自行置/清，模组全程不再读写它——
 * 本 accessor 仅保留作后续诊断用途。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface PlayerConnectionAccessor {
    @Accessor("awaitingPositionFromClient")
    Vec3 battleroyale$getAwaitingPosition();

    @Accessor("awaitingPositionFromClient")
    void battleroyale$setAwaitingPosition(Vec3 pos);
}
