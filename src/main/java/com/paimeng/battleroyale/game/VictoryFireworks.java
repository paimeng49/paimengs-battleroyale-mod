package com.paimeng.battleroyale.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.particles.ParticleTypes;

import java.util.List;
import java.util.Random;

/** 吃鸡时在冠军队伍几何中心上空按八边形依次发射烟花。 */
public final class VictoryFireworks {
    public static final VictoryFireworks INSTANCE = new VictoryFireworks();
    private static final int DURATION_TICKS = 200;
    private static final int INTERVAL_TICKS = 10;
    private static final double RADIUS = 18.0;
    private final Random random = new Random();
    private ServerLevel level;
    private double centerX;
    private double centerY;
    private double centerZ;
    private int ticksLeft;
    private int launchIndex;

    private VictoryFireworks() {}

    public void start(List<ServerPlayer> survivors) {
        if (survivors == null || survivors.isEmpty()) return;
        ServerPlayer first = survivors.get(0);
        if (!(first.level() instanceof ServerLevel serverLevel)) return;
        double x = 0.0, y = 0.0, z = 0.0;
        int count = 0;
        for (ServerPlayer player : survivors) {
            if (player.level() != serverLevel) continue;
            x += player.getX();
            y += player.getY();
            z += player.getZ();
            count++;
        }
        if (count == 0) return;
        level = serverLevel;
        centerX = x / count;
        centerY = y / count;
        centerZ = z / count;
        ticksLeft = DURATION_TICKS;
        launchIndex = 0;
    }

    public void tick(MinecraftServer server) {
        if (level == null || ticksLeft <= 0) {
            level = null;
            ticksLeft = 0;
            return;
        }
        if ((DURATION_TICKS - ticksLeft) % INTERVAL_TICKS == 0) launch();
        ticksLeft--;
    }

    private void launch() {
        int vertex = launchIndex++ % 8;
        double angle = vertex * (Math.PI / 4.0);
        double x = centerX + Math.cos(angle) * RADIUS;
        double z = centerZ + Math.sin(angle) * RADIUS;
        double y = centerY + 1.0;
        ItemStack rocket = createRocket();
        FireworkRocketEntity entity = new FireworkRocketEntity(level, x, y, z, rocket);
        level.addFreshEntity(entity);
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 10, 0.25, 0.25, 0.25, 0.04);
    }

    private ItemStack createRocket() {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        CompoundTag fireworks = new CompoundTag();
        ListTag explosions = new ListTag();
        CompoundTag explosion = new CompoundTag();
        int[] colors = {0xFF5555, 0xFFAA00, 0x55FF55, 0x55AAFF, 0xFF55FF, 0xFFFF55};
        explosion.putIntArray("Colors", new int[]{colors[random.nextInt(colors.length)]});
        explosion.putByte("Type", (byte) random.nextInt(5));
        explosion.putByte("Trail", (byte) 1);
        explosion.putByte("Flicker", (byte) 1);
        explosions.add(explosion);
        fireworks.put("Explosions", explosions);
        fireworks.putByte("Flight", (byte) 1);
        CompoundTag tag = new CompoundTag();
        tag.put("Fireworks", fireworks);
        stack.setTag(tag);
        return stack;
    }
}
