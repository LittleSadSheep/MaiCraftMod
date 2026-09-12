// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.dimension.DimensionTravelTaskRecord;
import org.maiwithu.maicraft.core.task.endgame.DragonFightTaskRecord;
import org.maiwithu.maicraft.core.task.endgame.SemanticElytraTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskRecord;

/** Constructs typed private children while propagating the milestone's safety envelope. */
public final class ProgressionChildFactory {
    private final LocalPlayer player;
    private final ReachMilestoneTaskRecord parent;
    private int serial;

    public ProgressionChildFactory(LocalPlayer player, ReachMilestoneTaskRecord parent) {
        this.player = player;
        this.parent = parent;
    }

    public SemanticAcquireTaskRecord acquire(
            ProgressionRequirementProfile.Requirement requirement) {
        List<SemanticAcquireTaskRecord.Source> sources = new ArrayList<>(
                SemanticMaterialSupplyCoordinator.resolveSources(
                        parent.materialPolicy, parent.allowedSources));
        if (!parent.mayAlterTerrain) sources.remove(SemanticAcquireTaskRecord.Source.MINE);
        boolean allowHostileHunt = requirement.hostileHuntAllowed() && parent.allowCombat;
        if (!allowHostileHunt) {
            sources.remove(SemanticAcquireTaskRecord.Source.HUNT);
        } else if (parent.allowedSources.isEmpty()
                && parent.materialPolicy
                        != SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY
                && !sources.contains(SemanticAcquireTaskRecord.Source.HUNT)) {
            // The ordinary material-supply default intentionally omits harm. Progression may add
            // hostile hunting only after the separate combat permission has actually been granted.
            sources.add(SemanticAcquireTaskRecord.Source.HUNT);
        }
        return new SemanticAcquireTaskRecord(
                callId("supply"), parent.getDeadlineGameTime(), requirement.alternatives(),
                requirement.finalCount(), sources, allowHostileHunt,
                requirement.sourceHint(), parent.protectedLabels,
                SemanticAcquireTaskRecord.MAX_RADIUS);
    }

    public EquipTaskRecord equip(
            ProgressionFacts facts, ProgressionRequirementProfile.Requirement requirement) {
        Item item = facts.carriedAlternative(player, requirement);
        if (item == Items.AIR || requirement.equipSlot() == null) return null;
        return new EquipTaskRecord(
                callId("equip"), parent.getDeadlineGameTime(), item,
                requirement.equipSlot(), "progression protective equipment");
    }

    public PhysicalStructureSearchTaskRecord stronghold() {
        return new PhysicalStructureSearchTaskRecord(
                callId("stronghold"), parent.getDeadlineGameTime(),
                "minecraft:stronghold", parent.maxSearchDistance,
                parent.mayAlterTerrain, true, parent.allowRareConsumables);
    }

    public DimensionTravelTaskRecord travel(String destinationDimension) {
        return new DimensionTravelTaskRecord(
                callId("dimension"), parent.getDeadlineGameTime(), destinationDimension,
                parent.portalSearchRadius, parent.mayAlterTerrain,
                new org.maiwithu.maicraft.core.task.dimension.PortalPreparationPolicy(parent.preparePortal,
                        parent.allowRareConsumables, parent.allowCombat, parent.maxSearchDistance,
                        parent.materialPolicy, parent.allowedSources, parent.protectedLabels));
    }

    public DragonFightTaskRecord dragonFight() {
        return new DragonFightTaskRecord(
                callId("dragon"), parent.getDeadlineGameTime(), parent.allowCombat,
                parent.mayAlterTerrain, parent.allowRareConsumables,
                parent.minimumHealth, parent.protectedLabels);
    }

    public SemanticElytraTaskRecord elytra() {
        return new SemanticElytraTaskRecord(
                callId("elytra"), parent.getDeadlineGameTime(), parent.maxSearchDistance,
                parent.mayAlterTerrain, parent.allowCombat,
                parent.allowRareConsumables, parent.protectedLabels);
    }

    /** Choose a real standable cell beside a verified portal-frame anchor. */
    public MoveToTaskRecord approachStronghold(BlockPos anchor) {
        BlockPos stance = standableNear(anchor);
        if (stance == null) return null;
        return new MoveToTaskRecord(
                callId("stronghold-approach"), parent.getDeadlineGameTime(),
                (double) stance.getX(), (double) stance.getY(), (double) stance.getZ(),
                null, parent.mayAlterTerrain);
    }

    private BlockPos standableNear(BlockPos anchor) {
        for (int radius = 1; radius <= 5; radius++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos feet = anchor.offset(dx, dy, dz);
                        BlockPos head = feet.above();
                        BlockPos support = feet.below();
                        if (!player.clientLevel.isLoaded(feet)
                                || !player.clientLevel.isLoaded(head)
                                || !player.clientLevel.isLoaded(support)) continue;
                        if (!player.clientLevel.getBlockState(feet)
                                        .getCollisionShape(player.clientLevel, feet).isEmpty()
                                || !player.clientLevel.getBlockState(head)
                                        .getCollisionShape(player.clientLevel, head).isEmpty()
                                || !player.clientLevel.getBlockState(support)
                                        .isFaceSturdy(player.clientLevel, support, Direction.UP)) {
                            continue;
                        }
                        return feet.immutable();
                    }
                }
            }
        }
        return null;
    }

    private String callId(String purpose) {
        return parent.getToolCallId() + "-internal-" + purpose + "-" + (++serial);
    }
}
