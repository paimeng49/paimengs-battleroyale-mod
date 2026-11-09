# 大逃杀 (battleroyale)

Minecraft **1.18.2 / Fabric** 模组：游戏状态机 + 毒圈收缩 + 毒圈能量墙渲染。
显示名：大逃杀 ｜ 模组 ID：`battleroyale` ｜ 包名：`com.paimeng.battleroyale` ｜ Java 17

已完成：状态机、自定义事件总线、`/paimengmods br` 指令、tick 驱动、**毒圈多阶段收缩**、
**服务端→客户端状态同步**、**客户端蓝色能量墙**、**极简队伍系统 + 队友编号 HUD**、
**快捷消息（`/paimengmods qc`，仅预设）**、**V 键标点系统（单击标地点 / 双击标敌人）**。
撤离点、跳伞、GUI 后续再接——它们都通过订阅事件接入，不用改状态机或毒圈。

## 项目结构

```
build.gradle                        构建脚本（loom 1.10.5，勿改依赖版本）
gradle.properties                   版本坐标（1.18.2 / yarn build 4 / loader 0.16.14 / fabric-api 0.77.0）
settings.gradle
src/main/resources/
  fabric.mod.json                   模组元数据（id=battleroyale，入口=BattleRoyaleMod）
src/main/java/com/paimeng/battleroyale/
  BattleRoyaleMod.java              入口：注册事件订阅、指令、ServerTickEvents；毒圈播报订阅者
  event/
    EventBus.java                   自定义事件总线（纯 Java，跨版本可原样搬走）
    GameStateChangedEvent.java      状态切换事件（record：from/to/globalTicks/server）
    ZoneStageChangedEvent.java      毒圈阶段变化事件（record：stageIndex/phase/stage/圆心/半径/server）
  game/
    GamePhase.java                  WAITING / PREPARING / RUNNING / ENDED
    GameManager.java                状态机核心，状态切换唯一出口 + 每 tick 检测
    GameRules.java                  可调参数（倒计时、最少人数、最长对局等）
  zone/                             ―― 毒圈模块 ――
    ZonePhase.java                  毒圈小阶段 IDLE / WAIT / SHRINK / DONE
    ZoneStage.java                  单阶段配置 record（等待、收缩时长、目标半径、圈外 dps）
    ZoneStages.java                 静态默认阶段表 + 初始半径 + 伤害间隔（以后可换成读配置）
    ZoneManager.java                毒圈核心：圆心+半径、阶段推进、线性收缩、圈外伤害结算
  command/
    CommandRoots.java               全模组指令命名空间唯一来源（/paimengmods + br）
    BrCommand.java                  /paimengmods br join|leave|start|stop|team ...
  config/                           ―― 本轮新增：服务端/客户端共用配置 ――
    QuickChatStore.java             quickchat.json 预设列表读写（客户端快捷栏 + 服务端校验）
  team/                             ―― 队伍 + 标点服务端 ――
    Team.java                       队伍对象（队伍ID/队内编号 1-99/成员名单）
    TeamManager.java                建队/邀请/接受/退队/断线清理 + 数据变化立即同步
    TeamMessageSender.java          队伍消息发送公共出口（标点、快捷消息共用；经验拾取音效）
    PingType.java                   ―― 本轮新增：标点类型枚举（WAYPOINT/ENEMY + 固定文案）――
    PingService.java                ―― 本轮重写：标点服务端（冷却1s/距离≤128格/类型校验，固定消息广播队友）――
  network/
    ZoneSync.java                   毒圈快照同步包（battleroyale:zone_sync，服务端→客户端）
    TeamSync.java / TeamSyncPayload.java  队伍快照同步包（battleroyale:team_sync）
    TeamChat.java                   ―― 本轮重写：快捷消息 C2S（仅预设，只走队伍频道，无 all 广播）――
    PingSync.java                   ―― 本轮重写：标点 C2S（坐标+类型，无文本）+ S2C（坐标+类型+发起人UUID）――
  client/                           ―― 全部只在客户端加载 ――
    BattleRoyaleModClient.java      客户端入口：收包、挂渲染、清状态、初始化快捷消息/标点
    ClientZoneState.java            本地毒圈状态（圆心/半径/阶段），渲染只读它
    ClientTeamState.java            本地队伍快照（HUD 只读它）
    TeamHudRenderer.java            左上角队友列表（编号按队内编号固定标志色）
    ZoneWallRenderer.java           蓝色能量墙（WorldRenderEvents.AFTER_TRANSLUCENT）
    QuickChatConfig.java            客户端配置入口（委托 config.QuickChatStore）
    QuickChatClient.java            /paimengmods qc 客户端指令（仅预设列表内消息可发送）
    PingClient.java                 ―― 本轮重写：V 键单击(300ms 判定)/双击，射线 128 格，无指令 ――
    ClientPingState.java            ―― 本轮重写：活动标点生命周期（ENEMY 10s / 自发 WAYPOINT 永久 / 队友 WAYPOINT 10s）――
    MarkManager.java                ―― 本轮新增：小地图标记接口预留（addMapMark/removeMapMark，暂不渲染）――
    PingRenderer.java               世界里的发光柱子标记（颜色=发起者队内编号，永久标记不渐隐）
  util/
    BrText.java                     文本/广播统一出口（版本隔离层，of()/mutable() 构造）
```

