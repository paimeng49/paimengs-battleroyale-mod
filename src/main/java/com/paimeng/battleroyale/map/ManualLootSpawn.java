package com.paimeng.battleroyale.map;

import net.minecraft.core.BlockPos;

/** 工具标记的具体物资箱生成坐标，不属于旧的地图标签 LootPoint。 */
public class ManualLootSpawn {
    public int x;
    public int y;
    public int z;

    public ManualLootSpawn() {
        this(0, 0, 0);
    }

    public ManualLootSpawn(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public ManualLootSpawn(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }
}
