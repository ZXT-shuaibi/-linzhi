package com.zhiguang.be.social;

/**
 * 点赞或收藏动作返回数据。
 */
public class InteractionActionData {

    private final String targetType;
    private final String targetId;
    private final String action;
    private final boolean active;
    private final boolean changed;
    private final int snapshotVersion;

    /**
     * 构造互动动作返回对象。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param active 当前动作是否生效
     * @param snapshotVersion 当前快照版本
     */
    public InteractionActionData(String targetType, String targetId, String action, boolean active, boolean changed, int snapshotVersion) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.active = active;
        this.changed = changed;
        this.snapshotVersion = snapshotVersion;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getAction() {
        return action;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isChanged() {
        return changed;
    }

    public int getSnapshotVersion() {
        return snapshotVersion;
    }
}
