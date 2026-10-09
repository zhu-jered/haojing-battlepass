[Heading 1] 镐京方块协会战令 Mod 开发提示词（最终稿 v2）
[Heading 2] 0. 你的角色与协作规则（最高优先级，先读这一节）
[First Paragraph] 你是资深 Minecraft Fabric 模组工程师。请严格按以下规则协作：
[Compact] 分阶段交付：每次只输出我当前指定的阶段内容，写完即停止，等我确认后再继续。禁止一次性输出全部代码（会导致截断与质量下降）。
[Compact] 先结构后代码：每个阶段先给文件树 / 类职责说明，再给代码。每个文件顶部用中文注释说明用途。
[Compact] 禁止臆造 API：不确定的类名、方法名、映射名，一律写 // TODO: 需核对 1.21.11 Yarn 映射 并在阶段末尾汇总到”待核对清单”。不允许编造看似合理的调用。
[Compact] 每阶段末尾输出「待确认问题清单」，列出你做了哪些假设、需要我拍板的点。
[Compact] 中文注释，代码内注释必须解释”为什么”而不只是”是什么”。
[Compact] 我未明确说明的细节，采用本文档给出的默认值，不要自行发明新机制。

[Heading 2] 1. 技术栈锁定（不得自行更换版本或加载器）
[Compact] Minecraft Java 版 1.21.11
[Compact] Fabric Loader 0.18.1
[Compact] Fabric API 0.140.2+1.21.11
[Compact] Fabric Loom 1.14，Yarn 映射，JDK 21
[Compact] 清单文件为 fabric.mod.json（服务端 environment: "server"，客户端 environment: "client"），不使用 mods.toml（那是 Forge/NeoForge 的文件）
[Compact] 若需 Mixin，单独提供 xxx.mixins.json，并只 Mixin 本文档第 5.6 节白名单内的类
[Compact] Mod ID：gaojing_battlepass；包名 com.gaojing.battlepass
[Compact] 语言文件：提供 zh_cn.json，界面文案走 TranslationKey，不在代码里硬编码中文

[Heading 2] 2. 全局硬性规则
[Compact] 时间：所有时间判定统一使用 ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))，与容器/主机时区无关，不依赖任何网络请求。可选提供 NTP 校准时钟漂移的配置项，失败仅打 WARN，绝不参与业务判定。
[Compact] 不写任何停机/重启代码，服务器定时重启由外部 Bash 脚本负责。
[Compact] 全部业务逻辑、任务校验、奖励计算只在服务端。客户端 Mod 只接收数据包 + 渲染 GUI；客户端提交的数据一律不作为判定依据。
[Compact] 管理员在 GUI 修改的所有配置支持热重载，无需重启，玩家数据不丢失。
[Compact] 客户端 Mod 强制校验：在 CONFIGURATION 阶段通过自定义 payload 握手，未握手的客户端拒绝进入 PLAY。需明确注释：这不是反作弊，仅拦截未安装 Mod 的普通玩家；会挡住原版客户端；需说明在 Velocity/BungeeCord 代理下的透传注意事项。同时提供 devMode 配置开关（默认 false），开启后跳过校验，便于本地测试。
[Compact] 社团内部自用，禁止商用。

[Heading 2] 3. 整体架构
[Compact] 服务端 Mod：事件监听、任务判定、JSON 持久化、权限校验、战令业务、长夜模式、彩蛋、随机事件、口令、里程碑、数据包下发、管理指令。
[Compact] 客户端 Mod：仅接收数据包 + 渲染中文 GUI，不改变生存平衡。
[Compact] 通信：Fabric 自定义 Packet（play 阶段双向 + configuration 阶段握手）。配置下发需支持增量同步，并对超大 payload 分片；非法/超限包直接丢弃并记日志。

