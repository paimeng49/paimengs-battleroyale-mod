package com.paimeng.battleroyale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 匹配模式的<b>配置存储</b>：{@code config/battleroyale/match.json}，服务端读取。
 *
 * <p>需求（R15 任务一第 3 条）：模式上限从配置读取——solo = 1、duo = 2、squad = 4，
 * 三个上限都可以在配置里改（比如把 duo 改成 3 人队），改完<b>下一局开局自动生效</b>
 * （Matchmaker 每次开局前会重读一次文件，不用重启服务器）。
 *
 * <p>文件长这样（不存在时自动生成默认值）：
 * <pre>{@code
 * {
 *   "mode": "squad",
 *   "soloTeamSize": 1,
 *   "duoTeamSize": 2,
 *   "squadTeamSize": 4
 * }
 * }</pre>
 *
 * <p>解析失败时保留上一次（或内置默认）的模式与上限，只打日志，绝不抛异常炸游戏。
 *
 * <p><b>升级预留</b>：Gson 与 {@code FabricLoader.getConfigDir()} 跨版本稳定，
 * 本类搬到任何 Fabric 版本都不用改。
 */
public final class GameModeStore {

    /** 三种匹配模式。{@code defaultSize} 是需求写死的默认上限。 */
    public enum GameMode {
        SOLO("solo", 1),
        DUO("duo", 2),
        SQUAD("squad", 4);

        private final String configName;
        private final int defaultSize;

        GameMode(String configName, int defaultSize) {
            this.configName = configName;
            this.defaultSize = defaultSize;
        }

        public String configName() {
            return configName;
        }

        /** 配置损坏 / 越界时的兜底上限（需求默认：solo=1，duo=2，squad=4）。 */
        public int defaultSize() {
            return defaultSize;
        }

