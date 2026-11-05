package com.paimeng.battleroyale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 游戏内系统通报配置。 */
public final class BroadcastStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFAULT_DETAILED = true;
    private static volatile boolean detailedBroadcast = DEFAULT_DETAILED;

    private BroadcastStore() {}

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("battleroyale").resolve("broadcast.json");
    }

    public static void loadOrCreate() { load(); }

    public static synchronized void load() {
        Path file = configFile();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(new FileFormat()), StandardCharsets.UTF_8);
                detailedBroadcast = DEFAULT_DETAILED;
                return;
            }
            FileFormat format = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileFormat.class);
            detailedBroadcast = format == null || format.detailedBroadcast == null
                    ? DEFAULT_DETAILED : format.detailedBroadcast;
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("读取通报配置失败，继续使用当前值：{}", e.toString());
        }
    }

    public static boolean isDetailedBroadcast() { return detailedBroadcast; }

    public static synchronized void setDetailedBroadcast(boolean value) {
        detailedBroadcast = value;
        try {
            Files.createDirectories(configFile().getParent());
            FileFormat format = new FileFormat();
            format.detailedBroadcast = value;
            Files.writeString(configFile(), GSON.toJson(format), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BattleRoyaleMod.LOGGER.warn("保存通报配置失败：{}", e.toString());
        }
    }

    private static final class FileFormat {
        Boolean detailedBroadcast = DEFAULT_DETAILED;
    }
}
