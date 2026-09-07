package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Logical native landing mechanism, never a claim that a held item has already prevented damage. */
public record LandingAssistPlan(Kind kind, BlockPos feet, BlockPos cell, BlockPos clicked,
                                Direction face, boolean existing) {
    public LandingAssistPlan {
        feet = feet.immutable(); cell = cell.immutable(); clicked = clicked.immutable();
    }
    public enum Kind {
        WATER(Items.WATER_BUCKET, Blocks.WATER), COBWEB(Items.COBWEB, Blocks.COBWEB),
        BERRIES(Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH),
        TWISTING_VINES(Items.TWISTING_VINES, Blocks.TWISTING_VINES),
        WEEPING_VINES(Items.WEEPING_VINES, Blocks.WEEPING_VINES), SLIME(Items.SLIME_BLOCK, Blocks.SLIME_BLOCK),
        HAY(Items.HAY_BLOCK, Blocks.HAY_BLOCK);
        public final Item item;
        public final Block block;
        Kind(Item item, Block block) { this.item = item; this.block = block; }
        public boolean solidSupport() { return this == SLIME || this == HAY; }
        public boolean matches(BlockState state) {
            return state.is(block) || this == TWISTING_VINES && state.is(Blocks.TWISTING_VINES_PLANT)
                    || this == WEEPING_VINES && state.is(Blocks.WEEPING_VINES_PLANT);
        }
    }

    public Vec3 aimPoint() {
        return Vec3.atCenterOf(clicked).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    public record InventorySnapshot(Set<Kind> available, boolean waterAllowed, boolean othersAllowed, boolean ultraWarm,
                                    double width, double height) {
        public InventorySnapshot { available = Set.copyOf(available); }
        public InventorySnapshot(Set<Kind> available, boolean waterAllowed, boolean othersAllowed, boolean ultraWarm) {
            this(available, waterAllowed, othersAllowed, ultraWarm, 0.6, 1.8);
        }
        public static InventorySnapshot capture(LocalPlayer player, TerrainPermit permit, boolean ultraWarm) {
            var kinds = java.util.EnumSet.noneOf(Kind.class);
            for (Kind kind : Kind.values()) {
                if (player.getOffhandItem().is(kind.item)) kinds.add(kind);
                for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
                    if (player.getInventory().getItem(slot).is(kind.item)) { kinds.add(kind); break; }
                }
            }
            return new InventorySnapshot(kinds, permit.mayUseWaterBucket(), permit.mayUseLandingAssists(), ultraWarm,
                    player.getBbWidth(), Math.max(1.8, player.getBbHeight()));
        }

        public List<LandingAssistPlan> plans(BlockGetter view, BlockPos feet, Predicate<BlockPos> protectedCell) {
            List<LandingAssistPlan> plans = new ArrayList<>();
            BlockState target = view.getBlockState(feet);
            BlockState support = view.getBlockState(feet.below());
            for (Kind kind : Kind.values()) {
                if (kind == Kind.WATER ? !waterAllowed || ultraWarm : !othersAllowed) continue;
                if (!kind.solidSupport() && kind.matches(target) && existingSafe(kind, target)) {
                    plans.add(new LandingAssistPlan(kind, feet, feet, feet.below(), Direction.UP, true));
                    continue;
                }
                if (kind.solidSupport() && kind.matches(support)) {
                    plans.add(new LandingAssistPlan(kind, feet, feet.below(), feet.below(2), Direction.UP, true));
                    continue;
                }
                if (!available.contains(kind) || !target.isAir() || protectedCell.test(feet)) continue;
                BlockPos anchor = kind == Kind.WEEPING_VINES ? feet.above() : feet.below();
                Direction face = kind == Kind.WEEPING_VINES ? Direction.DOWN : Direction.UP;
                if (!canPlace(kind, view, feet)) continue;
                plans.add(new LandingAssistPlan(kind, feet, feet, anchor, face, false));
            }
            return List.copyOf(plans);
        }
    }

    public static boolean existingSafe(Kind kind, BlockState state) {
        if (!kind.matches(state)) return false;
        return switch (kind) {
            case WATER -> state.getFluidState().isSource();
            case BERRIES -> state.getValue(BlockStateProperties.AGE_3) == 0;
            case COBWEB, TWISTING_VINES, WEEPING_VINES -> state.is(BlockTags.FALL_DAMAGE_RESETTING);
            case SLIME, HAY -> true; // Slime needs a safe rebound; hay needs a nonfatal damage budget.
        };
    }

    public static boolean canPlace(Kind kind, BlockGetter view, BlockPos feet) {
        if (!view.getBlockState(feet).isAir()) return false;
        if (kind != Kind.WATER && !kind.solidSupport() && !kind.block.defaultBlockState().is(BlockTags.FALL_DAMAGE_RESETTING)) return false;
        BlockState support = view.getBlockState(feet.below());
        if (!support.getFluidState().isEmpty()) return false;
        return switch (kind) {
            case WATER -> org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall.canPlace(
                    view.getBlockState(feet), support, false)
                    && !support.getCollisionShape(view, feet.below()).isEmpty();
            case BERRIES -> (support.is(BlockTags.DIRT) || support.is(Blocks.FARMLAND));
            case TWISTING_VINES -> support.is(Blocks.TWISTING_VINES) || support.is(Blocks.TWISTING_VINES_PLANT)
                    || support.isFaceSturdy(view, feet.below(), Direction.UP);
            // A solid ceiling one cell above this landing obstructs the player's full body.
            // Extending an existing hanging vine has a native upward anchor without that collision.
            case WEEPING_VINES -> view.getBlockState(feet.above()).is(Blocks.WEEPING_VINES)
                    || view.getBlockState(feet.above()).is(Blocks.WEEPING_VINES_PLANT);
            case COBWEB, SLIME, HAY -> support.isFaceSturdy(view, feet.below(), Direction.UP);
        };
    }

    public boolean survives(org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget budget,
                            double playerY, boolean includeAccumulated) {
        return kind != Kind.HAY || budget.survives(Math.max(0, playerY - cell.getY() - 1),
                org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.Landing.of(kind.block.defaultBlockState()), includeAccumulated);
    }

    public static boolean canRecover(boolean confirmedOwnPlacement, BlockState recorded,
                                     BlockState current, boolean protectedCell) {
        return confirmedOwnPlacement && recorded != null && !recorded.isAir()
                && !protectedCell && recorded.equals(current);
    }
}
