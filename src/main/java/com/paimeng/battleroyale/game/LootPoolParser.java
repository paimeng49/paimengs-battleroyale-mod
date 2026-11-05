package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [BR-LOOT 模块一] 物资池字符串解析器（纯静态、无状态、绝不抛异常）。
 *
 * <p>配置语法（{@code GameMap.lootPool} / {@code GameMap.airdropLootPool}）：
 * <pre>
 *   组1 || 组2 |and| 组3 || ...
 *   单个物品：命名空间:路径[ NBT][ 数量]
 *   示例：minecraft:iron_sword || minecraft:bow |and| minecraft:arrow 32 || minecraft:bandage 5
 * </pre>
 * <ul>
 *   <li>{@code ||} 分隔<b>物资组</b>：一次抽取只命中一个组；</li>
 *   <li>{@code |and|} 分隔<b>组内绑定物品</b>：命中该组时必须一并刷出；</li>
 *   <li>物品 ID 后可加<b>空格 + 纯数字</b>指定刷新数量（如 {@code minecraft:grass_block 64}），
 *       不写默认 1；数量支持 {@code 1..999999999} 范围内的正整数，写 0 或非法数字只跳过该物品；</li>
 *   <li>数量可与 NBT 共存，写在花括号段之后：{@code tacz:modern_kinetic_gun{GunId:"tacz:ak47"} 1}；</li>
 *   <li>NBT 从第一个 {@code &#123;} 起、按<b>引号感知的括号配对</b>找到闭合 {@code &#125;}，
 *       整段交给原版 {@link TagParser}（与 /give 同一套解析），因此 NBT 字符串里带空格、
 *       带嵌套花括号都不会切错，数量也不会从字符串内部误提取；</li>
 *   <li><b>数量口径（[BR-LOOT-BULLET-FIX] 已改）</b>：解析器<b>不再</b>把数量压回最大堆叠上限——
 *       只负责照实算出配置里写的数字。构造顺序固定为「先建 1 个 → 贴 NBT → 最后 setCount」，
 *       保证 TACZ 子弹这类"上限随 NBT 变化"的物品能拿到真实上限；
 *       超量的拆分由放入端的原版容器加入逻辑自动处理；</li>
 *   <li><b>容错（[BR-LOOT-SAVE-FIX] 改过）</b>：任何解析失败只<b>跳过那一件物品</b>并写日志，
 *       不再因为组内一件写错就作废整组；整组全非法时用一张改名「配置错误的物品」的纸占位，
 *       让配置错误在箱子里可见。异常一律不外逃——物资池配错不能崩服务器。</li>
 * </ul>
 *
 * <p>解析结果缓存由 {@link LootSpawner}/{@link AirdropManager} 按
 * (mapId, 字符串) 负责；本类只做纯解析。
 */
public final class LootPoolParser {

    private static final String GROUP_SEPARATOR = "||";
    private static final String ITEM_SEPARATOR = "|and|";

    /** 整组全非法时的占位物品显示名（箱子里看得见，日志里也能对得上）。 */
    public static final String INVALID_PLACEHOLDER_NAME = "配置错误的物品";

    /**
     * 占位物品名判定用常量（原版会把它包成 Literal 组件，这里比对纯文本）。
     * 不对外暴露 ItemStack 引用，只做「这个组里有没有真东西」的判断。
     */
    private static final String PLACEHOLDER_MARKER = INVALID_PLACEHOLDER_NAME;

    /**
     * [BR-LOOT-SAVE-FIX] 解析结果里是否至少有一件<b>真实</b>物品（占位纸不算）。
     *
     * <p>调用方用它区分两种「解析不出东西」：
     * <ul>
     *   <li>配置里有一段写错、但别处还有好货 → 有真物品，照用管理员的配置（错的那组只剩占位纸）；</li>
     *   <li>整串配置一个都没解析出来 → 没有真物品，调用方该回退内置默认池，
     *       别让整张地图的箱子全刷成一张纸。</li>
     * </ul>
     */
    public static boolean hasAnyRealItem(List<List<ItemStack>> groups) {
        if (groups == null) {
            return false;
        }
        for (List<ItemStack> group : groups) {
            if (group == null) {
                continue;
            }
            for (ItemStack stack : group) {
                if (!isInvalidPlaceholder(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是不是「配置错误的物品」占位纸。null / 空堆栈也算占位（不是真物品）。 */
    public static boolean isInvalidPlaceholder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        try {
            var name = stack.getHoverName();
            return name != null && PLACEHOLDER_MARKER.equals(name.getString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 合法数量：1~9 位纯数字（保证 Integer.parseInt 不溢出），值域另在 parseItem 校验。 */
    private static final Pattern COUNT_PATTERN = Pattern.compile("\\d{1,9}");

    private LootPoolParser() {
    }

    /**
     * 清洗 GUI / 网络 / JSON 往返可能带入的不可见字符：
     * CR、LF、TAB、NUL 等全部替换为普通空格，再去除首尾空白。
     */
    public static String cleanConfiguration(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            cleaned.append(c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0 ? ' ' : c);
        }
        return cleaned.toString().trim();
    }

    /**
     * 解析物资池字符串为「物资组列表」。空串 → 空列表（调用方自行回退内置默认池）。
     *
     * <p>[BR-LOOT-SAVE-FIX] 容错口径改过一轮：旧版「组内任何一件非法 → 整组作废」
     * 会让管理员打错一个物品 ID 就吞掉一整组物资（甚至整池归零），排查时只看到
     * 「未配置或全部非法」，非常难定位。现在的规则是：
     * <ul>
     *   <li>单件非法（ID 拼错 / 模组没装 / 数量写错）→ <b>只跳过这一件</b>，组内其余照常保留；</li>
     *   <li>组内至少还剩一件合法物品 → 保留这一组；</li>
     *   <li>整组一件都没解析出来（全非法 / 空组）→ 放一张改名「配置错误的物品」的纸占位，
     *       让玩家和管理员在箱子里直接看到「这段配置有问题」，比整局什么都不刷更好。</li>
     * </ul>
     * 无论哪种失败都会带<b>原文片段</b>写日志，绝不静默、也绝不抛异常。
     */
    public static List<List<ItemStack>> parse(String pool, String tag, String mapId) {
        pool = cleanConfiguration(pool);
        if (pool.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<ItemStack>> groups = new ArrayList<>();
        String[] rawGroups;
        try {
            rawGroups = pool.split(Pattern.quote(GROUP_SEPARATOR));
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[{}] 地图 {} 物资池按 {} 分割失败，已忽略整张配置",
                    tag, mapId, GROUP_SEPARATOR, t);
            return Collections.emptyList();
        }
        for (String rawGroup : rawGroups) {
            String fragment = rawGroup == null ? "<null>" : rawGroup.trim();
            if (fragment.isEmpty()) {
                // 连续两个 || 之类的写法产生的真空段：直接跳过，不占位、不刷垃圾。
                BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物资池里有一个空物资组（多余的 || ），已跳过",
                        tag, mapId);
                continue;
            }
            List<ItemStack> group = parseGroup(fragment, tag, mapId);
            if (group.isEmpty()) {
                // 整组一件都没解析成功：放占位纸，让配置错误在箱子里可见（原来这里整组作废，
                // 管理员只看到箱子空着，根本不知道是哪一段写错了）。
                BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物资组内没有任何合法物品，已用占位物品保留该组；具体片段：'{}'",
                        tag, mapId, fragment);
                ItemStack placeholder = invalidPlaceholder();
                if (placeholder == null) {
                    continue; // 连占位物品都造不出来（注册表异常）：这组只能放弃
                }
                group.add(placeholder);
            }
            groups.add(group);
        }
        return groups;
    }

    /** 占位物品：一张改名为 {@link #INVALID_PLACEHOLDER_NAME} 的纸。任何异常都返回 null，绝不影响调用方。 */
    private static ItemStack invalidPlaceholder() {
        try {
            ItemStack paper = new ItemStack(net.minecraft.world.item.Items.PAPER, 1);
            paper.setHoverName(net.minecraft.network.chat.Component.literal(INVALID_PLACEHOLDER_NAME));
            return paper;
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-LOOT] 占位物品创建失败（已忽略该物资组）：{}", t.toString());
            return null;
        }
    }

    /**
     * 解析一个物资组（{@code |and|} 分隔的绑定物品）。
     * [BR-LOOT-SAVE-FIX] 单件非法只跳过这一件，绝不再因为一件写错就作废整组。
     */
    private static List<ItemStack> parseGroup(String rawGroup, String tag, String mapId) {
        List<ItemStack> items = new ArrayList<>();
        if (rawGroup == null || rawGroup.isBlank()) {
            return items;
        }
        for (String rawToken : rawGroup.split(Pattern.quote(ITEM_SEPARATOR))) {
            ItemStack stack = parseItem(rawToken, tag, mapId);
            if (stack == null) {
                // 这一件非法（parseItem 已经带原文打过日志）：跳过它，组内其余物品照留。
                continue;
            }
            items.add(stack);
        }
        return items;
    }

    /**
     * 解析单个物品 token；非法返回 null（调用方<b>只跳过这一件</b>，不再作废整组）。
     * 支持形态：{@code ns:id}、{@code ns:id 数量}、{@code ns:id{Nbt...}}、
     * {@code ns:id{Nbt...} 数量}，数量与花括号之间允许任意空格。
     */
    private static ItemStack parseItem(String rawToken, String tag, String mapId) {
        if (rawToken == null) {
            return null;
        }
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            String[] split = splitToken(token);
            String idText = split[0];
            String nbtText = split[1];
            String countText = split[2];

            int count = 1;
            if (countText != null) {
                if (!COUNT_PATTERN.matcher(countText).matches()) {
                    BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物品数量不是合法数字（应为 ID 后空格加正整数），已跳过：{}",
                            tag, mapId, token);
                    return null;
                }
                count = Integer.parseInt(countText);
                if (count < 1) {
                    BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物品数量必须大于 0，已跳过：{}",
                            tag, mapId, token);
                    return null;
                }
            }

            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物资配置物品 ID 非法，已跳过：{}",
                        tag, mapId, token);
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            // [BR-LOOT-SAVE-FIX] 关键修正：1.18.2 的 BuiltInRegistries.ITEM 是 DefaultedRegistry，
            // 查不到的 ID 返回的是 Items.AIR 而**不是 null**，旧判空从不生效。
            // 于是「拼错的 ID / 模组没装」会生成一个 AIR 的 ItemStack 塞进箱子：
            // 物资箱看着刷出来了、打开却是空的（正是实机看到的「一个物资都没刷」的成因之一）。
            // 现在按 AIR 判定为非法，跳过这件并在日志里写清是哪一个 ID。
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物品 {} 未注册（模组缺失或拼写错误），已跳过；原片段：{}",
                        tag, mapId, id, token);
                return null;
            }

            // [BR-LOOT-BULLET-FIX] 解析器只负责照实算出配置里写的数量，绝不压回最大堆叠。
            // 旧写法 new ItemStack(item, count) 再 setTag：像 TACZ 子弹这类
            // 「最大堆叠上限由 NBT 决定」的物品，构造瞬间上限还是 1，count 直接被原版
            // 截成 1 发——这正是实机「9mm 子弹只刷 1 发」的根因。
            // 现在的顺序：1) 先建 1 个；2) 贴 NBT（上限随 NBT 动态变化）；3) 最后 setCount(配置值)。
            // 总量超过单格上限的部分不用在这里处理：放入箱子时由容器加入逻辑自动拆叠成多格
            // （配置 120 发子弹 → 自动两格 60）。
            ItemStack stack = new ItemStack(item, 1);
            if (nbtText != null) {
                CompoundTag nbt = TagParser.parseTag(nbtText);
                stack.setTag(nbt);
            }
            stack.setCount(count);
            return stack;
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[{}] 地图 {} 物品解析失败，已跳过：{}（{}）",
                    tag, mapId, token, t.toString());
            return null;
        }
    }

    /**
     * 纯文本拆分单个物品 token 为 [ID 文本, NBT 文本或 null, 数量文本或 null]（不校验、不查注册表）。
     *
     * <p>规则：
     * <ol>
     *   <li>token 中若含 {@code &#123;}：ID = 首个 {@code &#123;} 之前的部分（trim）；
     *       NBT = 从首个 {@code &#123;} 到与之配对的闭合 {@code &#125;}（含两端）——配对计数
     *       跳过引号内的花括号，因此 {@code &#123;Name:"a { b &#125; c"&#125;} 这种整段保留不切错；
     *       闭合 {@code &#125;} 之后的尾随文本若非空即作为数量文本（是否为合法数字由调用方校验）；</li>
     *   <li>不含 {@code &#123;}：若按空格分割后<b>最后一段是纯数字</b>，把它提出来作为数量，
     *       第一段作为 ID；否则整个 token 视为 ID（带空格会在 tryParse 处报错跳过）；</li>
     *   <li>花括号不配对时整段交给调用方送 TagParser 报错（防呆，不在此处硬切）。</li>
     * </ol>
     *
     * <p>包可见，便于单元自测（纯字符串逻辑，不依赖注册表）。
     */
    static String[] splitToken(String token) {
        String t = token.trim();
        int brace = t.indexOf('{');
        if (brace < 0) {
            String[] parts = t.split("\\s+");
            if (parts.length >= 2 && COUNT_PATTERN.matcher(parts[parts.length - 1]).matches()) {
                return new String[]{parts[0], null, parts[parts.length - 1]};
            }
            return new String[]{t, null, null};
        }
        String head = t.substring(0, brace).trim();
        String tail = t.substring(brace);
        int end = findCloseBrace(tail);
        if (end < 0) {
            // 花括号不配对：整段留给 TagParser 报错，不猜数量。
            return new String[]{head, tail, null};
        }
        String nbt = tail.substring(0, end + 1);
        String rest = tail.substring(end + 1).trim();
        return new String[]{head, nbt, rest.isEmpty() ? null : rest};
    }

    /**
     * 从 {@code s[0] == '&#123;'} 的文本中找配对的闭合 {@code '&#125;'} 下标；找不到返回 -1。
     * 双引号 / 单引号内的字符（含花括号、转义）不参与配对计数，
     * 保证 {@code &#123;Lore:["a { b &#125; c"]&#125;} 不被字符串内容干扰。
     */
    static int findCloseBrace(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++; // 跳过转义字符（如 \" 或 \\）
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
