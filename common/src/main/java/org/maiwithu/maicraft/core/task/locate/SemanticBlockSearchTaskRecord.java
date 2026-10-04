// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.locate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一次只读的已加载方块证据查询。公开数量、距离及岩浆的连通池与整形预算；
 * 最近匹配位置作为已观察事实交付，完整候选位置仍在 Mod 内部用于连通分析。
 */
public final class SemanticBlockSearchTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "find_block";
    public static final int MIN_DISTANCE = 4;
    public static final int DEFAULT_DISTANCE = 64;
    public static final int MAX_DISTANCE = 128;
    public static final int MAX_BLOCK_IDS = 32;
    public static final int MAX_COUNT = 32;

    /** 明确找浇筑池时按池子验收；普通查块仍按方块数量验收，不能只改描述却沿用单格成功条件。 */
    public enum Purpose {
        BLOCKS, PORTAL_CASTING;
        public static Purpose parse(String value) {
            if (value == null) return BLOCKS;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "blocks" -> BLOCKS;
                case "portal_casting" -> PORTAL_CASTING;
                default -> throw new IllegalArgumentException("find_block purpose must be blocks or portal_casting");
            };
        }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public final List<Block> blockTargets;
    public final int count;
    /** 公开查找使用水平圆半径覆盖各高度段；这是只读范围，不是角色行走距离、交互半径或开路授权。 */
    public final int maxDistance;
    public final Purpose purpose;

    static {
        TaskFactory.register(SemanticBlockSearchTaskRecord.class, SemanticBlockSearchCompanionTask::new);
    }

    public SemanticBlockSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<Block> blockTargets,
            int count,
            int maxDistance) {
        this(toolCallId, deadlineGameTime, blockTargets, count, maxDistance, Purpose.BLOCKS);
    }

    public SemanticBlockSearchTaskRecord(String toolCallId, long deadlineGameTime, List<Block> blockTargets,
                                         int count, int maxDistance, Purpose purpose) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.blockTargets = validateTargets(blockTargets);
        this.purpose = purpose == null ? Purpose.BLOCKS : purpose;
        // 只读浇筑调查只接受岩浆；不能把石头等其他候选悄悄算成符合用途的池子。
        if (this.purpose == Purpose.PORTAL_CASTING && !this.blockTargets.equals(List.of(Blocks.LAVA)))
            throw new IllegalArgumentException("portal_casting requires minecraft:lava only");
        this.count = Math.clamp(count, 1, MAX_COUNT);
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
    }

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        if (purpose == Purpose.PORTAL_CASTING) return "在已加载可见范围寻找 " + count + " 处具备浇筑布局与岩浆余量的池子";
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
