package org.maiwithu.maicraft.core.task.move;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

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
        if (water.allowLandingAssists || exact.allowLandingAssists)
            throw new AssertionError("legacy water permission must not authorize other landing items");
        var assisted = (MoveToTaskRecord) new org.maiwithu.maicraft.core.tools.MovementOps().moveTo(
                -81D, 103D, -4D, null, false, false, "ground", true,
                new org.maiwithu.maicraft.agent.tool.api.ToolContext("landing", 0));
        if (!assisted.allowLandingAssists || assisted.mayAlterTerrain || assisted.allowWaterBucketFall)
            throw new AssertionError("landing items need their own permission, not broad terrain alteration");
        if (exact.transportMode != TransportMode.AUTO || internal.transportMode != TransportMode.AUTO
                || column.transportMode != TransportMode.AUTO || water.transportMode != TransportMode.AUTO)
            throw new AssertionError("existing movement calls retain the default automatic transport policy");
        for (TransportMode mode : TransportMode.values()) {
            var configured = (MoveToTaskRecord) new org.maiwithu.maicraft.core.tools.MovementOps().moveTo(
                    -81D, 103D, -4D, null, false, true, mode.name().toLowerCase(java.util.Locale.ROOT),
                    new org.maiwithu.maicraft.agent.tool.api.ToolContext("transport", 0));
            if (configured.transportMode != mode || !configured.allowWaterBucketFall
                    || configured.mayAlterTerrain || !configured.requiresStrictStance())
                throw new AssertionError("movement records must retain transport and independent terrain/water permissions");
            var stance = MoveToTaskRecord.strictStance("explore-leg", 600, new BlockPos(-81, 103, -4), false, mode);
            if (stance.transportMode != mode || !stance.requiresStrictStance() || stance.allowWaterBucketFall)
                throw new AssertionError("strict exploration legs retain mode without adding water permission");
        }
        try {
            new org.maiwithu.maicraft.core.tools.MovementOps().moveTo(-81D, 103D, -4D, null, false,
                    false, "teleport", new org.maiwithu.maicraft.agent.tool.api.ToolContext("invalid", 0));
            throw new AssertionError("invalid transport modes must not fall back to auto");
        } catch (IllegalArgumentException expected) {
            for (String mode : new String[]{"auto", "ground", "jetpack", "elevator"})
                if (!expected.getMessage().contains(mode)) throw new AssertionError("the error must teach every valid transport mode");
        }
        System.out.println("MoveToContractTest: passed");
    }
}
