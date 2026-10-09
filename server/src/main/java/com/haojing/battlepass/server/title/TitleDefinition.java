package com.haojing.battlepass.server.title;

/**
 * 用途：一个称号的可编辑定义（对应 config/haojing_battlepass/titles.json 的一项）。
 *
 * <p>称号是纯装饰：本类只承载"显示什么文字、什么颜色、怎么包裹"，
 * 不携带任何属性/buff/生存加成。管理员在 titles.json 里增删改后热重载即生效。
 */
public class TitleDefinition {

    /** 称号 ID（与奖励里的 titleId、玩家解锁列表里的字符串一致）。 */
    public String id = "";

    /** 称号显示名；留空时客户端回退到 lang 译文。 */
    public String name = "";

    /** 自定义描述文字（悬浮 Tooltip 第二行）。 */
    public String description = "";

    /** 获取途径说明（例如 "战令 1 级奖励"、"商店 40 京币兑换"）。 */
    public String acquireHint = "";

    /** 称号文字颜色（原版 § 代码，如 §e）。 */
    public String color = "§f";

    /** 包裹符号前缀（默认 "【"）。 */
    public String wrapPrefix = "【";

    /** 包裹符号后缀（默认 "】"）。 */
    public String wrapSuffix = "】";

    /** 称号名最大长度（汉字计），超长在校验时截断。 */
    public static final int MAX_NAME_CHARS = 12;

    /** @return 校验/规范化后的名称（截断到 12 个汉字）。 */
    public String normalizedName() {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.length() > MAX_NAME_CHARS ? trimmed.substring(0, MAX_NAME_CHARS) : trimmed;
    }
}
