package com.paimeng.battleroyale.map;

import java.util.ArrayList;
import java.util.List;

/** 一张可编辑的大逃杀地图。字段保持公开，便于 Gson 直接读写。 */
public class GameMap {
    public String mapId = "default";
    public String displayName = "默认地图";
    public int minX = -128;
    public int minZ = -128;
    public int maxX = 128;
    public int maxZ = 128;
    public List<LootPoint> lootPoints = new ArrayList<>();
    /** 工具标记的具体箱子坐标；与用于地图显示的 lootPoints 完全分离。 */
    public List<ManualLootSpawn> manualLootSpawns = new ArrayList<>();
    public double zoneCenterX;
    public double zoneCenterZ;
    /** setcenter 是否指定过毒圈初始圆心（false = 用选区中心的最大内切圆）。 */
    public boolean zoneCenterSet = false;
    /**
     * 毒圈阶段配置（模块一）：每阶段的等待秒数 / 缩圈秒数 / 目标半径 / 圈外淘汰时间（秒）。
     * 空 = 该地图没配过，{@code ZoneManager} 用按选区缩放的默认阶段表兜底。
     */
    public List<ZonePhaseConfig> zonePhases = new ArrayList<>();
    /**
     * [r148 TTK 模型] 地图级「圈外淘汰时间」（秒，double）：不打包的玩家在圈外，
     * 从满血掉到 0（被毒圈淘汰）需要的秒数。运行期按
     * {@code damagePerSecond = player.getMaxHealth() / 本值} 反推每秒伤害，
     * 有额外血量上限的玩家按实际最大生命值算（血多扛得久，节奏一致）。
     * 默认 250 秒（对应示例配置第 1 阶段）。
     * {@code zone.ZoneManager.ZoneRuntime} 每秒把反推出的小数伤害累加进每玩家独立的
     * 伤害池，攒满 1 点才真扣 1 点血、零头留在池子里滚下一秒——原版的受击无敌帧
     * 因此吞不掉小数伤害（直接 hurt(0.08) 会被无敌帧整段丢掉）。
     *
     * <p>优先级：某个阶段单独配了 &gt; 0 的 {@code zoneTtkSeconds}（阶段字段，
     * 见 {@link ZonePhaseConfig}）时以阶段值为准；阶段填 0 = 跟随这张地图的全局值。
     *
     * <p>旧地图 JSON 里按"每秒伤害"设计的 {@code zoneDamagePerSecond} / {@code damage}
     * 字段<b>不再被读取</b>（数值口径不同，误读会让毒圈瞬间致死）：缺字段时 Gson
     * 保留初始值 250，读盘 / 落盘 / 收包都过 {@link #normalizeZoneTtk()}
     * （坏值夹回 {@value #MIN_ZONE_TTK_SECONDS} ~ {@value #MAX_ZONE_TTK_SECONDS} 秒）。
     */
    public double zoneTtkSeconds = DEFAULT_ZONE_TTK_SECONDS;
    /**
     * [BR-FLIGHT-SPEED] 飞机水平速度（格/tick）：不同地图尺寸不同，小图必须能调慢。
     * 旧地图 JSON 没有这个字段时 Gson 保留字段初始值 4.0；读盘 / 落盘前都会过一遍
     * {@link #normalizeFlightSpeed()}，坏值（NaN / 越界）一律夹回 0.5 ~ 20.0。
     */
    public double flightSpeed = 4.0;
    /**
     * [BR-MATCHTEAM 模块一] 本局允许的最大队伍数（2~25，默认 25）。
     * WAITING 阶段的开局门槛看的就是它：独立队伍数达到 maxTeams
     * （或管理员强制开局）才进入下一阶段。
     */
    public int maxTeams = DEFAULT_MAX_TEAMS;
    /**
     * [BR-MATCHTEAM 模块一] 每队最大人数（1~4，默认 4）。
     * 开局匹配（补位 / 合并 / 新建队）全部按这个上限执行。
     */
    public int maxTeamSize = DEFAULT_MAX_TEAM_SIZE;
    public int maxRooms = 1;
    public int teamLimit = 4;
    /**
     * [BR-LOOT 模块一] 普通物资池。配置语法：
     * <ul>
     *   <li>{@code ||} 分隔互不相关的物资组；</li>
     *   <li>{@code |and|} 分隔同一组内必须一起刷出的物品；</li>
     *   <li>单个物品可携带 NBT：{@code tacz:gun{GunId:"tacz:ak47"}}；</li>
     *   <li>可在末尾追加数量：{@code minecraft:arrow{...} 30}。</li>
     * </ul>
     */
    public String lootPool = "";
    /** [BR-AIRDROP 模块三] 空投物资池，语法同 {@link #lootPool}。 */
    public String airdropLootPool = "";
    /** 单张地图正常物资箱上限；达到上限后本局不再刷新。 */
    public int maxLootBoxes = DEFAULT_MAX_LOOT_BOXES;
    /** 单局空投上限。 */
    public int maxAirdrops = DEFAULT_MAX_AIRDROPS;
    /** 触发空投的毒圈阶段（1 基）。默认第 2、4 阶段。 */
    public List<Integer> airdropStages = new ArrayList<>(List.of(2, 4));
    /** 导入存档的根目录；源存档只读，不会被复制或修改。 */
    public String sourceArchivePath = "";
    /** 地图封面相对于 maps 目录的路径。 */
    public String coverImagePath = "";
    /** 游戏维度出生点；spawnSet=false 时进入走选区中心。 */
    public double spawnX = 0;
    public double spawnY = 64;
    public double spawnZ = 0;
    public boolean spawnSet = false;
    /** 出生岛区域（spawn_tool 在游戏维度圈选）；spawnAreaSet=false 时开局传送到地图中心。 */
    public int spawnMinX = 0;
    public int spawnMinZ = 0;
    public int spawnMaxX = 0;
    public int spawnMaxZ = 0;
    public boolean spawnAreaSet = false;
    /**
     * 服务端异步预生成的小地图底图（MapColorGenerator 生成完毕后发布）。
     * transient：只存服务端内存，Gson 序列化会跳过它，不进 JSON、不进任何网络包。
     */
    public transient volatile MapBaseImage baseImage;