## 状态流转

```
WAITING --/paimengmods br start(≥2人)--> PREPARING --10s倒计时--> RUNNING --名单清空/超时20min br stop--> ENDED --15s展示--> WAITING
                                              │
                                              └--进入时 ZoneManager.start()，离开时 stop()
```

```
毒圈（仅 RUNNING 期间推进，由 ServerTickEvents.END_SERVER_TICK 驱动）：
WAIT(等 waitTicks) → SHRINK(shrinkTicks 内线性收到目标半径) → 下一阶段 WAIT → ... → DONE(保持最终半径)
每 20 tick 结算一次圈外伤害（伤害量 = 当前阶段每秒伤害）；每次阶段推进 publish ZoneStageChangedEvent。
```

- 所有切换都会 `EventBus.publish(...)`，入口类里的默认订阅者负责日志 + 全服广播；
  以后 GUI 画圈、倒计时 HUD 用 `EventBus.INSTANCE.subscribe(ZoneStageChangedEvent.class, ...)` 挂进来。
- 圈外判定只看 X/Z 平面距离（`Math.hypot`），与玩家所在维度/Y 层无关；旁观/创造模式不参与掉血。

## 默认毒圈节奏（写死在 ZoneStages.DEFAULT）

| 阶段 | 等待 | 收缩用时 | 收到半径 | 圈外每秒伤害 |
| --- | --- | --- | --- | --- |
| 第一阶段 | 20s | 15s | 64 | 1 |
| 第二阶段 | 15s | 15s | 40 | 2 |
| 第三阶段 | 15s | 10s | 24 | 4 |
| 第四阶段 | 10s | 10s | 12 | 6 |
| 第五阶段 | 10s | 10s | 5 | 8 |
| 终圈 | 10s | 10s | 0 | 12 |

初始半径 96 米，全程约 4 分钟；改节奏只需改这张表。

## 指令

所有指令统一挂在根指令 `/paimengmods` 下，避免和别的模组撞名（撞名时后注册的会被前者吃掉）。
字面量集中在 `command/CommandRoots.java`，改根名只需要改那一个文件。

| 指令 | 谁能用 | 作用 |
| --- | --- | --- |
| `/paimengmods br join` | 玩家 | 加入大厅名单（RUNNING/ENDED 阶段禁止） |
| `/paimengmods br leave` | 玩家 | 退出名单 |
| `/paimengmods br start` | OP(等级2) | WAITING → PREPARING，10 秒后自动 RUNNING 并启动毒圈 |
| `/paimengmods br stop` | OP(等级2) | 强制进入 ENDED（毒圈随之关闭），15 秒后自动回 WAITING |
| `/paimengmods br team create` | 玩家 | 建队，自己当队长（队员 1 号） |
| `/paimengmods br team invite <玩家名>` | 玩家 | 邀请在线玩家（玩家名 Tab 可补全） |
| `/paimengmods br team accept` | 玩家 | 接受邀请 |
| `/paimengmods br team leave` | 玩家 | 退队（队长退则编号最小的接任，最后一人退即解散） |
| `/paimengmods br team list` | 玩家 | 列出 "编号 名字"，队长行尾标（队长） |
| `/paimengmods qc` | 玩家(客户端) | 在聊天框列出当前快捷消息，每条可点击填入 |
| `/paimengmods qc <消息>` | 玩家(客户端) | 把 `<消息>` 发到**队伍频道**（带 `[队伍 N][编号]` 前缀、正文绿色高亮 + 经验拾取音效）；**只允许预设列表内的消息**，否则拒绝并提示；没有队伍时只显示给自己并提示 |
| `/paimengmods qc reload` | 玩家(客户端) | 改完 quickchat.json 后热加载，不用重启游戏 |

旧的 `/br`、`/qc`、`/quickchat` 根指令已全部去掉，不再有别名。
`/paimengmods ping` 与 `/paimengmods qc all` 已按需求**删除**：标点只通过 V 键触发，
快捷消息不再提供全服广播。连的是没装本模组的服务器时，`qc` 会直接提示"服务器不支持"，
不再退回原版聊天（避免绕过"仅预设 + 队伍频道"限制）。

## 标点系统（V 键）

标点 = "把我想让队友看的位置标出来"。**只有 V 一个键，按快慢区分两种标点**：

