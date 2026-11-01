package com.paimeng.battleroyale.map;

import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 编辑世界不需要进度条，避免启动时刷屏。 */
public enum SilentWorldGenListener implements ChunkProgressListener {
    INSTANCE;

    @Override
    public void updateSpawnPos(ChunkPos spawnPos) {
    }

    @Override
    public void onStatusChange(ChunkPos pos, ChunkStatus status) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
