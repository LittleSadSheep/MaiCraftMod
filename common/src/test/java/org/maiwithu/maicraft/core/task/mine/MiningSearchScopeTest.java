package org.maiwithu.maicraft.core.task.mine;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
            // 上个分片未完成时立即续查，不能把一秒重查冷却重复套在每个索引分片上。
            var complete=MineCompanionTask.class.getDeclaredField("lastQueryComplete"); complete.setAccessible(true);
            var cooldown=MineCompanionTask.class.getDeclaredField("queryCooldown"); cooldown.setAccessible(true);
            var poll=MineCompanionTask.class.getDeclaredMethod("maybeQuery"); poll.setAccessible(true);
            complete.setBoolean(task,false); cooldown.setInt(task,20); h.nextTick(); poll.invoke(task);
            check(cooldown.getInt(task)==20,"unfinished indexing must call the next bounded slice without cooldown");
            complete.setBoolean(task,true); cooldown.setInt(task,20); h.nextTick(); poll.invoke(task);
            check(cooldown.getInt(task)==19,"completed queries retain the existing refresh cooldown");
            // 扫描口径要完整声明：中心、区块窗口、范围与过滤开关，known_sources 波动才有解释依据。
            @SuppressWarnings("unchecked")
            var scope = (Map<String, Object>) task.result(TaskState.CANCELLED).data().get("search_scope");
            check(scope != null && Boolean.FALSE.equals(scope.get("natural_logs_only"))
                    && scope.get("query_chunk_radius").equals(1) && scope.get("radius_blocks").equals(2)
                    && scope.get("center").equals(List.of(8, 1, 8)) && h.blockUses() == 0,
                    "read-only scanning reports its actual scope, chunk window and filter switch without mining");
            // 同一已加载区块的远角超过默认十六格：不声明小半径时应仍被完整视距索引发现。
            h.position(new Vec3(0.5, 1, 0.5));
            BlockPos corner = new BlockPos(15, 1, 15); h.set(corner, Blocks.DIRT.defaultBlockState());
            var broad = new MineCompanionTask(h.player, new MineBlockTaskRecord("loaded-view", 1000,
                    Set.of(Blocks.DIRT), 1, "dirt"));
            broad.start(h.player); finishQuery(h, broad);
            check(((List<?>) field.get(broad)).contains(corner), "已加载的远角不能被附近十六格球体裁掉");
            var broadScope = (Map<?, ?>) broad.result(TaskState.CANCELLED).data().get("search_scope");
            check("loaded_view".equals(broadScope.get("mode")) && !broadScope.containsKey("radius_blocks")
                    && Boolean.TRUE.equals(broadScope.get("loaded_chunks_only")), "回执如实说明已加载视距搜索");
        }
        System.out.println("MiningSearchScopeTest: passed");
    }
    private static void finishQuery(InteractionWorldTestHarness h, MineCompanionTask task) throws Exception {
        var query = MineCompanionTask.class.getDeclaredMethod("runQuery"); query.setAccessible(true);
        var complete = MineCompanionTask.class.getDeclaredField("lastQueryComplete"); complete.setAccessible(true);
        for (int i = 0; i < 64; i++) { h.nextTick(); query.invoke(task); if (complete.getBoolean(task)) break; }
        if (!complete.getBoolean(task)) throw new AssertionError("bounded local query did not finish");
        // 查询命中先过公平闸门（即时可见 ∨ 观察记忆），真实任务每刻在 upkeep 里结算；测试里显式驱动同一入口。
        var drainGate = MineCompanionTask.class.getDeclaredMethod("drainGate"); drainGate.setAccessible(true);
        for (int i = 0; i < 4; i++) { h.nextTick(); drainGate.invoke(task); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
