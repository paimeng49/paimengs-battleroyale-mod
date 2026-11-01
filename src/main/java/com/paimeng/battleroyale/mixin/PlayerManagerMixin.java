package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.map.GameWorldManager;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [BR-WORLD-FIX] 玩家登录时的维度校验 Mixin（PlayerManager 是 Yarn 叫法，
 * 本模组用 Mojang 官方映射，对应类为 {@link PlayerList}）。
 *
 * <p>注入点在 {@code placeNewPlayer} 最开始：此时原版已按玩家存档里的 Dimension
 * 键把 ServerPlayer 绑到某个 ServerLevel（键查得到就用、查不到回主世界），
 * 但还没有把玩家加进世界实体表、也没发登录包。我们在这唯一的"还能改"的窗口
 * 检查绑定结果：
 * <ul>
 *   <li>绑定的是 battleroyale_game:* / battleroyale_edit:* 而实例不存在
 *       （维度未加载或已卸载）或已失效（正在卸载 / 引用了别的服务器）
 *       → 强制改绑 minecraft:overworld 并清掉指向死维度的重生点；</li>
 *   <li>否则玩家在死亡/传送时还会被丢回那个永远不会加载出来的维度，
 *     客户端表现为卡"加载地形中"。</li>
 * </ul>
 * 单人档"退出到标题 → 重进"（同一 JVM 换 MinecraftServer 实例）和服务器强制
 * 重连都走这个方法，两种场景一并覆盖。具体校验逻辑在
 * {@link GameWorldManager#validateLoginPlayer} ——Mixin 保持薄，逻辑可复用。
 */
@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {
    /** PlayerList 持有的服务器引用（Mojang 映射字段名 server）。 */
    @Shadow
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer(Lnet/minecraft/network/Connection;"
            + "Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void battleroyale$validateLoginDimension(Connection connection, ServerPlayer player, CallbackInfo ci) {
        GameWorldManager.validateLoginPlayer(this.server, player);
    }
}
