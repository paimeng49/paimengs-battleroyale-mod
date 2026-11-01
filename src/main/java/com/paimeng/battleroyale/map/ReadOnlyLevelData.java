package com.paimeng.battleroyale.map;

import net.minecraft.CrashReportCategory;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.timers.TimerQueue;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * 1.20.1 移植（第二步）：等价于 1.18.2 Yarn 的 {@code UnmodifiableLevelProperties}——
 * 给动态挂载的自定义维度一份“只读”的关卡数据：全部 getter 委托给真实存档数据
 * （主世界的 {@link ServerLevelData}），全部 setter 静默丢弃，
 * 防止编辑/游戏维度把主世界存档的天气、时间、边界等写坏。
 */
public class ReadOnlyLevelData implements ServerLevelData {

    private final ServerLevelData delegate;

    public ReadOnlyLevelData(ServerLevelData delegate) {
        this.delegate = delegate;
    }

    @Override public String getLevelName() { return delegate.getLevelName(); }
    @Override public void setThundering(boolean thundering) { }
    @Override public int getRainTime() { return delegate.getRainTime(); }
    @Override public void setRainTime(int rainTime) { }
    @Override public void setThunderTime(int thunderTime) { }
    @Override public int getThunderTime() { return delegate.getThunderTime(); }
    @Override public void fillCrashReportCategory(CrashReportCategory category, LevelHeightAccessor level) {
        delegate.fillCrashReportCategory(category, level);
    }
    @Override public int getClearWeatherTime() { return delegate.getClearWeatherTime(); }
    @Override public void setClearWeatherTime(int clearWeatherTime) { }
    @Override public int getWanderingTraderSpawnDelay() { return delegate.getWanderingTraderSpawnDelay(); }
    @Override public void setWanderingTraderSpawnDelay(int delay) { }
    @Override public int getWanderingTraderSpawnChance() { return delegate.getWanderingTraderSpawnChance(); }
    @Override public void setWanderingTraderSpawnChance(int chance) { }
    @Override public UUID getWanderingTraderId() { return delegate.getWanderingTraderId(); }
    @Override public void setWanderingTraderId(UUID id) { }
    @Override public GameType getGameType() { return delegate.getGameType(); }
    @Override public void setWorldBorder(WorldBorder.Settings settings) { }
    @Override public WorldBorder.Settings getWorldBorder() { return delegate.getWorldBorder(); }
    @Override public boolean isInitialized() { return delegate.isInitialized(); }
    @Override public void setInitialized(boolean initialized) { }
    @Override public boolean getAllowCommands() { return delegate.getAllowCommands(); }
    @Override public void setGameType(GameType type) { }
    @Override public TimerQueue<MinecraftServer> getScheduledEvents() { return delegate.getScheduledEvents(); }
    @Override public void setGameTime(long gameTime) { }
    @Override public void setDayTime(long dayTime) { }
    @Override public void setXSpawn(int x) { }
    @Override public void setYSpawn(int y) { }
    @Override public void setZSpawn(int z) { }
    @Override public void setSpawnAngle(float angle) { }
    @Override public void setSpawn(BlockPos pos, float angle) { }
    @Override public int getXSpawn() { return delegate.getXSpawn(); }
    @Override public int getYSpawn() { return delegate.getYSpawn(); }
    @Override public int getZSpawn() { return delegate.getZSpawn(); }
    @Override public float getSpawnAngle() { return delegate.getSpawnAngle(); }
    @Override public long getGameTime() { return delegate.getGameTime(); }
    @Override public long getDayTime() { return delegate.getDayTime(); }
    @Override public boolean isThundering() { return delegate.isThundering(); }
    @Override public boolean isRaining() { return delegate.isRaining(); }
    @Override public void setRaining(boolean raining) { }
    @Override public boolean isHardcore() { return delegate.isHardcore(); }
    @Override public GameRules getGameRules() { return delegate.getGameRules(); }
    @Override public Difficulty getDifficulty() { return delegate.getDifficulty(); }
    @Override public boolean isDifficultyLocked() { return delegate.isDifficultyLocked(); }
}
