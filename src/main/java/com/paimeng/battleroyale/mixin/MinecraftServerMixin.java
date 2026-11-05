package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.team.MatchTeamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "createLevels(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V", at = @At("TAIL"))
    private void battleroyale$registerEditWorlds(ChunkProgressListener listener, CallbackInfo ci) {
        // [BR-INVISO] 先恢复上一个会话没结清的背包隔离台账（按当前存档目录读盘），
        // 必须赶在玩家登录前完成，登录兜底 onLogin 才有账可查。
        com.paimeng.battleroyale.game.InventoryIsolation.INSTANCE.load((MinecraftServer) (Object) this);
        EditWorldManager.registerAll((MinecraftServer) (Object) this);
        // 磁盘上已有数据的游戏维度在启动时就挂进 worlds：玩家登录时原版
        // PlayerManager.createPlayer 按存档里的 Dimension 键查世界，查不到会报
        // “Unknown respawn dimension …, defaulting to overworld” 并把人丢回主世界。
        GameWorldManager.registerSavedWorldsAtStartup((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void battleroyale$tickCustomWorlds(java.util.function.BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        EditWorldManager.tick(server);
        GameWorldManager.tick(server);
    }

    @Inject(method = "stopServer()V", at = @At("HEAD"))
    private void battleroyale$clearCustomWorlds(CallbackInfo ci) {
        MatchTeamManager.INSTANCE.clearSilently();
        GameWorldManager.onServerStopping();
        EditWorldManager.onServerStopping();
    }
}
