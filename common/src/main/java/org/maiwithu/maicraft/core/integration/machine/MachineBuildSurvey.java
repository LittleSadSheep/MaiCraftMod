// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementRules;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.integration.create.CreateProcessingCapabilities;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;

/**
 * 开工前分批检查世界边界、AE2 部件宿主和临时洞口。普通方块的替换与材料检查另交给建筑子任务。
 */
final class MachineBuildSurvey {
    record Progress(boolean complete, BlockPos needsLoad, String failure) {}
    private final MachineConstructionPlan plan;
    private final List<BlockPos> positions;
    private final Set<BlockPos> centers = new HashSet<>(), clears = new HashSet<>();
    private final Set<BlockPos> openings = new HashSet<>();
    private final List<BuildTaskRecord.Target> sealTargets;
    private int boundsIndex, partIndex;
    private int sealIndex;
    private int fluidIndex;
    private int installationIndex;
    private int processingIndex;
    private int dependencyIndex;
    private final List<BlockPos> dependentTargets;
    private final Set<BlockPos> completedInstallations = new HashSet<>();
    private final Set<BlockPos> declaredPositions = new HashSet<>();
    private Map<String, Object> failureEvidence = Map.of();

    MachineBuildSurvey(MachineConstructionPlan plan) {
        this.plan = plan; positions = plan.positions();
        dependentTargets = List.copyOf(plan.placementDependencies().keySet());
        plan.blocks().forEach(target -> declaredPositions.add(target.pos()));
        sealTargets = plan.seals().stream().flatMap(seal -> seal.targets().stream()).toList();
        plan.parts().stream().filter(part -> part.spec().side() == null).forEach(part -> centers.add(part.position()));
    }

