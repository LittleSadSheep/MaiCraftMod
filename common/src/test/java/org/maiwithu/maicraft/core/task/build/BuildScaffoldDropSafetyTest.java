// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

public final class BuildScaffoldDropSafetyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos scaffold = new BlockPos(8, 4, 8), intake = new BlockPos(8, 1, 8), barrier = intake.above();
            Map<BlockPos, BlockState> planned = new HashMap<>();
            check(check(h, planned, scaffold) == null, "ordinary permanent ground outside intakes is safe");
            h.set(intake, Blocks.HOPPER.defaultBlockState());
            check(check(h, planned, scaffold) != null, "an existing hopper must be checked before collision shielding");
            h.set(intake, Blocks.AIR.defaultBlockState()); planned.put(intake, Blocks.BARREL.defaultBlockState());
            check(check(h, planned, scaffold) != null, "planned block entities matter before they are installed");
            planned.put(barrier, Blocks.STONE.defaultBlockState());
            check(check(h, planned, scaffold) != null, "an unbuilt planned floor cannot promise to catch drops");
            h.set(barrier, Blocks.STONE.defaultBlockState());
            check(check(h, planned, scaffold) == null, "a real retained full floor shields a lower intake");
            check(BuildScaffoldDropSafety.check(h.level, h.level::isLoaded, planned::get,
                    barrier::equals, scaffold) != null, "a temporary floor will itself be removed and cannot shield cleanup");
            planned.put(barrier, Blocks.AIR.defaultBlockState());
            check(check(h, planned, scaffold) != null, "a full floor scheduled for removal cannot shield cleanup");
            h.set(barrier, Blocks.AIR.defaultBlockState()); planned.clear();
            planned.put(scaffold.east(), Blocks.BARREL.defaultBlockState());
            check(check(h, planned, scaffold) != null, "same-height nearby intakes can receive freshly spawned drops");
            planned.clear(); planned.put(scaffold.east(3), Blocks.BARREL.defaultBlockState());
            check(check(h, planned, scaffold) == null, "positions outside the bounded lateral margin remain available");
            check(h.blockUses() == 0 && h.itemUses() == 0, "debris checks never operate on inventories or the world");
        }
        actualBuilderRejectsUnsafeSupport();
        System.out.println("BuildScaffoldDropSafetyTest: current/planned intakes, real barriers and builder rejection passed");
    }

    private static BuildScaffoldDropSafety.Risk check(InteractionWorldTestHarness h, Map<BlockPos, BlockState> planned, BlockPos at) {
        return BuildScaffoldDropSafety.check(h.level, h.level::isLoaded, planned::get, ignored -> false, at);
    }

    private static void actualBuilderRejectsUnsafeSupport() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(8, 3, 8), "raised machine", null, null, null);
            var intake = new BuildTaskRecord.Target(Blocks.HOPPER, Items.HOPPER, new BlockPos(8, 1, 8), "planned intake", null, null, null);
            var record = new BuildTaskRecord("debris", 1000, List.of(target, intake), false);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            check(!task.permitsTemporaryScaffold(target.pos().below()), "the real navigation provider rejects debris above planned intake");
            check(task.permitsTemporaryScaffold(new BlockPos(12, 1, 8)), "a real alternative outside the intake margin remains allowed");
            Class<?> cell = Class.forName(task.getClass().getName() + "$CellPlan");
            var constructor = cell.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            var field = task.getClass().getDeclaredField("cell"); field.setAccessible(true); field.set(task, constructor.newInstance(target, List.of()));
            var prepare = task.getClass().getDeclaredMethod("prepareTemporarySupports"); prepare.setAccessible(true);
            check(prepare.invoke(task) == TaskState.FAILED, "a target with no safe adjacent support must report the obstruction");
            check(task.resultData().get("failure_code").equals("temporary_support_drop_risk")
                            && task.resultData().containsKey("scaffold_drop_risk"),
                    "support exhaustion must retain a concrete debris exclusion reason");
            check(record.scaffoldLedger().isEmpty() && h.blockUses() == 0, "rejection grants no placement or cleanup ownership");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
