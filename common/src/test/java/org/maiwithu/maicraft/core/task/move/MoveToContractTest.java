package org.maiwithu.maicraft.core.task.move;

import net.minecraft.core.BlockPos;

/** Complete coordinates cannot fall back to success at the bottom of a cliff. */
public final class MoveToContractTest {
    public static void main(String[] args) {
        var exact = new MoveToTaskRecord("exact", 600, -399D, 65D, 330D, null, true);
        if (!exact.requiresStrictStance()) throw new AssertionError("Full coordinates must require supported exact arrival");
        var internal = MoveToTaskRecord.strictStance("internal", 600, new BlockPos(-399, 65, 330), true);
        if (internal.kind != exact.kind || !internal.requiresStrictStance()) throw new AssertionError("Internal and public exact goals diverged");
        var column = new MoveToTaskRecord("column", 600, -399D, null, 330D, null, false);
        if (column.requiresStrictStance() || column.kind != MoveToTaskRecord.Kind.COLUMN) throw new AssertionError("Omitted height must remain a column goal");
        if (column.allowWaterBucketFall || exact.allowWaterBucketFall)
            throw new AssertionError("legacy navigation must not acquire new water permission");
        var water = (MoveToTaskRecord) new org.maiwithu.maicraft.core.tools.MovementOps().moveTo(
                -81D, 103D, -4D, null, false, true,
                new org.maiwithu.maicraft.agent.tool.api.ToolContext("water", 0));
        if (!water.allowWaterBucketFall || water.mayAlterTerrain || !water.requiresStrictStance())
            throw new AssertionError("water-only travel must retain height while keeping structural changes disabled");
        System.out.println("MoveToContractTest: passed");
    }
}