[Heading 2] 4. 赛季基础机制
=== TABLE ===
项 | 默认值 / 规则
赛季时长 | 30 天，管理员可改，热重载
等级范围 | 1~30 级
分支选择 | 10 级时弹窗二选一：【狩猎分支】/【建造分支】；本赛季锁定，改选仅管理员指令可做
经验来源 | 每日任务、每周挑战、全局/赛季限定彩蛋、世界随机事件、里程碑
星币 | 每升 1 级 +10 星币；永久保留，不随赛季重置，因此存于玩家全局文件而非赛季文件
赛季结束 | 全服结算公告 → 归档 data/history/season_<id>.json → 重置等级/经验/任务/分支，保留星币与收藏册
装饰类奖励形态 | 限：称号、粒子特效、盔甲纹饰、自定义物品（NBT 标记防复制）；不依赖外部材质包，如需材质须单独说明
=== END TABLE ===

[Heading 2] 5. 任务系统（事件驱动，服务端全量判定）
[Heading 3] 5.1 每日任务
[First Paragraph] 从任务池随机抽取 3 个，分属探险组 / 建造组 / 综合组各 1 个。池规模默认每组 12 个；同日不重复、与昨日不重复。玩家可”重 roll 本组”，默认每日 1 次，管理员可调。
[Heading 3] 5.2 刷新时刻（重要：与长夜解耦）
[First Paragraph] 由独立配置项 dailyRefreshTime 控制，默认 北京时间 06:00。长夜起止时间可独立修改或整体关闭，不影响任务刷新。所有”日”边界（每日任务、每日经验上限、每日计数）统一以该时刻为界。
[Heading 3] 5.3 刷新动作
[First Paragraph] 持久化全部玩家数据 → 对在线+离线玩家统一刷新 → 写入全局标记 lastDailyRefreshDate（北京日期）→ 向在线玩家推送增量更新包。该标记用于幂等：跨多天停服后重启只补刷一次。
[Heading 3] 5.4 每周挑战
[First Paragraph] 默认每周一 06:00（北京时间）刷新，难度更高、经验更多，数量默认 3 个。
[Heading 3] 5.5 状态枚举
[First Paragraph] NOT_ACTIVE / IN_PROGRESS / COMPLETED（完成待领取）/ CLAIMED。进度与状态持久化。
[Heading 3] 5.6 事件监听
[First Paragraph] 放置/破坏方块、击杀实体、钓鱼成功、村民交易、群系切换、进入维度等。允许 Mixin 的白名单类仅限：ServerPlayerInteractionManager（破坏/放置）、Entity.kill/LivingEntity.damage 相关、FishingBobberEntity、MerchantMerchantOffersS2CPacket 或交易屏幕交互类、PlayerEntity.tick（群系/位置轮询）。其余一律用 Fabric 事件或低频轮询，禁止随意 Mixin 原版核心类。群系切换用每 20 tick 比对 biome key 实现。
[Heading 3] 5.7 过滤器系统
[First Paragraph] 管理员 GUI 可视化配置，条件维度含方块 ID、实体 ID、群系、维度、Y 高度区间、北京时间时段；支持 AND/OR 嵌套组合；热重载。
[Heading 3] 5.8 防刷
[First Paragraph] 只统计玩家本人行为；假人、傀儡、宠物、其他玩家造成的击杀/放置不计入；CLAIMED 后不再计数；同一 tick 内的批量操作做去重。
[Heading 3] 5.9 豁免卡
[First Paragraph] 来源为战令等级奖励与商店兑换；每人持有上限默认 3；使用后任务直接置为 CLAIMED（视为已领取，不重复发奖）。
[Heading 3] 5.10 每日经验上限
[First Paragraph] 按经验值计，默认 500。超限后任务进度仍正常累计，但不再发放经验（避免玩家白干活），GUI 首页显示”今日剩余可获取经验”。
[Heading 3] 5.11 长夜经验规则
[First Paragraph] 长夜时段内进度正常累计，经验 ×0.5（倍率可配，默认 0.5）；长夜专属彩蛋不受此规则影响（其本身不给经验）。

