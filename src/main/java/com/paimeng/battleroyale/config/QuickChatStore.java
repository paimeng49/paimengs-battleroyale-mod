package com.paimeng.battleroyale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paimeng.battleroyale.BattleRoyaleMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 快捷消息的<b>预设列表</b>：{@code config/battleroyale/quickchat.json}，
 * 服务端与客户端<b>共用这一个类</b>读取。
 *
 * <p>快捷消息不再注册 {@code /qc} 或 {@code /paimengmods qc} 指令，只通过客户端 UI
 * 和网络包发送；服务端收到包后仍按自身加载的列表校验，确保只能发送预设消息。
 * 专用服务器由服主维护服务器端的 quickchat.json。
 *
 * <p>文件长这样（第一次启动时若不存在会自动生成默认文件）：
 * <pre>{@code
 * {
 *   "messages": ["向安全区移动", "发现敌人", "需要支援", "这里有物资", "撤退"]
 * }
 * }</pre>
 *
 * <p>修改配置后重启服务端即可生效。
 * 解析失败时保留上一次（或内置默认）的列表，只打日志，绝不抛异常炸游戏。
 *
 * <p>过滤规则：去空行、去首尾空格、去重复、去掉以 "/" 开头的条目
 * （那种内容会被原版聊天当成指令执行），最多取前 32 条——原版建议框一屏
 * 也展示不了更多。
 *
 * <p><b>升级预留</b>：Gson、{@code FabricLoader.getConfigDir()} 跨版本稳定，
 * 本类搬到任何 Fabric 版本都不用改。
 */
public final class QuickChatStore {

    /** 内置默认快捷消息（文件不存在时生成它，也是两边校验的兜底基准）。 */
    public static final List<String> DEFAULT_MESSAGES = List.of(
            "向安全区移动", "发现敌人", "需要支援", "这里有物资", "撤退");

    /** 写文件用：缩进美化 + 不转义中文（disableHtmlEscaping 保证 JSON 里直接是可读的汉字）。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final int MAX_MESSAGES = 32;

    /** 当前生效的列表。volatile + 不可变 List，读侧（建议线程）无锁。 */
    private static volatile List<String> messages = DEFAULT_MESSAGES;

    private QuickChatStore() {
    }

    /** 配置文件绝对路径（.minecraft/config/battleroyale/quickchat.json；服务端即服务端目录那份）。 */
    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("battleroyale")
                .resolve("quickchat.json");
    }

    /** 当前生效的快捷消息（不可变）。 */
    public static List<String> messages() {
        return messages;
    }

    /** 启动时调用：没有文件就生成默认文件，有就加载。 */
    public static void loadOrCreate() {
        load();
    }

    /** 重读文件，返回加载到的条数。 */
    public static int reload() {
        load();
        return messages.size();
    }

    /** 精确匹配预设列表（trim 后逐条 equals，大小写敏感——中文没大小写问题）。 */
    public static boolean isPreset(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return !trimmed.isEmpty() && messages.contains(trimmed);
    }

    // ------------------------------------------------------------------

    private static void load() {
        Path path = configFile();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(FileFormat.of(DEFAULT_MESSAGES)), StandardCharsets.UTF_8);
                messages = DEFAULT_MESSAGES;
                BattleRoyaleMod.LOGGER.info("[battleroyale] 已生成快捷消息默认配置文件：{}", path);
                return;
            }
            FileFormat format = GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), FileFormat.class);
            List<String> cleaned = sanitize(format == null ? null : format.messages);
            if (cleaned.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[battleroyale] {} 里没有有效条目，继续使用当前快捷消息", path);
                return;
            }
            messages = cleaned;
            BattleRoyaleMod.LOGGER.info("[battleroyale] 已加载 {} 条快捷消息（{}）", cleaned.size(), path);
        } catch (Exception e) {
            // IOException / JsonParseException / 任何脏数据异常：保留旧列表，只记日志。
            BattleRoyaleMod.LOGGER.warn("[battleroyale] 读取快捷消息配置 {} 失败，保留当前列表：{}",
                    path, e.toString());
        }
    }

    private static List<String> sanitize(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return List.of();
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("/") || out.contains(trimmed)) {
                continue;
            }
            out.add(trimmed);
            if (out.size() >= MAX_MESSAGES) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** JSON 文件结构，Gson 直接读写字段。 */
    static final class FileFormat {
        List<String> messages;

        static FileFormat of(List<String> list) {
            FileFormat format = new FileFormat();
            format.messages = new ArrayList<>(list);
            return format;
        }
    }
}