    // 每次最多推进 128 项；需要的格子没加载时返回其位置，让外层先接近并加载，再从原进度继续。
    Progress tick(Level world) {
        int budget = 128;
        while (boundsIndex < positions.size() && budget-- > 0) {
            BlockPos at = positions.get(boundsIndex++);
            if (world.isOutsideBuildHeight(at) || !world.getWorldBorder().isWithinBounds(at))
                return new Progress(false, null, "The compiled installation crosses the world's build boundary.");
        }
        if (boundsIndex < positions.size()) return new Progress(false, null, null);
        // 整条原生结构已正确时保留它，不能为了复用准备轴目标把完成的传送带拆回轴。
        while (installationIndex < plan.installations().size() && budget-- > 0) {
            var installation = plan.installations().get(installationIndex);
            for (BlockPos at : installation.targets().keySet()) if (!world.isLoaded(at)) return new Progress(false, at, null);
            try {
                if (installation.matches(world)) completedInstallations.addAll(installation.targets().keySet());
                else if (installation.reusesPreparation(world)) {
                    // 原有带只需补中间带轮时保留整段，开工前只检查新增轴点的修改权限。
                    for (BlockPos at : installation.mutationPositions(world)) if (NavigationSafetyContext.protectsMutation(at))
                        return blocked(world, at, "native_pulley_protected", "Native pulley addition intersects a protected area.");
                    completedInstallations.addAll(installation.targets().keySet());
                } else for (BlockPos at : installation.targets().keySet()) {
                    if (NavigationSafetyContext.protectsMutation(at)) return blocked(world, at, "native_installation_protected", "Native installation intersects a protected area.");
                    if (BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).toString().equals("create:belt"))
                        return blocked(world, at, "native_belt_chain_mismatch", "An existing belt has a different native chain; inspect it before replacing any segment.");
                    if (!declaredPositions.contains(at) && !world.getBlockState(at).isAir()) {
                        // assembly 已声明整条安装路径；允许替换时先清理其中可破坏、无流体/库存的白名单方块，再执行原生连接器。
                        var state = world.getBlockState(at);
                        if (plan.replaceExisting() && ClearanceWhitelist.allows(state) && !state.hasBlockEntity()
                                && state.getFluidState().isEmpty() && state.getDestroySpeed(world, at) >= 0) {
                            MachinePlacementRules.requireModeledEffects(state.getBlock()); clears.add(at);
                        } else return blocked(world, at, "native_installation_path_occupied", "Native installation path needs authorized clearance of ordinary whitelisted obstacles.");
                    }
                }
            } catch (RuntimeException unavailable) { return new Progress(false, null, "Native installation observation unavailable: " + unavailable.getMessage()); }
            installationIndex++;
        }
        if (installationIndex < plan.installations().size()) return new Progress(false, null, null);
        // 支承在蓝图内时由编译器检查最终状态；引用已有支承时先检查现场，不能先开工再发现依赖不成立。
        while (dependencyIndex < dependentTargets.size() && budget-- > 0) {
            BlockPos at = dependentTargets.get(dependencyIndex);
            for (BlockPos support : plan.placementDependencies().get(at)) if (!plan.preview().containsKey(support)) {
                if (!world.isLoaded(support)) return new Progress(false, support, null);
                try { plan.validatePlacementDependency(world, at); }
                catch (RuntimeException unavailable) { return new Progress(false, null, unavailable.getMessage()); }
            }
            dependencyIndex++;
        }
        if (dependencyIndex < dependentTargets.size()) return new Progress(false, null, null);
        // 加工净空同样只读检查；原图没有声明拆除时，不会为了摆得下设备自动挖空附近结构。
        while (processingIndex < plan.processing().size() && budget-- > 0) {
            for (BlockPos gap : plan.processing().get(processingIndex).clearance()) {
                if (!world.isLoaded(gap)) return new Progress(false, gap, null);
                if (!declaredPositions.contains(gap)
                        && !CreateProcessingCapabilities.openProcessingSpace(world.getBlockState(gap), world, gap))
                    return blocked(world, gap, "processing_clearance_occupied", "A required processing space is occupied outside the declared blueprint targets.");
            }
            processingIndex++;
        }
        if (processingIndex < plan.processing().size()) return new Progress(false, null, null);
        // 源流体不进入普通方块任务；只把有明确替换许可的普通占用记入准备清空，已有正确源格原样保留。
        while (fluidIndex < plan.fluidTargets().size() && budget-- > 0) {
            var target = plan.fluidTargets().get(fluidIndex); BlockPos at = target.pos();
            if (!world.isLoaded(at)) return new Progress(false, at, null);
            String issue = FluidPlacementRules.preparationProblem(
                    world, at, target.desiredState(), plan.replaceExisting(), plan.replaceBlockEntities());
            if (issue != null) return blocked(world, at, "source_fluid_site_blocked", issue);
            var actual = world.getBlockState(at);
            if (!actual.isAir() && actual.getFluidState().isEmpty()) {
                MachinePlacementRules.requireModeledEffects(actual.getBlock()); clears.add(at);
            }
            fluidIndex++;
        }
        if (fluidIndex < plan.fluidTargets().size()) return new Progress(false, null, null);
        while (partIndex < plan.parts().size() && budget-- > 0) {
            var part = plan.parts().get(partIndex); BlockPos at = part.position();
            if (!world.isLoaded(at)) return new Progress(false, at, null);
            var state = world.getBlockState(at);
            boolean matches = MachineInstallation.matches(world, at, part.spec());
            if (!matches && NavigationSafetyContext.protectsMutation(at))
                return blocked(world, at, "native_part_protected", "A protected area occupies a planned native part site.");
            // 面部件所在格现在为空，但计划先装中心部件时，可以等待那个宿主生成。
            boolean futureHost = state.isAir() && part.spec().side() != null && centers.contains(at);
            if (!futureHost && !MachineInstallation.canInstall(world, at, part.spec())) {
                // 部件宿主不兼容时，只允许清除普通、可破坏、没有另一半的方块；不会按整格方块的替换选项拆掉不兼容方块实体。
                if (!state.isAir() && plan.replaceExisting() && !state.hasBlockEntity()
                        && !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && !state.hasProperty(BlockStateProperties.BED_PART) && state.getDestroySpeed(world, at) >= 0) {
                    MachinePlacementRules.requireModeledEffects(state.getBlock()); clears.add(at);
                } else return blocked(world, at, "native_part_site_incompatible", "An AE2 part site is occupied or its native slot is incompatible.");
            }
            partIndex++;
        }
        if (partIndex < plan.parts().size()) return new Progress(false, null, null);
        // 洞口为空且未受保护才记作临时入口；原本已是最终封口状态可以保留，其他占用则报告受阻。
        while (sealIndex < sealTargets.size() && budget-- > 0) {
            var target = sealTargets.get(sealIndex); BlockPos at = target.pos();
            if (!world.isLoaded(at)) return new Progress(false, at, null);
            var state = world.getBlockState(at);
            if (state.isAir() && !NavigationSafetyContext.protectsMutation(at)) openings.add(at);
            else if (!target.matches(state)) return blocked(world, at, "machine_entrance_blocked", "A temporary machine entrance is occupied or protected.");
            sealIndex++;
        }
        return new Progress(sealIndex == sealTargets.size(), null, null);
    }
    Set<BlockPos> completedInstallations() { return Set.copyOf(completedInstallations); }

    /** 只报告已经加载并检查过的第一处阻塞；保留实际方块与蓝图偏移，修订前不会暗中清障或扩大权限。 */
    private Progress blocked(Level world, BlockPos at, String code, String detail) {
        var state = world.getBlockState(at); var offset = at.subtract(plan.anchor());
        failureEvidence = Map.of("failure_code", code, "failure_position", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()),
                "blueprint_offset", List.of(offset.getX(), offset.getY(), offset.getZ()),
                "observed_block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                "observed_state", state.toString(), "declared_target", declaredPositions.contains(at),
                "protected", NavigationSafetyContext.protectsMutation(at), "world_modified", false);
        return new Progress(false, null, detail + " Observed " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " at " + at.toShortString() + " (blueprint offset " + offset.toShortString() + ").");
    }
    Map<String, Object> failureEvidence() { return failureEvidence; }

    List<BlockPos> partClears() { return new ArrayList<>(clears); }
    Set<BlockPos> openings() { return Set.copyOf(openings); }
}
