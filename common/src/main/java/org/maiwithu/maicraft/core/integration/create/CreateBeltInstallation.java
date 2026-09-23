// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.machine.MachineAssemblyDocument;
import org.maiwithu.maicraft.core.integration.machine.MachinePlacementRules;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineNativeInstallation;
import org.maiwithu.maicraft.task.TaskRecord;

/** 作者指定轴与跨度，安装器只把这些准备轴连接成同一条原生带，绝不添加产品工作站。 */
public final class CreateBeltInstallation implements MachineNativeInstallation {
    private final CreateBeltGeometry.Span span;
    private final Set<BlockPos> pulleys;
    private final Map<BlockPos, BlockState> preparation, targets;
    public CreateBeltInstallation(BlockPos anchor, CreateBeltGeometry.Span relative, Map<BlockPos, JsonObject> declared) {
        try {
            span = CreateBeltGeometry.between(anchor.offset(relative.first()), anchor.offset(relative.second()),
                    relative.axis(), CreateBeltAccess.maximumLength());
        } catch (RuntimeException unavailable) { throw new IllegalArgumentException("native_belt_installation_unavailable: " + unavailable.getMessage(), unavailable); }
        Set<BlockPos> shafts = new LinkedHashSet<>(); Map<BlockPos, BlockState> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (BlockPos local : relative.cells()) {
            BlockPos at = anchor.offset(local); var block = declared.get(local);
            boolean shaft = block != null && block.get("block_id").getAsString().equals("create:shaft");
            if (shaft) shafts.add(at);
            BlockState initial = shaft ? MachinePlacementRules.resolveState("create:shaft", MachineAssemblyDocument.properties(block)) : Blocks.AIR.defaultBlockState();
            if (!initial.getFluidState().isEmpty()) throw new IllegalArgumentException("native_belt_requires_dry_prepared_span");
            before.put(at, initial);
            after.put(at, MachinePlacementRules.resolveState("create:belt", span.properties(at, shaft)));
        }
        pulleys = Set.copyOf(shafts); preparation = Map.copyOf(before); targets = Map.copyOf(after);
    }
    @Override public Map<BlockPos, BlockState> preparation() { return preparation; }
    @Override public Map<BlockPos, BlockState> targets() { return targets; }
    @Override public Map<ResourceLocation, Integer> materials() { return Map.of(CreateBeltAccess.ITEM, 1); }
    @Override public Map<ResourceLocation, Integer> materials(Level world) {
        var missing = CreateBeltAccess.pulleysToAdd(world, span, pulleys);
        return missing == null ? materials() : missing.isEmpty() ? Map.of() : Map.of(CreateBeltAccess.SHAFT, missing.size());
    }
    @Override public boolean reusesPreparation(Level world) { return CreateBeltAccess.pulleysToAdd(world, span, pulleys) != null; }
    @Override public Set<BlockPos> mutationPositions(Level world) {
        var missing = CreateBeltAccess.pulleysToAdd(world, span, pulleys); return missing == null ? targets.keySet() : missing;
    }
    @Override public boolean matches(Level world) { return CreateBeltAccess.matches(world, span, pulleys); }
    @Override public TaskRecord task(String id, long deadline, List<BlockPos> installation, List<String> protectedLabels) {
        return new CreateBeltInstallTaskRecord(id, deadline, span, pulleys, installation, protectedLabels);
    }
}
