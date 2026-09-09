package org.maiwithu.maicraft.agent.tool;

import java.util.UUID;

/**
 * 旧 ToolCall 携带的玩家身份接口，只给 UUID。它没有保存世界或控制权；当前只有旧调用对象引用它。
 */
public interface ToolAnchor {

    /** 要操作的玩家身份。只有编号不代表该玩家当前在线，也不代表旧的玩家对象仍可使用。 */
    UUID entityUuid();
}
