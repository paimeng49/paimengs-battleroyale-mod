package com.paimeng.battleroyale.game;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.GameWorldManager;
import com.paimeng.battleroyale.network.FlightSync;
import com.paimeng.battleroyale.event.ZoneStageChangedEvent;
import com.paimeng.battleroyale.zone.ZoneManager;
import com.paimeng.battleroyale.zone.ZonePhase;
import com.paimeng.battleroyale.zone.ZoneSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import com.paimeng.battleroyale.entity.AirdropEntity;
import com.paimeng.battleroyale.entity.ModEntities;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * [BR-AIRDROP 模块三] 空投系统（毒圈阶段触发，复用 C-130 渲染链路）。
 *
 * <p>流程：
 * <ol>
 *   <li><b>触发</b>：毒圈进入地图配置 {@link GameMap#airdropStages} 指定的阶段
 *       （默认第 2、4 阶段）时，每局最多 {@link GameMap#maxAirdrops} 次；
 *       毒圈只在 RUNNING 阶段推进，天然避开开局运人的 C-130。</li>
 *   <li><b>飞行</b>：每次读取当前白圈的圆心和半径，随机生成一条穿过白圈的新 A→B
 *       航线；端点位于白圈半径外 500 格。客户端仍走既有 {@code flight_route}
 *       与 {@code flight_tick} 通道渲染 C-130（纯函数载具，不是实体，零实体开销）。</li>
 *   <li><b>投放</b>：每 tick 沿航线计算飞机位置；到达白圈圆心的投影点（即全程
 *       距离圆心最近的点）时强制投放，不再按 30%~70% 随机进度判定。</li>
 *   <li><b>下落</b>：投放物为空投自定义实体 {@code AirdropEntity}，每 tick 以 -0.8 格速度缓降，
 *       触地后放置红色潜影盒。</li>
 *   <li>落地后台账捕捉：从 {@link GameMap#airdropLootPool} 洗牌抽取 3~4 组不重复物资，
 *       命名「空投箱」、正上方打一条 END_ROD 粒子光柱（信标观感），
 *       并登记台账供安全重置清除；填箱走统一容器加入逻辑，超量物品由原版规则自动拆格。</li>
 *   <li>飞机飞完航线：收机包 + active=false，内存引用即刻摘除。</li>
 * </ol>
 *
 * <p>线程模型：只在服务端主线程（事件总线 / ServerTickEvents）访问。
 */
public final class AirdropManager {

    public static final AirdropManager INSTANCE = new AirdropManager();

    /** 航线投放点直接取白圈圆心最近点，不再使用随机进度候选。 */
    /** 空投箱每次落地的物资组数下限 / 上限。 */
    private static final int MIN_GROUPS = 3;
    public static final int MAX_GROUPS = 4;
    /** 空投实体下落速度：-0.8 格/tick，约 15~20 秒从航线高度落地。 */
    private static final double CRATE_FALL_SPEED = -0.8;
    /** 光柱粒子发射周期（tick）。 */
    private static final int BEAM_INTERVAL_TICKS = 10;
    /** 已投放箱子台账上限（防呆：超出说明追踪泄漏）。 */
    private static final int MAX_TRACKED_CRATES = 64;
    /** 区块未加载时最多追多久（tick）后放弃（20 分钟）。 */
    private static final int CRATE_TRACK_TIMEOUT = 20 * 60 * GameRules.TICKS_PER_SECOND;

    private static final Random RANDOM = new Random();

    /** 每维度本局空投运行时。 */
    private final Map<ResourceKey<Level>, DimState> states = new LinkedHashMap<>();
    /** 已投放、还没落地的空投实体追踪表。 */
    private final List<TrackedCrate> crates = new ArrayList<>();
    /** 落地成功的空投箱光柱（每 10 tick 补一发粒子，玩家争夺地标）。 */
    private final List<Beam> beams = new ArrayList<>();
    /** 空投箱台账（维度 → 坐标）：安全重置按台账清箱，口径同死亡箱 / 物资箱。 */
    private static final Map<ResourceKey<Level>, List<BlockPos>> AIRDROP_BOXES = new LinkedHashMap<>();

    private AirdropManager() {
    }

    // ==================================================================
    // 生命周期（主类订阅 GameStateChangedEvent 接线）
    // ==================================================================

    /** 进入 RUNNING：重置每维度本局计数（触发阶段、已投次数、池缓存重新按配置解析）。 */
    public void start(MinecraftServer server) {
        stop(server);
        if (server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> key = world.dimension();
            if (!GameWorldManager.isGameWorld(key)) {
                continue;
            }
            GameMap map = GameWorldManager.mapForWorld(key);
            if (map == null || !hasMatchPlayer(world)) {
                continue;
            }
            map.normalizeLootConfiguration();
            states.put(key, new DimState(key, map));
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 地图 {} 空投运行时就绪：触发阶段 {}，本局上限 {} 次，空投池配置：{}",
                    map.mapId, map.airdropStages, map.maxAirdrops,
                    map.airdropLootPool == null || map.airdropLootPool.isEmpty()
                            ? "内置默认池" : map.airdropLootPool);
        }
        if (states.isEmpty()) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] RUNNING 已开启但没有任何游戏维度建立空投运行时"
                    + "（没有参赛者在游戏维度，或维度未映射到地图）——后续毒圈阶段不会触发空投");
        }
    }

    /** 离开 RUNNING：停飞所有空投飞机（通知客户端收线收机）、清运行时引用。 */
    public void stop(MinecraftServer server) {
        for (DimState state : states.values()) {
            Plane plane = state.plane;
            if (plane != null && !plane.closed) {
                ServerLevel world = server == null ? null : server.getLevel(state.dimension);
                closePlane(world, plane);
            }
        }
        states.clear();
        crates.clear();
        beams.clear();
    }

    /**
     * [BR-LOOT 模块四] 安全重置入口：清掉本维度台账里的空投箱（方块 + 内容抹掉，
     * 不掉落）。还在半空的下落实体由 {@code unloadMatchWorlds} 的通用实体清理负责。
     */
    public static void clearAirdropBoxes(ServerLevel level) {
        if (level == null) {
            return;
        }
        List<BlockPos> positions = AIRDROP_BOXES.remove(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }
        int removed = 0;
        for (BlockPos pos : positions) {
            try {
                if (level.getBlockState(pos).is(Blocks.RED_SHULKER_BOX)) {
                    level.removeBlock(pos, false);
                    level.removeBlockEntity(pos);
                    removed++;
                }
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 清除空投箱 {} 失败：{}", pos.toShortString(), t);
            }
        }
        BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 安全重置：维度 {} 清除空投箱 {} 个",
                level.dimension().location(), removed);
    }

    /** 关服 / 全局重置兜底：空投箱台账整体作废。 */
    public static void clearTracking() {
        AIRDROP_BOXES.clear();
    }

    // ==================================================================
    // 触发：毒圈阶段事件（主类把 onZoneStageChanged 挂到 EventBus）
    // ==================================================================

    /** 毒圈进入配置阶段（默认第 2、4 阶段，1 基编号与播报一致）时拉起一架空投 C-130。 */
    public void onZoneStageChanged(ZoneStageChangedEvent event) {
        if (event.phase() != ZonePhase.WAITING || event.server() == null) {
            return; // 只在「进入新阶段的等待期开始」这条发布上触发
        }
        ResourceKey<Level> key = GameWorldManager.worldKey(event.mapId());
        DimState state = states.get(key);
        if (state == null) {
            return; // 本维度没跑对局
        }
        int stageNumber = event.stageIndex() + 1;
        if (!state.map.airdropStages.contains(stageNumber)) {
            return;
        }
        if (state.usedStages.contains(stageNumber)) {
            return; // 同一阶段只触发一次（防御重复发布）
        }
        if (state.triggered >= state.map.maxAirdrops) {
            return; // 本局次数上限（模块三 1：X 可配置）
        }
        if (state.plane != null && !state.plane.closed) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 地图 {} 上一架空投飞机还在飞，跳过本阶段空投", state.map.mapId);
            return;
        }
        ServerLevel world = event.server().getLevel(key);
        if (world == null) {
            return;
        }
        ZoneManager.ZoneRuntime zone = ZoneManager.INSTANCE.runtimeAt(key);
        ZoneSnapshot snapshot = zone == null ? null : zone.snapshot();
        if (snapshot == null || !snapshot.active() || snapshot.whiteRadius() <= 0.0
                || !Double.isFinite(snapshot.whiteCenterX()) || !Double.isFinite(snapshot.whiteCenterZ())) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 地图 {} 当前没有有效白圈，跳过第 {} 阶段空投", state.map.mapId, stageNumber);
            return;
        }
        // 每次空投都以当前白圈为中心重算航线：A/B 位于白圈外 500 格，直线穿过圆心。
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double dirX = Math.cos(angle);
        double dirZ = Math.sin(angle);
        double distance = snapshot.whiteRadius() + 500.0;
        double ax = snapshot.whiteCenterX() - dirX * distance;
        double az = snapshot.whiteCenterZ() - dirZ * distance;
        double bx = snapshot.whiteCenterX() + dirX * distance;
        double bz = snapshot.whiteCenterZ() + dirZ * distance;
        FlightManager.RouteLine route = FlightManager.RouteLine.create(key, state.map.mapId,
                ax, az, bx, bz, Math.min(FlightManager.FLIGHT_HEIGHT, world.getMaxBuildHeight() - 5.0),
                state.map.flightSpeed);
        state.usedStages.add(stageNumber);
        state.triggered++;
        Plane plane = new Plane(route, world.getGameTime(), snapshot.whiteCenterX(), snapshot.whiteCenterZ());
        state.plane = plane;
        for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
            FlightSync.sendAirdropRoute(player, true, route.mapId,
                    route.ax, route.az, route.bx, route.bz,
                    plane.startTick, route.speed, route.flightY);
        }
        BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 空投航线已生成 A->B，投放点 X Z：A({},{}) -> B({},{})，最近点 ({},{})",
                (int) route.ax, (int) route.az, (int) route.bx, (int) route.bz,
                (int) snapshot.whiteCenterX(), (int) snapshot.whiteCenterZ());
    }

    // ==================================================================
    // 每 tick 推进（由 ServerTickEvents.END_SERVER_TICK 驱动）
    // ==================================================================

    public void tick(MinecraftServer server) {
        if (server == null || states.isEmpty()) {
            return;
        }
        if (GameManager.INSTANCE.getPhase() != GamePhase.RUNNING) {
            return; // 双保险：正常生命周期里离开 RUNNING 已 stop()
        }
        for (DimState state : states.values()) {
            ServerLevel world = server.getLevel(state.dimension);
            if (world == null) {
                state.plane = null;
                continue;
            }
            tickPlane(world, state);
        }
        tickCrates(server);
        tickBeams(server);
    }

    /** 空投飞机推进 + 到点投放。 */
    private void tickPlane(ServerLevel world, DimState state) {
        Plane plane = state.plane;
        if (plane == null || plane.closed) {
            return;
        }
        FlightManager.RouteLine route = plane.route;
        plane.ticks++;
        double travel = Math.min(plane.ticks * route.speed, route.length);
        if (plane.ticks % 20 == 0) {
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 飞机进度 {}/{}", travel, plane.dropDistance);
        }

        // 强制投放：航线穿过白圈圆心，飞机到达“距圆心最近”的点时只投放一次。
        if (!plane.dropped && travel >= plane.dropDistance) {
            double px = route.ax + route.dirX * plane.dropDistance;
            double pz = route.az + route.dirZ * plane.dropDistance;
            spawnCrate(world, px, pz, route.flightY);
            plane.dropped = true;
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 地图 {} 飞机到达距白圈圆心最近点（{},{}）强制投放空投",
                    state.map.mapId, (int) px, (int) pz);
        }

        boolean reachedB = travel >= route.length - 1.0e-6;
        if (!plane.endedSyncSent) {
            if (!reachedB) {
                FlightSync.broadcastTick(world, world.getGameTime(), plane.ticks);
            } else {
                plane.endedSyncSent = true;
                FlightSync.broadcastTick(world, world.getGameTime(), FlightSync.PLANE_FINISHED);
            }
        }
        if (reachedB) {
            // 模块三 4：飞完航线立即收机 + 收线，清内存引用（下一架空投到点重新建）。
            closePlane(world, plane);
            state.plane = null;
        }
    }

    /** 收机：给同维度玩家发 active=false，客户端收起航线与飞机。 */
    private void closePlane(ServerLevel world, Plane plane) {
        plane.closed = true;
        if (world == null) {
            return;
        }
        for (ServerPlayer player : new ArrayList<>(world.getPlayers(p -> true))) {
            FlightSync.sendRoute(player, false, plane.route.mapId, 0, 0, 0, 0, 0L, 0.0, 0.0);
        }
    }

    /** 本轮白圈（下一目标圆）是否罩住这个平面坐标。 */
    private boolean isInsideWhiteCircle(ResourceKey<Level> key, double x, double z) {
        ZoneManager.ZoneRuntime runtime = ZoneManager.INSTANCE.runtimeAt(key);
        if (runtime == null) {
            return false;
        }
        ZoneSnapshot snap = runtime.snapshot();
        double dx = x - snap.whiteCenterX();
        double dz = z - snap.whiteCenterZ();
        return Math.hypot(dx, dz) <= snap.whiteRadius();
    }

    /**
     * 吐出一个"空投实体"：原版方块展示实体（BlockDisplay）顶着红色潜影盒方块。
     * 展示实体无碰撞、不可被攻击、随区块存档；<b>下落由本管理器权威驱动</b>
     * （每 tick Y 减 {@value #CRATE_FALL_SPEED} 的绝对值），不依赖原版重力 /
     * 下落方块的触地放置逻辑——判定口径就是需求原文："下方必须是实体方块"。
     */
    private void spawnCrate(ServerLevel world, double x, double z, double flightY) {
        try {
            if (crates.size() >= MAX_TRACKED_CRATES) {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 追踪中的空投已达上限 {}，本次不投放", MAX_TRACKED_CRATES);
                return;
            }
            AirdropEntity crate = new AirdropEntity(world, x, flightY, z);
            boolean added = world.addFreshEntity(crate);
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 空投实体已投下：addFreshEntity={}，实体 {} @ ({},{},{})，"
                            + "追踪表 {} 条",
                    added, crate.getStringUUID(), (int) x, (int) flightY, (int) z, crates.size() + 1);
            if (added) {
                crates.add(new TrackedCrate(world.dimension(), crate.getUUID(), x, flightY, z));
            } else {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投实体未能加入世界（addFreshEntity 返回 false），"
                        + "改为直接兜底放置：({},{})", (int) x, (int) z);
                BlockPos landed = forcePlaceCrate(world, Mth.floor(x), Mth.floor(z), Mth.floor(flightY));
                if (landed != null) {
                    finalizeCrate(world, landed);
                }
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 投放空投实体失败：{}", t.toString());
        }
    }

    /**
     * 空投缓降推进（每个服务端 tick）：
     * <ul>
     *   <li>区块未加载 → 冻结等待（绝不强制加载区块），超时才放弃；</li>
     *   <li>区块已加载 → 权威 Y 下移，实体跟到同一位置；下方出现实体方块即触地：
     *       销毁实体、原地放置红色潜影盒、填物资、点光柱、记台账；</li>
     *   <li>实体中途失踪（被异常路径清掉等）→ 在最后位置兜底补放，绝不让空投凭空消失；</li>
     *   <li>坠入虚空 → 销毁并告警。</li>
     * </ul>
     */
    private void tickCrates(MinecraftServer server) {
        // 下落和触地已迁移到 AirdropEntity.tick()，这里仅保留旧台账接口兼容。
        return;
        /*
        for (int i = crates.size() - 1; i >= 0; i--) {
            TrackedCrate crate = crates.get(i);
            ServerLevel world = server.getLevel(crate.dimension);
            if (world == null) {
                crates.remove(i);
                continue;
            }
            int cx = net.minecraft.util.Mth.floor(crate.x);
            int cz = net.minecraft.util.Mth.floor(crate.z);
            ChunkPos chunkPos = new ChunkPos(cx, cz);
            if (!world.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) {
                // 投放点无人时主动请求区块，避免空投永远冻结在天上；getChunk 会在服务端主线程安全加载它。
                BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 投放点区块未加载，强制加载区块 ({},{})", chunkPos.x, chunkPos.z);
                world.getChunk(chunkPos.x, chunkPos.z);
                if (!world.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) {
                    if (crate.ageTicks++ >= CRATE_TRACK_TIMEOUT) {
                        BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投 {} 长期未加载，已收掉", crate.entityId);
                        crates.remove(i);
                    }
                    continue;
                }
            }
            crate.ageTicks = 0;
            Entity entity = world.getEntity(crate.entityId);
            if (entity == null || entity.isRemoved()) {
                // 实体失踪（重启边界 / 异常清理）：兜底补放，不让空投凭空消失。
                crates.remove(i);
                BlockPos landed = forcePlaceCrate(world, cx, cz, (int) net.minecraft.util.Mth.floor(crate.y));
                if (landed != null) {
                    finalizeCrate(world, landed);
                } else {
                    BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投实体在 ({},{}) 附近消失且无法补放", cx, cz);
                }
                continue;
            }
            crate.y += CRATE_FALL_SPEED;
            entity.setPos(crate.x, crate.y, crate.z);
            // [BR-AIRDROP-FIX] 下落途中每 8 tick 打一小团 END_ROD 粒子：原版掉落物本体很小，
            // 让玩家能在天上看到"有条发光的东西正在缓降"，也便于确认投放确实发生了。
            if ((world.getGameTime() & 7L) == 0L) {
                try {
                    world.sendParticles(ParticleTypes.END_ROD,
                            crate.x, crate.y, crate.z, 5, 0.15, 0.3, 0.15, 0.02);
                } catch (Throwable ignored) {
                    // 粒子失败不影响下落
                }
            }
            if (crate.y <= world.getMinBuildHeight() + 1) {
                entity.discard();
                crates.remove(i);
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投在 ({},{}) 坠入虚空，已销毁", cx, cz);
                continue;
            }
            BlockPos below = new BlockPos(cx, net.minecraft.util.Mth.floor(crate.y - 0.5), cz);
            BlockState belowState = world.getBlockState(below);
            boolean solidGround = !belowState.isAir() && belowState.isSolid();
            if (!solidGround) {
                continue;
            }
            BlockPos target = below.above();
            if (!isCrateSpaceFree(world.getBlockState(target))) {
                // 落点被占（极端：树冠 / 建筑突出部）：继续下移一格判定，最多穿 3 格，
                // 仍不合适就在正下方找第一个空位；全列无空位则按原版坠落路径收掉。
                BlockPos shifted = firstFreeCellAtOrBelow(world, target);
                if (shifted == null) {
                    entity.discard();
                    crates.remove(i);
                    BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投落点 ({},{}) 无空位，已销毁", cx, cz);
                    continue;
                }
                target = shifted;
            }
            entity.discard();
            crates.remove(i);
            world.setBlockAndUpdate(target, Blocks.RED_SHULKER_BOX.defaultBlockState());
            finalizeCrate(world, target);
        }
        */
    }

    /** 实体触地时调用：放置红色潜影盒、填充物资并启动光柱。 */
    public void land(ServerLevel world, BlockPos below, UUID entityId) {
        for (int i = crates.size() - 1; i >= 0; i--) {
            if (crates.get(i).entityId.equals(entityId)) {
                crates.remove(i);
                break;
            }
        }
        BlockPos target = below.above();
        if (!isCrateSpaceFree(world.getBlockState(target))) {
            target = firstFreeCellAtOrBelow(world, target);
        }
        if (target == null) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投 {} 落点无可用空间", entityId);
            return;
        }
        Entity entity = world.getEntity(entityId);
        if (entity != null) {
            entity.discard();
        }
        world.setBlockAndUpdate(target, Blocks.RED_SHULKER_BOX.defaultBlockState());
        finalizeCrate(world, target);
    }

    /** 触地格上方必须是空气 / 草丛（潜影盒放得下）。 */
    private static boolean isCrateSpaceFree(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        try {
            return state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目标格被占时：沿列向下找第一个"下方实体 + 本格空"的位置。 */
    private BlockPos firstFreeCellAtOrBelow(ServerLevel world, BlockPos from) {
        for (int y = from.getY(); y > world.getMinBuildHeight(); y--) {
            BlockPos cell = new BlockPos(from.getX(), y, from.getZ());
            BlockState here = world.getBlockState(cell);
            BlockState belowState = world.getBlockState(cell.below());
            if (isCrateSpaceFree(here) && !belowState.isAir() && belowState.isSolid()) {
                return cell;
            }
        }
        return null;
    }

    /** 兜底补放：实体失踪时按高度图 / 向下扫描放箱，绝不让空投凭空消失。 */
    private BlockPos forcePlaceCrate(ServerLevel world, int x, int z, int fromY) {
        try {
            int y = Math.min(fromY, world.getMaxBuildHeight() - 2);
            for (; y > world.getMinBuildHeight(); y--) {
                BlockPos cell = new BlockPos(x, y, z);
                BlockState here = world.getBlockState(cell);
                BlockState below = world.getBlockState(cell.below());
                if (isCrateSpaceFree(here) && !below.isAir() && below.isSolid()) {
                    world.setBlockAndUpdate(cell, Blocks.RED_SHULKER_BOX.defaultBlockState());
                    BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 空投实体失踪兜底：已在 {} 补放空投箱",
                            cell.toShortString());
                    return cell;
                }
            }
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 手动补放空投箱失败：{}", t.toString());
        }
        return null;
    }

    /** 落地收尾：填 3~4 组空投物资、命名「空投箱」、登记台账 + 光柱。 */
    private void finalizeCrate(ServerLevel world, BlockPos pos) {
        try {
            if (!(world.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box)) {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投箱 {} 的方块实体异常，跳过物资填充", pos.toShortString());
                return;
            }
            DimState state = states.get(world.dimension());
            List<List<ItemStack>> pool = state == null ? List.of() : refreshPool(state);
            int groups = Math.min(MIN_GROUPS + RANDOM.nextInt(MAX_GROUPS - MIN_GROUPS + 1), pool.size());
            // [BR-LOOT-SHUFFLE] 复制后洗牌，直接取前 groups 个物资组；同一个空投箱绝不重复抽组。
            List<List<ItemStack>> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled, RANDOM);
            for (int i = 0; i < groups; i++) {
                for (ItemStack item : shuffled.get(i)) {
                    // [BR-LOOT-BULLET-FIX] 复用统一容器加入逻辑，自动合并并按动态上限拆分槽位。
                    LootSpawner.addToContainer(box, item.copy());
                }
            }
            box.setCustomName(Component.literal("空投箱"));
            box.setChanged();
            AIRDROP_BOXES.computeIfAbsent(world.dimension(), k -> new ArrayList<>()).add(pos);
            beams.add(new Beam(world.dimension(), pos));
            BattleRoyaleMod.LOGGER.info("[BR-AIRDROP] 空投箱落地 {}：{} 组物资、光柱已点亮",
                    pos.toShortString(), groups);
        } catch (Throwable t) {
            BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 空投箱落地收尾 {} 失败：{}", pos.toShortString(), t.toString());
        }
    }

    /** airdropLootPool 改过就重解析（与 LootSpawner 的 lootPool 同一套热更口径）；空/非法回退内置默认空投池。 */
    private List<List<ItemStack>> refreshPool(DimState state) {
        String config = LootPoolParser.cleanConfiguration(state.map.airdropLootPool);
        if (config.equals(state.poolConfig)) {
            return state.pool;
        }
        state.poolConfig = config;
        List<List<ItemStack>> parsed = LootPoolParser.parse(config, "BR-AIRDROP", state.map.mapId);
        // [BR-LOOT-SAVE-FIX] 与 LootSpawner.resolvePool 同一判据：解析器现在会给写错的组
        // 补一张占位纸，「非空」不代表「有真东西」。整池只剩占位纸时必须当空池回退默认，
        // 否则空投箱会刷成一沓纸；回退时把原始串照原样打进日志，方便分清是没送到还是切坏了。
        if (!LootPoolParser.hasAnyRealItem(parsed)) {
            if (!parsed.isEmpty()) {
                BattleRoyaleMod.LOGGER.warn("[BR-AIRDROP] 地图 {} 空投池解析不出任何真实物品（{} 个组全是占位）："
                        + "已回退内置默认空投池，请对照每条「具体片段」日志修改配置", state.map.mapId, parsed.size());
            }
            BattleRoyaleMod.LOGGER.warn("原始配置字符串：[{}]", state.map.airdropLootPool);
            parsed = List.of();
        }
        state.pool = DefaultLootPools.orFallback("BR-AIRDROP", state.map.mapId, parsed, true);
        return state.pool;
    }

    /** 光柱粒子：每 10 tick 沿箱子正上方到高空补一列 END_ROD（信标观感，玩家争夺地标）。 */
    private void tickBeams(MinecraftServer server) {
        for (int i = beams.size() - 1; i >= 0; i--) {
            Beam beam = beams.get(i);
            beam.ticks++;
            if (beam.ticks < BEAM_INTERVAL_TICKS) {
                continue;
            }
            beam.ticks = 0;
            ServerLevel world = server.getLevel(beam.dimension);
            if (world == null || !world.getBlockState(beam.pos).is(Blocks.RED_SHULKER_BOX)) {
                beams.remove(i); // 维度没了 / 箱子被玩家收走：光柱熄灭
                continue;
            }
            try {
                double cx = beam.pos.getX() + 0.5;
                double cz = beam.pos.getZ() + 0.5;
                for (int y = beam.pos.getY() + 2; y <= 310; y += 4) {
                    world.sendParticles(ParticleTypes.END_ROD, cx, y, cz, 2,
                            0.05, 0.0, 0.05, 0.0);
                }
            } catch (Throwable ignored) {
                // 粒子发不出去绝不影响对局
            }
        }
    }

    private static boolean hasMatchPlayer(ServerLevel world) {
        for (ServerPlayer player : world.getPlayers(p -> true)) {
            if (GameManager.INSTANCE.isJoined(player.getGameProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 内部数据结构（全部只在服务端主线程访问）
    // ==================================================================

    /** 每维度本局空投运行时：次数闸门 + 在飞机 + 空投池缓存。 */
    private static final class DimState {
        final ResourceKey<Level> dimension;
        final GameMap map;
        final Set<Integer> usedStages = new HashSet<>();
        int triggered;
        Plane plane;
        String poolConfig = "\u0000";
        List<List<ItemStack>> pool = List.of();

        DimState(ResourceKey<Level> dimension, GameMap map) {
            this.dimension = dimension;
            this.map = map;
        }
    }

    /** 一架在飞的空投 C-130（纯逻辑，渲染走既有 flight 通道）。 */
    private static final class Plane {
        final FlightManager.RouteLine route;
        final long startTick;
        /** 飞机从 A 端出发后到达白圈圆心投影点所需的距离。 */
        final double dropDistance;
        int ticks;
        boolean dropped;
        boolean endedSyncSent;
        boolean closed;

        Plane(FlightManager.RouteLine route, long startTick, double centerX, double centerZ) {
            this.route = route;
            this.startTick = startTick;
            // 航线以白圈圆心为中点，投影距离就是半条航线长度；数值夹回航线范围防异常。
            double projection = (centerX - route.ax) * route.dirX + (centerZ - route.az) * route.dirZ;
            this.dropDistance = Mth.clamp(projection, 0.0, route.length);
        }
    }

    /**
     * 还在下落、还没落地的空投实体追踪条目。
     * 位置是本管理器的权威状态（x/y/z），实体只是它的"显示器"——
     * 区块卸载时实体不在场也能安全冻结，重载后把实体重新钉回同一坐标。
     */
    private static final class TrackedCrate {
        final ResourceKey<Level> dimension;
        final UUID entityId;
        final double x;
        double y;
        final double z;
        int ageTicks;

        TrackedCrate(ResourceKey<Level> dimension, UUID entityId, double x, double y, double z) {
            this.dimension = dimension;
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 已落地空投箱的光柱状态。 */
    private static final class Beam {
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        int ticks;

        Beam(ResourceKey<Level> dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }
}
