package com.haojing.battlepass.server.checkin;

import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：连续签到里程碑档位 —— 达到连续 N 天时一次性发放的额外奖励（与每日循环奖励互补）。
 *
 * <p>为什么里程碑要单独成类而不是塞进 dailyRewards：每日奖励按"连击天数取模"循环，
 * 而里程碑是"达到某一固定连击天数只发一次"，两者发放时机、去重口径都不同，混在一张表里
 * 管理员很难配，也容易出现"第 7 天奖励被第 14 天重复领走"的错误。
 */
public class CheckinMilestone {

    /** 触发该里程碑所需的连续签到天数（必须为正整数）。 */
    public int streak = 0;

    /** 达到该里程碑时一次性发放的奖励列表。 */
    public List<Reward> rewards = new ArrayList<>();

    /** 规范化空列表；逐条奖励的合法性过滤在 {@link CheckinPool#validate()} 统一完成。 */
    public void normalize() {
        if (rewards == null) {
            rewards = new ArrayList<>();
        }
    }
}
