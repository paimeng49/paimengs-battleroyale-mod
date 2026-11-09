package com.paimeng.battleroyale.game;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [BR-SETTLE] 吃鸡烟花：在每个存活玩家周围（半径 5~10 格内）分批生成烟花火箭实体，
 * 持续 4 秒（80 tick），每 10 tick 发一批（每名玩家每批 1 发）。
 *
 * <p>分批定时生成是硬要求：一次性铺几百发火箭会把服务器 TPS 打没。
 * 按 4 人小队算，全流程共 8 批 x 4 发 = 32 发实体，完全安全。
 */
public final class VictoryFireworks {

    public static final VictoryFireworks INSTANCE = new VictoryFireworks();

    /** 特效总时长：4 秒（需求口径 3~5 秒）。 */
    private static final int DURATION_TICKS = 80;
    /** 批次间隔：0.5 秒一批，共 8 批。 */
    private static final int INTERVAL_TICKS = 10;
    /** 环绕半径：5~10 格随机。 */
    private static final double MIN_RADIUS = 5.0;
    private static final double MAX_RADIUS = 10.0;

    private final Random random = new Random();
    private final List<ServerPlayer> survivors = new ArrayList<>();
    private int elapsedTicks;
    private boolean running;

    private VictoryFireworks() {}

    /** 吃鸡时调用：记住这批存活玩家，从头开始 4 秒烟花流程。 */
    public void start(List<ServerPlayer> players) {
        survivors.clear();
        running = false;
        if (players == null || players.isEmpty()) {
            return;
        }
        survivors.addAll(players);
        elapsedTicks = 0;
        running = true;
    }

    /** 由服务端主线程每 tick 驱动（BattleRoyaleMod 注册）。 */
    public void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        if (elapsedTicks % INTERVAL_TICKS == 0) {
            launchBatch();
        }
        elapsedTicks++;
        if (elapsedTicks >= DURATION_TICKS) {
            running = false;
            survivors.clear();
        }
    }

    /** 一批：在每个存活玩家当前位置周围随机方向生成一发火箭。 */
    private void launchBatch() {
        // 倒序遍历，玩家已离线/维度异常时直接剔除，避免空引用。
        for (int i = survivors.size() - 1; i >= 0; i--) {
            ServerPlayer player = survivors.get(i);
            if (!(player.level() instanceof ServerLevel level)) {
                survivors.remove(i);
                continue;
            }
            launchAround(level, player.getX(), player.getY(), player.getZ());
        }
    }

    private void launchAround(ServerLevel level, double x, double y, double z) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
        double sx = x + Math.cos(angle) * radius;
        double sz = z + Math.sin(angle) * radius;
        // 从玩家上方 2~6 格生成，火箭随即向上飞并爆炸。
        double sy = y + 2.0 + random.nextDouble() * 4.0;
        FireworkRocketEntity rocket = new FireworkRocketEntity(level, sx, sy, sz, createRocket());
        level.addFreshEntity(rocket);
        for (ServerPlayer viewer : level.getPlayers(p -> p.distanceToSqr(sx, sy, sz) <= 64.0D * 64.0D)) {
            level.sendParticles(viewer, ParticleTypes.END_ROD, false,
                    sx, sy, sz, 6, 0.2, 0.2, 0.2, 0.04);
        }
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
