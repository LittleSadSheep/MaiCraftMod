package org.maiwithu.maicraft.agent.tool;

import java.util.UUID;

/**
 * 告诉分发器“这次调用针对哪个玩家”。接口只提供 UUID，不保存世界或身体控制权。
 * 分发器仍须拿这个身份与当前本地玩家核对。
 */
public interface ToolAnchor {

    /** 要操作的玩家身份。只有编号不代表该玩家当前在线，也不代表旧的玩家对象仍可使用。 */
    UUID entityUuid();
}
