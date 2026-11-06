# 派蒙的大逃杀模组 (Paimeng's Battle Royale)

> 一个基于 Minecraft **Fabric 1.20.1** 的大逃杀玩法基座，深度集成 **TACZ (Timeless & Classics Zero)** 枪械。
> **代码 100% 由 AI 生成**，人类负责架构设计、玩法策划与实机排雷。

---

## 💡 写在前面（关于代码质量）

本项目的开发过程是一次极限的“AI 辅助开发”实验。
项目中的 Java 逻辑、Mixin 注入、网络包同步以及部分像素贴图，均由 AI（AutoMods）生成。
**我们深知 AI 生成的代码缺乏优雅的设计模式，是一座名副其实的“屎山”。**
因此，本项目毫无保留地采用了 **[MIT License](LICENSE)**。如果你是一位代码洁癖大佬，欢迎随意 Fork 或提交 PR 重构，你的每一次提交都是对这个项目的救赎。

---

## ✨ 核心特性 (Features)

### 🎯 沉浸式队伍与状态管理
- **动态队伍分配**：支持 1~4 人动态容量，大厅队伍与局内队伍生命周期完全解耦。
- **防拔线与背包隔离**：进局自动保存并清空原背包，退局原样返还；局内拔线逃跑直接判定阵亡并生成骨灰盒。
- **游戏模式自动恢复**：进局记录玩家原游戏模式，回大厅完美还原（拒绝出局变生存模式的惨剧）。

### 🔫 深度绑定的战术竞技
- **智能物资解析**：支持 `||` 分组与 `|and|` 绑定，完美兼容 TACZ 的 NBT 标签与指定数量（如 `tacz:9mm 30`）。
- **双轨物资刷新**：手动物资点工具（支持批量框选）+ 网格配额自动扫描兜底，保证机场大厅不扎堆、荒野小房不落空。
- **C-130 经典空投**：沿白圈重算随机航线，发射带降落伞/推进器的空投箱，落点附带红蓝烟雾与信标光柱。

### ⛈️ 极度硬核的缩圈机制
- **自定义 TTK 毒圈模型**：按“满血淘汰所需时间”反推每秒伤害，底层使用浮点伤害累加器，完美绕过原版无敌帧。
- **圈外淘汰倒计时 HUD**：实时预测玩家在圈外被淘汰的剩余秒数。采用毫秒级时间流逝与增量补偿算法，**圈外吃金苹果提供的黄心（吸收伤害）也会真实增加倒计时**，不再出现掉血才跳秒的卡顿。
- **自定义淘汰播报**：千人千面，击杀/被击杀/路人看到的文本颜色与音效各不相同，支持距离与武器播报。

### 🖥️ 现代 FPS 交互体验
- **键盘快捷消息**：按 `T` 呼出聊天框，`↑↓` 切换，`Tab` 直接发送，告别打指令的原始时代。
- **战术小地图**：队伍编号头顶渲染，实时队友光标追踪，安全区/航线可视化。
- **异步地图重置**：对局结束后，异步删除区块文件重新加载，附带 BossBar 进度条，每一局都是崭新的地图。

---

## 📦 环境依赖 (Dependencies)

请务必确保你的客户端/服务端满足以下环境，否则可能会崩溃：
- **Minecraft**: 1.20.1
- **Fabric Loader**: 0.15.0 及以上
- **Fabric API**: 最新版
- **[TACZ - Refabricated](https://github.com/Sh1roCu/TACZ-Refabricated)**：非官方 Fabric 移植版（需额外前置 `Forge Config API Port`）
- *(可选)* Iris + Sodium：光影与优化（注意部分版本混入可能导致 Mixin 冲突崩溃）

---

## 🛠️ 指令列表 (Commands)

### 👤 玩家指令（所有玩家可用）
- `/paimengmods br team hh <自定义文本>`：在大厅发送组队招募喊话（支持点击加入）。
- `/paimengmods br team invite <玩家名>`：邀请玩家加入你的大厅队伍。
- `/paimengmods br team accept` / `deny`：接受或拒绝组队邀请。
- `/paimengmods br team leave`：退出当前大厅队伍。
- `/paimengmods br join`：**加入游戏**。（队长执行可带领全队加入，散人可直接加入）。
- *(注：游戏内提供快捷消息系统，无需使用指令)*

### 🛡️ 管理员指令（OP 专属）
- `/paimengmods br start`：强制立即开始游戏（无视人数门槛）。
- `/paimengmods br stop`：强制结束当前对局并重置地图。
- `/paimengmods br loot_tool`：获取手动物资点标记工具（右键添加、Shift+右键框选）。
- `/paimengmods br debug loot`：在脚下强制刷一个物资箱（用于排障）。

---

## 🗺️ 地图版权声明 (Map Credits)

本项目官方测试地图使用 **Los Perrito City**。
请在游玩或开服前前往原贴下载，并严格遵守原作者协议（CC BY 4.0）：

- **Map**: Los Perrito City by **Uospag**
- **Download**: [CurseForge Link](https://www.curseforge.com/minecraft/worlds/los-perrito)
- **License**: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

*(注：本项目仅提供地图适配配置文件与玩法逻辑，不含地图本体，请尊重原作者版权。)*

---

## 📥 下载与反馈 (Download & Feedback)

- **模组下载**：请前往右侧的 **[Releases](../../releases)** 页面下载最新的 `.jar` 文件。
- **问题反馈**：如果你在游玩中遇到 Bug，欢迎提交 [Issue](../../issues)，并附带 `latest.log` 文件。
- **作者主页**：[在此填入你的B站/主页链接]

---

## 📜 AIGC 声明 (AI Usage Disclosure)

- **代码生成**：AutoMods (AI 辅助开发平台)
- **人类工作**：制定架构边界、确定玩法规则、进行实机测试与 Bug 排查。
- **声明依据**：本项目遵循开源透明原则，采用 W3C AI Content Disclosure 词汇表中的 `ai-generated` 类别。

**玩得开心，大吉大利，今晚吃鸡！** 🍗