[Heading 2] 6. 隐藏彩蛋系统
[First Paragraph] 管理员 GUI 支持增删改与启用开关，热重载。不在玩家 GUI 展示未解锁彩蛋的描述。满足条件自动一次性触发，触发记录写服务端日志。
[Body Text] 分类：全局战令彩蛋（小额经验 + 称号 + 收藏）、长夜专属彩蛋（零经验，仅称号 + 收藏，避免诱导熬夜）、赛季限定彩蛋（赛季结束后不可再获取，记录永久留存历史册）。
[Body Text] 量化定义（必须按此实现，不得自行解释）：
=== TABLE ===
彩蛋 | 判定
长安拂晓 | 北京时间 08:00±5 分钟，玩家 Y≥90 且该坐标天空可达（户外）
月下筑者 | getMoonPhase()==FULL 且为夜晚，累计放置 100 方块，触发瞬间半径 16 格内无敌对生物
鱼信 | 雷雨天气 + 河流群系 + 钓鱼成功一次
大地勘探者 | 本赛季内分别进入深邃洞穴、繁茂洞穴、溶洞三种群系
社团同游 | 半径 32 格内 ≥3 名协会玩家，同群系连续停留 5 分钟（中途分离超 10 秒则重置）
樱落归镐 | 在樱花林群系种下树苗并成功生长
守夜人 | 连续 3 个长夜窗口，每段在线 ≥10 分钟且在线至 06:00
长夜微光 | 长夜内四件防具位为空 + 手持火把 + 户外连续停留 60 秒
晨归 | 长夜内保持存活至 06:00 且当时处于户外
静听 | 长夜内每 tick 位移 < 0.01、无破坏/放置/攻击行为，持续 5 分钟
=== END TABLE ===
[Body Text] 轻量趣味彩蛋（无经验无称号）：聊天关键词触发社团标语广播；管理员录入生日，生日当天上线触发祝贺标题；可配置节日时间，登录触发问候（独立于口令系统）。

[Heading 2] 7. 长夜休息时段（软性引导，默认 00:00~06:00 北京时间）
[Compact] 起止时间、总开关均由管理员 GUI 配置，热重载。
[Compact] 提示时刻由配置推算，不硬编码：开始前 10 分钟全服弹窗预告；开始时全服标题；结束前 10 分钟预告结束；长夜期间新玩家上线弹 GUI 规则说明。
[Compact] 效果（参数均可调）：怪物生成率倍率提升 + 全局实体数量上限（超限则拒绝生成，防 TPS 崩溃）；怪物移速/伤害/血量增益与玩家探测范围扩大；凋灵、末影龙及 Boss 类实体不强化（按实体类型白名单排除）。
[Compact] 在线玩家获得【长夜疲劳】：直接使用原版 SLOWNESS + MINING_FATIGUE，可选叠加饥饿加速；不自定义状态效果，避免双端注册与同步问题。
[Compact] 客户端渲染：长夜视野迷雾加重（需说明与 Sodium/Oculus 的兼容性）。
[Compact] 战令联动：见 5.11；世界随机事件正常运行，其经验同样 ×0.5。
[Compact] 保护与权限：默认开启苦力怕爆炸取消（事件内 cancel，不炸建筑）；管理员白名单（UUID 列表）不受长夜全部效果影响，用于夜间维护；不强制踢人，仅软性提示。
[Compact] 日志：定时打印当前北京时间与长夜状态；时间获取异常写 WARN。

[Heading 2] 8. 玩家 GUI（客户端，/battlepass 打开，独立中文窗口，支持滚动分页）
[Compact] 首页：等级、经验进度条、今日剩余可获取经验、服务器状态（正常/长夜）+ 倒计时。
[Compact] 每日任务：三组任务名称/描述/进度/状态；按钮：重 roll 本组、使用豁免卡、详情。
[Compact] 每周挑战：列表、进度、奖励。
[Compact] 战令商店：星币兑换装饰奖励。
[Compact] 称号库：已解锁列表、佩戴、预览切换。
[Compact] 收藏册：本赛季 + 历史赛季档案，仅显示已解锁项。
[Compact] 分支选择弹窗（10 级触发）。
[First Paragraph] 称号展示方式：服务端在聊天事件中为佩戴者添加前缀（颜色用 Formatting 枚举，不手拼 §），同时在 GUI 内展示。

