package com.haojing.battlepass.common.data;

/**
 * 用途：单个任务在某个玩家身上的进度与状态（需求文档 §5.5：进度与状态持久化）。
 *
 * <p>为什么状态字段用 String 而不是直接的 {@link TaskStatus} 枚举：见
 * {@link TaskStatus#fromName(String)} 的说明 —— 让存档对未知状态保持宽容，
 * 不至于被持久化层当成损坏文件回滚。对外仍通过 {@link #status()} 暴露强类型。
 */
public class TaskProgress {

    /** 任务 ID，对应任务配置里的 id。放进存档是为了让 JSON 具备自解释性，便于人工排查。 */
    public String taskId = "";

    /** 状态名，取值见 {@link TaskStatus}。 */
    public String status = TaskStatus.NOT_ACTIVE.name();

    /** 已完成的量（例如已放置方块数）。封顶由任务配置的 target 决定，在服务端判定时处理。 */
    public int progress = 0;

    /** Gson 反序列化需要无参构造。 */
    public TaskProgress() {
    }

    public TaskProgress(String taskId) {
        this.taskId = taskId;
    }

    /** @return 强类型状态；未知值按 {@link TaskStatus#NOT_ACTIVE} 处理。 */
    public TaskStatus status() {
        return TaskStatus.fromName(status);
    }

    /** @param newStatus 新状态；null 按 {@link TaskStatus#NOT_ACTIVE} 处理。 */
    public void setStatus(TaskStatus newStatus) {
        this.status = (newStatus == null ? TaskStatus.NOT_ACTIVE : newStatus).name();
    }

    /** @return 是否已领取。需求文档 §5.8 要求 CLAIMED 之后不再计入进度。 */
    public boolean isClaimed() {
        return status() == TaskStatus.CLAIMED;
    }
}
