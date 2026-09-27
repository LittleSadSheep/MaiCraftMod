// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.locate;

import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一次只读的已加载方块证据查询。公开结果只含数量与最近距离统计；
 * 具体位置保留在 Mod 内部，后续交互由对应能力自行定位。
 */
public final class SemanticBlockSearchTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "find_block";
    public static final int MIN_DISTANCE = 4;
    public static final int DEFAULT_DISTANCE = 64;
    public static final int MAX_DISTANCE = 128;
    public static final int MAX_BLOCK_IDS = 32;
    public static final int MAX_COUNT = 32;

    public final List<Block> blockTargets;
    public final int count;
    public final int maxDistance;

    static {
        TaskFactory.register(SemanticBlockSearchTaskRecord.class, SemanticBlockSearchCompanionTask::new);
    }

    public SemanticBlockSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<Block> blockTargets,
            int count,
            int maxDistance) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.blockTargets = validateTargets(blockTargets);
        this.count = Math.clamp(count, 1, MAX_COUNT);
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
    }

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return "在已加载范围寻找 " + count + " 处目标方块证据（"
                + blockTargets.size() + " 种可接受类型）";
    }

    private static List<Block> validateTargets(List<Block> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("find_block needs at least one block_id");
        }
        LinkedHashSet<Block> unique = new LinkedHashSet<>(values);
        if (unique.size() > MAX_BLOCK_IDS) {
            throw new IllegalArgumentException(
                    "find_block accepts at most " + MAX_BLOCK_IDS + " block ids");
        }
        return List.copyOf(unique);
    }
}
