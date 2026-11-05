package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.map.ArchiveEntry;
import com.paimeng.battleroyale.map.GameMap;
import com.paimeng.battleroyale.map.LootPoint;
import com.paimeng.battleroyale.map.ZonePhaseConfig;
import com.paimeng.battleroyale.network.MapEditorSync;
import com.paimeng.battleroyale.network.MapListSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 地图编辑器：副本内圈选，GUI 内命名、改物资点并保存。 */
public final class MapEditorScreen extends Screen {
    private GameMap map;
    private final List<ArchiveEntry> archives = new ArrayList<>();
    private final List<GameMap> savedMaps = new ArrayList<>();
    private final List<Button> enterButtons = new ArrayList<>();
    private final List<Button> lootUpdateButtons = new ArrayList<>();
    private final List<Button> lootDeleteButtons = new ArrayList<>();
    private final List<Button> mapDeleteButtons = new ArrayList<>();
    private final List<Button> mapEnterGameButtons = new ArrayList<>();
    /** 详情面板「关联存档」选择框里的存档选项（每个 import 存档一个按钮）。 */
    private final List<Button> archiveLinkButtons = new ArrayList<>();
    private final List<EditBox> lootNames = new ArrayList<>();
    private final List<EditBox> lootFonts = new ArrayList<>();
    private final Map<AbstractWidget, Integer> originalYMap = new HashMap<>();
    /**
     * 右侧面板控件的「逻辑可见性」（由 {@link #refreshDetailWidgets()} 决定，
     * 滚动裁剪 {@link #applyRightPanelClip()} 只在它基础上再隐藏滚出边界的控件，
     * 不会把本就该显示的控件永久弄丢）。
     */
    private final Map<AbstractWidget, Boolean> logicalVisibleMap = new HashMap<>();
    private final List<Integer> labelYs = new ArrayList<>();
    private EditBox id, name;
    /** [BR-FLIGHT-SPEED] 飞机飞行速度输入框（格/tick，0.5~20，详情面板专用）。 */
    private EditBox flightSpeedField;
    /** [BR-MATCHTEAM 模块一] 最大队伍数输入框（2~25，详情面板专用）。 */
    private EditBox maxTeamsField;
    /** [BR-MATCHTEAM 模块一] 每队最大人数输入框（1~4，详情面板专用）。 */
    private EditBox maxTeamSizeField;
    /** [BR-LOOT] 普通物资池、空投物资池与刷新上限配置（详情面板专用）。 */
    private EditBox lootPoolField;
    private EditBox airdropLootPoolField;
    private EditBox maxLootBoxesField;
    private EditBox maxAirdropsField;
    private EditBox airdropStagesField;
    private static final int LOOT_POOL_MAX_LEN = 16384;
    private static final int LOOT_STAGE_MAX_LEN = 128;
    private Button saveButton;
    private Button cancelButton;
    private Button selectionButton;
    private Button clearSelectionButton;
    /** 详情面板控件：点地图列表某一行后打开该地图的专属配置面板。 */
    private Button backButton;
    private Button detailEnterEditButton;
    private Button detailEnterGameButton;
    /** 详情面板「点击关联存档」/「更换关联存档」按钮，以及选择框里的刷新 / 收起按钮。 */
    private Button detailLinkArchiveButton;
    private Button archiveRefreshButton;
    private Button archiveClosePickerButton;
    /** 详情面板里是否展开存档选择框。 */
    private boolean archivePickerOpen;
    /** 详情面板有未保存的本地改动（改名 / 改物资点）：此时不让服务端的列表刷新覆盖面板内容。 */
    private boolean detailDirty;
    private boolean detailMode;
    private int detailIndex = -1;
    /** 打开面板时的 mapId：保存时用来判断是「编辑已有地图」还是「新建撞名」。 */
    private String loadedMapId = "";
    /** 详情面板右侧随滚动条移动的文字标签（与 detailLabelXs / detailLabelYs / detailLabelColors 平行）。 */
    private final List<String> detailLabelTexts = new ArrayList<>();
    private final List<Integer> detailLabelXs = new ArrayList<>();
    private final List<Integer> detailLabelYs = new ArrayList<>();
    private final List<Integer> detailLabelColors = new ArrayList<>();
    private boolean managementTab;
    private boolean confirmDelete;
    private String deleteMapId = "";
    private String notice = "";
    private int mapListScroll;
    private boolean requestedInitialData;
    // ---- [BR-ZONE] 模块一：详情面板里的「毒圈设置」侧栏 ----
    /** 毒圈面板是否展开（每个地图详情独立，进出详情面板都会收起重建）。 */
    private boolean zonePanelOpen;
    private Button zoneTitleButton;
    private Button zoneAddButton;
    private Button zoneSaveButton;
    private Button zoneCollapseButton;
    /** [r148 TTK 模型] 地图级圈外淘汰秒数（小数输入框）：阶段没单独配（填 0）时就用它。 */
    private EditBox zoneGlobalTtk;
    /** 四条输入框列（等待 / 缩圈 / 半径 / 淘汰秒，宽 40、列距 55）+ 删除按钮（宽 50、放不下自动换行），与 map.zonePhases 一一对应。 */
    private final List<EditBox> zoneWaits = new ArrayList<>();
    private final List<EditBox> zoneShrinks = new ArrayList<>();
    private final List<EditBox> zoneRadii = new ArrayList<>();
    private final List<EditBox> zoneTkts = new ArrayList<>();
    private final List<Button> zoneDeleteButtons = new ArrayList<>();
    // ---- [BR-ZONE] r114 自适应换行布局常量（严格按用户伪代码逐条对应） ----
    /** 伪代码第 1 步：标题画完，currentY += 15 才到小标题行。 */
    private static final int ZONE_TITLE_DROP = 15;
    /** 伪代码第 2~4 步：输入框挂在各自小标题下方 +10 处。 */
    private static final int ZONE_FIELD_DROP = 10;
    /** 输入框宽度（伪代码固定 40）与列距（伪代码 currentX += 55）。 */
    private static final int ZONE_FIELD_WIDTH = 40;
    private static final int ZONE_COL_STEP = 55;
    /** 伪代码第 5 步：删除按钮宽 50；X + 50 超过 maxRight 就换行。 */
    private static final int ZONE_DELETE_WIDTH = 50;
    /** 换行时 currentY += 30（按钮行高 20 + 10 缝，压不到上一行的输入框）。 */
    private static final int ZONE_WRAP_DROP = 30;
    /** 每阶段结尾 currentY += 60：块内最高内容（含换行）也留 ≥30 像素空白。 */
    private static final int ZONE_BLOCK_ADVANCE = 60;
    /** 阶段行的理想最大右边界 = startX + 275（四列 55 + 删除按钮 50）；再与面板实际右边缘取小。 */
    private static final int ZONE_ROW_SPAN = 275;
    /** 详情面板物资点同样一块一行：标题行 Y / 输入行 Y+22，块高 45，互不重叠。 */
    private static final int LOOT_BLOCK_HEIGHT = 45;
    /** 所有数值输入框的最大长度：放宽到 10 位，保证合理的秒数/半径不被误截；读取时仍有 clamp 兜底。 */
    private static final int NUM_FIELD_MAX_LEN = 10;
    private double rightPanelScrollOffset = 0.0;
    private double rightPanelMaxScroll = 0.0;
    private int rightPanelTop;
    private int rightPanelBottom;
    private int rightPanelX;
    private int rightPanelWidth;

    public MapEditorScreen(GameMap map) {
        super(Component.literal("地图编辑器"));
        this.map = map == null ? new GameMap() : map.copy();
        this.loadedMapId = this.map.mapId == null ? "" : this.map.mapId;
    }

    public void setArchives(List<ArchiveEntry> entries) {
        archives.clear();
        if (entries != null) archives.addAll(entries);
        if (minecraft != null && minecraft.screen == this) init();
    }

    public void setSavedMaps(List<GameMap> maps) {
        savedMaps.clear();
        if (maps != null) for (GameMap m : maps) if (m != null) savedMaps.add(m.copy());
        // 详情面板打开且没有本地未保存改动时，用服务端刚推来的真实数据刷新面板：
        // 「物资点（N 个）」和「源存档：xxx」都必须读自服务端内存对象，不是本地空壳。
        if (detailMode && !detailDirty) {
            reloadDetailFromList();
        }
        if (minecraft != null && minecraft.screen == this) init();
    }

    /** 按 mapId 在最新列表里找到这张地图的真实记录，替换详情面板当前的副本。 */
    private void reloadDetailFromList() {
        if (loadedMapId.isEmpty()) return;
        for (int i = 0; i < savedMaps.size(); i++) {
            GameMap g = savedMaps.get(i);
            if (loadedMapId.equals(g.mapId)) {
                map = g.copy();
                detailIndex = i;
                return;
            }
        }
        // 列表里已经没有这张图（被删除了）：退回草稿面板，避免详情面板继续显示幽灵数据。
        detailMode = false;
        detailIndex = -1;
        archivePickerOpen = false;
        MapEditorSync.requestCurrentState();
    }

