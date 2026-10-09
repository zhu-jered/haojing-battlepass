package com.haojing.battlepass.server.title;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：称号注册表（对应 titles.json 整体）。纯数据 POJO，Gson 直接序列化。
 *
 * <p>用 LinkedHashMap 保持管理员在文件里书写的顺序，悬浮列表与客户端渲染顺序一致。
 */
public class TitleCatalog {

    /** 中文说明字段（Gson 序列化时原样写进 JSON，管理员打开文件即见用途）。 */
    public String _comment = "称号注册表：纯装饰，不提供任何属性/buff/生存加成。"
            + "id 必须与奖励里的 titleId 一致；name 留空则用客户端 lang 译文；"
            + "color 用原版 § 颜色代码（如 §e）；wrapPrefix/wrapSuffix 为聊天与头顶的包裹符号；"
            + "name 最长 12 个汉字。修改后保存即热重载，无需重启。";

    /** 全部称号定义，按 id 索引。 */
    public List<TitleDefinition> titles = new ArrayList<>();

    /** 校验并规范化；不合法的项剔除并记日志（由调用方打日志）。 */
    public void validate() {
        if (titles == null) {
            titles = new ArrayList<>();
            return;
        }

        Map<String, TitleDefinition> dedup = new LinkedHashMap<>();

        for (TitleDefinition def : titles) {
            if (def == null || def.id == null || def.id.isBlank()) {
                continue;
            }

            def.id = def.id.trim();
            def.name = def.normalizedName();
            def.description = def.description == null ? "" : def.description.trim();
            def.acquireHint = def.acquireHint == null ? "" : def.acquireHint.trim();
            def.color = def.color == null || def.color.isBlank() ? "§f" : def.color;
            def.wrapPrefix = def.wrapPrefix == null ? "【" : def.wrapPrefix;
            def.wrapSuffix = def.wrapSuffix == null ? "】" : def.wrapSuffix;

            dedup.put(def.id, def);
        }

        titles = new ArrayList<>(dedup.values());
    }

    /** @return 全部称号定义（不可变视图）。 */
    public List<TitleDefinition> all() {
        return titles;
    }

    /** @return 按 id 查定义；不存在时返回 null。 */
    public TitleDefinition byId(String id) {
        if (id == null || titles == null) {
            return null;
        }
        for (TitleDefinition def : titles) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** @return 数量。 */
    public int size() {
        return titles == null ? 0 : titles.size();
    }
}
