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
    public double zoneCenterX;
    public double zoneCenterZ;
    /** setcenter 是否指定过毒圈初始圆心（false = 用选区中心的最大内切圆）。 */
    public boolean zoneCenterSet = false;
    /**
     * 毒圈阶段配置（模块一）：每阶段的等待秒数 / 缩圈秒数 / 目标半径 / 每秒伤害。
     * 空 = 该地图没配过，{@code ZoneManager} 用按选区缩放的默认阶段表兜底。
     */
    public List<ZonePhaseConfig> zonePhases = new ArrayList<>();
    /**
     * [BR-FLIGHT-SPEED] 飞机水平速度（格/tick）：不同地图尺寸不同，小图必须能调慢。
     * 旧地图 JSON 没有这个字段时 Gson 保留字段初始值 4.0；读盘 / 落盘前都会过一遍
     * {@link #normalizeFlightSpeed()}，坏值（NaN / 越界）一律夹回 0.5 ~ 20.0。
     */
    public double flightSpeed = 4.0;
    public int maxPlayers = 100;
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
        copy.flightSpeed = flightSpeed;
        copy.maxPlayers = maxPlayers;
        copy.maxTeams = maxTeams;
        copy.maxTeamSize = maxTeamSize;
        copy.maxRooms = maxRooms;
        copy.teamLimit = teamLimit;
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
}
