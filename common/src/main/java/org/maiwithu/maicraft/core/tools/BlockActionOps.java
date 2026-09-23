package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.MouseButton;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * 把挖矿、原地点击和实体点击的内部参数转换成任务记录。
 * 点击坐标要么三个都提供，要么全不提供；操作前的方块身份与操作后的方块要求分别校验。这里不实际按键。
 */
public final class BlockActionOps {

    // 挖掘预算与范围。
    private static final int MAX_COUNT = 256;
    /** 单方块预算留有余量；总预算随目标数量增长，避免大型任务超时。 */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 每个方块预留 30 秒。
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 为挖掘任务预留至少 60 秒，避免短名单或单个目标因时间预算过小而提前终止。

    // interact_at：包含走到瞄准位置所需时间。
    private static final long INTERACT_AT_TIMEOUT_TICKS = 30 * 20;
    // interact_entity：包含追赶移动目标所需时间。
    private static final long INTERACT_ENTITY_TIMEOUT_TICKS = 60 * 20;

    // 把物品来源方块名读成目标集合，数量压到 1～256，并按数量给采矿任务一个初始期限。
    // 这里创建的是通用采矿任务，没有填写期望产物；后面会通过背包变化猜本轮产物。
    public TaskRecord autoMine(List<String> block_ids, int count, ToolContext ctx) {
        Set<Block> targets = ToolParse.parseBlocks(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        int clampedCount = Math.clamp(count, 1, MAX_COUNT);
        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) clampedCount * TICKS_PER_BLOCK);
        long deadline = ctx.deadline(timeout);
        return new MineBlockTaskRecord(ctx.toolCallId(), deadline, targets, clampedCount, label);
    }

    /** 消息使用的简短标签：显示第一个目标路径（例如 "iron_ore"），另有目标时附加 "+N"。 */
    // 只为消息生成短标签：一种显示名字，多种显示首个名字加剩余种类数；不影响实际目标集合。
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    public TaskRecord interactAt(
String button,
Integer x,
Integer y,
Integer z,
Integer hold_ticks,
String item_id,
String expected_block_id,
            ToolContext ctx) {
        return interactAt(button, x, y, z, hold_ticks, item_id, expected_block_id, null, ctx);
    }

    // 把鼠标键、可选坐标、按住时间和手持物品组合成任务单。坐标要么全给，要么全省略。
    public TaskRecord interactAt(String button, Integer x, Integer y, Integer z, Integer hold_ticks,
                                 String item_id, String expected_block_id, String required_block_id, ToolContext ctx) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;

        BlockPos aim = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "an aim point needs all of x, y, z (or leave all null to use the held item straight ahead).");
            }
            aim = new BlockPos(x, y, z);
        }
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        // expected 是完成后该格应变成什么；required 是出手前该格必须仍是什么。
        // 例如取水前要求水源还在，完成后再要求变成空气，前后条件各管一件事。
        Block expected = null;
        if (expected_block_id != null) {
            var id = ResourceLocation.tryParse(expected_block_id);
            if (aim == null || id == null || !BuiltInRegistries.BLOCK.containsKey(id))
                throw new IllegalArgumentException("expected_block_id needs a valid block and an explicit aim");
            expected = BuiltInRegistries.BLOCK.get(id);
        }
        Block required = null;
        if (required_block_id != null) {
            var id = ResourceLocation.tryParse(required_block_id);
            if (aim == null || id == null || !BuiltInRegistries.BLOCK.containsKey(id))
                throw new IllegalArgumentException("required_block_id needs a valid block and an explicit aim");
            required = BuiltInRegistries.BLOCK.get(id);
        }
        return new InteractAtTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_AT_TIMEOUT_TICKS),
                buttonVal, aim, holdTicks, item, expected, required);
    }

    // 实体交互只在这里保存实体编号和点击要求；能否找到它、走近和实际点到，交给执行任务判断。
    public TaskRecord interactEntity(
String button,
int entity_id,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_ENTITY_TIMEOUT_TICKS), buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id));
    }
}