| 操作 | 类型 | 队伍消息（写死，不可自定义） | 世界渲染 | 小地图标记 |
| --- | --- | --- | --- | --- |
| 单击 `V`（按下后 300ms 内没有第二下） | WAYPOINT | `玩家名：我标记了一处地点`（绿色高亮 + 经验拾取音效） | 自己发的：**永久**；队友发的：10 秒后消失 | **永久保留** |
| 双击 `V`（两次按下间隔 <300ms） | ENEMY | `玩家名：发现敌人`（绿色高亮 + 经验拾取音效） | 10 秒后消失 | 10 秒后**彻底删除** |

- 标记位置：客户端准星射线，最大 **128 格**；没指到方块时取射线终点。
  服务端校验：同一玩家 1 秒冷却、水平距离 ≤128 格（+1 格余量）、坐标/类型脏数据直接丢弃。
- 世界里的发光柱子颜色 = 发起者**队内编号的固定标志色**（1 黄、2 橙、3 蓝、4 绿）；
  生命周期由客户端按"类型 + 是否自发"决定（S2C 包带发起者 UUID）。
- 没队伍的玩家也能按 V：消息与标记只回自己，并提示"仅自己可见"。
- **小地图接口已预留**（本轮不渲染小地图）：
  `MarkManager.addMapMark(x, y, z, type, color)` 返回 markId，`MarkManager.removeMapMark(markId)` 删除；
  WAYPOINT 在接口数据里永久、ENEMY 10 秒自动清除。以后接小地图只读 `MarkManager.activeMarks()`。

## 快捷消息（仅预设）

1. 按 `T` 打开聊天框，输入 `/paimengmods qc` 再敲一个空格 → 弹出预设消息列表；
2. 继续打几个字（如 `/paimengmods qc 发`）会实时过滤（前缀、包含都算）；
3. `↑` `↓` 方向键选中某条，按 `Tab` 确认 → 文本填入输入框；
4. 再按 `回车` → 走模组通道 `battleroyale:quick_chat`，**只发队伍频道**；
5. 输入的消息**不在预设列表里会被拒绝**，提示"只能发送预设的快捷消息"
   （客户端先拦一次，服务端再按 `config/battleroyale/quickchat.json` 校验一次，防改包）。

不打 `/paimengmods qc` 时正常打字，就是普通聊天，模组完全不拦。

> 说明：1.18.2 原版的聊天建议框只对以 `/` 开头的文本生效（这是原版行为，
> 不动 Mixin 绕不过去），所以快捷列表挂在 `/paimengmods qc` 指令参数上，列表、方向键、
> Tab 补全全部复用原版 UI。

**配置文件** `config/battleroyale/quickchat.json`（首次启动自动生成；专用服务器读服务器自己那份）：

```json
{
  "messages": ["向安全区移动", "发现敌人", "需要支援", "这里有物资", "撤退"]
}
```

改完文件游戏里 `/paimengmods qc reload` 即生效（客户端）。空行/重复/以 `/` 开头的条目会被自动过滤，
上限 32 条；解析失败时保留当前列表并写日志，不会崩。

## 升级到高版本时改哪里（预留说明）

1. `BattleRoyaleMod.java`：`CommandRegistrationCallback` 在 1.19+ 变为
   `command.v2`，回调多一个 `registryAccess` 参数（代码里已标注 `// [UPGRADE]`）。
2. `BrText.java`：1.19.3+ 用 `Text.literal(s)` 替换 `new LiteralText(s)`；
   1.20.5+ `sendMessage(Text, boolean)` 改名 `displayClientMessage`。
3. `BrCommand.java`：1.19.4+ 建议把 `source.getPlayer()` 换成 `getPlayerOrThrow()`。
4. `ZoneManager.java`：1.18.2 用常量 `DamageSource.MAGIC`；1.19+ 改
   `server.getDamageSources().magic()`，1.20.1+ 改 `DamageTypes.MAGIC` 构造（已标 `[UPGRADE]`）。
5. `QuickChatClient.java`：1.18.2 用 command-api **v1**（`ClientCommandManager.DISPATCHER` 直接注册）；
   1.19+ 改 v2 的 `ClientCommandRegistrationCallback.EVENT`，指令树和 suggest 代码原样不动。
   现在消息只走模组通道发送，不再调 `sendChatMessage`，升级时少一处签名适配。
6. `PingClient.java` / `TeamChat.java` / `PingSync.java`：C2S 发包在 1.20.5+ 改 CustomPayload
   （`ClientPlayNetworking.send(payload)` + streamCodec），校验与消息拼装逻辑不动；
   `KeyBinding` 注册在 1.20.x 内不变，1.21.2+ 换 Trigger 体系时改 `PingClient.register()` 一处。
7. 其余文件（事件总线、record 事件、枚举、状态机、毒圈阶段表与推进逻辑）与版本基本无关。
   注册方块/物品时注意：1.19.3+ 的 `Registry.register` 用法与 1.18.2 不同。

## 安装

把 `build/libs` 下最新的 `battleroyale-*.jar` 放进 `mods` 文件夹，
需要 Fabric Loader 0.16.14+ 与 Fabric API 0.77.0+1.18.2（客户端和服务端都要装才有完整效果；
服务端单机集成端自带）。