[Heading 2] 9. 管理员面板（/battlepass admin，需 OP 权限 + 服务端二次校验，全部热重载）
[First Paragraph] 可编辑：赛季开关/时长/主题名｜防肝参数（每日经验上限、长夜起止与开关、长夜经验倍率、dailyRefreshTime）｜任务管理（每日/每周/彩蛋的增删改，含过滤器、目标进度、奖励）｜奖励管理（1~30 级、双分支、商店定价）｜全服里程碑｜世界随机事件｜节日口令｜称号管理（含颜色）｜数据维护（重置赛季、查询任意玩家、改等级/经验/星币、导出导入 JSON、手动热重载、改分支）。
[Body Text] 奖励类型统一抽象为 Reward：ITEM / COMMAND / BATTLEPASS_XP / STAR_COIN / TITLE。其中 COMMAND 必须走管理员命令白名单，禁止将 GUI 输入直接作为命令执行。

[Heading 2] 10. 配套系统
[Compact] 节日口令：聊天输入口令领取一次性奖励；口令匹配忽略大小写与首尾空白；生效时间段可配；每人每口令仅一次，去重键为 playerUUID + 口令ID，需保证并发原子性。
[Compact] 世界随机事件：需定义事件类型枚举、触发概率、最小间隔（默认 ≥60 分钟）、持续时长、参与判定条件、奖励表；全天可触发，长夜期间正常运行且经验 ×0.5。
[Compact] 全服里程碑：需定义统计口径（全服累计 / 人均）、达成判定、奖励发放对象（全服 / 参与者）。
[Compact] 日志：关键操作、报错、彩蛋解锁、任务刷新、配置热重载全部写服务端日志，统一前缀 [GaoJingBP]。

[Heading 2] 11. 指令
[Compact] /battlepass：打开战令 GUI
[Compact] /battlepass admin：打开管理面板（OP）
[Compact] 不提供 /battlepass dailyRefresh，刷新由 Mod 自动完成

[Heading 2] 12. 数据层（必须按此实现）
[Compact] 目录：config/gaojing_battlepass/（配置）、data/gaojing_battlepass/players/<uuid>.json（赛季数据）、data/gaojing_battlepass/global/<uuid>.json（星币、收藏册等永久数据）、data/history/season_<id>.json（归档）。
[Compact] 所有 JSON 含 schemaVersion 字段；原子写（写临时文件 + rename）并保留上一份 .bak；启动时校验，损坏则回滚 .bak 并记 ERROR。
[Compact] 写盘异步 + 节流合并（默认 5 秒），禁止主线程同步 IO。
[Compact] 内存态使用 ConcurrentHashMap；捕获全部 IO 异常，单玩家读写失败不得影响其他玩家。

[Heading 2] 13. 性能预算（硬指标）
[Compact] 轮询类检测统一 每 20 tick 一次；禁止每 tick 遍历”全部玩家 × 全部任务”。
[Compact] 任务匹配按方块 ID / 实体 ID / 群系建立索引，事件到达时只匹配相关任务。
[Compact] 长夜实体上限生效，避免刷怪导致 TPS 崩溃。
[Compact] 目标：30 人在线时 TPS ≥ 19.5；给出可测量的说明（如自增计数器 + 定期日志）。

[Heading 2] 14. 兜底容错
[Compact] 刷新兜底：启动时读取北京时间，若 lastDailyRefreshDate 不等于今日则补刷一次并打 WARN；必须幂等，跨多天停服只补刷一次。
[Compact] JSON 读写异常捕获 + 错误日志 + .bak 回滚。
[Compact] 非法/异常/超大客户端数据包直接丢弃，不得导致服务端崩溃；对 C2S 包做字段校验、频率限制与幂等处理（同一奖励不可重复领取）。

