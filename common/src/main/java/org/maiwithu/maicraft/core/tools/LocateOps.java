package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.locate.LocateBiomeTaskRecord;
import org.maiwithu.maicraft.core.task.locate.LocateStructureTaskRecord;

/**
 * 把两种定位工具的输入去掉首尾空格、检查长度，再建立带三十秒游戏时间上限的任务记录。
 * 这里只组装请求，不读取世界，也不负责判断找到的是不是最近目标。
 */
public final class LocateOps {

    private static final long TIMEOUT_TICKS = 30 * 20;
    private static final int MAX_ARG_LENGTH = 128;

    public TaskRecord locateStructure(
String structure,
            ToolContext ctx) {
        structure = structure.trim();
        if (structure.isEmpty() || structure.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid structure argument");
        }
        return new LocateStructureTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), structure);
    }

    public TaskRecord locateBiome(
String biome,
            ToolContext ctx) {
        biome = biome.trim();
        if (biome.isEmpty() || biome.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid biome argument");
        }
        return new LocateBiomeTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), biome);
    }
}
