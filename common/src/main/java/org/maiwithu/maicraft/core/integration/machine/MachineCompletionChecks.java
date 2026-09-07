// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineCommissioning;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismNativeConfiguration;

/** Final assembly assertions are re-observed after all block, part and configuration effects settle. */
final class MachineCompletionChecks {
    private MachineCompletionChecks() {}

    static boolean configurationMatches(Level world, BlockPos anchor, JsonObject configuration) {
        var position = MachineConstructionPlan.offset(anchor, configuration.get("offset"));
        var side = Direction.byName(configuration.get("face").getAsString());
        return side != null && MekanismNativeConfiguration.inspect(world, position, side,
                configuration.get("medium").getAsString()).current().equals(configuration.get("mode").getAsString());
    }

    static JsonObject inspect(Level world, BlockPos anchor, JsonObject requirement) {
        if (requirement.get("kind").getAsString().equals("mekanism_induction_matrix")) {
            return MachineCommissioning.verifyMatrix(world,
                    MachineConstructionPlan.offset(anchor, requirement.get("min_offset")),
                    MachineConstructionPlan.offset(anchor, requirement.get("max_offset")));
        }
        JsonObject unsupported = new JsonObject();
        unsupported.addProperty("status", "unsupported_commissioning_requirement");
        return unsupported;
    }

    static BlockPos regionToLoad(Level world, BlockPos anchor, JsonObject requirement) {
        if (!requirement.has("min_offset") || !requirement.has("max_offset")) return null;
        BlockPos min = MachineConstructionPlan.offset(anchor, requirement.get("min_offset"));
        BlockPos max = MachineConstructionPlan.offset(anchor, requirement.get("max_offset"));
        for (BlockPos at : BlockPos.betweenClosed(min, max)) if (!world.isLoaded(at))
            return new BlockPos(min.getX() - 2, min.getY(), (min.getZ() + max.getZ()) / 2);
        return null;
    }

    static boolean satisfied(JsonObject requirement, JsonObject evidence) {
        return requirement.get("kind").getAsString().equals("mekanism_induction_matrix")
                && evidence.has("matrix_formed_verified") && evidence.get("matrix_formed_verified").getAsBoolean();
    }
}
