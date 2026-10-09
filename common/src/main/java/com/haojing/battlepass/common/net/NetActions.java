package com.haojing.battlepass.common.net;

/**
 * 用途：客户端可发起的动作名（需求文档 §8 的按钮，§14 的字段校验）。
 *
 * <p>为什么用枚举 + 字符串双份：字符串是**线上协议**，枚举是服务端与客户端的
 * 编译期常量。两端都从这个枚举取名字，就不可能拼错；
 * 而服务端收到未知字符串时会明确拒绝（§14 要求非法数据包直接丢弃并记日志），
 * 不会静默忽略。
 */
public final class NetActions {

    private NetActions() {
    }

    /** 玩家动作（{@link ModPayloads.ClientAction}）。 */
    public enum ClientAction {

        /** 请求全量重新同步（打开界面或重连后使用）。 */
        RESYNC(""),

        /** 重 roll 某个每日任务组：arg = explore / build / general。 */
        REROLL("group"),

        /** 领取某个任务奖励：arg = 任务 ID。 */
        CLAIM("taskId"),

        /** 对某个每日任务组使用豁免卡：arg = 组名。 */
        EXEMPT("group"),

        /** 用星币兑换商品：arg = 商品 ID。 */
        BUY("itemId"),

        /** 佩戴称号：arg = 称号 ID（空串表示卸下）。 */
        EQUIP_TITLE("titleId"),

        /** 切换"聊天中显示称号"开关（无参）。 */
        TOGGLE_CHAT_TITLE(""),

        /** 切换"头顶显示称号"开关（无参）。 */
        TOGGLE_NAMETAG_TITLE(""),

        /** 选择分支：arg = HUNT / BUILD。 */
        CHOOSE_BRANCH("branch"),

        /** 选择本日要做的每日任务组：arg = explore / build / general。选完后其它组锁定。 */
        CHOOSE_DAILY_GROUP("group");

        private final String argHint;

        ClientAction(String argHint) {
            this.argHint = argHint;
        }

        /** @return 该动作的参数含义（用于日志与文档）。 */
        public String argHint() {
            return argHint;
        }

        /** @return 是否需要参数。 */
        public boolean requiresArg() {
            return !argHint.isEmpty();
        }

        /**
         * 宽容解析动作名。
         *
         * @param name 线上传来的动作名
         * @return 对应动作；无法识别时为 null（调用方必须丢弃该包）
         */
        public static ClientAction fromName(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }

            String trimmed = name.trim();

            for (ClientAction action : values()) {
                if (action.name().equalsIgnoreCase(trimmed)) {
                    return action;
                }
            }

