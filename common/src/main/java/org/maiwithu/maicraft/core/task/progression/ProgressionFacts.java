// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/** Pure client-observation snapshot used to reconcile progression after every child and handoff. */
public record ProgressionFacts(
        String dimension,
        boolean strongholdFrameLoaded,
        boolean strongholdApproachVerified,
        BlockPos strongholdAnchorInternal,
        boolean activeEndPortalNearby,
        DragonState dragonState,
        boolean elytraPossessed,
        long fingerprint) {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    private static final double STRONGHOLD_ARRIVAL_DISTANCE_SQUARED = 8.0D * 8.0D;
    private static final Map<Object, Long> SHARED_DRAGON_RECEIPTS = new WeakHashMap<>();

    public enum DragonState { ALIVE, DEFEATED_CORROBORATED, UNKNOWN }

    public static ProgressionFacts observe(LocalPlayer player, BlockPos preferredStrongholdAnchor) {
        ClientLevel level = player.clientLevel;
        String dimension = level.dimension().location().toString();

        BlockPos frame = liveFrame(level, preferredStrongholdAnchor);
        if (frame == null && OVERWORLD.equals(dimension)) {
            TargetIndex.Result frames = TargetIndex.query(
                    level, player.blockPosition(), List.of(Blocks.END_PORTAL_FRAME), 8, 2, 32);
            frame = nearestLive(level, player.blockPosition(), frames.hits(), Blocks.END_PORTAL_FRAME);
        }
        boolean frameLoaded = frame != null;
        boolean arrived = OVERWORLD.equals(dimension) && frameLoaded
                && player.position().distanceToSqr(Vec3.atCenterOf(frame))
                        <= STRONGHOLD_ARRIVAL_DISTANCE_SQUARED;

        boolean activeEntryPortal = false;
        if (OVERWORLD.equals(dimension) && (arrived || frameLoaded)) {
            BlockPos center = frame == null ? player.blockPosition() : frame;
            TargetIndex.Result portals = TargetIndex.query(
                    level, center, List.of(Blocks.END_PORTAL), 1, 2, 32);
            activeEntryPortal = portals.hits().stream()
                    .anyMatch(pos -> level.isLoaded(pos)
                            && level.getBlockState(pos).is(Blocks.END_PORTAL)
                            && pos.distSqr(center) <= 24.0D * 24.0D);
        }

        boolean dragonAlive = false;
        boolean exitPortalSignature = false;
        if (END.equals(dimension)) {
            AABB arena = new AABB(
                    -256.0D, level.getMinBuildHeight(), -256.0D,
                    256.0D, level.getMaxBuildHeight(), 256.0D);
            dragonAlive = !level.getEntitiesOfClass(
                    EnderDragon.class, arena,
                    dragon -> !dragon.isRemoved() && dragon.isAlive()
                            && !dragon.isDeadOrDying()).isEmpty();
            exitPortalSignature = observeExitPortalSignature(level);
        }
        boolean sharedReceipt = SHARED_DRAGON_RECEIPTS.containsKey(player.connection);
        DragonState dragon = dragonAlive ? DragonState.ALIVE
                : END.equals(dimension) && (exitPortalSignature || sharedReceipt)
                        ? DragonState.DEFEATED_CORROBORATED : DragonState.UNKNOWN;

        boolean elytra = totalPossessed(player, Items.ELYTRA) > 0;
        long hash = 17L;
        hash = 31L * hash + dimension.hashCode();
        hash = 31L * hash + inventoryHash(player);
        hash = 31L * hash + player.blockPosition().asLong();
        hash = 31L * hash + (frameLoaded ? 1 : 0);
        hash = 31L * hash + (arrived ? 1 : 0);
        hash = 31L * hash + (activeEntryPortal ? 1 : 0);
        hash = 31L * hash + dragon.ordinal();
        hash = 31L * hash + (elytra ? 1 : 0);
        return new ProgressionFacts(
                dimension, frameLoaded, arrived,
                frame == null ? null : frame.immutable(), activeEntryPortal,
                dragon, elytra, hash);
    }

    /** Record only a typed successful dragon-fight transition on this live connection. */
    public static void markDragonTransition(LocalPlayer player) {
        SHARED_DRAGON_RECEIPTS.put(player.connection, player.level().getGameTime());
    }

    public boolean milestoneDone(ReachMilestoneTaskRecord.Milestone milestone) {
        return switch (milestone) {
            case NETHER -> NETHER.equals(dimension);
            case STRONGHOLD -> strongholdApproachVerified;
            case DEFEAT_DRAGON -> END.equals(dimension)
                    && dragonState == DragonState.DEFEATED_CORROBORATED;
            case ELYTRA -> elytraPossessed;
        };
    }

    public String completionFact(ReachMilestoneTaskRecord.Milestone milestone) {
        return switch (milestone) {
            case NETHER -> "current_dimension_verified";
            case STRONGHOLD -> "portal_frame_arrival_verified";
            case DEFEAT_DRAGON -> "dragon_defeat_corroborated";
            case ELYTRA -> "elytra_possession_verified";
        };
    }

    public int mainInventoryCount(LocalPlayer player, List<ResourceLocation> alternatives) {
        int total = 0;
        for (ResourceLocation id : alternatives) {
            Item item = BuiltInRegistries.ITEM.get(id);
            total += PlayerInv.buildableCount(player.getInventory(), item);
        }
        return total;
    }

    public boolean requirementSatisfied(
            LocalPlayer player, ProgressionRequirementProfile.Requirement requirement) {
        if (requirement.equipSlot() != null) {
            ItemStack equipped = player.getItemBySlot(requirement.equipSlot());
            if (!equipped.isEmpty()) {
                ResourceLocation equippedId = BuiltInRegistries.ITEM.getKey(equipped.getItem());
                if (requirement.alternatives().contains(equippedId)) return true;
            }
            return false;
        }
        return mainInventoryCount(player, requirement.alternatives()) >= requirement.finalCount();
    }

    public Item carriedAlternative(
            LocalPlayer player, ProgressionRequirementProfile.Requirement requirement) {
        for (ResourceLocation id : requirement.alternatives()) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (PlayerInv.buildableCount(player.getInventory(), item) > 0) return item;
        }
        return Items.AIR;
    }

    private static BlockPos liveFrame(ClientLevel level, BlockPos candidate) {
        return candidate != null && level.isLoaded(candidate)
                && level.getBlockState(candidate).is(Blocks.END_PORTAL_FRAME)
                        ? candidate.immutable() : null;
    }

    private static BlockPos nearestLive(
            ClientLevel level, BlockPos origin, List<BlockPos> candidates,
            net.minecraft.world.level.block.Block expected) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            if (!level.isLoaded(candidate) || !level.getBlockState(candidate).is(expected)) continue;
            double distance = candidate.distSqr(origin);
            if (distance < bestDistance) {
                best = candidate.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Static recovery evidence: a loaded central End portal plus its bedrock fountain signature. */
    private static boolean observeExitPortalSignature(ClientLevel level) {
        BlockPos center = new BlockPos(0, 64, 0);
        if (!level.isLoaded(center)) return false;
        TargetIndex.Result portals = TargetIndex.query(
                level, center, List.of(Blocks.END_PORTAL), 12, 1, 24);
        for (BlockPos portal : portals.hits()) {
            if (!level.isLoaded(portal) || !level.getBlockState(portal).is(Blocks.END_PORTAL)
                    || Math.abs(portal.getX()) > 8 || Math.abs(portal.getZ()) > 8) continue;
            int bedrock = 0;
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos sample = portal.offset(dx, dy, dz);
                        if (level.isLoaded(sample) && level.getBlockState(sample).is(Blocks.BEDROCK)) {
                            bedrock++;
                            if (bedrock >= 4) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static int totalPossessed(LocalPlayer player, Item item) {
        int total = PlayerInv.buildableCount(player.getInventory(), item);
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.is(item)) total += offhand.getCount();
        for (EquipmentSlot slot : List.of(
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static long inventoryHash(LocalPlayer player) {
        long hash = 1L;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            hash = 31L * hash + BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
            hash = 31L * hash + stack.getCount();
        }
        hash = 31L * hash + stackHash(player.getOffhandItem());
        for (EquipmentSlot slot : List.of(
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            hash = 31L * hash + stackHash(player.getItemBySlot(slot));
        }
        return hash;
    }

    private static int stackHash(ItemStack stack) {
        return 31 * BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode() + stack.getCount();
    }
}
