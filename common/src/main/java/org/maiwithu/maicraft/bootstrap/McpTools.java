// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.reminder.Reminder;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.mcp.tool.EventsTool;
import org.maiwithu.maicraft.mcp.tool.ExecuteTool;
import org.maiwithu.maicraft.mcp.tool.GoalTool;
import org.maiwithu.maicraft.mcp.tool.LookupTool;
import org.maiwithu.maicraft.mcp.tool.ObserveTool;
import org.maiwithu.maicraft.mcp.tool.RecentToolCalls;
import org.maiwithu.maicraft.mcp.tool.ToolDispatcher;

/**
 * MCP 的五个工具怎么接：能力注册表、目标运行表与任务事件流。LLM 用 execute 下达的目标经目标运行表
 * 成为控制循环的主任务；目标处境每次变化都发成任务事件，宿主用 events 等。
 * 能力按清单在进世界时登记进这份注册表。事件流用客户端入口的那一份：目标处境与生存需求共一条流。
 */
final class McpTools {
    private McpTools() {}

    /** 五个工具装进一个调用分发；观察读当前世界的现场，不在世界里时各视图如实说不在。 */
    static ToolDispatcher dispatcher(AbilityRegistry abilities, GoalRunTable goals, ClientTickWork clientWork,
                                     TaskEventLog taskEvents, ChatEventLog chatEvents, Supplier<WorldScope> worldScope,
                                     RecentToolCalls recentCalls, LookupTool lookup,
                                     Supplier<List<Reminder>> reminders) {
        return new ToolDispatcher(List.of(
                new ObserveTool(() -> worldScope.get() == null ? null : worldScope.get().scene(),
                        () -> worldScope.get() == null ? null : worldScope.get().memory(), goals, clientWork,
                        reminders, () -> worldScope.get() == null ? null : worldScope.get().menus()),
                // 查资料接随包的机制常识、物品资料页、联动模组登记的资料来源与配方；联网的资料来源还没登记。
                lookup,
                new ExecuteTool(abilities, goals, clientWork),
                new GoalTool(goals, clientWork),
                new EventsTool(taskEvents, chatEvents, goals, clientWork)), recentCalls);
    }
}
