package com.haojing.battlepass.common.data;

/**
 * 用途：一条奖励的统一描述（需求文档 §9：奖励类型统一抽象为 Reward）。
 * 等级奖励表（config/haojing_battlepass/rewards.json）与商店（shop.json）都用它表达"发什么"。
 *
 * <p>为什么把六种奖励塞进同一个类而不是给每类一个子类：奖励要能被管理员在管理面板里
 * 随手增删改并序列化成 JSON。单一 DTO + 一个 type 字段的形式，管理员看到的就是
 * "type 决定读哪个字段"，一目了然；换成多态（Gson 需要自定义 TypeAdapter 才能按 type 反序列化）
 * 只会让配置文件的容错能力变差。
 *
 * <p>为什么不在这里做发放：本类位于 :common，是两端共享的契约；发放要碰物品栏、命令、
 * 玩家数据，属于服务端业务，因此放在服务端的 RewardSink 实现里。这里只提供校验。
 */
public class Reward {

    /** 奖励类型名，取值见 {@link RewardType}。 */
    public String type = "";

    /** 数量：ITEM 的个数、STAR_COIN 的枚数、BATTLEPASS_XP 的经验值、EXEMPT_CARD 的张数。 */
    public int amount = 1;

    /** 物品 ID（type = ITEM 时必填），例如 {@code minecraft:diamond}。 */
    public String itemId = "";

    /** 待执行的命令（type = COMMAND 时必填），**不带**前导斜杠。 */
    public String command = "";

    /** 称号 ID（type = TITLE 时必填）。 */
    public String titleId = "";

    /** Gson 反序列化需要无参构造。 */
    public Reward() {
    }

    public Reward(RewardType type, int amount) {
        this.type = type.name();
        this.amount = amount;
    }

    /** @return 强类型；无法识别时为 null。 */
    public RewardType typeOrNull() {
        return RewardType.fromName(type);
    }

    /**
     * 就地规范化：去掉首尾空白、补齐默认数量、剥掉命令的前导斜杠。
     *
     * <p>为什么要剥前导斜杠：管理员在面板里填命令时习惯带 "/"，而服务端执行入口
     * （{@code executeWithPrefix}）不带斜杠。两种写法都接受、统一成一种，可以避免
     * "配置里明明写了却提示命令不合法"这种低级挫败。
     */
    public void normalize() {
        type = type == null ? "" : type.trim();
        itemId = itemId == null ? "" : itemId.trim();
        titleId = titleId == null ? "" : titleId.trim();
        command = command == null ? "" : command.trim();

        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
    }

    /**
     * 校验是否有能力被发放。返回 null 表示合法。
     *
     * <p>之所以返回"原因字符串"而不是 boolean：配置出错时必须能告诉管理员
     * 是哪一条、错在哪，否则只能得到一句"配置无效"。
     *
     * @return 不合法时的中文原因；合法时为 null
     */
    public String validateError() {
        RewardType resolved = typeOrNull();

        if (resolved == null) {
            return "无法识别的奖励类型：" + type;
        }

        if (amount <= 0) {
            return "奖励数量必须为正数（" + resolved + " amount=" + amount + "）";
        }

        switch (resolved) {
            case ITEM:
                if (itemId.isBlank()) {
                    return "ITEM 奖励缺少 itemId";
                }
                return null;
            case COMMAND:
                if (command.isBlank()) {
                    return "COMMAND 奖励缺少 command";
                }
                return null;
            case TITLE:
                if (titleId.isBlank()) {
                    return "TITLE 奖励缺少 titleId";
                }
                return null;
            default:
                // BATTLEPASS_XP / STAR_COIN / EXEMPT_CARD 只需要一个正数 amount，上面已经校验过。
                return null;
        }
    }

    /** @return 是否合法（等价于 {@code validateError() == null}）。 */
    public boolean isValid() {
        return validateError() == null;
    }

    /** @return 便于日志与测试断言的一行摘要。 */
    @Override
    public String toString() {
        RewardType resolved = typeOrNull();

        if (resolved == null) {
            return "Reward(未知类型=" + type + ")";
        }

        switch (resolved) {
            case ITEM:
                return "Reward(ITEM " + itemId + " x" + amount + ")";
            case COMMAND:
                return "Reward(COMMAND /" + command + ")";
            case TITLE:
                return "Reward(TITLE " + titleId + ")";
            default:
                return "Reward(" + resolved + " x" + amount + ")";
        }
    }
}