    public void setSelection(boolean present, int minX, int minZ, int maxX, int maxZ) {
        if (detailMode) return; // 详情面板显示的是地图 JSON 里已存的选区，不被会话选区覆盖
        if (present) {
            map.minX = minX; map.minZ = minZ; map.maxX = maxX; map.maxZ = maxZ;
            notice = "已填入当前选区";
        } else notice = "当前没有完整选区";
    }

    public void setMap(GameMap updated) {
        if (updated == null) return;
        // 详情面板打开时忽略服务端草稿推送，避免正在配置的已存地图被编辑副本草稿覆盖。
        if (detailMode) return;
        map = updated.copy();
        loadedMapId = map.mapId == null ? "" : map.mapId;
        if (minecraft != null && minecraft.screen == this) init();
    }

    @Override
    protected void init() {
        clearWidgets();
        enterButtons.clear(); lootUpdateButtons.clear(); lootDeleteButtons.clear(); mapDeleteButtons.clear();
        mapEnterGameButtons.clear(); archiveLinkButtons.clear();
        lootNames.clear(); lootFonts.clear(); originalYMap.clear(); logicalVisibleMap.clear();
        zoneWaits.clear(); zoneShrinks.clear(); zoneRadii.clear(); zoneTkts.clear(); zoneDeleteButtons.clear();
        rightPanelScrollOffset = Mth.clamp(rightPanelScrollOffset, 0.0, rightPanelMaxScroll);

        id = addRight(field(0, 0, 120, map.mapId));
        name = addRight(field(0, 0, 120, map.displayName));
        name.setResponder(text -> { if (detailMode) detailDirty = true; });
        // [BR-FLIGHT-SPEED] 飞机速度：只允许数字 + 一个小数点，读取时再夹进 0.5~20（防 0 防离谱值）。
        flightSpeedField = addRight(decimalField(0, 0, 56, trimNumber(map.flightSpeed)));
        flightSpeedField.setResponder(text -> { if (detailMode) detailDirty = true; });
        // [BR-MATCHTEAM 模块一] 最大队伍数（2~25）/ 每队最大人数（1~4）：整数输入框，读取时夹回区间。
        maxTeamsField = addRight(numField(0, 0, 40, String.valueOf(map.maxTeams)));
        maxTeamsField.setResponder(text -> { if (detailMode) detailDirty = true; });
        maxTeamSizeField = addRight(numField(0, 0, 40, String.valueOf(map.maxTeamSize)));
        maxTeamSizeField.setResponder(text -> { if (detailMode) detailDirty = true; });
        // [BR-LOOT] 长文本框保留完整物资池配置；面板本身可滚动，避免挤压其他设置。
        lootPoolField = addRight(longField(0, 0, 120, map.lootPool,
                "minecraft:iron_sword || minecraft:bow |and| minecraft:arrow 16"));
        lootPoolField.setResponder(text -> { if (detailMode) detailDirty = true; });
        airdropLootPoolField = addRight(longField(0, 0, 120, map.airdropLootPool,
                "minecraft:diamond || minecraft:golden_apple"));
        airdropLootPoolField.setResponder(text -> { if (detailMode) detailDirty = true; });
        maxLootBoxesField = addRight(numField(0, 0, 56, String.valueOf(map.maxLootBoxes)));
        maxLootBoxesField.setResponder(text -> { if (detailMode) detailDirty = true; });
        maxAirdropsField = addRight(numField(0, 0, 56, String.valueOf(map.maxAirdrops)));
        maxAirdropsField.setResponder(text -> { if (detailMode) detailDirty = true; });
        airdropStagesField = addRight(longField(0, 0, 80, stagesText(map.airdropStages), "2,4"));
        airdropStagesField.setResponder(text -> { if (detailMode) detailDirty = true; });
        saveButton = addRight(Button.builder(Component.literal("保存"), b -> save()).pos(0, 0).size(70, 20).build());
        cancelButton = addRight(Button.builder(Component.literal("取消"), b -> minecraft.setScreen(null)).pos(0, 0).size(70, 20).build());
        selectionButton = addRight(Button.builder(Component.literal("使用当前选区"), b -> MapEditorSync.requestSelection()).pos(0, 0).size(90, 20).build());
        clearSelectionButton = addRight(Button.builder(Component.literal("清空选区"), b -> {
            MapEditorSync.clearSelection(); map.minX = map.minZ = map.maxX = map.maxZ = 0; notice = "已清空选区";
        }).pos(0, 0).size(70, 20).build());
        backButton = addRight(Button.builder(Component.literal("返回列表"), b -> exitDetail()).pos(0, 0).size(70, 20).build());
        int detailButtonWidth = Math.max(120, width - (int) (width * .58f) - 16);
        detailEnterEditButton = addRight(Button.builder(Component.literal("进入编辑副本去圈选"), b -> detailEnterEdit()).pos(0, 0).size(detailButtonWidth, 20).build());
        detailEnterGameButton = addRight(Button.builder(Component.literal("进入游戏维度配物资"), b -> detailEnterGame()).pos(0, 0).size(detailButtonWidth, 20).build());
        detailLinkArchiveButton = addRight(Button.builder(Component.literal("点击关联存档"), b -> toggleArchivePicker()).pos(0, 0).size(detailButtonWidth, 20).build());
        archiveRefreshButton = addRight(Button.builder(Component.literal("刷新存档列表"), b -> { MapEditorSync.requestArchives(); notice = "正在重新扫描 config/battleroyale/import/ …"; }).pos(0, 0).size((detailButtonWidth - 8) / 2, 20).build());
        archiveClosePickerButton = addRight(Button.builder(Component.literal("收起"), b -> { archivePickerOpen = false; init(); }).pos(0, 0).size((detailButtonWidth - 8) / 2, 20).build());

        List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
        for (int i = 0; i < points.size(); i++) {
            final int index = i;
            lootNames.add((EditBox) addRight(field(0, 0, 80, points.get(i).name())));
            lootFonts.add((EditBox) addRight(numField(0, 0, 35, String.valueOf(points.get(i).fontSize()))));
            lootUpdateButtons.add(addRight(Button.builder(Component.literal("更新"), b -> updateLoot(index)).pos(0, 0).size(42, 20).build()));
            lootDeleteButtons.add(addRight(Button.builder(Component.literal("删除"), b -> {
                if (detailMode) deleteLootLocal(index); else MapEditorSync.deleteLoot(index);
            }).pos(0, 0).size(42, 20).build()));
        }
        // ---- [BR-ZONE] 模块一：毒圈设置侧栏的控件（行控件仅在详情面板 + 展开时创建） ----
        int zoneButtonWidth = Math.max(120, width - (int) (width * .58f) - 16);
        zoneTitleButton = addRight(Button.builder(Component.literal("毒圈设置"), b -> toggleZonePanel()).pos(0, 0).size(zoneButtonWidth, 20).build());
        zoneAddButton = addRight(Button.builder(Component.literal("＋阶段"), b -> addZonePhase()).pos(0, 0).size(60, 20).build());
        zoneSaveButton = addRight(Button.builder(Component.literal("保存毒圈"), b -> saveZonePanel()).pos(0, 0).size(80, 20).build());
        // [r148 TTK 模型] 地图级圈外淘汰秒数：满血在圈外多少秒被毒死（支持小数，默认 250）。
        zoneGlobalTtk = addRight(decimalField(0, 0, 60, trimNumber(map.zoneTtkSeconds)));
        zoneCollapseButton = addRight(Button.builder(Component.literal("收起"), b -> toggleZonePanel()).pos(0, 0).size(44, 20).build());
        if (detailMode && zonePanelOpen) {
            List<ZonePhaseConfig> phases = map.zonePhases == null ? List.of() : map.zonePhases;
            for (int i = 0; i < phases.size(); i++) {
                final int index = i;
                ZonePhaseConfig phase = phases.get(i);
                // 三个整数输入框：宽度固定 40、列距 55（等待 / 缩圈 / 半径），全部限长 NUM_FIELD_MAX_LEN 位；
                // 删除按钮宽 50，排版时超界自动换行（见 layoutZoneRow）。
                // [r148] 新增阶段的淘汰秒在 addZonePhase 里按示例梯度自动给。
                zoneWaits.add((EditBox) addRight(numField(0, 0, ZONE_FIELD_WIDTH, String.valueOf(phase.waitTime))));
                zoneShrinks.add((EditBox) addRight(numField(0, 0, ZONE_FIELD_WIDTH, String.valueOf(phase.shrinkTime))));
                zoneRadii.add((EditBox) addRight(numField(0, 0, ZONE_FIELD_WIDTH, trimNumber(phase.radius))));
                // [r148 TTK 模型] 第四列：本阶段圈外淘汰秒数（小数输入，0 = 跟随上面的全局值）
                zoneTkts.add((EditBox) addRight(decimalField(0, 0, ZONE_FIELD_WIDTH,
                        trimNumber(phase.zoneTtkSeconds))));
                zoneDeleteButtons.add(addRight(Button.builder(Component.literal("删除"), b -> deleteZonePhase(index)).pos(0, 0).size(ZONE_DELETE_WIDTH, 20).build()));
            }
        }
        for (ArchiveEntry entry : archives) {
            enterButtons.add(addRenderableWidget(Button.builder(Component.literal("进入副本"), b -> {
                MapEditorSync.enterArchive(entry.name()); minecraft.setScreen(null);
            }).pos(0, 0).size(80, 20).build()));
        }
        // 详情面板的存档选择框：每个 import 存档一个按钮，点了就把绝对路径写进这张地图。
        for (ArchiveEntry entry : archives) {
            final String archiveName = entry.name();
            archiveLinkButtons.add(addRight(Button.builder(Component.literal(font.plainSubstrByWidth(archiveName,
                            Math.max(60, width - (int) (width * .58f) - 44))), b -> pickArchive(archiveName)).pos(0, 0).size(Math.max(120, width - (int) (width * .58f) - 16), 20).build()));
        }
        for (GameMap saved : savedMaps) {
            final String mapId = saved.mapId;
            mapDeleteButtons.add(addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
                deleteMapId = mapId; confirmDelete = true;
            }).pos(0, 0).size(50, 20).build()));
            mapEnterGameButtons.add(addRenderableWidget(Button.builder(Component.literal("进入游戏维度"), b -> {
                if (minecraft != null && minecraft.player != null && mapId != null && mapId.matches("[A-Za-z0-9_-]+")) {
                    minecraft.getConnection().sendUnsignedCommand("paimengmods manager game enter " + mapId);
                    minecraft.setScreen(null);
                }
            }).pos(0, 0).size(96, 20).build()));
        }
        layout();
        if (!requestedInitialData) {
            requestedInitialData = true;
            MapEditorSync.requestArchives(); MapListSync.requestList(); MapEditorSync.requestCurrentState();
        }
    }

    private EditBox field(int x, int y, int w, String value) {
        EditBox f = new EditBox(font, x, y, Math.max(30, w), 20, Component.literal(""));
        setFieldText(f, value);
        return f;
    }

    /**
     * 数值输入框：限长 NUM_FIELD_MAX_LEN 位。
     * 关键顺序：必须先 setMaxLength 再写文本——1.18.2 原版的 setMaxLength
     * 只把已有 text 截短，不会重置光标/选区/滚动偏移（firstCharacterIndex），
     * 顺序反了就会残留越界光标，渲染输入框时 substring 直接崩溃
     * （StringIndexOutOfBoundsException: begin 11, end 6, length 6）。
     */
    private EditBox numField(int x, int y, int w, String value) {
        EditBox f = new EditBox(font, x, y, Math.max(30, w), 20, Component.literal(""));
        f.setMaxLength(NUM_FIELD_MAX_LEN);
        setFieldText(f, value);
        return f;
    }

    /** 长文本输入框：用于物资池和阶段列表，允许空格、NBT 和分隔符。 */
    private EditBox longField(int x, int y, int w, String value, String hint) {
        EditBox f = new EditBox(font, x, y, Math.max(30, w), 20, Component.literal(""));
        f.setMaxLength(hint.equals("2,4") ? LOOT_STAGE_MAX_LEN : LOOT_POOL_MAX_LEN);
        setFieldText(f, value);
        f.setHint(Component.literal(hint));
        return f;
    }

    /**
     * [BR-FLIGHT-SPEED] 小数输入框（飞机速度专用）：在 numField 的防崩溃顺序之上
     * 再加一层 setTextPredicate 字符闸——逐字符只放行数字与小数点，并且全串最多
     * 一个小数点（粘贴 "1.2.3" 也进不来）。负号 / 空格 / 字母一律拒绝，
     * 从源头杜绝非数字文本进解析。
     */
    private EditBox decimalField(int x, int y, int w, String value) {
        EditBox f = numField(x, y, w, value);
        f.setFilter(s -> {
            long dots = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '.') {
                    dots++;
                } else if (c < '0' || c > '9') {
                    return false; // 负号 / 空格 / 字母等一切非数字字符直接拒绝
                }
            }
            // 全串只允许一个小数点：粘贴 "1.2.3" 或已有小数点再打 "." 都进不来
            return dots <= 1 && (dots == 0 || !f.getValue().contains("."));
        });
        return f;
    }

    /** 安全写文本：空值兜底 + 截断到当前上限，写完立刻把光标和选区归位到文本末尾，杜绝残留越界下标。 */
    private static void setFieldText(EditBox f, String value) {
        f.setValue(value == null ? "" : value.trim()); // 原版 setText 会按当前 maxLength 截断输入串
        int len = f.getValue().length();               // 以截断后的真实长度为准
        f.setCursorPosition(len);                             // setCursor 内部把 selectionStart/End 夹回 [0, len]
        f.setCursorPosition(len);
        f.setHighlightPos(len);
    }

    private <T extends AbstractWidget> T addRight(T widget) {
        addRenderableWidget(widget);
        originalYMap.put(widget, 0);
        return widget;
    }

    private void place(AbstractWidget widget, int x, int y) {
        widget.setX(x);
        originalYMap.put(widget, y);
        widget.setY((int) Math.round(y - rightPanelScrollOffset));
    }

    private void layout() {
        int split = (int) (width * .58f);
        rightPanelX = split + 8;
        rightPanelWidth = Math.max(120, width - rightPanelX - 8);
        rightPanelTop = 28;
        rightPanelBottom = height - 24;
        int y = rightPanelTop + 10;
        labelYs.clear();
        detailLabelTexts.clear(); detailLabelXs.clear(); detailLabelYs.clear(); detailLabelColors.clear();

        y = detailMode ? layoutDetail(y) : layoutEditor(y);

        rightPanelMaxScroll = Math.max(0, y - rightPanelBottom);
        rightPanelScrollOffset = Mth.clamp(rightPanelScrollOffset, 0.0, rightPanelMaxScroll);
        for (Map.Entry<AbstractWidget, Integer> entry : originalYMap.entrySet()) {
            entry.getKey().setY((int) Math.round(entry.getValue() - rightPanelScrollOffset));
        }

        int listTop = 62;
        int archiveRowHeight = 50;
        for (int i = 0; i < enterButtons.size(); i++) {
            int rowY = listTop + i * archiveRowHeight - mapListScroll;
            Button button = enterButtons.get(i);
            button.setX(8);
            button.setY(rowY + 22);
            button.visible = !managementTab && rowY + archiveRowHeight >= listTop && rowY < height;
        }
        for (int i = 0; i < mapDeleteButtons.size(); i++) {
            int rowY = listTop + i * 46 - mapListScroll;
            Button button = mapDeleteButtons.get(i);
            button.setX(split - 58);
            button.setY(rowY + 10);
            button.visible = managementTab && rowY + 40 >= listTop && rowY < height;
        }
        for (int i = 0; i < mapEnterGameButtons.size(); i++) {
            int rowY = listTop + i * 46 - mapListScroll;
            Button button = mapEnterGameButtons.get(i);
            button.setX(split - 160);
            button.setY(rowY + 10);
            button.visible = managementTab && rowY + 40 >= listTop && rowY < height;
        }
        for (Button b : enterButtons) b.visible = !managementTab && b.visible;
        for (Button b : mapDeleteButtons) b.visible = managementTab && b.visible;
        for (Button b : mapEnterGameButtons) b.visible = managementTab && b.visible;
        refreshDetailWidgets();
        applyRightPanelClip();
    }

    /**
     * 滚动裁剪（r113，杜绝控件越界）：右侧面板支持滚动，控件的屏幕 Y = 内容 Y − 滚动偏移；
     * 这里再补一刀——逻辑可见但整块滚出面板上下边界的控件直接 {@code visible = false}，
     * 既不画（原版控件不受 scissor 约束，不会盖住顶部标签页 / 底部提示行），
     * 也不响应点击（滚出可视区的输入框不该还能被点到）。
     */
    private void applyRightPanelClip() {
        double contentTop = rightPanelTop + rightPanelScrollOffset;
        double contentBottom = rightPanelBottom + rightPanelScrollOffset;
        for (Map.Entry<AbstractWidget, Integer> entry : originalYMap.entrySet()) {
            AbstractWidget w = entry.getKey();
            boolean logical = logicalVisibleMap.getOrDefault(w, true);
            int contentY = entry.getValue();
            w.visible = logical && contentY + w.getHeight() >= contentTop && contentY <= contentBottom;
        }
    }

    /** 「地图添加」标签页的右侧面板（编辑副本草稿：选区工具 + 物资点）。返回最终 y。 */
    private int layoutEditor(int y) {
        int rowHeight = 20;
        labelYs.add(y);
        y += 12;
        place(id, rightPanelX, y); y += rowHeight + 6;
        labelYs.add(y);
        y += 12;
        place(name, rightPanelX, y); y += rowHeight + 6;
        labelYs.add(y);
        y += 12;
        place(saveButton, rightPanelX, y);
        y += rowHeight + 6;
        place(cancelButton, rightPanelX + 76, y - 26);
        place(selectionButton, rightPanelX, y);
        y += rowHeight + 6;
        place(clearSelectionButton, rightPanelX + 96, y - 26);
        y += 6;
        labelYs.add(y);
        y += 12;

        List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
        for (int i = 0; i < points.size(); i++) {
            layoutLootRow(i, y, 0);
            y += 26;
        }
        labelYs.add(y);
        y += 12;
        return y;
    }

    /** 详情面板布局：mapId 展示、改名、选区、两个跳转按钮、物资点列表（带坐标）。返回最终 y。 */
    private int layoutDetail(int y) {
        detailLabel(y, "地图详情：" + (map.displayName == null || map.displayName.isBlank() ? map.mapId : map.displayName), 0xFFFFFFFF);
        y += 12;
        place(id, rightPanelX, y); y += 26;
        detailLabel(y, "显示名称（改完点「保存配置」）", 0xFFFFFFFF);
        y += 12;
        place(name, rightPanelX, y); y += 26;
        place(saveButton, rightPanelX, y);
        place(backButton, rightPanelX + 76, y); y += 26;
        detailLabel(y, "选区范围：X " + map.minX + " ~ " + map.maxX + "，Z " + map.minZ + " ~ " + map.maxZ, 0xFFFFFFAA);
        y += 12;
        String archive = archiveNameOf(map);
        if (archive.isEmpty()) {
            detailLabel(y, "源存档：未关联（圈选和生成地形都需要先关联存档）", 0xFFFF5555);
        } else {
            detailLabel(y, "源存档：" + archive, 0xFFAAFFAA);
            y += 12;
            detailLabel(y, "完整路径：" + abbreviatePath(map.sourceArchivePath), 0xFFAAAAAA);
        }
        y += 12;
        // [BR-FLIGHT-SPEED] 飞机速度：标题行 + 输入框行 + 提示行，各占整行、互不重叠
        // （沿用自适应换行那套垂直流式：一行一个元素，Y 逐段推进）。
        detailLabel(y, "飞机速度（格/tick，0.5 ~ 20，默认 4）", 0xFFFFFFFF);
        y += 12;
        place(flightSpeedField, rightPanelX, y); y += 26;
        detailLabel(y, "小图调慢、大图调快：4.0 = 80 格/秒，200 格航线约 2.5 秒飞完", 0xFFAAAAAA);
        y += 14;
        // [BR-MATCHTEAM 模块一] 开局门槛：最大队伍数（2~25）+ 每队最大人数（1~4），详情面板专用。
        detailLabel(y, "本局最大队伍数（2 ~ 25，默认 25）", 0xFFFFFFFF);
        y += 12;
        place(maxTeamsField, rightPanelX, y); y += 26;
        detailLabel(y, "每队最大人数（1 ~ 4，默认 4）", 0xFFFFFFFF);
        y += 12;
        place(maxTeamSizeField, rightPanelX, y); y += 26;
        // [r149 DYNCAP 模块一] 动态人数上限提示：写死的「100 人」已删除，
        // 上限 = 最大队伍数 × 每队最大人数，随两个输入框实时联动。
        int capTeams = Mth.clamp(number(maxTeamsField, map.maxTeams),
                GameMap.MIN_MAX_TEAMS, GameMap.MAX_MAX_TEAMS);
        int capSize = Mth.clamp(number(maxTeamSizeField, map.maxTeamSize),
                GameMap.MIN_MAX_TEAM_SIZE, GameMap.MAX_MAX_TEAM_SIZE);
        detailLabel(y, "地图人数上限 = " + capTeams + " × " + capSize + " = "
                + (capTeams * capSize) + " 人（等待区满员后拒绝进入）", 0xFFAAAAAA);
        y += 14;
        detailLabel(y, "独立队伍数凑满最大队伍数即自动开局；管理员可强制提前开", 0xFFAAAAAA);
        y += 14;
        // ---- [BR-LOOT] 物资刷新配置：长字符串完整保留，面板可滚动，不会遮挡其他设置 ----
        detailLabel(y, "普通物资池（用 || 分组，|and| 绑定必须同刷的物品，末尾可写数量）", 0xFFFFFFFF);
        y += 12;
        int lootPoolWidth = Math.max(120, rightPanelWidth - 4);
        lootPoolField.setWidth(lootPoolWidth);
        place(lootPoolField, rightPanelX + 2, y); y += 26;
        detailLabel(y, "空投物资池（语法同普通物资池）", 0xFFFFFFFF);
        y += 12;
        airdropLootPoolField.setWidth(lootPoolWidth);
        place(airdropLootPoolField, rightPanelX + 2, y); y += 26;
        detailLabel(y, "物资箱上限（1 ~ 10000，默认 300）", 0xFFFFFFFF);
        y += 12;
        place(maxLootBoxesField, rightPanelX + 2, y); y += 26;
        detailLabel(y, "空投次数上限（0 ~ 20，默认 2）", 0xFFFFFFFF);
        y += 12;
        place(maxAirdropsField, rightPanelX + 2, y); y += 26;
        detailLabel(y, "空投触发阶段（英文逗号分隔，如 2,4）", 0xFFFFFFFF);
        y += 12;
        int stageWidth = Math.min(220, Math.max(80, rightPanelWidth - 4));
        airdropStagesField.setWidth(stageWidth);
        place(airdropStagesField, rightPanelX + 2, y); y += 28;
        detailLabel(y, "跳转（点了才会传送）：", 0xFFFFFFFF);
        y += 12;
        place(detailEnterEditButton, rightPanelX, y); y += 24;
        place(detailLinkArchiveButton, rightPanelX, y); y += 26;
        if (archivePickerOpen) {
            detailLabel(y, "选择要关联的存档（config/battleroyale/import/）：", 0xFFFFFF55);
            y += 12;
            if (archiveLinkButtons.isEmpty()) {
                detailLabel(y, "import 目录下没有可用存档：把存档 zip 或文件夹放进去后点「刷新存档列表」", 0xFFAAAAAA);
                y += 14;
            }
            for (Button button : archiveLinkButtons) {
                place(button, rightPanelX, y);
                y += 22;
            }
            int pickerHalf = Math.max(60, (rightPanelWidth - 8) / 2);
            place(archiveRefreshButton, rightPanelX, y);
            place(archiveClosePickerButton, rightPanelX + pickerHalf + 8, y);
            y += 26;
        }
        place(detailEnterGameButton, rightPanelX, y); y += 26;

        // ---- [BR-ZONE] 模块一：毒圈设置侧栏（r114 自适应换行布局，按伪代码逐行实现） ----
        place(zoneTitleButton, rightPanelX, y); y += 26;
        if (detailMode && zonePanelOpen) {
            int maxRadius = (int) Math.floor(zoneMaxRadius());
            detailLabel(y, "每阶段：等待/缩圈 30~900 秒；半径 ≥0（上限 " + maxRadius
                    + " = 选区短边一半）；淘汰秒 = 满血在圈外多少秒被毒死", 0xFFFFFFAA);
            y += 12;
            detailLabel(y, "淘汰秒按 每秒伤害=生命上限÷秒数 反推（血多扛得久）；阶段填 0 = 跟随全局", 0xFFAAAAAA);
            y += 12;
            // [r148] 全局圈外淘汰秒数：标签在上、小数输入框在下（跟随阶段行同款排版）。
            detailLabel(y, "全局淘汰秒（阶段填 0 用它；默认 250 秒）", 0xFFEFEFEF);
            place(zoneGlobalTtk, rightPanelX + 2, y + ZONE_FIELD_DROP);
            y += 12 + ZONE_FIELD_DROP + 22;
            detailLabel(y, "空列表 = 未自定义，开局用默认 6 阶段表（淘汰梯度 250→100 秒）", 0xFFAAAAAA);
            y += 14;
            if (zoneWaits.isEmpty()) {
                detailLabel(y, "还没有阶段：点「＋阶段」加一条，改完点「保存毒圈」写进服务器", 0xFFAAAAAA);
                y += 14;
            }
            // 阶段块：layoutZoneRow 内部按伪代码推进（标题 → +15 → 三列输入框各 +55 → 删除按钮
            // 超出 maxRight 就换行到最左），并返回本块实际占用的行高（不换行 75 / 换行 105），
            // 下一阶段从这个高度之后起排，块与块之间永远留 ≥30 像素空白。
            int currentY = y;
            for (int i = 0; i < zoneWaits.size(); i++) {
                currentY += layoutZoneRow(i, currentY);
            }
            // 全局按钮行：放在最后一个阶段块下方再空 20 像素（currentY + 20），绝不与阶段区重叠。
            int actionsY = currentY + 20;
            int startX = rightPanelX + 2;
            place(zoneAddButton, startX, actionsY);
            place(zoneSaveButton, startX + 70, actionsY);
            place(zoneCollapseButton, startX + 160, actionsY);
            // 物资点列表起始 Y = 全局按钮行底部 + 6，盖不到上面的按钮。
            y = actionsY + 26;
        }

        List<LootPoint> points = map.lootPoints == null ? List.of() : map.lootPoints;
        detailLabel(y, "物资点（" + points.size() + " 个）", 0xFFFFFFFF);
        y += 16;
        // 物资点同样垂直流式：每个点独立占一块（标题行「物资点 N + 坐标」，下方才是输入行），互不重叠。
        for (int i = 0; i < points.size(); i++) {
            LootPoint p = points.get(i);
            int blockTop = y;
            int rowX = rightPanelX + 2;
            detailLabelAt(rowX, blockTop, "物资点 " + (i + 1), 0xFFEFEFEF);
            detailLabelAt(rowX + 66, blockTop,
                    font.plainSubstrByWidth("坐标 (" + Math.round(p.x()) + ", " + Math.round(p.z()) + ")",
                            Math.max(60, rightPanelWidth - 74)), 0xFFAAAAAA);
            layoutLootRow(i, blockTop + 22, 0);
            y += LOOT_BLOCK_HEIGHT;
        }
        return y;
    }

    /**
     * 物资点一行的排版：字号框和「更新」「删除」按钮从面板右边缘倒推（靠右对齐），
     * 名称输入框吃掉剩下的全部宽度，因此任何窗口宽度都不会把控件顶出屏幕。
     * 只摆位置，不改这些控件的点击行为。
     */
    private void layoutLootRow(int index, int rowY, int leftInset) {
        final int gap = 6;
        int right = rightPanelX + rightPanelWidth;
        int deleteX = right - 42;
        int updateX = deleteX - gap - 42;
        int fontX = updateX - gap - 35;
        int nameX = rightPanelX + leftInset;
        int nameWidth = Math.max(40, fontX - gap - nameX);
        EditBox nameField = lootNames.get(index);
        nameField.setWidth(nameWidth);
        place(nameField, nameX, rowY);
        place(lootFonts.get(index), fontX, rowY);
        place(lootUpdateButtons.get(index), updateX, rowY);
        place(lootDeleteButtons.get(index), deleteX, rowY);
    }

    private void detailLabel(int y, String text, int color) {
        detailLabelAt(rightPanelX, y, text, color);
    }

    /** 带 X 偏移的详情标签（毒圈块里的小标题要和输入框列对齐）；随右侧面板一起滚动。 */
    private void detailLabelAt(int x, int y, String text, int color) {
        detailLabelTexts.add(text); detailLabelXs.add(x); detailLabelYs.add(y); detailLabelColors.add(color);
    }

    // ==================== [BR-ZONE] 模块一：毒圈设置侧栏 ====================

    /**
     * 毒圈单阶段的「自适应换行」块（r114，逐行对应需求给的伪代码）：
     * <pre>
     * currentX = startX;  drawText("阶段 N", currentX, currentY);   currentY += 15
     * drawLabel("等待", currentX, currentY); drawField(currentX, currentY+10, 40, 20); currentX += 55
     * drawLabel("缩圈", currentX, currentY); drawField(currentX, currentY+10, 40, 20); currentX += 55
     * drawLabel("半径", currentX, currentY); drawField(currentX, currentY+10, 40, 20); currentX += 55
     * if (currentX + 50 > maxRight) { currentX = startX; currentY += 30; }
     * drawButton("删除", currentX, currentY+10, 50, 20)
     * </pre>
     * maxRight = min(startX + 220, 面板右边缘)：删除按钮既不超出 220 的内容宽，
     * 也永远贴不出右侧面板；放不下就换行到下一行最左侧（X 重置为 startX）。
     * 输入框 / 按钮高 20：不换行时块内容底部在 blockTop+45，块尾再 +60 → 下一阶段标题
     * blockTop+75，留 30 像素空白；换行时按钮独占一行，块尾 +30 → 阶段间隔 105，同样留 30。
     *
     * @return 本阶段块实际占用的行高（不换行 75 / 换行 105），调用方按它推进 currentY
     */
    private int layoutZoneRow(int index, int blockTop) {
        int startX = rightPanelX + 2;
        int maxRight = Math.min(startX + ZONE_ROW_SPAN, rightPanelX + rightPanelWidth);
        int currentX = startX;
        int currentY = blockTop;

        // 1. 标题「阶段 N」
        detailLabelAt(currentX, currentY, "阶段 " + (index + 1), 0xFFEFEFEF);
        currentY += ZONE_TITLE_DROP;

        // 2. 等待（输入框宽 40，挂在小标题下方 +10）
        detailLabelAt(currentX, currentY, "等待", 0xFFBBBBBB);
        place(zoneWaits.get(index), currentX, currentY + ZONE_FIELD_DROP);
        currentX += ZONE_COL_STEP;

        // 3. 缩圈
        detailLabelAt(currentX, currentY, "缩圈", 0xFFBBBBBB);
        place(zoneShrinks.get(index), currentX, currentY + ZONE_FIELD_DROP);
        currentX += ZONE_COL_STEP;

        // 4. 半径
        detailLabelAt(currentX, currentY, "半径", 0xFFBBBBBB);
        place(zoneRadii.get(index), currentX, currentY + ZONE_FIELD_DROP);
        currentX += ZONE_COL_STEP;

        // [r148 TTK 模型] 4b. 淘汰秒（本阶段圈外满血淘汰时间，0 = 跟随全局）
        detailLabelAt(currentX, currentY, "淘汰秒", 0xFFBBBBBB);
        place(zoneTkts.get(index), currentX, currentY + ZONE_FIELD_DROP);
        currentX += ZONE_COL_STEP;

        // 5. 删除按钮（宽 50）：X + 50 超出 maxRight 就换行到最左侧
        boolean wrapped = currentX + ZONE_DELETE_WIDTH > maxRight;
        if (wrapped) {
            currentX = startX;
            currentY += ZONE_WRAP_DROP;
        }
        place(zoneDeleteButtons.get(index), currentX, currentY + ZONE_FIELD_DROP);

        return ZONE_TITLE_DROP + (wrapped ? ZONE_WRAP_DROP : 0) + ZONE_BLOCK_ADVANCE;
    }

    /** 展开 / 收起毒圈面板（重建控件列表，沿用 archivePicker 的 toggle 模式）。 */
    private void toggleZonePanel() {
        if (!detailMode) return;
        zonePanelOpen = !zonePanelOpen;
        if (zonePanelOpen) notice = "改完点「保存毒圈」才会写入服务端";
        init();
    }

    /** 半径硬限制上限：选区短边的一半（与服务端 ZonePlanner.Bounds.maxRadius 同一口径）。 */
    private double zoneMaxRadius() {
        int spanX = Math.abs(map.maxX - map.minX);
        int spanZ = Math.abs(map.maxZ - map.minZ);
        return Math.max(0.0, Math.min(spanX, spanZ) / 2.0);
    }

    /**
     * 加一条阶段：默认值 = 90/60 秒，半径取上一阶段的 0.6 倍（首阶段 0.65 倍上限），
     * [r148] 淘汰秒按示例梯度取（250 → 200 → … → 8，越界用最后一档）。
     */
    private void addZonePhase() {
        if (map.zonePhases == null) map.zonePhases = new ArrayList<>();
        if (map.zonePhases.size() >= ZonePhaseConfig.MAX_PHASES) {
            notice = "最多 " + ZonePhaseConfig.MAX_PHASES + " 个阶段";
            return;
        }
        double maxRadius = zoneMaxRadius();
        double previous = map.zonePhases.isEmpty()
                ? maxRadius : map.zonePhases.get(map.zonePhases.size() - 1).radius;
        ZonePhaseConfig phase = new ZonePhaseConfig(
                ZonePhaseConfig.MAX_SECONDS, ZonePhaseConfig.MIN_SECONDS * 2,
                ZonePhaseConfig.clampRadius(previous * 0.6, maxRadius),
                com.paimeng.battleroyale.zone.ZoneStages.defaultTtkAt(map.zonePhases.size()));
        phase.clampIntoRange(maxRadius);
        map.zonePhases.add(phase);
        detailDirty = true;
        notice = "已加第 " + map.zonePhases.size() + " 阶段，点「保存毒圈」写进服务器";
        init();
    }

    /** 删一条阶段（只改本地副本，点保存才提交；空列表提交 = 恢复默认表）。 */
    private void deleteZonePhase(int index) {
        if (map.zonePhases == null || index < 0 || index >= map.zonePhases.size()) return;
        map.zonePhases.remove(index);
        detailDirty = true;
        notice = "已删除一个阶段，点「保存毒圈」写进服务器";
        init();
    }

    /** 提交整张阶段表：本地先夹硬限制，再走 map_editor_zone_save C2S，服务端落 JSON 后回推列表刷新。 */
    private void saveZonePanel() {
        if (!detailMode || map.zonePhases == null) return;
        double maxRadius = zoneMaxRadius();
        List<ZonePhaseConfig> updated = new ArrayList<>();
        for (int i = 0; i < zoneWaits.size() && i < map.zonePhases.size(); i++) {
            ZonePhaseConfig old = map.zonePhases.get(i);
            // 读取兜底：等待/缩圈 Mth.clamp(val, 30, 900)；半径 Math.max(0, val)；
            // [r148] 淘汰秒读第四列小数框（非法输入回退该阶段原值，0 = 跟随全局）。
            int wait = Mth.clamp(number(zoneWaits.get(i), old.waitTime), 30, 900);
            int shrink = Mth.clamp(number(zoneShrinks.get(i), old.shrinkTime), 30, 900);
            double radius = Math.max(0.0, decimal(zoneRadii.get(i), old.radius));
            double ttk = Math.max(0.0, decimal(zoneTkts.get(i), old.zoneTtkSeconds));
            ZonePhaseConfig phase = new ZonePhaseConfig(wait, shrink, radius, ttk);
            phase.clampIntoRange(maxRadius); // 再按选区短边一半夹上限（与服务端同口径）
            updated.add(phase);
        }
        map.zonePhases = updated;
        // [r148] 全局圈外淘汰秒数：非法输入 / 负数回退现值；0 也当非法（服务端 normalize 兜底下限 1 秒）。
        double globalTtk = decimal(zoneGlobalTtk, map.zoneTtkSeconds);
        if (!(globalTtk > 0.0)) {
            globalTtk = map.zoneTtkSeconds;
        }
        map.zoneTtkSeconds = globalTtk;
        detailDirty = false;
        MapEditorSync.saveZonePhases(map.mapId, updated, globalTtk);
        notice = "毒圈配置已发送：" + updated.size() + " 个阶段（服务端按选区落盘，稍后自动回填）";
        init();
    }

    /** 浮点输入框解析（非法输入回退旧值）。 */
    private double decimal(EditBox widget, double fallback) {
        try {
            return Double.parseDouble(widget.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 整数值不带 .0，浮点原样显示。 */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /** 按当前模式刷新右侧面板控件的可见性、文案和可编辑性。 */
    private void refreshDetailWidgets() {
        id.setEditable(!detailMode);
        saveButton.setMessage(Component.literal(detailMode ? "保存配置" : "保存"));
        cancelButton.visible = !detailMode;
        selectionButton.visible = !detailMode;
        clearSelectionButton.visible = !detailMode;
        backButton.visible = detailMode;
        flightSpeedField.visible = detailMode; // [BR-FLIGHT-SPEED] 只在详情面板出现（草稿面板不摆位）
        maxTeamsField.visible = detailMode;    // [BR-MATCHTEAM] 同上
        maxTeamSizeField.visible = detailMode; // [BR-MATCHTEAM] 同上
        lootPoolField.visible = detailMode;
        airdropLootPoolField.visible = detailMode;
        maxLootBoxesField.visible = detailMode;
        maxAirdropsField.visible = detailMode;
        airdropStagesField.visible = detailMode;
        detailEnterEditButton.visible = detailMode;
        detailEnterGameButton.visible = detailMode;
        detailLinkArchiveButton.visible = detailMode;
        archiveRefreshButton.visible = detailMode && archivePickerOpen;
        archiveClosePickerButton.visible = detailMode && archivePickerOpen;
        for (Button button : archiveLinkButtons) button.visible = detailMode && archivePickerOpen;
        // [BR-ZONE] 模块一：毒圈侧栏的可见性（行控件只在展开时创建，这里统一开关按钮组）。
        int phaseCount = map.zonePhases == null ? 0 : map.zonePhases.size();
        zoneTitleButton.visible = detailMode;
        zoneTitleButton.setMessage(Component.literal(zonePanelOpen
                ? "毒圈设置（" + phaseCount + " 个阶段）"
                : "毒圈设置" + (phaseCount == 0 ? "（未配置：用默认表）" : "（" + phaseCount + " 个阶段）")));
        boolean zoneControlsVisible = detailMode && zonePanelOpen;
        zoneAddButton.visible = zoneControlsVisible;
        zoneSaveButton.visible = zoneControlsVisible;
        zoneCollapseButton.visible = zoneControlsVisible;
        zoneAddButton.active = phaseCount < ZonePhaseConfig.MAX_PHASES;
        for (EditBox widget : zoneWaits) widget.visible = zoneControlsVisible;
        for (EditBox widget : zoneShrinks) widget.visible = zoneControlsVisible;
        for (EditBox widget : zoneRadii) widget.visible = zoneControlsVisible;
        for (Button button : zoneDeleteButtons) button.visible = zoneControlsVisible;
        if (detailMode) {
            String archive = archiveNameOf(map);
            detailEnterEditButton.setMessage(Component.literal(font.plainSubstrByWidth(
                    "进入编辑副本去圈选（存档：" + (archive.isEmpty() ? "未关联" : archive) + "）", detailButtonWidth())));
            detailEnterEditButton.active = !archive.isEmpty();
            detailEnterGameButton.setMessage(Component.literal(font.plainSubstrByWidth(
                    "进入游戏维度配物资（mapId：" + map.mapId + "）", detailButtonWidth())));
            detailLinkArchiveButton.setMessage(Component.literal(archive.isEmpty() ? "点击关联存档" : "更换关联存档"));
        }
        // 记录「逻辑可见性」快照：滚动裁剪只在这个基础上叠加，滚回来时控件会恢复显示。
        for (Map.Entry<AbstractWidget, Integer> entry : originalYMap.entrySet()) {
            logicalVisibleMap.put(entry.getKey(), entry.getKey().visible);
        }
    }

    private int detailButtonWidth() {
        return Math.max(60, width - (int) (width * .58f) - 28);
    }

    /** 展开 / 收起详情面板里的存档选择框，展开时顺便让服务端重新扫一遍 import 目录。 */
    private void toggleArchivePicker() {
        archivePickerOpen = !archivePickerOpen;
        if (archivePickerOpen) {
            MapEditorSync.requestArchives();
            notice = "选择一个存档即可完成关联并自动保存";
        }
        init();
    }

    /** 选中某个存档：让服务端把它的绝对路径写进这张地图的 sourceArchivePath 并自动保存。 */
    private void pickArchive(String archiveName) {
        if (map.mapId == null || map.mapId.isBlank()) {
            notice = "这张地图还没有 ID，无法关联存档";
            return;
        }
        archivePickerOpen = false;
        detailDirty = false;
        // 绝对路径由服务端解析并写回 JSON；列表包推回来后面板自动显示真实的「源存档：xxx」。
        notice = "已提交关联：存档 " + archiveName + "，正在写回地图配置…";
        MapEditorSync.linkArchive(map.mapId, archiveName);
        init();
    }

    /** 完整路径太长时截断，保证详情面板一行放得下。 */
    private String abbreviatePath(String path) {
        if (path == null || path.isBlank()) return "";
        return font.plainSubstrByWidth(path.trim(), Math.max(60, rightPanelWidth - 70));
    }

    /** 从地图记录的源存档绝对路径里取目录名（即 /paimengmods manager enter 需要的 <存档名>）。 */
    private static String archiveNameOf(GameMap g) {
        String p = g == null ? null : g.sourceArchivePath;
        if (p == null || p.isBlank()) return "";
        String s = p.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int idx = s.lastIndexOf('/');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    /** 点击地图列表某一行：打开详情面板，不发送任何传送指令。 */
    private void openDetail(int i) {
        detailIndex = i;
        detailMode = true;
        archivePickerOpen = false;
        zonePanelOpen = false;
        detailDirty = false;
        map = savedMaps.get(i).copy();
        loadedMapId = map.mapId == null ? "" : map.mapId;
        notice = "";
        rightPanelScrollOffset = 0;
        init();
    }

    /** 详情面板「返回列表」：同时让服务端把编辑副本草稿推回来，右侧面板回到草稿状态。 */
    private void exitDetail() {
        detailMode = false;
        detailIndex = -1;
        archivePickerOpen = false;
        zonePanelOpen = false;
        detailDirty = false;
        notice = "";
        MapEditorSync.requestCurrentState();
        init();
    }

    /** 按钮 A：执行 /paimengmods manager enter <存档名>，进编辑副本重新圈选。 */
    private void detailEnterEdit() {
        if (minecraft == null || minecraft.player == null) return;
        String archive = archiveNameOf(map);
        if (archive.isEmpty()) { notice = "这张地图没有关联源存档，无法进入编辑副本"; return; }
        if (archive.matches("[A-Za-z0-9_]+")) {
            minecraft.getConnection().sendUnsignedCommand("paimengmods manager enter " + archive);
        } else {
            // 存档名带命令解析不接受的字符（比如中文）时，走同名网络入口，服务端行为完全一致。
            MapEditorSync.enterArchive(archive);
        }
        minecraft.setScreen(null);
    }

    /** 按钮 B：执行 /paimengmods manager game enter <mapId>，进游戏维度配物资。 */
    private void detailEnterGame() {
        if (minecraft == null || minecraft.player == null) return;
        String mapId = map.mapId;
        if (mapId == null || !mapId.matches("[A-Za-z0-9_-]+")) { notice = "地图ID 不合法，无法进入游戏维度"; return; }
        minecraft.getConnection().sendUnsignedCommand("paimengmods manager game enter " + mapId);
        minecraft.setScreen(null);
    }

    private void updateLoot(int i) {
        if (map.lootPoints == null || i < 0 || i >= map.lootPoints.size()) return;
        String newName = lootNames.get(i).getValue().trim();
        int newFont = Mth.clamp(number(lootFonts.get(i), 12), 6, 32);

        LootPoint p = map.lootPoints.get(i);
        if (detailMode) {
            // 详情面板：只在本地这份副本上改（换新对象，不动 savedMaps 里共享的 LootPoint），
            // 点「保存配置」时整包写回地图 JSON，不走编辑副本草稿的 RENAME_LOOT 通道。
            map.lootPoints.set(i, new LootPoint(p.x(), p.y(), p.z(),
                    newName.isEmpty() ? p.name() : newName, newFont));
            setFieldText(lootFonts.get(i), String.valueOf(newFont));
            detailDirty = true;
            notice = "物资点已修改，点「保存配置」写回地图";
            return;
        }

        p.setName(newName);
        p.setFontSize(newFont);
        setFieldText(lootFonts.get(i), String.valueOf(newFont));

        MapEditorSync.renameLoot(i, newName, newFont);
        notice = "物资点已更新（字号 " + newFont + "）";
    }

    /** 详情面板里删除物资点：只改本地副本，保存配置才写回 JSON。 */
    private void deleteLootLocal(int i) {
        if (map.lootPoints == null || i < 0 || i >= map.lootPoints.size()) return;
        map.lootPoints.remove(i);
        detailDirty = true;
        notice = "已删除一个物资点，点「保存配置」写回地图";
        init();
    }

    private int number(EditBox f, int fallback) {
        try { return Integer.parseInt(f.getValue().trim()); } catch (Exception e) { return fallback; }
    }

    /** 阶段列表转输入框文本；null / 空列表按默认 2,4 展示。 */
    private static String stagesText(List<Integer> stages) {
        if (stages == null || stages.isEmpty()) return "2,4";
        List<String> values = new ArrayList<>();
        for (Integer stage : stages) if (stage != null) values.add(String.valueOf(stage));
        return String.join(",", values);
    }

    /** 解析逗号分隔的毒圈阶段，忽略非法项并按 1~12 阶段上限去重排序。 */
    private List<Integer> parseAirdropStages(EditBox field, List<Integer> fallback) {
        List<Integer> result = new ArrayList<>();
        for (String raw : field.getValue().split("[,，;；/\\s]+")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            try {
                int stage = Integer.parseInt(token);
                if (stage >= 1 && stage <= ZonePhaseConfig.MAX_PHASES && !result.contains(stage)) {
                    result.add(stage);
                }
            } catch (NumberFormatException ignored) {
                // 单个非法阶段直接跳过，不让整个配置保存失败。
            }
        }
        if (result.isEmpty()) {
            return fallback == null ? new ArrayList<>(List.of(2, 4)) : new ArrayList<>(fallback);
        }
        result.sort(Integer::compareTo);
        return result;
    }

    private void save() {
        String mapId = id.getValue().trim();
        if (!mapId.matches("[A-Za-z0-9_]+")) { notice = "地图ID只能使用字母、数字、下划线"; return; }
        if (detailMode) {
            // 详情面板保存：这张地图本来就存在于列表，ID 保持原样，属于合法更新。
            if (!mapId.equals(loadedMapId)) { notice = "地图ID " + mapId + " 已存在，请更换其他ID！"; return; }
            map.mapId = mapId; map.displayName = name.getValue().trim();
            // [BR-FLIGHT-SPEED] 读取飞机速度：非法/空输入回退地图现值，合法值夹进 0.5~20。
            // 随同毒圈、物资点、选区一起走 sendSave 整包发给服务端落盘。
            map.flightSpeed = Mth.clamp(decimal(flightSpeedField, map.flightSpeed),
                    GameMap.MIN_FLIGHT_SPEED, GameMap.MAX_FLIGHT_SPEED);
            // [BR-MATCHTEAM 模块一] 读取队伍上限：空/非法回退现值，合法值夹进各自区间。
            map.maxTeams = Mth.clamp(number(maxTeamsField, map.maxTeams),
                    GameMap.MIN_MAX_TEAMS, GameMap.MAX_MAX_TEAMS);
            map.maxTeamSize = Mth.clamp(number(maxTeamSizeField, map.maxTeamSize),
                    GameMap.MIN_MAX_TEAM_SIZE, GameMap.MAX_MAX_TEAM_SIZE);
            map.normalizeMatchLimits();
            // [BR-LOOT] 物资刷新配置读取：非法数字回退现值；阶段非法文本不会覆盖当前配置。
            map.lootPool = lootPoolField.getValue().trim();
            map.airdropLootPool = airdropLootPoolField.getValue().trim();
            map.maxLootBoxes = Mth.clamp(number(maxLootBoxesField, map.maxLootBoxes),
                    GameMap.MIN_MAX_LOOT_BOXES, GameMap.MAX_MAX_LOOT_BOXES);
            map.maxAirdrops = Mth.clamp(number(maxAirdropsField, map.maxAirdrops),
                    GameMap.MIN_MAX_AIRDROPS, GameMap.MAX_MAX_AIRDROPS);
            map.airdropStages = parseAirdropStages(airdropStagesField, map.airdropStages);
            map.normalizeLootConfiguration();
            // 服务端只把「显示名 / 源存档 / 飞机速度 / 物资点名字字号」合并到真实内存对象上再落盘，
            // 选区、出生点、人数上限等仍以服务端原值为准，不会被这份副本覆盖。
            MapEditorSync.sendSave(map, true);
            detailDirty = false; // 保存后允许服务端回推的列表刷新面板
            notice = "正在保存地图 " + mapId + " 的配置";
            return;
        }
        // 防重名第一道闸（客户端前置检查）：新建地图时输入的 ID 已经在已保存列表里、
        // 而且不是打开面板时装载的那张同 ID 地图 → 直接拦截，不发保存包、不覆盖 JSON。
        if (isDuplicateNewMapId(mapId)) { notice = "地图ID " + mapId + " 已存在，请更换其他ID！"; return; }
        if (map.minX == 0 && map.minZ == 0 && map.maxX == 0 && map.maxZ == 0) { notice = "请先使用当前选区"; return; }
        map.mapId = mapId; map.displayName = name.getValue().trim();
        map.normalizeFlightSpeed(); // [BR-FLIGHT-SPEED] 草稿路径没这个输入框，值从服务端原样往返，兜一道闸
        map.normalizeMatchLimits(); // [BR-MATCHTEAM] 同上：草稿路径的队伍上限也过闸
        MapEditorSync.sendSave(map, false); notice = "正在保存地图"; managementTab = true;
    }

    /** 输入的 ID 是否与另一张已保存地图撞名（打开面板时装载的那张不算撞名，那是编辑它本身）。 */
    private boolean isDuplicateNewMapId(String mapId) {
        if (mapId.equals(loadedMapId)) return false;
        for (GameMap g : savedMaps) {
            if (mapId.equals(g.mapId)) return true;
        }
        return false;
    }

    /**
     * 文本框防崩溃核心闸（r113，逐条按 1.18.2 字节码核实过的修法）：
     * 渲染任何 EditBox <b>之前</b>强制体检。原版 renderButton 里有一句
     * 无保护的 {@code text.substring(firstCharacterIndex)}：光标 / 滚动下标一旦
     * 超过实际文本长度，立刻 StringIndexOutOfBoundsException 崩游戏。
     *
     * <ul>
     *   <li>{@code getCursor()}（字节码里就是 selectionStart）&gt; 文本长度 → 需求规定的
     *       {@code setCursorToEnd()}：光标与选区起点归位到合法末尾；</li>
     *   <li>再补一发 {@code setSelectionEnd(len)}——字节码显示 selecting=true（按住鼠标
     *       框选中）时 setCursorToEnd 会跳过选区终点的修正，而 setSelectionEnd 内部会把
     *       越界的 firstCharacterIndex（滚动偏移，崩溃那行 substring 的入参）当场夹回长度；</li>
     *   <li>兜底：修完仍越界（内部下标整体坏掉）→ {@link #setFieldText} 整组重建。</li>
     * </ul>
     *
     * <p>只在体检<b>不合格</b>时才动手：正常帧一个调用都不发，不会打扰用户打字和选区。
     */
    private static void guardTextField(EditBox f) {
        if (f == null) {
            return;
        }
        int len = f.getValue().length();
        if (f.getCursorPosition() > len) {
            f.moveCursorToEnd();
            f.setHighlightPos(len);
            if (f.getCursorPosition() > len) {
                setFieldText(f, f.getValue());
            }
        }
    }

    /** 对本面板全部文本框做渲染前体检（越界的当场修好）。 */
    private void guardAllTextFields() {
        guardTextField(id);
        guardTextField(name);
        guardTextField(flightSpeedField); // [BR-FLIGHT-SPEED]
        guardTextField(maxTeamsField);    // [BR-MATCHTEAM]
        guardTextField(maxTeamSizeField); // [BR-MATCHTEAM]
        guardTextField(lootPoolField);
        guardTextField(airdropLootPoolField);
        guardTextField(maxLootBoxesField);
        guardTextField(maxAirdropsField);
        guardTextField(airdropStagesField);
        for (EditBox f : lootNames) guardTextField(f);
        for (EditBox f : lootFonts) guardTextField(f);
        for (EditBox f : zoneWaits) guardTextField(f);
        for (EditBox f : zoneShrinks) guardTextField(f);
        for (EditBox f : zoneRadii) guardTextField(f);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        PoseStack matrices = graphics.pose();
        // 必须先于 super.render()（那才是真正画 EditBox 的地方）。
        guardAllTextFields();
        renderBackground(graphics);
        int split = (int) (width * .58f);
        GuiDraw.fill(matrices, 0, 0, split, height, 0xFF151515);
        GuiDraw.fill(matrices, split, 0, width, height, 0xFF202020);
        GuiDraw.drawTextWithShadow(matrices, font, "地图添加", 12, 10, managementTab ? 0xFFAAAAAA : 0xFFFFFFFF);
        GuiDraw.drawTextWithShadow(matrices, font, "地图管理", split / 2, 10, managementTab ? 0xFFFFFFFF : 0xFFAAAAAA);
        if (managementTab) drawManagement(matrices, split); else drawEditor(matrices, split);
        if (!notice.isBlank()) GuiDraw.drawTextWithShadow(matrices, font,
                font.plainSubstrByWidth(notice, Math.max(60, width - split - 16)), split + 8, height - 14, 0xFFFFFF55);
        super.render(graphics, mouseX, mouseY, delta);
        if (confirmDelete) drawConfirm(matrices);
    }

    private void drawEditor(PoseStack matrices, int split) {
        GuiDraw.drawTextWithShadow(matrices, font, "可导入存档", 10, 38, 0xFFFFFFFF);
        if (archives.isEmpty()) GuiDraw.drawTextWithShadow(matrices, font, "import 文件夹里没有存档", 10, 58, 0xFFAAAAAA);
        for (int i = 0; i < archives.size(); i++) {
            ArchiveEntry entry = archives.get(i);
            int rowY = 62 + i * 50 - mapListScroll;
            if (rowY + 50 < 62 || rowY > height) continue;
            String label = font.plainSubstrByWidth(entry.name() + "  " + entry.displayStatus(), 196);
            GuiDraw.drawTextWithShadow(matrices, font, label, 8, rowY + 6, 0xFFAAFFAA);
        }
        int x = rightPanelX;
        matrices.pushPose();
        matrices.translate(0, -rightPanelScrollOffset, 0);
        enableRightScissor();
        String[] labels = {
            "地图ID（字母数字下划线）",
            "显示名称",
            "当前选区：" + map.minX + "~" + map.maxX + ", " + map.minZ + "~" + map.maxZ,
            "物资点（名称 / 坐标 / 字号）",
            "在副本里手持 loot_tool 左键添加物资点"
        };
        for (int i = 0; i < Math.min(labels.length, labelYs.size()); i++) {
            GuiDraw.drawTextWithShadow(matrices, font, labels[i], x, labelYs.get(i), i == labels.length - 1 ? 0xFFAAAAAA : 0xFFFFFFFF);
        }
        RenderSystem.disableScissor();
        matrices.popPose();
    }

    private void enableRightScissor() {
        if (minecraft == null) return;
        double scale = minecraft.getWindow().getGuiScale();
        int sx = (int) (rightPanelX * scale);
        int sy = (int) ((height - rightPanelBottom) * scale);
        int sw = (int) (rightPanelWidth * scale);
        int sh = (int) ((rightPanelBottom - rightPanelTop) * scale);
        RenderSystem.enableScissor(sx, sy, sw, sh);
    }

    private void drawManagement(PoseStack matrices, int split) {
        GuiDraw.drawTextWithShadow(matrices, font, "已保存的地图（" + savedMaps.size() + "）", 10, 38, 0xFFFFFFFF);
        GuiDraw.drawTextWithShadow(matrices, font, detailMode ? "已打开详情：右侧修改后点「保存配置」" : "点击一行查看并配置该地图（不会直接传送）",
                10, 50, 0xFFAAAAAA);
        for (int i = 0; i < savedMaps.size(); i++) {
            GameMap g = savedMaps.get(i); int y = 62 + i * 46 - mapListScroll;
            if (y < 28 || y > height - 20) continue;
            boolean selected = detailMode && i == detailIndex;
            GuiDraw.fill(matrices, 8, y, split - 8, y + 40, selected ? 0xFF3C6E46 : 0xFF303030);
            GuiDraw.drawTextWithShadow(matrices, font, font.plainSubstrByWidth(
                    g.displayName == null || g.displayName.isBlank() ? g.mapId : g.displayName, Math.max(80, split - 190)),
                    14, y + 7, 0xFFFFFFFF);
            String archiveLabel = archiveNameOf(g);
            GuiDraw.drawTextWithShadow(matrices, font, font.plainSubstrByWidth("mapId：" + g.mapId
                    + "  源存档：" + (archiveLabel.isEmpty() ? "未关联" : archiveLabel)
                    + "  物资点：" + (g.lootPoints == null ? 0 : g.lootPoints.size()) + " 个", Math.max(80, split - 190)),
                    14, y + 22, 0xFFAAAAAA);
        }
        if (detailMode && !detailLabelTexts.isEmpty()) {
            matrices.pushPose();
            matrices.translate(0, -rightPanelScrollOffset, 0);
            enableRightScissor();
            for (int i = 0; i < detailLabelTexts.size(); i++) {
                int labelX = i < detailLabelXs.size() ? detailLabelXs.get(i) : rightPanelX;
                int avail = Math.max(40, rightPanelWidth - (labelX - rightPanelX) - 6);
                GuiDraw.drawTextWithShadow(matrices, font, font.plainSubstrByWidth(detailLabelTexts.get(i), avail),
                        labelX, detailLabelYs.get(i), detailLabelColors.get(i));
            }
            RenderSystem.disableScissor();
            matrices.popPose();
        }
    }

    private void drawConfirm(PoseStack matrices) {
        int x = width / 2 - 130, y = height / 2 - 30;
        GuiDraw.fill(matrices, x, y, x + 260, y + 60, 0xFF111111);
        GuiDraw.drawTextWithShadow(matrices, font, "确定删除地图 " + deleteMapId + "？", x + 10, y + 10, 0xFFFFFFFF);
        GuiDraw.drawTextWithShadow(matrices, font, "点击左侧确认，右侧取消", x + 10, y + 28, 0xFFFF5555);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (x >= rightPanelX && x < width && y >= rightPanelTop && y <= rightPanelBottom) {
            rightPanelScrollOffset = Mth.clamp(rightPanelScrollOffset - amount * 20.0, 0.0, rightPanelMaxScroll);
            for (Map.Entry<AbstractWidget, Integer> entry : originalYMap.entrySet()) entry.getKey().setY((int) Math.round(entry.getValue() - rightPanelScrollOffset));
            // 滚多少像素 = 控件 Y 坐标就减多少（上面一行），再按新的滚动偏移重新裁剪可见性。
            applyRightPanelClip();
            return true;
        }
        if (managementTab || (x < width * .58f)) {
            mapListScroll = Mth.clamp(mapListScroll - (int) (amount * 24), 0, Math.max(0, savedMaps.size() * 46 - height + 80));
            layout(); return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        int split = (int) (width * .58f);
        if (confirmDelete) { if (y > height / 2 && x < width / 2) { MapListSync.requestDelete(deleteMapId); confirmDelete = false; } else if (y > height / 2) confirmDelete = false; return true; }
        if (y < 28) {
            if (x < split / 2) {
                boolean wasDetail = detailMode;
                managementTab = false;
                if (wasDetail) {
                    // 退出详情面板回到「地图添加」：让服务端把编辑副本草稿推回来重新装载。
                    detailMode = false; detailIndex = -1;
                    MapEditorSync.requestCurrentState();
                }
                layout();
            } else if (x < split) { managementTab = true; layout(); }
            return true;
        }
        if (managementTab && button == 0 && x < split - 168) {
            int relative = (int) (y - 62 + mapListScroll);
            int i = relative >= 0 ? relative / 46 : -1;
            if (i >= 0 && i < savedMaps.size()) {
                int rowTop = 62 + i * 46 - mapListScroll;
                if (rowTop + 40 >= 62 && rowTop < height) openDetail(i); // 只打开详情面板，不传送
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
}