    public GameMap copy() {
        GameMap copy = new GameMap();
        copy.mapId = mapId;
        copy.displayName = displayName;
        copy.minX = minX;
        copy.minZ = minZ;
        copy.maxX = maxX;
        copy.maxZ = maxZ;
        copy.lootPoints = lootPoints == null ? new ArrayList<>() : new ArrayList<>(lootPoints);
        copy.manualLootSpawns = new ArrayList<>();
        if (manualLootSpawns != null) {
            for (ManualLootSpawn spawn : manualLootSpawns) {
                if (spawn != null) {
                    copy.manualLootSpawns.add(new ManualLootSpawn(spawn.x, spawn.y, spawn.z));
                }
            }
        }
        copy.zoneCenterX = zoneCenterX;
        copy.zoneCenterZ = zoneCenterZ;
        copy.zoneCenterSet = zoneCenterSet;
        List<ZonePhaseConfig> phases = new ArrayList<>();
        if (zonePhases != null) {
            for (ZonePhaseConfig phase : zonePhases) {
                // [r131 索引防错位] null 空洞用默认值占位、原位保留：
                // 压缩列表会让副本的 config[1] 顶替 config[0]（白圈小一档），
                // 编辑面板解引用 null 也会直接崩。
                phases.add(phase == null ? new ZonePhaseConfig() : phase.copy());
            }
        }
        copy.zonePhases = phases;
        // [r148 TTK 模型] 地图级圈外淘汰时间（秒，double）随副本一起走。
        copy.zoneTtkSeconds = zoneTtkSeconds;
        copy.flightSpeed = flightSpeed;
        copy.maxTeams = maxTeams;
        copy.maxTeamSize = maxTeamSize;
        copy.maxRooms = maxRooms;
        copy.teamLimit = teamLimit;
        copy.lootPool = lootPool;
        copy.airdropLootPool = airdropLootPool;
        copy.maxLootBoxes = maxLootBoxes;
        copy.maxAirdrops = maxAirdrops;
        copy.airdropStages = airdropStages == null ? new ArrayList<>() : new ArrayList<>(airdropStages);
        copy.sourceArchivePath = sourceArchivePath;
        copy.coverImagePath = coverImagePath;
        copy.spawnX = spawnX;
        copy.spawnY = spawnY;
        copy.spawnZ = spawnZ;
        copy.spawnSet = spawnSet;
        copy.spawnMinX = spawnMinX;
        copy.spawnMinZ = spawnMinZ;
        copy.spawnMaxX = spawnMaxX;
        copy.spawnMaxZ = spawnMaxZ;
        copy.spawnAreaSet = spawnAreaSet;
        // 底图不可变，浅拷贝共享引用即可（也不会被 Gson / 网络包带上）。
        copy.baseImage = baseImage;
        return copy;
    }

