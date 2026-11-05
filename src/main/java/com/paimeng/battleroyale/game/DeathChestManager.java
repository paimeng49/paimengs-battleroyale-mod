package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameWorldManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [BR-DEATHCHEST] 死亡掉落箱（骨灰盒）。
 *
 * <p>规则（与本模组需求封板一致）：
 * <ol>
 *   <li>玩家主动按 Q 丢弃的物品继续走原版掉落物实体——本类不拦截；</li>
 *   <li>局内玩家死亡 / 被彻底淘汰时，取消原版"天女散花"：整套装备（主背包、
 *       快捷栏、护甲、副手、鼠标手持物）在死亡点原位封进原版箱子，
 *       超过 27 件时拼成双格大箱子（54 槽，整套装备最多 41 件，装得下）；</li>
 *   <li>尸体在半空 / 岩浆里：从死亡列向下找最近的实体方块顶面，
 *       箱子落在那块地面上方，不会悬空，也不会让物品泡在岩浆里烧毁；</li>
 *   <li>背包为空不生成箱子；</li>
 *   <li>队友和敌人死亡走同一套逻辑，方便任何人舔包。</li>
 * </ol>
 *
 * <p>拦截点：{@code mixin.PlayerDeathChestMixin} 注入 {@code Player#die} 的 HEAD。
 * 原版伤害致死、淘汰流程里显式的 {@code player.die(source)}、逃跑判死，全部汇聚到
 * 这一个方法，只需拦一次就覆盖所有死亡路径：先收走（copy）再当场清空背包，
 * 原版随后的掉落流程看到的就是空库存，天然一件不掉、一件不丢。
 */
public final class DeathChestManager {
    /** 原版单箱容量（ChestBlockEntity 固定 27 槽）；也是双箱左右半各自的分界。 */
    private static final int SINGLE_SIZE = 27;

    /**
     * [BR-RESET] 本服运行期间放出去的死亡箱台账：维度 → 箱格坐标
     * （双格大箱登记两个半格）。安全重置卸载维度前按台账逐格清除，
     * 箱内装备随之消失——"结算后清死亡箱"需求的实现载体。
     */
    private static final Map<ResourceKey<Level>, List<BlockPos>> PLACED_CHESTS = new ConcurrentHashMap<>();

    /** 记一格死亡箱坐标（放置成功后调用）。 */
    private static void registerChest(ServerLevel level, BlockPos pos) {
        PLACED_CHESTS.computeIfAbsent(level.dimension(),
                        key -> Collections.synchronizedList(new ArrayList<>()))
                .add(pos.immutable());
    }

    /**
     * [BR-RESET] 安全重置入口：清除该维度台账里的全部死亡箱（方块 + 箱内物品直接
     * 抹掉，不掉落），region 文件不动——方块置空会随维度 save 一起落盘，
     * 下一局重新实例化时自然是干净地图。
     */
    public static void clearDeathChests(ServerLevel level) {
        List<BlockPos> positions = PLACED_CHESTS.remove(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }
        int removed = 0;
        for (BlockPos pos : positions) {
            try {
                if (level.getBlockState(pos).is(Blocks.CHEST)) {
                    level.removeBlock(pos, false);
                    level.removeBlockEntity(pos);
                    removed++;
                }
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("[BR-DEATHCHEST] 清除死亡箱 {} 失败：{}", pos.toShortString(), t);
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-DEATHCHEST] 安全重置：维度 {} 清除死亡箱 {} 格",
                level.dimension().location(), removed);
    }

    /** 关服兜底：台账整体作废（磁盘状态由各自的 save 流程负责）。 */
    public static void clearTracking() {
        PLACED_CHESTS.clear();
    }

    private DeathChestManager() {
    }

    /**
     * 死亡 / 淘汰结算入口：收走整套装备并在死亡点生成骨灰盒。
     *
     * <p>重复调用安全：第二次进来背包已被清空，收不到东西，不再生成箱子。
     *
     * @return true 表示实际生成了死亡箱
     */
    public static boolean onDeath(ServerPlayer player) {
        String rejection = rejectionReason(player);
        if (rejection != null) {   // [BR-DEATHCHEST-DEBUG] 排查用：没生成时把拒绝原因写进日志
            BattleRoyaleMod.LOGGER.info("[BR-DEATHCHEST] {} 死亡未生成骨灰盒：{}",
                    player.getGameProfile().getName(), rejection);
            return false;
        }
        List<ItemStack> stacks = harvest(player);
        if (stacks.isEmpty()) {   // 规则 5：空背包不生成箱子
            BattleRoyaleMod.LOGGER.info("[BR-DEATHCHEST] {} 死亡未生成骨灰盒：背包与手持均为空",
                    player.getGameProfile().getName());
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos origin = player.blockPosition();

        // 规则 4：半空 / 岩浆中的尸体——从死亡列向下找最近的实体方块顶面。
        BlockPos chestPos = findGroundCell(level, origin);
        if (chestPos == null) {
            // 整列到世界底部都放不下（比如死在虚空上方）：退化为原地掉落，保证不丢装备。
            scatter(level, origin, stacks);
            BattleRoyaleMod.LOGGER.warn("[BR-DEATHCHEST] 玩家 {} 死亡列无可放置地面，{} 件装备原地掉落",
                    player.getGameProfile().getName(), stacks.size());
            return false;
        }

        Direction facing = Direction.fromYRot(player.getYRot());
        if (stacks.size() <= SINGLE_SIZE) {
            return placeSingle(level, chestPos, facing, stacks, player);
        }
        // 装备多于 27 件（满背包 36+4+1+手持 最多 42 件）：拼 54 槽的双格大箱子。
        BlockPos[] pair = pickDoubleCells(level, chestPos, facing);
        if (pair != null && placeDouble(level, pair[0], pair[1], facing, stacks, player)) {
            return true;
        }
        // 两侧都没有合法格子：单箱装前 27 件，余量原地掉落。
        return placeSingle(level, chestPos, facing, stacks, player);
    }

    /** 收装备资格判定（局内参赛者、游戏维度、非旁观创造）：可收返回 {@code null}，否则返回拒绝原因。 */
    private static String rejectionReason(ServerPlayer player) {
        GamePhase phase = GameManager.INSTANCE.getPhase();
        if (phase != GamePhase.FLIGHT && phase != GamePhase.RUNNING && phase != GamePhase.ENDED) {
            return "阶段为 " + phase.name() + "（局外 / 等待大厅不动任何人）";
        }
        if (!GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
            return "不在本局名单（观战者 / 管理员）";
        }
        if (!GameWorldManager.isGameWorld(player.level().dimension())) {
            return "不在游戏维度（已回大厅）";
        }
        // 旁观 / 创造：原版本来就不掉装备，背包原样保留。
        if (player.isSpectator() || player.isCreative()) {
            return "已是旁观 / 创造模式（应在切旁观前显式结算）";
        }
        return null;
    }

    /** 收走主背包、护甲、副手、手持物并清空玩家库存；返回的都是副本。 */
    private static List<ItemStack> harvest(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        Inventory inv = player.getInventory();
        // 统一索引 0~35 主背包/快捷栏，36~39 护甲，40 副手（Inventory#getContainerSize()==41）。
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        ItemStack cursor = player.containerMenu.getCarried();
        if (!cursor.isEmpty()) {
            stacks.add(cursor.copy());
        }
        // 关键一步：清空原版背包与手持物。随后原版 die() 的掉落流程看到全空气，
        // "取消天女散花"就此达成——物品只进箱子，地上不会多出一件。
        inv.clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        return stacks;
    }

    /**
     * 从死亡列（脚底格起）向下扫描，返回第一个可放箱格；找不到返回 null。
     * 上限压到 maxY-2：保证箱子顶上一格还在世界高度内。
     */
    private static BlockPos findGroundCell(ServerLevel level, BlockPos origin) {
        int minY = level.getMinBuildHeight();
        int startY = Math.min(origin.getY(), level.getMaxBuildHeight() - 2);
        for (int y = startY; y > minY; y--) {
            BlockPos cell = new BlockPos(origin.getX(), y, origin.getZ());
            if (canPlaceChest(level, cell)) {
                return cell;
            }
        }
        return null;
    }

    /**
     * 可放箱格三条件：下方是实体方块（箱子不悬空；岩浆没有实体顶面，会被自动跳过）、
     * 本格非实体且不带容器（不覆盖别人的箱子/机器）、顶上一格没被全方块封死（箱子要能打开）。
     */
    private static boolean canPlaceChest(ServerLevel level, BlockPos pos) {
        BlockPos floor = pos.below();
        if (!level.getBlockState(floor).isRedstoneConductor(level, floor)) {
            return false;
        }
        BlockState here = level.getBlockState(pos);
        if (here.hasBlockEntity() || here.isRedstoneConductor(level, pos)) {
            return false;
        }
        BlockPos above = pos.above();
        return !level.getBlockState(above).isRedstoneConductor(level, above);
    }

    /**
     * 双箱格位：原版配对规则是两半共享同一 facing，左半（LEFT）的伙伴格在
     * facing.getClockWise() 方向——先试本格作左半、伙伴在顺时针侧；
     * 顺时针侧不合法再反过来（逆时针侧作左半、本格作右半）。
     *
     * @return [左箱格, 右箱格]；两侧都不合法返回 null
     */
    private static BlockPos[] pickDoubleCells(ServerLevel level, BlockPos origin, Direction facing) {
        Direction clockwise = facing.getClockWise();
        BlockPos cwCell = origin.relative(clockwise);
        if (canPlaceChest(level, cwCell)) {
            return new BlockPos[]{origin, cwCell};
        }
        BlockPos ccwCell = origin.relative(clockwise.getOpposite());
        if (canPlaceChest(level, ccwCell)) {
            return new BlockPos[]{ccwCell, origin};
        }
        return null;
    }

    private static boolean placeDouble(ServerLevel level, BlockPos leftPos, BlockPos rightPos,
                                       Direction facing, List<ItemStack> stacks, ServerPlayer victim) {
        BlockState base = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
        // 状态属性本身即配对关系（1.13+ 双箱没有持久化"伴侣"对象），直接写死 LEFT/RIGHT。
        level.setBlockAndUpdate(leftPos, base.setValue(ChestBlock.TYPE, ChestType.LEFT));
        level.setBlockAndUpdate(rightPos, base.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        registerChest(level, leftPos);
        registerChest(level, rightPos);
        if (!(level.getBlockEntity(leftPos) instanceof ChestBlockEntity left)
                || !(level.getBlockEntity(rightPos) instanceof ChestBlockEntity right)) {
            // 理论上到不了（方块刚同步放置）：兜底原地掉落，不吞装备。
            scatter(level, leftPos, stacks);
            return false;
        }
        // 左半吃前 27 件，右半吃剩下的（整套装备 ≤42 件 < 54 槽，必然装得下）。
        for (int i = 0; i < stacks.size(); i++) {
            if (i < SINGLE_SIZE) {
                left.setItem(i, stacks.get(i));
            } else {
                right.setItem(i - SINGLE_SIZE, stacks.get(i));
            }
        }
        Component name = Component.literal("死亡箱");
        left.setCustomName(name);
        right.setCustomName(name);
        left.setChanged();
        right.setChanged();
        finish(level, leftPos, stacks.size(), true, victim);
        return true;
    }

    private static boolean placeSingle(ServerLevel level, BlockPos pos, Direction facing,
                                       List<ItemStack> stacks, ServerPlayer victim) {
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        registerChest(level, pos);
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            scatter(level, pos, stacks);
            return false;
        }
        int put = 0;
        for (; put < stacks.size() && put < SINGLE_SIZE; put++) {
            chest.setItem(put, stacks.get(put));
        }
        chest.setCustomName(Component.literal("死亡箱"));
        chest.setChanged();
        if (put < stacks.size()) {
            // 只在"多于 27 件又拼不出双箱"的罕见情况触发：余量原地掉落，不丢装备。
            scatter(level, pos, stacks.subList(put, stacks.size()));
        }
        finish(level, pos, stacks.size(), put == stacks.size(), victim);
        return true;
    }

    /** 播报 + 日志：给死者一条带坐标的动作栏提示，方便他观战时指给队友舔包。 */
    private static void finish(ServerLevel level, BlockPos pos, int count, boolean packedAll, ServerPlayer victim) {
        String kind = count > SINGLE_SIZE ? (packedAll ? "大箱子" : "箱子（部分掉地）") : "箱子";
        victim.displayClientMessage(Component.literal("你的装备已封入死亡" + kind + "：("
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")，他人可拾取").withStyle(ChatFormatting.GRAY), true);
        BattleRoyaleMod.LOGGER.info("[BR-DEATHCHEST] {} 死亡：{} 件装备封入{} {}",
                victim.getGameProfile().getName(), count, kind, pos.toShortString());
    }

    /** 兜底：物品以原版掉落物实体形式留在原地（不会凭空消失）。 */
    private static void scatter(ServerLevel level, BlockPos pos, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            level.addFreshEntity(new ItemEntity(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
        }
    }
}
