package com.paimeng.battleroyale.entity;

import com.paimeng.battleroyale.game.AirdropManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.particles.ParticleTypes;

/** 空投降落实体：服务端权威控制速度和落地，客户端只负责显示。 */
public final class AirdropEntity extends Entity {
    public static final double FALL_SPEED = -0.8D;

    public AirdropEntity(EntityType<? extends AirdropEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.setNoGravity(true);
    }

    public AirdropEntity(Level level, double x, double y, double z) {
        this(ModEntities.AIRDROP, level);
        this.setPos(x, y, z);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            this.setNoGravity(true);
            this.setDeltaMovement(0.0D, FALL_SPEED, 0.0D);
            this.move(MoverType.SELF, this.getDeltaMovement());
            if (level().getGameTime() % 2L == 0L) {
                ((ServerLevel) level()).sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.35D, getZ(), 2, 0.12D, 0.15D, 0.12D, 0.01D);
                ((ServerLevel) level()).sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.1D, getZ(), 1, 0.08D, 0.08D, 0.08D, 0.0D);
                ((ServerLevel) level()).sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 0.2D, getZ(), 1, 0.08D, 0.08D, 0.08D, 0.0D);
            }
            if (getY() <= level().getMinBuildHeight() + 1) {
                discard();
                return;
            }
            BlockPos below = BlockPos.containing(getX(), getY() - 0.15D, getZ());
            BlockState state = level().getBlockState(below);
            if (!state.isAir() && state.isSolid()) {
                AirdropManager.INSTANCE.land((ServerLevel) level(), blockPosition(), getUUID());
            }
        }
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }
}
