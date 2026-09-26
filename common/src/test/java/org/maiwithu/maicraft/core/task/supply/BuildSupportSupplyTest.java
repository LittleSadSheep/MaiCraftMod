package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTemporarySupportMaterials.SupplyNeed;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 使用明确注入的库存与子任务回执检查供料编排；不把夹具拿到材料当作真实网络或挖掘成功。 */
public final class BuildSupportSupplyTest {
    private static final List<SupplyNeed> NEEDS = List.of(new SupplyNeed(Items.DIRT, 2), new SupplyNeed(Items.COBBLESTONE, 2));
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); SemanticAcquireTaskRecord.ensureRegistered();
        Field field = TaskFactory.class.getDeclaredField("RUNNERS"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field.get(null);
        var before = runners.get(SemanticAcquireTaskRecord.class);
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 4.5)); h.player.setOnGround(true);
            var requested = new ArrayList<SemanticAcquireTaskRecord>();
            TaskFactory.register(SemanticAcquireTaskRecord.class, (player, record) -> {
                requested.add(record);
                return stub(record.itemIds.getFirst().getPath().equals("cobblestone")
                        ? TaskResult.ok("fixture stock") : TaskResult.fail("no dirt stock"));
            });
            // 两种各一块不能凑成两格支撑；泥土现货失败后必须继续查圆石，不能中途转去采泥土。
            h.inventory.setItem(0, new ItemStack(Items.DIRT)); h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE));
            var supply = new BuildSupportSupply(h.player, owner(List.of()), NEEDS);
            check(supply.tick(h.player, task -> task.tick(h.player)) == TaskState.RUNNING, "mixed single blocks cannot finish a uniform chain");
            supply.tick(h.player, task -> task.tick(h.player));
            check(requested.size() == 2 && requested.get(1).itemIds.getFirst().getPath().equals("cobblestone")
                    && requested.stream().allMatch(r -> r.allowedSources.equals(List.of(Source.INVENTORY, Source.WIRELESS))),
                    "all alternatives use in-place stock before any mine, craft or storage journey");
            h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 2));
            check(supply.tick(h.player, task -> task.tick(h.player)) == TaskState.SUCCESS && supply.fulfilled().item() == Items.COBBLESTONE,
                    "confirmed cobblestone stock returns the actual chosen support material");

            // 仅背包约束即使同时传入仓储来源也不能放宽；回执未知时也不能擅自尝试另一种材料。
            h.inventory.clearContent(); requested.clear();
            var carriedOnly = owner(List.of(Source.STORAGE));
            carriedOnly = new SemanticBuildSupplyTaskRecord("inventory-only", 2000, carriedOnly.plan,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY, carriedOnly.allowedSources, false, List.of(), false);
            check(new BuildSupportSupply(h.player, carriedOnly, NEEDS).tick(h.player, task -> task.tick(h.player)) == TaskState.FAILED
                    && requested.isEmpty(), "inventory-only support cannot open the network");
            TaskFactory.register(SemanticAcquireTaskRecord.class, (player, record) -> stub(TaskResult.fail("unconfirmed", Map.of("outcome_uncertain", true))));
            var uncertain = new BuildSupportSupply(h.player, owner(List.of()), NEEDS);
            uncertain.tick(h.player, task -> task.tick(h.player));
            check(uncertain.tick(h.player, task -> task.tick(h.player)) == TaskState.FAILED
                    && Boolean.TRUE.equals(uncertain.receipt().get("outcome_uncertain")), "unknown native transfer must stop alternatives");

            // 只允许眼前这一格采收；保护格、远处泥土、深坑和铁矿都不能成为临时支撑的递归取材入口。
            BlockPos dirt = new BlockPos(6, 1, 4); h.set(dirt, Blocks.DIRT.defaultBlockState());
            var local = new BuildSupportSupply(h.player, owner(List.of(Source.MINE)), List.of(new SupplyNeed(Items.DIRT, 2)));
            check(local.nearOrigin(new BlockPos(10, 1, 4)) && !local.nearOrigin(new BlockPos(11, 1, 4))
                    && !local.nearOrigin(new BlockPos(4, -1, 4)), "work area stays bounded horizontally and vertically");
            check(BuildSupportSupply.localOutputs(Blocks.IRON_ORE.defaultBlockState()).isEmpty(), "ore cannot trigger a support expedition");
            check(NavigationSafetyContext.withProtectedArea(List.of(dirt), List.of(),
                    () -> local.tick(h.player, task -> task.tick(h.player))) == TaskState.FAILED, "protected dirt cannot be harvested");
            h.set(dirt.east(), Blocks.WATER.defaultBlockState());
            check(new BuildSupportSupply(h.player, owner(List.of(Source.MINE)), List.of(new SupplyNeed(Items.DIRT, 2)))
                    .tick(h.player, task -> task.tick(h.player)) == TaskState.FAILED, "temporary support collection cannot open a fluid boundary");
            h.set(dirt.east(), Blocks.AIR.defaultBlockState());
            var available = new BuildSupportSupply(h.player, owner(List.of(Source.MINE)), List.of(new SupplyNeed(Items.DIRT, 2)));
            check(available.tick(h.player, task -> task.tick(h.player)) == TaskState.RUNNING, "visible local dirt should create one native harvest");
            Field record = BuildSupportSupply.class.getDeclaredField("record"); record.setAccessible(true);
            var harvest = (MineBlockTaskRecord) record.get(available);
            check(harvest.exactHarvest() && harvest.searchCenter().equals(dirt) && harvest.count == 1
                    && !harvest.inSearchScope(dirt.below()), "fallback cannot expand to a quarry or underground source");
            available.cancel(h.player);
            parentResumesWithTheActualAlternative(h);
        } finally { if (before == null) runners.remove(SemanticAcquireTaskRecord.class); else runners.put(SemanticAcquireTaskRecord.class, before); }
        System.out.println("BuildSupportSupplyTest: passed");
    }

    // 使用实际施工父任务核对回执接线：补到圆石后跳过清包，原建筑材料账仍然保留。
    private static void parentResumesWithTheActualAlternative(InteractionWorldTestHarness h) throws Exception {
        h.inventory.clearContent();
        var parent = new SemanticBuildSupplyCompanionTask(h.player, owner(List.of()), (r, plan) -> Decision.DISABLED);
        var request = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("requestSupportSupply", TaskResult.class);
        request.setAccessible(true);
        check(Boolean.TRUE.equals(request.invoke(parent, TaskResult.fail("supports missing", Map.of("temporary_support_demand",
                Map.of("item_id", "minecraft:dirt", "required_final_count", 2, "support_blocks", 2))))),
                "the parent must accept the shortage without fixing supply to the suggested dirt");
        check(parent.progress().get("phase").equals("temporary_support_supply"), "support preparation must precede normal cargo and supply");
        h.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 2)); h.inventory.setItem(1, new ItemStack(Items.STONE));
        var tick = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("tickSupportSupply"); tick.setAccessible(true);
        check(tick.invoke(parent) == TaskState.RUNNING, "confirmed support stock resumes the still-incomplete building");
        Field selected = SemanticBuildSupplyCompanionTask.class.getDeclaredField("supportItem"); selected.setAccessible(true);
        Field cargo = SemanticBuildSupplyCompanionTask.class.getDeclaredField("cargoCheckPending"); cargo.setAccessible(true);
        check(selected.get(parent) == Items.COBBLESTONE && !cargo.getBoolean(parent), "do not deposit the newly fetched supports before construction");
        var next = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("nextNeed"); next.setAccessible(true);
        check(next.invoke(parent) == null && h.inventory.getItem(1).getCount() == 1,
                "carried permanent stone remains available while the support obligation clears");
    }
    private static SemanticBuildSupplyTaskRecord owner(List<Source> sources) {
        var plan = new BuildTaskRecord("support-plan", 2000, List.of(new BuildTaskRecord.Target(Blocks.STONE.defaultBlockState(),
                Items.STONE, new BlockPos(12, 1, 12), "permanent", null, null, null)), false, true);
        return new SemanticBuildSupplyTaskRecord("support-owner", 2000, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY, sources, false, List.of(), false);
    }
    private static Task stub(TaskResult result) {
        return new Task() {
            public TaskState tick(LocalPlayer p) { return result.success() ? TaskState.SUCCESS : TaskState.FAILED; }
            public void stop(LocalPlayer p, StopReason why) {}
            public String name() { return "support fixture"; }
            public TaskResult result(TaskState terminal) { return result; }
        };
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
