package com.haojing.battlepass.server.redeem;

import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：一条节日口令（需求文档 §10：聊天输入口令领取一次性奖励）。
 *
 * <p>字段设计对应 §10 的每一条要求：
 * <ul>
 *   <li>「口令匹配忽略大小写与首尾空白」→ {@code code} 比较时统一 trim + 小写</li>
 *   <li>「生效时间段可配」→ {@code startDate} / {@code endDate}（MM-dd，每年循环）</li>
 *   <li>「每人每口令仅一次，去重键为 playerUUID + 口令ID」→ {@code id} 就是去重键的一半，
 *       另一半存在玩家永久数据里（{@code GlobalData#claimedRedeemCodes}）</li>
 * </ul>
 */
public class RedeemCode {

    /** 口令 ID，配置内唯一；也是"每人一次"的去重键。 */
    public String id = "";

    /** 口令文本（玩家要在聊天里输入的内容）。 */
    public String code = "";

    /** 展示名（管理面板与日志用）。 */
    public String name = "";

    /** 是否启用。 */
    public boolean enabled = true;

    /** 生效起始日期 MM-dd（空表示不限）。 */
    public String startDate = "";

    /** 生效结束日期 MM-dd（空表示不限）。 */
    public String endDate = "";

    /** 领取成功的提示文案翻译键（发给领取者本人）。 */
    public String successMessageKey = "haojing_battlepass.code.success";

    /** 领取成功后是否全服公告（例如节日口令值得让全服看到）。 */
    public boolean broadcast = false;

    /** 奖励列表。 */
    public List<Reward> rewards = new ArrayList<>();

    /** 就地规范化：口令文本去首尾空白（匹配时再统一小写）。 */
    public void normalize() {
        id = id == null ? "" : id.trim();
        code = code == null ? "" : code.trim();
        name = name == null ? "" : name.trim();
        startDate = com.haojing.battlepass.server.time.MonthDayWindow.normalize(startDate);
        endDate = com.haojing.battlepass.server.time.MonthDayWindow.normalize(endDate);

        if (successMessageKey == null || successMessageKey.isBlank()) {
            successMessageKey = "haojing_battlepass.code.success";
        }

        if (rewards == null) {
            rewards = new ArrayList<>();
        }
    }
}
