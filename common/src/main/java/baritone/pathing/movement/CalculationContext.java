/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {


    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.InventorySnapshot landingInventory;
    public final org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingSnapshot landingBoats;
    private final org.maiwithu.maicraft.core.pathing.baritone.landing.WaterLandingWindow waterLandingWindow;
    private final boolean automaticLandingSupply;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public final FallDamageBudget fallDamageBudget;
    private final BlockPos fallOrigin;
    private final double fallOriginY;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    /** Frozen task safety policy; safe for the calculation worker. */
    public final EmbeddedBaritonePolicy.Snapshot maicraftPolicy;

    /**
     * Fluid-placement settings are copied into the context for the same reason as the other
     * movement switches: a worker must see one coherent policy for its entire search. A
     * read-only terrain probe may deliberately enable these without mutating global settings.
     */
    private final boolean allowPlaceInFluidsSource;
    private final boolean allowPlaceInFluidsFlow;

    public final PrecomputedData precomputedData;
    public final CollisionGeometry collisionGeometry;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this(baritone, forUseOnAnotherThread, false, EmbeddedBaritonePolicy.snapshot());
    }

    /**
     * Make a frozen, worker-safe context used only to answer "would terrain alteration make a
     * route possible?". This does not change {@link Baritone#settings()} and does not claim the
     * player currently owns throwaway blocks or a water bucket; those are requirements reported
     * by the resulting terrain plan, not actions performed by the probe.
     *
     * <p>The caller supplies the exact protected/body-cell snapshot paired with the failed
     * preserve search. Keeping it explicit prevents a later semantic task from silently changing
     * the question while this A* is running.</p>
     */
    public static CalculationContext forTerrainProbe(
            IBaritone baritone,
            EmbeddedBaritonePolicy.Snapshot frozenPolicy) {
        return new CalculationContext(baritone, true, true,
                Objects.requireNonNull(frozenPolicy, "frozenPolicy"));
    }

    private CalculationContext(
            IBaritone baritone,
            boolean forUseOnAnotherThread,
            boolean forceTerrainMutation,
            EmbeddedBaritonePolicy.Snapshot frozenPolicy) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        LocalPlayer player = baritone.getPlayerContext().player();
        this.world = baritone.getPlayerContext().world();
        this.worldData = (WorldData) baritone.getPlayerContext().worldData();
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.collisionGeometry = new CollisionGeometry(bsi.access, forUseOnAnotherThread,
                player.position(), baritone.getPlayerContext().playerFeet(),
                org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime::physicalObstacles,
                player.getBbWidth(), player.getBbHeight());
        this.fallDamageBudget = FallDamageBudget.capture(player);
        this.fallOrigin = baritone.getPlayerContext().playerFeet().immutable();
        this.fallOriginY = player.getY();
        this.waterLandingWindow = new org.maiwithu.maicraft.core.pathing.baritone.landing.WaterLandingWindow(
                player.getAttributeValue(Attributes.GRAVITY), player.blockInteractionRange(),
                Math.max(1.62, player.getEyeHeight()), Math.max(0, -player.getDeltaMovement().y));
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = forceTerrainMutation
                || (Baritone.settings().allowPlace.value
                && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway());
        this.landingInventory = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.InventorySnapshot.capture(
                player, org.maiwithu.maicraft.core.pathing.moves.TerrainPermit.LANDING_ONLY,
                world.dimensionType().ultraWarm());
        this.automaticLandingSupply = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.automaticSupplyAllowed();
        this.landingBoats = landingInventory.othersAllowed()
                ? org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist.capture(player) : null;
        this.canSprint = Baritone.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = Baritone.settings().blockPlacementPenalty.value;
        this.allowBreak = forceTerrainMutation || Baritone.settings().allowBreak.value;
        this.allowBreakAnyway = forceTerrainMutation
                ? List.of()
                : new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = Baritone.settings().allowParkour.value;
        this.allowParkourPlace = forceTerrainMutation
                || Baritone.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = Baritone.settings().allowParkourAscend.value;
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // Vanilla applies Frost Walker from the feet slot. Keep the proven upstream movement
        // behaviour, but do not treat command-enchanted items in unrelated slots as active boots.
        int frostWalkerLevel = 0;
        ItemEnchantments bootEnchantments = baritone.getPlayerContext()
            .player()
            .getItemBySlot(EquipmentSlot.FEET)
            .getEnchantments();
        for (Holder<Enchantment> enchant : bootEnchantments.keySet()) {
            if (enchant.is(Enchantments.FROST_WALKER)) {
                frostWalkerLevel = bootEnchantments.getLevel(enchant);
            }
        }
        // A probe must account only for explicit break/place/bucket movements. Frost Walker would
        // otherwise create an implicit mutation that cannot be represented by its terrain bill.
        this.frostWalker = forceTerrainMutation
                ? 0
                : Baritone.settings().allowPlace.value ? frostWalkerLevel : 0;
        this.allowDiagonalDescend = Baritone.settings().allowDiagonalDescend.value;
        this.allowDiagonalAscend = Baritone.settings().allowDiagonalAscend.value;
        this.allowDownward = forceTerrainMutation || Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        // WATER_MOVEMENT_EFFICIENCY is the fraction of the gap from normal water speed to land
        // speed that an enchantment closes. No enchantment therefore starts at 0, not 1; using 1
        // made ordinary surface swimming look as cheap as walking and sent ground searches across
        // large bodies of water for no real travel-time benefit.
        float waterSpeedMultiplier = 0.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = Math.max(0.0f, Math.min(1.0f,
                                effect.amount().calculate(itemEnchantments.getLevel(enchant))));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        this.allowPlaceInFluidsSource = forceTerrainMutation
                || Baritone.settings().allowPlaceInFluidsSource.value;
        this.allowPlaceInFluidsFlow = forceTerrainMutation
                || Baritone.settings().allowPlaceInFluidsFlow.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
        this.maicraftPolicy = Objects.requireNonNull(frozenPolicy, "frozenPolicy");
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public List<org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan> landingPlans(BlockPos feet) {
        if (!bsi.worldContainsLoadedChunk(feet.getX(), feet.getZ())) return List.of();
        return landingInventory.automaticCandidates(bsi.access, feet,
                pos -> isPossiblyProtected(pos.getX(), pos.getY(), pos.getZ()), automaticLandingSupply).stream()
                .filter(plan -> org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistGeometry.safe(
                        bsi.access, pos -> bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()), plan,
                        landingInventory.width(), landingInventory.height(), maicraftPolicy.forbiddenBodyCells())).toList();
    }

    public List<org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan> landingPlans(BlockPos feet, int drop) {
        return landingPlans(feet).stream().filter(plan -> plan.survives(fallDamageBudget, feet.getY() + drop, true))
                .filter(plan -> plan.existing()
                || plan.kind() != org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.Kind.WATER
                || waterLandingWindow.permits(drop + 1
                        - CollisionGeometry.supportHeight(bsi.access, feet.below()))).toList();
    }

    public boolean canLandWithoutDamage(int x, int y, int z, int effectiveStartHeight,
                                       int destX, int supportY, int destZ, BlockState support) {
        return fallDamageBudget.damage(fallDistance(x, y, z, effectiveStartHeight, destX, supportY, destZ),
                FallDamageBudget.Landing.of(support), initialFall(x, y, z, effectiveStartHeight)) == 0;
    }

    public org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingSnapshot.Plan landingBoatPlan(BlockPos source, BlockPos feet) {
        if (landingBoats == null || isPossiblyProtected(feet.getX(), feet.getY(), feet.getZ())) return null;
        return landingBoats.plan(bsi.access, source, feet,
                pos -> bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()));
    }

    /** Survival estimate; route admission separately requires protecting any predicted injury. */
    public boolean canSurviveFall(int x, int y, int z, int effectiveStartHeight,
                                 int destX, int supportY, int destZ, BlockState support) {
        return fallDamageBudget.survives(fallDistance(x, y, z, effectiveStartHeight, destX, supportY, destZ),
                FallDamageBudget.Landing.of(support), initialFall(x, y, z, effectiveStartHeight));
    }

    private double fallDistance(int x, int y, int z, int effectiveStartHeight, int destX, int supportY, int destZ) {
        if (!bsi.worldContainsLoadedChunk(destX, destZ)) return Double.NaN;
        double supportHeight = CollisionGeometry.supportHeight(bsi.access, new BlockPos(destX, supportY, destZ));
        if (!Double.isFinite(supportHeight) || supportHeight <= 0) return Double.NaN;
        double distance = effectiveStartHeight - supportY - Math.min(1, supportHeight);
        if (initialFall(x, y, z, effectiveStartHeight)) distance += Math.max(0, fallOriginY - y);
        return Math.max(0, distance);
    }

    private boolean initialFall(int x, int y, int z, int effectiveStartHeight) {
        return effectiveStartHeight == y && fallOrigin.getX() == x && fallOrigin.getY() == y && fallOrigin.getZ() == z;
    }

    /** Include a fall already in progress when deciding whether a ladder can actually catch it. */
    public double initialFallDistance(int x, int y, int z) {
        return fallOrigin.getX() == x && fallOrigin.getY() == y && fallOrigin.getZ() == z
                ? fallDamageBudget.accumulatedFallDistance() + Math.max(0, fallOriginY - y) : 0;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsSource && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsFlow && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        return maicraftPolicy.protects(x, y, z);
    }

    /** A semantic parent may forbid occupying exact body cells without changing block costs. */
    public boolean isBodyCellForbidden(int x, int y, int z) {
        return maicraftPolicy.forbidsBody(x, y, z);
    }

    /** Avoid constructing movement objects in the hot A* loop when no body policy is active. */
    public boolean hasForbiddenBodyCells() {
        return !maicraftPolicy.forbiddenBodyCells().isEmpty();
    }
}
