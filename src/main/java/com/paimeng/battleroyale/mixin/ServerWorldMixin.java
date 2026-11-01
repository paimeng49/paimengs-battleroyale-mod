package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.map.EditWorldManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin {
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
    private void battleroyale$skipIdleEditWorld(java.util.function.BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ServerLevel world = (ServerLevel) (Object) this;
        if (EditWorldManager.shouldSkipTick(world)) {
            ci.cancel();
        }
    }
}
