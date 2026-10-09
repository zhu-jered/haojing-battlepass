package com.haojing.battlepass.common.data;

/**
 * 用途：任务状态枚举（需求文档 §5.5：NOT_ACTIVE / IN_PROGRESS / COMPLETED（完成待领取）/ CLAIMED）。
 *
 * <p>为什么把状态放在共享源码里：阶段 7 客户端 GUI 需要展示任务状态，而服务端与客户端对
 * 同一份状态必须有一致的解释，否则会出现"服务端认为已领取、客户端还显示可领取"的错位。
 *
 * <p>为什么提供 {@link #fromName(String)}：理由同 {@link Branch#fromName(String)} ——
 * 避免 Gson 遇到未知枚举值抛异常，被持久化层误判为文件损坏。
 */
public enum TaskStatus {

    /** 未激活：尚未进入玩家视野（例如长夜专属任务在非长夜时段）。 */
    NOT_ACTIVE,

    /** 进行中：进度正在累计。 */
    IN_PROGRESS,

    /** 已完成待领取：进度已达标，等待玩家领取奖励。 */
    COMPLETED,

    /** 已领取：奖励已发放，且按需求文档 §5.8 不再计入后续进度。 */
    CLAIMED;

    /**
     * 宽容地把字符串转成枚举：不认识或为空一律按 {@link #NOT_ACTIVE} 处理。
     *
     * @param name 存档里的状态名
     * @return 对应枚举，无法识别时返回 {@link #NOT_ACTIVE}
     */
    public static TaskStatus fromName(String name) {
        if (name == null || name.isEmpty()) {
            return NOT_ACTIVE;
        }

        for (TaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }

        return NOT_ACTIVE;
    }
}
