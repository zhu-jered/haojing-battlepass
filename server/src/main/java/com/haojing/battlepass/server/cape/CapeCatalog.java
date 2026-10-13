package com.haojing.battlepass.server.cape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：披风注册表（对应 capes.json 整体）。纯数据 POJO，Gson 直接序列化。
 *
 * <p>用 LinkedHashMap 保持管理员在文件里书写的顺序，衣柜列表与客户端渲染顺序一致。
 */
public class CapeCatalog {

    /** 中文说明字段（Gson 序列化时原样写进 JSON，管理员打开文件即见用途）。 */
    public String _comment = "披风注册表：纯装饰，不提供任何属性/buff/生存加成。"
            + "id 必须唯一；textureFile 指向 capes/ 目录下的 64×32 PNG；"
            + "name/acquireHint 用于衣柜悬浮提示。修改后保存即热重载，无需重启。";

    /** 全部披风定义。 */
    public List<CapeDefinition> capes = new ArrayList<>();

    /** 校验并规范化；不合法的项剔除。 */
    public void validate() {
        if (capes == null) {
            capes = new ArrayList<>();
            return;
        }

        Map<String, CapeDefinition> dedup = new LinkedHashMap<>();

        for (CapeDefinition def : capes) {
            if (def == null || def.id == null || def.id.isBlank()) {
                continue;
            }

            def.id = def.normalizedId();
            def.name = def.normalizedName();
            def.description = def.description == null ? "" : def.description.trim();
            def.acquireHint = def.acquireHint == null ? "" : def.acquireHint.trim();
            def.textureFile = def.textureFile == null ? "" : def.textureFile.trim();

            dedup.put(def.id, def);
        }

        capes = new ArrayList<>(dedup.values());
    }

    /** @return 全部披风定义。 */
    public List<CapeDefinition> all() {
        return capes;
    }

    /** @return 按 id 查定义；不存在时返回 null。 */
    public CapeDefinition byId(String id) {
        if (id == null || capes == null) {
            return null;
        }
        for (CapeDefinition def : capes) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** @return 数量。 */
    public int size() {
        return capes == null ? 0 : capes.size();
    }
}