    /** [BR-FLIGHT-SPEED] 合法速度区间：0.5 ~ 20 格/tick（防 0 除零、防飞出大气层）。 */
    public static final double MIN_FLIGHT_SPEED = 0.5;
    public static final double MAX_FLIGHT_SPEED = 20.0;
    public static final double DEFAULT_FLIGHT_SPEED = 4.0;

    /**
     * [r148 TTK 模型] 圈外淘汰时间（<b>double，支持小数</b>，单位秒）：
     * 默认 250 秒（示例配置第 1 阶段），合法区间 1 ~ 3600 秒。
     * 下限 1 秒 = 一秒带走满血玩家（20 点/秒），再短没有意义；
     * 上限 3600 秒 = 一小时毒不死，防呆手滑多打一个 0。
     * 阶段值与地图全局值共用同一套治理口径（{@link ZonePhaseConfig}）。
     */
    public static final double DEFAULT_ZONE_TTK_SECONDS = 250.0;
    public static final double MIN_ZONE_TTK_SECONDS = 1.0;
    public static final double MAX_ZONE_TTK_SECONDS = 3600.0;

    /**
     * [r148 TTK 模型] 把 {@link #zoneTtkSeconds} 治理成合法值：
     * 缺字段（旧 JSON）/ 手改越界 / NaN / 负数一律夹回 1 ~ 3600，坏值回落到默认 250。
     * 读盘、落盘、收编辑包时都调用（和 normalizeFlightSpeed 同一套姿势）。
     */
    public void normalizeZoneTtk() {
        if (!Double.isFinite(zoneTtkSeconds) || zoneTtkSeconds < MIN_ZONE_TTK_SECONDS) {
            zoneTtkSeconds = DEFAULT_ZONE_TTK_SECONDS;
        }
        zoneTtkSeconds = Math.min(MAX_ZONE_TTK_SECONDS,
                Math.max(MIN_ZONE_TTK_SECONDS, zoneTtkSeconds));
    }

    /**
     * [BR-FLIGHT-SPEED] 把 flightSpeed 治理成合法值：
     * 缺字段（旧 JSON）/ 0 / NaN / 负数 / 离谱大值一律夹回安全区间，
     * 完全没配过时取默认 4.0。读写地图 JSON、收保存包时都调用。
     */
    public void normalizeFlightSpeed() {
        if (!Double.isFinite(flightSpeed) || flightSpeed <= 0.0) {
            flightSpeed = DEFAULT_FLIGHT_SPEED;
        }
        flightSpeed = Math.min(MAX_FLIGHT_SPEED, Math.max(MIN_FLIGHT_SPEED, flightSpeed));
    }

    /** [BR-MATCHTEAM] maxTeams 合法区间与默认值（2~25，PUBG 标准 25 队）。 */
    public static final int MIN_MAX_TEAMS = 2;
    public static final int MAX_MAX_TEAMS = 25;
    public static final int DEFAULT_MAX_TEAMS = 25;
    /** [BR-MATCHTEAM] maxTeamSize 合法区间与默认值（1~4，四排）。 */
    public static final int MIN_MAX_TEAM_SIZE = 1;
    public static final int MAX_MAX_TEAM_SIZE = 4;
    public static final int DEFAULT_MAX_TEAM_SIZE = 4;

