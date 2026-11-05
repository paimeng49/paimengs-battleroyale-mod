package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.team.MatchTeamManager;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-INVISO] 背包隔离仓：玩家进游戏维度（br join 落岛 / 开局传送）时把原背包
 * 整份存进隔离仓并清空身上，退出对局（结算 / br leave / 掉线判逃回大厅 / 关服送出）
 * 时清掉局内捡的一切、按<b>原槽位</b>原样返还。
 *
 * <p><b>数据结构</b>：{@code Map<UUID, SavedInventory>}；SavedInventory 按
 * 主背包 36 格 + 护甲 4 格 + 副手 1 格三个数组存 {@code stack.copy()} 深拷贝，
 * 与玩家当前背包完全脱钩（之后局内怎么改都不影响存档）。
 *
 * <p><b>防重复发放（刷物品）三原则</b>：
 * <ol>
 *   <li>保存只认第一次：进局时仓里已有记录（等待落岛 + 开局二次传送同一人）
 *       绝不覆盖旧档，只做"清空当前"；</li>
 *   <li>返还用 {@code remove(uuid)}：取出即销账，同一条存档不可能发第二次；</li>
 *   <li>返还前先清空玩家当前背包，局内物品只清不落（随维度卸载一起消失）。</li>
 * </ol>
 *
 * <p><b>极端异常兜底</b>（对应需求 4）：
 * <ul>
 *   <li>局内掉线且没重连：记录留在仓里，游戏结束不删；他<b>下次登录落在主世界</b>
 *       （大厅）时由 {@link #onLogin} 当场返还；</li>
 *   <li>局内掉线又重连、人直接落回游戏维度（还在局内）：{@link #onLogin} 检测到
 *       落点是游戏维度或对局未结束，绝不发第二份；之后随统一结算 / leave 返还；</li>
 *   <li>关服 / 单人退出到标题：所有台账实时序列化到
 *       {@code <存档根>/battleroyale_data/inventory_isolation.dat}，下次开服
 *       {@link #load} 原样恢复——就算人没来得及回大厅服务器就关了，物品也不丢。</li>
 * </ul>
 *
 * <p>线程模型：全部入口都在服务端主线程（传送 / 登录事件 / tick / stopServer HEAD），
 * 台账本身用 ConcurrentHashMap 只是防御性选择。
 */
public final class InventoryIsolation {

    public static final InventoryIsolation INSTANCE = new InventoryIsolation();

    private static final int MAIN_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    private static final int OFFHAND_SIZE = 1;
    private static final String SAVE_DIR = "battleroyale_data";
    private static final String SAVE_FILE = "inventory_isolation.dat";

    /** UUID → 进局前的原背包快照。 */
    private final Map<UUID, SavedInventory> saved = new ConcurrentHashMap<>();

    private InventoryIsolation() {
    }

    // ==================================================================
    // 进局：保存 + 清空（传送前调用）
    // ==================================================================

    /**
     * 开局传送 / br join 落岛进游戏维度前调用（{@code GameWorldManager.teleportTo}
     * 的 match 分支）：第一次进局把原背包深拷贝入仓，随后无条件清空当前背包。
     * 同一个人被二次传送（等待落岛 → 开局再传）不会覆盖第一份快照。
     */
    public void onEnterMatch(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            Inventory inv = player.getInventory();
            boolean fresh = !saved.containsKey(player.getUUID());
            if (fresh) {
                // 光标上可能挂着正拖着的东西（原版容器界面没关）：先收进主背包再拍快照，
                // 否则这件会在下面的清空里无声消失。
                ItemStack carried = player.inventoryMenu.getCarried();
                if (!carried.isEmpty()) {
                    player.inventoryMenu.setCarried(ItemStack.EMPTY);
                    inv.add(carried);
                }
                saved.put(player.getUUID(), SavedInventory.capture(inv));
                BattleRoyaleMod.LOGGER.debug("[BR-INVISO] 玩家 {} 已完成进局背包隔离",
                        player.getGameProfile().getName());
            }
            wipeCurrent(player);
            flush(player.getServer());
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[BR-INVISO] 玩家 {} 进局背包隔离失败（继续传送，未清空）",
                    player == null ? "?" : player.getGameProfile().getName(), t);
        }
    }

    // ==================================================================
    // 退局：清局内 + 返还（传送出游戏维度后调用）
    // ==================================================================

    /**
     * 玩家离开游戏维度回大厅（结算 / br leave / 判逃 / 关服送出）后调用：
     * 清空当前（局内）背包 → 按原槽位返还隔离仓快照 → 销账。
     * 仓里没有他的记录时是 no-op（管理员进出游戏维度不走隔离）。
     */
    public void onLeaveMatch(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        SavedInventory snapshot = null;
        try {
            snapshot = saved.remove(id);
            if (snapshot == null) {
                return;
            }
            wipeCurrent(player); // 局内捡的全部清掉（丢弃处理，不落生成物）
            restore(player.getInventory(), snapshot);
            player.inventoryMenu.broadcastChanges();
            player.displayClientMessage(BrText.of("背包隔离：局内物品已留下，原背包已按原槽位返还。"), false);
            BattleRoyaleMod.LOGGER.debug("[BR-INVISO] 玩家 {} 已完成退局背包恢复",
                    player.getGameProfile().getName());
            flush(player.getServer());
        } catch (Throwable t) {
            // 返还失败：把快照塞回仓里，等下一次登录 / 兜底扫描重试，绝不吞档。
            if (snapshot != null) {
                saved.putIfAbsent(id, snapshot);
            }
            BattleRoyaleMod.LOGGER.error("[BR-INVISO] 玩家 {} 退局返还背包失败（记录已保留，登录后重试）",
                    player.getGameProfile().getName(), t);
        }
    }

    // ==================================================================
    // 登录兜底 / 结算兜底
    // ==================================================================

    /**
     * JOIN 事件调用（需求 4 的兜底）：仓里还有他进局前的背包、且这次登录落点
     * <b>不在</b>游戏维度、对局也已不在进行中（或他已不在局）→ 当场返还。
     * 局内掉线又重连、被原版放回游戏维度继续打的：这里绝不能发第二份。
     */
    public void onLogin(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }
        try {
            UUID id = player.getUUID();
            if (!saved.containsKey(id)) {
                return; // 绝大多数普通登录：秒退
            }
            if (GameWorldManager.isGameWorld(player.level().dimension())) {
                BattleRoyaleMod.LOGGER.debug("[BR-INVISO] 玩家 {} 重连仍在游戏维度，暂不返还原背包",
                        player.getGameProfile().getName());
                return; // 还在局内：等结算 / leave 返还
            }
            GamePhase phase = GameManager.INSTANCE.getPhase();
            boolean participant = MatchTeamManager.INSTANCE.isAssigned(id)
                    || GameManager.INSTANCE.isJoined(player.getGameProfile().getName());
            if (phase.isActiveMatch() && participant) {
                // 对局还没结束而他此刻在大厅（比如被判逃后 leave 传送失败）：
                // 不抢先发，等统一结算 / 下次 leave 路径，避免与在局状态打架。
                return;
            }
            onLeaveMatch(player);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[BR-INVISO] 玩家 {} 登录返还原背包失败（记录已保留）",
                    player == null ? "?" : player.getGameProfile().getName(), t);
        }
    }

    /**
     * 结算安全重置（{@code MatchResetManager.execute}）扫尾：人在线、已不在游戏维度、
     * 但隔离仓里还压着原背包的（leave 传送中途失败等边角漏网者），统一结清。
     */
    public void sweepOnlineOutsideGameWorld(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (GameWorldManager.isGameWorld(player.level().dimension())) {
                continue;
            }
            if (saved.containsKey(player.getUUID())) {
                onLeaveMatch(player);
            }
        }
    }

    /** 是否还压着某人的原背包（诊断 / 测试用）。 */
    public boolean hasSaved(UUID playerId) {
        return playerId != null && saved.containsKey(playerId);
    }

    /**
     * 关服 / 单人退出到标题：最终台账落盘后清空内存——账是跟着存档走的，
     * 同一 JVM 换存档再进时由 {@link #load} 按该存档目录下的文件重新恢复，
     * 绝不把 A 档的欠账带进 B 档（跨存档复制物品）。
     */
    public void onServerStopping(MinecraftServer server) {
        flush(server);
        saved.clear();
    }

    // ==================================================================
    // 落盘持久化（关服 / 崩溃也不丢档的最后一道保险）
    // ==================================================================

    /** 开服 createLevels 完成后调用：把上一个会话没结清的隔离仓台账读回内存。 */
    public void load(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path file = saveFile(server);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            CompoundTag root = NbtIo.readCompressed(in);
            ListTag players = root.getList("players", CompoundTag.TAG_COMPOUND);
            int loaded = 0;
            for (int i = 0; i < players.size(); i++) {
                CompoundTag entry = players.getCompound(i);
                if (!entry.hasUUID("id")) {
                    continue;
                }
                UUID id = entry.getUUID("id");
                SavedInventory snapshot = new SavedInventory();
                readSlots(entry.getList("main", CompoundTag.TAG_COMPOUND), snapshot.main);
                readSlots(entry.getList("armor", CompoundTag.TAG_COMPOUND), snapshot.armor);
                readSlots(entry.getList("offhand", CompoundTag.TAG_COMPOUND), snapshot.offhand);
                saved.put(id, snapshot);
                loaded++;
            }
            if (loaded > 0) {
                BattleRoyaleMod.LOGGER.info("[BR-INVISO] 已从磁盘恢复 {} 份未返还的原背包（{}）",
                        loaded, file);
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[BR-INVISO] 读取隔离仓台账失败（按空仓继续，不影响开服）", t);
        }
    }

    /** 台账有任何增减就实时落盘；文件很小（几 KB），主线程同步写可接受。 */
    private void flush(MinecraftServer server) {
        if (server == null) {
            return; // 单人退出到标题的最后一次 flush 可能已无 server 引用：磁盘上是上一次变更的快照，可接受
        }
        try {
            Path file = saveFile(server);
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            ListTag players = new ListTag();
            for (Map.Entry<UUID, SavedInventory> e : saved.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("id", e.getKey());
                entry.put("main", writeSlots(e.getValue().main));
                entry.put("armor", writeSlots(e.getValue().armor));
                entry.put("offhand", writeSlots(e.getValue().offhand));
                players.add(entry);
            }
            root.put("players", players);
            try (OutputStream out = Files.newOutputStream(file)) {
                NbtIo.writeCompressed(root, out);
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.error("[BR-INVISO] 隔离仓台账落盘失败（内存记录仍在，登录后仍可返还）", t);
        }
    }

    private static Path saveFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(SAVE_DIR).resolve(SAVE_FILE);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 清空玩家当前一切随身物品：主背包、护甲、副手、光标（局内物品按需求直接丢弃）。 */
    private static void wipeCurrent(ServerPlayer player) {
        Inventory inv = player.getInventory();
        inv.clearContent();
        inv.armor.clear();
        inv.offhand.clear();
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        inv.selected = 0;
        player.inventoryMenu.broadcastChanges();
    }

    /** 把快照按原槽位写回玩家背包（列表长度不定时先补齐 EMPTY，防原版按定长下标访问越界）。 */
    private static void restore(Inventory inv, SavedInventory snapshot) {
        restoreList(inv.items, snapshot.main);
        restoreList(inv.armor, snapshot.armor);
        restoreList(inv.offhand, snapshot.offhand);
    }

    private static void restoreList(List<ItemStack> target, ItemStack[] source) {
        while (target.size() < source.length) {
            target.add(ItemStack.EMPTY);
        }
        for (int i = 0; i < source.length; i++) {
            target.set(i, source[i]);
        }
    }

    private static int countNonEmpty(ItemStack[] stacks) {
        int n = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // NBT 序列化（只用最稳定的 API：Registry 键名 + Count + tag，
    // 不依赖 ItemStack#save 这类跨小版本会漂移的方法）
    // ------------------------------------------------------------------

    private static ListTag writeSlots(ItemStack[] stacks) {
        ListTag list = new ListTag();
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            t.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            t.putByte("Count", (byte) Math.min(127, stack.getCount()));
            CompoundTag data = stack.getTag();
            if (data != null) {
                t.put("tag", data.copy());
            }
            list.add(t);
        }
        return list;
    }

    private static void readSlots(ListTag list, ItemStack[] into) {
        for (int i = 0; i < into.length; i++) {
            into[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int slot = t.getByte("Slot") & 0xFF;
            if (slot >= into.length) {
                continue;
            }
            into[slot] = readStack(t);
        }
    }

    private static ItemStack readStack(CompoundTag t) {
        ResourceLocation id = ResourceLocation.tryParse(t.getString("id"));
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) {
            BattleRoyaleMod.LOGGER.warn("[BR-INVISO] 隔离仓里的物品 {} 在当前模组列表里不存在，返还时跳过", id);
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item.get(), Math.max(0, t.getByte("Count")));
        if (t.contains("tag", CompoundTag.TAG_COMPOUND)) {
            stack.setTag(t.getCompound("tag"));
        }
        return stack;
    }

    // ------------------------------------------------------------------

    /** 一名玩家的进局前背包快照：三段定长数组，全部是深拷贝。 */
    private static final class SavedInventory {
        final ItemStack[] main = new ItemStack[MAIN_SIZE];
        final ItemStack[] armor = new ItemStack[ARMOR_SIZE];
        final ItemStack[] offhand = new ItemStack[OFFHAND_SIZE];

        SavedInventory() {
            fillEmpty(main);
            fillEmpty(armor);
            fillEmpty(offhand);
        }

        static SavedInventory capture(Inventory inv) {
            SavedInventory snapshot = new SavedInventory();
            copyInto(inv.items, snapshot.main);
            copyInto(inv.armor, snapshot.armor);
            copyInto(inv.offhand, snapshot.offhand);
            return snapshot;
        }

        private static void fillEmpty(ItemStack[] stacks) {
            for (int i = 0; i < stacks.length; i++) {
                stacks[i] = ItemStack.EMPTY;
            }
        }

        private static void copyInto(List<ItemStack> source, ItemStack[] target) {
            int n = Math.min(source.size(), target.length);
            for (int i = 0; i < n; i++) {
                ItemStack stack = source.get(i);
                target[i] = stack == null ? ItemStack.EMPTY : stack.copy();
            }
        }
    }
}
