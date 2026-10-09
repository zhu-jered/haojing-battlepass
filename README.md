# haojing-battlepass

镐京方块协会自研的 **Minecraft 1.21.11 Fabric 战令（Battle Pass）系统**。
多模块工程，服务端权威，客户端只做渲染，不引入任何属性/生存加成。

> 本项目为社团内部自用 Mod，当前版本 `0.1.0-SNAPSHOT`。

---

## 功能一览

- **赛季战令**：等级 / 经验曲线 / 京币（永久货币）/ 双分支（HUNT / BUILD，10 级解锁）/ 赛季滚动归档。
- **任务系统**：每日三组（探索 / 建造 / 通用）+ 每周挑战，支持刷新任务（每日限次）、任务卡（跳过任务）。
- **商店**：京币兑换、限购、商品分组配色、价格独立着色；商品类型含物品 / 指令 / 京币 / 经验 / 任务卡 / 称号。
- **称号系统**：纯装饰。
  - 战令 GUI 悬浮提示（名称 / 获取途径 / 描述 / "装饰类，不提供任何属性加成" 标注）。
  - 聊天前缀 `【称号】玩家名：消息`，称号组件带悬浮 Tooltip。
  - 玩家头顶 NameTag 前缀，颜色与包裹符号可配。
  - 玩家可在 GUI 独立开关「聊天称号显示」「头顶称号显示」。
- **彩蛋 / 随机事件 / 节日口令 / 全服里程碑**：长夜模式（00:00–06:00 经验倍率）配套。
- **管理面板**：OP 在游戏内可视化查看状态、改等级 / 京币 / 分支、热重载配置、导出/导入配置备份。
- **GUI 体验**：任务条目选中高亮、Tab 切换高亮、任务悬浮 Tooltip、首页右下角社团链接（点击唤起浏览器）。

所有配置文件支持 **热重载**，改完 1 秒内生效，无需重启服务端。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.1` |
| Fabric API | `0.140.2+1.21.11`（**硬依赖**） |
| JDK | `21` |
| Gradle | 工程自带 wrapper（不要用系统 Gradle） |

版本唯一事实来源：`gradle.properties`。

---

## 构建

```bat
# 在工程根目录（ASCII 路径下）
gradlew.bat build
```

产物：

```
server/build/libs/haojing-battlepass-server-0.1.0-SNAPSHOT.jar
client/build/libs/haojing-battlepass-client-0.1.0-SNAPSHOT.jar
```

只编译不打包：`gradlew.bat compileJava`；跑单测：`gradlew.bat test`。

> 说明：本工程在开发机上默认走 BMCLAPI 镜像（见 `settings.gradle` 与 `gradle.properties`）。
> 网络通畅的环境直接 `gradlew build` 即可，无需额外配置。

---

## 部署

### 服务端

1. 安装 Fabric Loader `0.18.1`（MC `1.21.11`）。
2. 把 **Fabric API** 放进 `mods/`。
3. 把 **`haojing-battlepass-server-*.jar`** 放进 `mods/`。
4. 首次启动会自动生成：

```
config/haojing_battlepass/   season.json / daily_tasks.json / rewards.json / shop.json
                             eggs.json / codes.json / events.json / milestones.json / titles.json
data/haojing_battlepass/     players/<uuid>.json   global/<uuid>.json   state.json
data/history/                season_<id>.json（赛季归档）
```

### 客户端

1. 玩家用 Fabric Loader 启动 `1.21.11`（同样需要 Fabric API）。
2. 把 **`haojing-battlepass-client-*.jar`** 放进客户端 `mods/`。
3. 打开战令界面：默认快捷键 **`B`**（可在 选项 → 控制 → 按键绑定 里改），或用命令 `/battlepass`。
4. OP 在游戏内执行 `/battlepass admin` 打开管理面板。

---

## 项目结构

```
haojing-battlepass/
├── common/    # 两端共享的协议 / DTO / 工具（源码并入 server 与 client）
├── server/    # 服务端：业务逻辑、配置、网络、Mixin、数据持久化
├── client/    # 客户端：GUI、网络接收、NameTag Mixin
├── docs/      # 需求、管理员手册、打包部署、验收用例
└── gradle.properties
```

- 协议层：`common/net/`（`ModPayloads` / `ModSnapshots` / `SyncChannels` / `NetActions`）。
- 配置层：`server/config/` + `server/<模块>/`（每个模块一个 Manager，自带热重载）。
- 网络层：`server/net/`（握手校验、C2S 频率限制、增量分片同步、S2C 广播）。

---

## 设计约束（开发时不要破坏）

1. **服务端权威**：所有判定（领奖、购买、称号装备、开关）都在服务端做二次校验，客户端动作只发请求。
2. **称号纯装饰**：任何称号不携带属性 / buff / 生存加成。
3. **京币仅游戏内产出**：不接入任何付费 / 充值 / 现实货币渠道。
4. **旧存档无缝兼容**：新增字段必须有默认值，老玩家升级后进度不清空。
5. **不崩溃优先**：颜色代码写错、配置损坏、包格式异常 → 回退默认 + WARN，绝不抛栈。
6. **客户端零业务计算**：新增 GUI 效果只在客户端执行，服务端不增加每 tick 压力。

---

## 协议与版本

- 自定义通道全部在 `common/net/ModNetworkingIds.java` 注册，两端共用同一份源码。
- 协议版本 `PROTOCOL_VERSION` 在 `common/ModConstants.java`；不兼容的客户端会在 configuration 阶段被踢。

---

## 许可证

All Rights Reserved © 镐京方块协会。见 `LICENSE`。
