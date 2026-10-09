package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：某一等级的奖励定义（需求文档 §9「奖励管理（1~30 级、双分支、商店定价）」）。
 *
 * <p>为什么通用奖励与分支奖励放在同一个条目里：§4 规定 10 级时二选一分支，
 * 而奖励曲线是 1~30 级连续的。同一个等级上"人人都有的一份"与"选了某分支才有的一份"
 * 必须能同时表达，否则管理员就得把同一级的奖励拆到两张表里，改起来容易漏。
 */
public class LevelRewardEntry {

    /** 等级。 */
    public int level = 0;

    /**
     * 是否启用。关掉它等于"跳过这一级的奖励"，且不会丢掉配置内容 ——
     * 管理员想临时停发某一级奖励时不必删掉再重新写一遍。
     */
    public boolean enabled = true;

    /** 该等级的通用奖励（不论分支）。 */
    public List<Reward> common = new ArrayList<>();

    /** 该等级的狩猎分支专属奖励。 */
    public List<Reward> hunt = new ArrayList<>();

    /** 该等级的建造分支专属奖励。 */
    public List<Reward> build = new ArrayList<>();

    /** @return 指定分支对应的奖励列表；{@link Branch#NONE} 时返回空列表。 */
    public List<Reward> forBranch(Branch branch) {
        if (branch == null) {
            return List.of();
        }

        switch (branch) {
            case HUNT:
                return hunt == null ? List.of() : hunt;
            case BUILD:
                return build == null ? List.of() : build;
            default:
                return List.of();
        }
    }
}