            return null;
        }
    }

    /** 管理面板动作（{@link ModPayloads.AdminAction}，§9）。 */
    public enum AdminAction {

        /**
         * 请求打开管理面板（由客户端输入 {@code /battlepass admin} 时发出）。
         *
         * <p>为什么要走这个包而不是让客户端直接开界面：§9 要求"需 OP 权限 +
         * 服务端二次校验"。客户端自己开界面等于没有任何校验，
         * 因此这里让客户端"申请"，由服务端验证 OP 后再回一个 OpenPanel。
         */
        OPEN_PANEL(""),

        /**
         * 只要一份最新的管理快照，**不要**再把"打开面板"回给客户端。
         *
         * <p>为什么必须与 {@link #OPEN_PANEL} 分开：早期版本让面板每次重建控件时都发
         * {@code OPEN_PANEL}，而服务端收到它就会回一个 OpenPanel 让客户端开面板 ——
         * 于是形成「开面板 → 重建 → 再请求 → 再开面板」的死循环（真机日志里刷了一屏
         * {@code 丢弃管理动作包（TOO_FREQUENT：OPEN_PANEL）} 就是它）。
         * 现在"打开面板"与"刷新数据"是两件事。
         */
        SYNC_ADMIN(""),

        /** 打开玩家战令界面（管理员自测用：面板里一键切到玩家视角）。 */
        OPEN_PLAYER_PANEL(""),

        /**
         * 给目标玩家加经验（走正常发经验流程：受长夜倍率与每日上限约束）。
         *
         * <p>与 {@link #SET_XP} 的区别：SET_XP 直接改"当前等级内经验"这个数字，
         * 不触发升级；ADD_XP 是"像做任务一样拿到经验"，会升级、发星币、结算等级奖励 ——
         * 管理员测试升级/奖励链路时用这个。
         */
        ADD_XP("player"),

        /** 给目标玩家加星币。 */
        ADD_STAR_COIN("player"),

        /** 给目标玩家发豁免卡（受 §5.9 的持有上限约束）。 */
        GIVE_EXEMPT_CARD("player"),

        /**
         * 立刻重抽某个玩家的每日任务（测试用）。
         *
         * <p>注意：这会**作废该玩家当天的任务进度**。之所以仍然提供，
         * 是因为没有它就无法反复测试"任务进度 → 领奖 → 升级"这条链路
         * （正常路径一天只刷一次）。
         */
        FORCE_TASK_REFRESH("player"),

        /** 把自己的赛季进度清空（等级/经验/任务/分支/豁免卡/彩蛋进度），用于反复自测。 */
        RESET_SELF(""),

        /** 重新读取全部配置（等价于手动热重载）。 */
        RELOAD(""),

        /** 查询某个玩家：arg = 玩家名或 UUID。 */
        QUERY("player"),

        /** 改等级：arg = 玩家名/UUID，value = 等级。 */
        SET_LEVEL("player"),

        /** 改经验：arg = 玩家名/UUID，value = 经验值。 */
        SET_XP("player"),

        /** 改星币：arg = 玩家名/UUID，value = 星币数。 */
        SET_STAR_COIN("player"),

        /** 改分支：arg = 玩家名/UUID，value = HUNT / BUILD / NONE。 */
        SET_BRANCH("player"),

        /** 改赛季开关：value = true / false。 */
        SET_SEASON_ENABLED("value"),

        /** 改每日经验上限：value = 数字。 */
        SET_DAILY_XP_CAP("value"),

        /** 改每日刷新时刻：value = HH:mm。 */
        SET_DAILY_REFRESH("value"),

        /** 改长夜参数：arg = start/end/enabled/xpMultiplier，value = 取值。 */
        SET_LONG_NIGHT("field"),

        /** 改等级上限：value = 数字。 */
        SET_MAX_LEVEL("value"),

        /** 改分支解锁等级：value = 数字。 */
        SET_BRANCH_UNLOCK("value"),

        /** 改经验曲线：arg = base/step，value = 数字。 */
        SET_XP_CURVE("field"),

        /** 重置赛季（滚动到配置里的赛季 ID）。 */
        RESET_SEASON(""),

        /** 导出全部配置到 data 目录下的备份文件。 */
        EXPORT_CONFIG(""),

        /** 从备份文件导入配置：value = 文件名。 */
        IMPORT_CONFIG("file"),

        /** 手动开一个随机事件：arg = 事件 ID。 */
        START_EVENT("eventId"),

        /** 重载某个玩家的彩蛋进度（调试用）：arg = 玩家名/UUID。 */
        RESET_EGG_PROGRESS("player");

        private final String argHint;

        AdminAction(String argHint) {
            this.argHint = argHint;
        }

        /** @return 参数含义（文档与日志用）。 */
        public String argHint() {
            return argHint;
        }

        /**
         * 宽容解析管理动作名。
         *
         * @param name 线上传来的动作名
         * @return 对应动作；无法识别时为 null
         */
        public static AdminAction fromName(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }

            String trimmed = name.trim();

            for (AdminAction action : values()) {
                if (action.name().equalsIgnoreCase(trimmed)) {
                    return action;
                }
            }

            return null;
        }
    }
}