        /** 名字（忽略大小写）→ 模式；认不出来返回 null。 */
        public static GameMode fromName(String name) {
            if (name == null) {
                return null;
            }
            String trimmed = name.trim();
            for (GameMode mode : values()) {
                if (mode.configName.equalsIgnoreCase(trimmed)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** 单队上限的合法范围：至少 1 人，最多 99 人（Team 的队内编号上限）。 */
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 99;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 当前模式与三档上限。volatile + 本类只整体替换字段值，Matchmaker 在开局线程读。 */
    private static volatile GameMode mode = GameMode.SQUAD;
    private static volatile int soloSize = GameMode.SOLO.defaultSize;
    private static volatile int duoSize = GameMode.DUO.defaultSize;
    private static volatile int squadSize = GameMode.SQUAD.defaultSize;

    /** 招募消息展示的每队人数上限（match.json 的 "teamLimit" 键；缺失/非法默认 4）。 */
    private static final int DEFAULT_RECRUIT_LIMIT = 4;
    private static final int DEFAULT_RECRUIT_VALID_SECONDS = 300;
    private static volatile int recruitLimit = DEFAULT_RECRUIT_LIMIT;
    private static volatile int recruitValidSeconds = DEFAULT_RECRUIT_VALID_SECONDS;

    private GameModeStore() {
    }

    /** 配置文件绝对路径（.minecraft/config/battleroyale/match.json）。 */
    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("battleroyale")
                .resolve("match.json");
    }

    /** 启动时调用：没有文件就生成默认文件，有就加载。 */
    public static void loadOrCreate() {
        load();
    }

    /**
     * 重读文件；失败保留旧值只记日志。Matchmaker 每次开局前调用，
     * 所以服主改完配置<b>下一局就生效</b>，不需要重启。
     */
    public static void load() {
        Path path = configFile();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(FileFormat.defaults()), StandardCharsets.UTF_8);
                BattleRoyaleMod.LOGGER.info("[battleroyale] 已生成匹配模式默认配置文件：{}", path);
                return; // 默认值已经在字段初始值里，模式默认 squad
            }
            FileFormat format = GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), FileFormat.class);
            if (format == null) {
                BattleRoyaleMod.LOGGER.warn("[battleroyale] {} 是空的，继续使用当前匹配配置", path);
                return;
            }
            GameMode parsed = GameMode.fromName(format.mode);
            mode = parsed == null ? GameMode.SQUAD : parsed;
            soloSize = clamp(format.soloTeamSize, GameMode.SOLO);
            duoSize = clamp(format.duoTeamSize, GameMode.DUO);
            squadSize = clamp(format.squadTeamSize, GameMode.SQUAD);
            recruitLimit = recruitLimitOrDefault(format.teamLimit);
            recruitValidSeconds = recruitValidSecondsOrDefault(format.recruitValidSeconds);
            BattleRoyaleMod.LOGGER.info("[battleroyale] 匹配配置：mode={}，上限 solo={} duo={} squad={}（{}）",
                    mode.configName(), soloSize, duoSize, squadSize, path);
        } catch (Exception e) {
            // IOException / JsonParseException / 任何脏数据异常：保留旧值，只记日志。
            BattleRoyaleMod.LOGGER.warn("[battleroyale] 读取匹配配置 {} 失败，保留当前配置：{}",
                    path, e.toString());
        }
    }

    /** 当前匹配模式。 */
    public static GameMode currentMode() {
        return mode;
    }

    /** 当前模式对应的每队人数上限（配置值非法时已在加载端兜底）。 */
    public static int teamLimit() {
        return switch (mode) {
            case SOLO -> soloSize;
            case DUO -> duoSize;
            case SQUAD -> squadSize;
        };
    }

    /** 指定模式的上限（给以后按模式切换用；本局 Matchmaker 只查 teamLimit()）。 */
    public static int limitOf(GameMode target) {
        return switch (target) {
            case SOLO -> soloSize;
            case DUO -> duoSize;
            case SQUAD -> squadSize;
        };
    }

    /** null / 越界都退回该模式的默认上限；上限夹在 [1, 99]。 */
    private static int clamp(Integer raw, GameMode target) {
        if (raw == null || raw < MIN_SIZE || raw > MAX_SIZE) {
            return target.defaultSize();
        }
        return raw;
    }

    /** teamLimit 缺失或越界（合法范围 [1, 99]）时回默认 4。 */
    private static int recruitLimitOrDefault(Integer raw) {
        if (raw == null || raw < MIN_SIZE || raw > MAX_SIZE) {
            return DEFAULT_RECRUIT_LIMIT;
        }
        return raw;
    }

    /**
     * 队伍招募消息展示的"（当前/上限）"里的上限：
     * 读 match.json 的 "teamLimit"，缺失时默认 4（招募指令用；不影响 Matchmaker）。
     */
    public static int recruitTeamLimit() {
        return recruitLimit;
    }

    public static int recruitValidSeconds() {
        return recruitValidSeconds;
    }

    private static int recruitValidSecondsOrDefault(Integer raw) {
        return raw == null || raw < 1 || raw > 86400 ? DEFAULT_RECRUIT_VALID_SECONDS : raw;
    }

    /** JSON 文件结构，Gson 直接读写字段。 */
    static final class FileFormat {
        String mode;
        Integer soloTeamSize;
        Integer duoTeamSize;
        Integer squadTeamSize;
        Integer teamLimit;
        Integer recruitValidSeconds;

        static FileFormat defaults() {
            FileFormat format = new FileFormat();
            format.mode = GameMode.SQUAD.configName();
            format.soloTeamSize = GameMode.SOLO.defaultSize;
            format.duoTeamSize = GameMode.DUO.defaultSize;
            format.squadTeamSize = GameMode.SQUAD.defaultSize;
            format.teamLimit = DEFAULT_RECRUIT_LIMIT;
            format.recruitValidSeconds = DEFAULT_RECRUIT_VALID_SECONDS;
            return format;
        }
    }
}