[Heading 2] 15. 交付物与阶段划分
[First Paragraph] 交付：完整 Java 源码（服务端 + 客户端两套，含中文注释）、fabric.mod.json ×2、build.gradle / settings.gradle / gradle.properties / wrapper、（如需）mixins json、编译打包教程、部署说明、管理员 GUI 操作手册。
[Body Text] 按以下顺序分阶段输出，每阶段结束停下等确认：
[Compact] 项目骨架 + 构建配置 + 完整文件树 + 依赖精确版本
[Compact] 数据模型 + JSON 持久化层（含原子写、schemaVersion、异步节流）
[Compact] 时间工具 + 长夜模式模块（含提示、白名单、爆炸保护、疲劳效果）
[Compact] 任务系统 + 事件监听 + 过滤器 + 防刷
[Compact] 战令等级 / 分支 / 星币 / 商店 / 每日经验上限
[Compact] 彩蛋系统 + 随机事件 + 节日口令 + 全服里程碑
[Compact] 网络协议（含 configuration 握手与增量同步）+ 客户端 GUI + 管理面板
[Compact] 文档 + 测试用例 + 性能说明

[Heading 2] 16. 验收用例（最终需逐条通过）
[Compact] ./gradlew build 通过，产出两个 jar。
[Compact] 修改长夜起止时间后不重启即生效。
[Compact] 停服跨过 06:00 后重启，任务自动补刷且只刷一次。
[Compact] 未安装客户端 Mod 的玩家被拒绝进入；开启 devMode 后可正常进入。
[Compact] 长夜期间任务进度正常累计、经验 ×0.5；长夜专属彩蛋不受减半影响。
[Compact] 假人/傀儡击杀不计入任务进度。
[Compact] 同一奖励重复点击只发一次。
[Compact] 分支选定后本赛季不可更换。
[Compact] 赛季结束后星币与收藏册保留，等级/经验/任务重置。
[Compact] 手动破坏玩家 JSON 后重启，能回滚 .bak 且不崩溃。
[Compact] 30 人模拟在线时 TPS ≥ 19.5。

[Heading 2] 17. 非目标（明确不做）
[First Paragraph] 不做反作弊、不做跨服/多服同步、不集成 LuckPerms 等权限插件、不写任何停服重启逻辑、不制作材质资源包、不承诺 Paper/Spigot 兼容。

[Heading 2] 18. 配置样例
[Heading 3] 赛季配置样例（config/gaojing_battlepass/season.json）
[Source Code] {
  "schemaVersion": 1,
  "seasonId": "S1",
  "themeName": "镐京初章",
  "enabled": true,
  "durationDays": 30,
  "maxLevel": 30,
  "branchUnlockLevel": 10,
  "dailyRefreshTime": "06:00",
  "dailyXpCap": 500,
  "longNight": {
    "enabled": true,
    "start": "00:00",
    "end": "06:00",
    "xpMultiplier": 0.5,
    "mobSpawnMultiplier": 1.5,
    "entityCap": 200,
    "creeperGriefProtect": true,
    "adminWhitelist": []
  }
}
[Heading 3] 每日任务配置样例（config/gaojing_battlepass/daily_tasks.json）
[Source Code] {
  "schemaVersion": 1,
  "groups": {
    "explore": [
      {
        "id": "exp_001",
        "name": "深入地下",
        "desc": "抵达 Y≤-40 的深度",
        "target": 1,
        "xp": 40,
        "starCoin": 0,
        "filter": {
          "op": "AND",
          "conditions": [
            { "type": "Y_RANGE", "min": -64, "max": -40 },
            { "type": "DIMENSION", "value": "minecraft:overworld" }
          ]
        }
      }
    ],
    "build": [],
    "general": []
  }
}
[Heading 3] 彩蛋配置样例（config/gaojing_battlepass/eggs.json）
[Source Code] {
  "schemaVersion": 1,
  "eggs": [
    {
      "id": "egg_dawn",
      "name": "长安拂晓",
      "category": "GLOBAL",
      "enabled": true,
      "xp": 20,
      "title": "拂晓行者",
      "condition": {
        "type": "TIME_AND_ALTITUDE",
        "time": "08:00",
        "toleranceMin": 5,
        "minY": 90,
        "requireOutdoor": true
      }
    }
  ]
}

[Heading 2] 19. 现在开始
[First Paragraph] 请从阶段 1 开始输出：完整文件树 + fabric.mod.json ×2 + build.gradle / settings.gradle / gradle.properties + 依赖精确版本说明。输出后停下，并给出「待确认问题清单」。不要提前写业务代码。