    /** 物资刷新与空投的合法配置区间。 */
    public static final int MIN_MAX_LOOT_BOXES = 1;
    public static final int MAX_MAX_LOOT_BOXES = 10000;
    public static final int DEFAULT_MAX_LOOT_BOXES = 300;
    public static final int MIN_MAX_AIRDROPS = 0;
    public static final int MAX_MAX_AIRDROPS = 20;
    public static final int DEFAULT_MAX_AIRDROPS = 2;

    /**
     * 治理所有物资配置：字符串只做必要清洗（空值归一为可安全解析的空字符串），
     * 数字配置夹回合法区间。旧 JSON 缺字段时 Gson 保留初始值。
     */
    public void normalizeLootConfiguration() {
        lootPool = com.paimeng.battleroyale.game.LootPoolParser.cleanConfiguration(lootPool);
        airdropLootPool = com.paimeng.battleroyale.game.LootPoolParser.cleanConfiguration(airdropLootPool);
        maxLootBoxes = Math.max(MIN_MAX_LOOT_BOXES,
                Math.min(MAX_MAX_LOOT_BOXES, maxLootBoxes));
        maxAirdrops = Math.max(MIN_MAX_AIRDROPS,
                Math.min(MAX_MAX_AIRDROPS, maxAirdrops));
        List<Integer> stages = new ArrayList<>();
        if (airdropStages != null) {
            for (Integer stage : airdropStages) {
                if (stage != null && stage >= 1 && stage <= ZonePhaseConfig.MAX_PHASES) {
                    stages.add(stage);
                }
            }
        }
        airdropStages = stages.isEmpty() ? new ArrayList<>(List.of(2, 4)) : stages;
    }

    /**
     * [BR-MATCHTEAM] 把 maxTeams / maxTeamSize 治理成合法值：
     * 旧地图 JSON 缺字段（Gson 保留初始值）/ 手改越界 / 0 负数一律夹回区间。
     * 读盘 / 落盘 / 收编辑包时都调用。
     */
    public void normalizeMatchLimits() {
        if (maxTeams < MIN_MAX_TEAMS || maxTeams > MAX_MAX_TEAMS) {
            maxTeams = maxTeams <= 0 ? DEFAULT_MAX_TEAMS
                    : Math.min(MAX_MAX_TEAMS, Math.max(MIN_MAX_TEAMS, maxTeams));
        }
        if (maxTeamSize < MIN_MAX_TEAM_SIZE || maxTeamSize > MAX_MAX_TEAM_SIZE) {
            maxTeamSize = maxTeamSize <= 0 ? DEFAULT_MAX_TEAM_SIZE
                    : Math.min(MAX_MAX_TEAM_SIZE, Math.max(MIN_MAX_TEAM_SIZE, maxTeamSize));
        }
    }

    /**
     * [r149 DYNCAP 模块一] 地图动态人数上限 = maxTeamSize * maxTeams
     * （例：单队 4 人 × 最多 25 队 = 100 人；改 4×8 就是 32 人）。
     *
     * <p>旧版这里是一个写死的 {@code maxPlayers = 100} 字段，与队伍配置完全脱钩，
     * 队长带满员队进图时会出现「上限 100 人但根本组不出 100 人」的误导。
     * 该字段连同它在 JSON / 网络包里的往返已整体移除：人数上限是**派生值**，
     * 唯一事实来源是 maxTeams / maxTeamSize 两个配置（读盘 / 收包都先过
     * {@link #normalizeMatchLimits()}，所以乘积必然落在 2~100 的合法区间，
     * Math.max(1, ...) 只是对未经治理的临时对象兜底）。
     */
    public int getMaxPlayers() {
        return Math.max(1, maxTeams * maxTeamSize);
    }
}
