package com.haojing.battlepass.server.cape;

/**
 * 用途：一个披风的可编辑定义（对应 config/haojing_battlepass/capes.json 的一项）。
 *
 * <p>披风是纯装饰：本类只承载"显示什么、贴图文件名、怎么获取"，
 * 不携带任何属性/buff/生存加成。管理员在 capes.json 里增删改后热重载即生效。
 */
public class CapeDefinition {

    /** 披风 ID（与奖励、玩家解锁列表、客户端注册的纹理 key 一致）。 */
    public String id = "";

    /** 披风显示名。 */
    public String name = "";

    /** 自定义描述文字（悬浮 Tooltip 第二行）。 */
    public String description = "";

    /** 获取途径说明（例如 "战令 25 级奖励"、"管理员手动发放"）。 */
    public String acquireHint = "";

    /**
     * 披风贴图文件名（位于 config/haojing_battlepass/capes/ 目录下）。
     * 必须是标准 MC 披风格式 64×32 PNG。文件缺失时自动回退原版空白披风，不崩溃。
     */
    public String textureFile = "";

    /** @return 规范化后的 id（去空白）。 */
    public String normalizedId() {
        return id == null ? "" : id.trim();
    }

    /** @return 规范化后的显示名。 */
    public String normalizedName() {
        return name == null ? "" : name.trim();
    }
}
