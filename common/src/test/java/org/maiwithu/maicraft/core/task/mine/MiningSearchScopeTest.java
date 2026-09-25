package org.maiwithu.maicraft.core.task.mine;

import java.util.List;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 用真实索引扫描附近两块泥土，核对范围外目标不会被选中，移动身体也不会扩大取材圈。 */
public final class MiningSearchScopeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            // 真实采矿会按药水效果估算工具效率；这个未走实体构造器的夹具需要明确初始化为空效果表。
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true);
            effects.set(h.player, new HashMap<>());
            h.position(new Vec3(8.5, 1, 8.5)); h.inventory.setItem(0, new ItemStack(Items.DIAMOND_SHOVEL));
            BlockPos near = new BlockPos(9, 1, 8), far = new BlockPos(12, 1, 8);
            h.set(near, Blocks.DIRT.defaultBlockState()); h.set(far, Blocks.DIRT.defaultBlockState());
            var record = new MineBlockTaskRecord("local-source", 1000, Set.of(Blocks.DIRT), 1, "dirt")
                    .withinRadius(h.player.blockPosition(), 2);
            check(record.queryChunkRadius(32) == 1, "block radius limits the index chunk window");
            var task = new MineCompanionTask(h.player, record); task.start(h.player); finishQuery(h, task);
            var field = MineCompanionTask.class.getDeclaredField("knownOres"); field.setAccessible(true);
            check(((List<?>) field.get(task)).contains(near) && !((List<?>) field.get(task)).contains(far),
                    "nearby indexing excludes the out-of-scope source");
            // 角色已经走近圈外泥土，查询仍围绕接单时的中心，不能藉移动逐次扩张原范围。
            h.position(new Vec3(11.5, 1, 8.5)); h.nextTick(); finishQuery(h, task);
            check(!((List<?>) field.get(task)).contains(far) && record.searchCenter().equals(new BlockPos(8, 1, 8)),
                    "moving the player does not widen the frozen mining scope");
            check(task.result(TaskState.CANCELLED).data().containsKey("search_scope") && h.blockUses() == 0,
                    "read-only scanning reports its actual scope without mining");
        }
        System.out.println("MiningSearchScopeTest: passed");
    }
    private static void finishQuery(InteractionWorldTestHarness h, MineCompanionTask task) throws Exception {
        var query = MineCompanionTask.class.getDeclaredMethod("runQuery"); query.setAccessible(true);
        var complete = MineCompanionTask.class.getDeclaredField("lastQueryComplete"); complete.setAccessible(true);
        for (int i = 0; i < 64; i++) { h.nextTick(); query.invoke(task); if (complete.getBoolean(task)) return; }
        throw new AssertionError("bounded local query did not finish");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
