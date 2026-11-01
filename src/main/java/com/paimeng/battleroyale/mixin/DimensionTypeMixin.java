package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.map.EditWorldManager;
import com.paimeng.battleroyale.map.GameWorldManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {
    @Inject(method = "getStorageFolder(Lnet/minecraft/resources/ResourceKey;Ljava/nio/file/Path;)Ljava/nio/file/Path;", at = @At("HEAD"), cancellable = true)
    private static void battleroyale$redirectCustomWorldDir(ResourceKey<Level> key, Path worldDirectory, CallbackInfoReturnable<Path> cir) {
        Path archive = EditWorldManager.archiveRootFor(key);
        if (archive != null) {
            cir.setReturnValue(archive);
            return;
        }
        Path gameRoot = GameWorldManager.storageRootFor(key);
        if (gameRoot != null) {
            cir.setReturnValue(gameRoot);
        }
    }
}
