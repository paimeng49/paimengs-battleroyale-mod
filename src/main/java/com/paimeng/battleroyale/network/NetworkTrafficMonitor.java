package com.paimeng.battleroyale.network;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;

/** 统计模组自定义 S2C 包，供服务端带宽排障使用。只在服务端主线程访问。 */
public final class NetworkTrafficMonitor {
    private static long packets;
    private static long bytes;
    private static int ticks;

    private NetworkTrafficMonitor() {
    }

    /** 在实际发送自定义包前调用，bytes 为编码后的可写字节数。 */
    public static void record(FriendlyByteBuf buf) {
        if (buf != null) {
            packets++;
            bytes += Math.max(0, buf.readableBytes());
        }
    }

    /** 每 100 tick 输出一次并清零窗口。 */
    public static void tick(MinecraftServer server) {
        if (server == null || ++ticks < 100) {
            return;
        }
        ticks = 0;
        BattleRoyaleMod.LOGGER.info("[BR-NET] 过去 5 秒内，向玩家发送了 {} 个包，总计 {} KB",
                packets, (bytes + 1023L) / 1024L);
        packets = 0;
        bytes = 0;
    }
}
