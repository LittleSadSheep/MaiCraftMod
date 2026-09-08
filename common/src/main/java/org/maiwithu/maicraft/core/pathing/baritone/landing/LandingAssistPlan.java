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
import org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall;

/** Logical native landing mechanism, never a claim that a held item has already prevented damage. */
public record LandingAssistPlan(Kind kind, BlockPos feet, BlockPos cell, BlockPos clicked,
                                Direction face, boolean existing, Vec3 hitPoint) {
    public LandingAssistPlan(Kind kind, BlockPos feet, BlockPos cell, BlockPos clicked, Direction face, boolean existing) {
        this(kind,feet,cell,clicked,face,existing,null);
    }
    public LandingAssistPlan {
        feet = feet.immutable(); cell = cell.immutable(); clicked = clicked.immutable();
    }
    public enum Kind {
        WATER(Items.WATER_BUCKET, Blocks.WATER), COBWEB(Items.COBWEB, Blocks.COBWEB),
        BERRIES(Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH),
        TWISTING_VINES(Items.TWISTING_VINES, Blocks.TWISTING_VINES),
        WEEPING_VINES(Items.WEEPING_VINES, Blocks.WEEPING_VINES), SLIME(Items.SLIME_BLOCK, Blocks.SLIME_BLOCK),
        HAY(Items.HAY_BLOCK, Blocks.HAY_BLOCK), BOAT(Items.OAK_BOAT,Blocks.AIR);
        public final Item item;
        public final Block block;
        Kind(Item item, Block block) { this.item = item; this.block = block; }
        public boolean solidSupport() { return this == SLIME || this == HAY; }
        public boolean matches(BlockState state) {
            if (this == BOAT) return false;
            if (this == WATER) return state.getFluidState().getType() instanceof net.minecraft.world.level.material.WaterFluid;
            return state.is(block) || this == TWISTING_VINES && state.is(Blocks.TWISTING_VINES_PLANT)
                    || this == WEEPING_VINES && state.is(Blocks.WEEPING_VINES_PLANT);
        }
    }

    public Vec3 aimPoint() {
        return hitPoint != null ? hitPoint : Vec3.atCenterOf(clicked).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    /** Native outline height of the face used by the bucket, including plants and partial blocks. */
    public double placementHeight(BlockGetter view) {
        if (hitPoint != null) return hitPoint.y;
        var shape = view.getBlockState(clicked).getShape(view,clicked);
        if (shape.isEmpty()) return Double.NaN;
        return clicked.getY() + switch (face) {
            case UP -> shape.max(Direction.Axis.Y);
            case DOWN -> shape.min(Direction.Axis.Y);
            default -> shape.bounds().getCenter().y;
        };
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
                if (kind==Kind.BOAT) {
                    if (LandingBoatRescue.carried(player)!=null) kinds.add(kind);
                    continue;
                }
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
                if (kind == Kind.BOAT) continue;
                if (kind == Kind.WATER ? !waterAllowed || ultraWarm : !othersAllowed) continue;
                if (kind == Kind.WATER) {
                    LandingAssistPlan water = waterPlan(view,feet,protectedCell,available.contains(Kind.WATER));
                    if (water != null) plans.add(water);
                    continue;
                }
                if (!kind.solidSupport() && kind.matches(target) && existingSafe(kind, target)) {
                    plans.add(new LandingAssistPlan(kind, feet, feet, feet.below(), Direction.UP, true));
                    continue;
                }
                if (kind.solidSupport() && kind.matches(support)) {
                    plans.add(new LandingAssistPlan(kind, feet, feet.below(), feet.below(2), Direction.UP, true));
                    continue;
                }
                if (!available.contains(kind) || !target.canBeReplaced() || protectedCell.test(feet)) continue;
                if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                    BlockPos paired = target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? feet.above() : feet.below();
                    if (view.getBlockState(paired).is(target.getBlock()) && protectedCell.test(paired)) continue;
                }
                BlockPos anchor = kind == Kind.WEEPING_VINES ? feet.above() : feet.below();
                Direction face = kind == Kind.WEEPING_VINES ? Direction.DOWN : Direction.UP;
                if (!canPlace(kind, view, feet)) continue;
                if (!target.isAir() && !target.getShape(view,feet).isEmpty()) {
                    anchor = feet; face = Direction.UP;
                }
                plans.add(new LandingAssistPlan(kind, feet, feet, anchor, face, false));
            }
            return List.copyOf(plans);
        }

        private static LandingAssistPlan waterPlan(BlockGetter view, BlockPos feet,
                Predicate<BlockPos> protectedCell, boolean carried) {
            BlockPos plantTop = WaterBucketFall.exposedWaterCell(view,feet);
            BlockPos source = plantTop;
            BlockPos clicked = source.below();
            if (WaterBucketFall.sourceWater(view.getBlockState(source)))
                return new LandingAssistPlan(Kind.WATER,feet,source,clicked,Direction.UP,true);
            var floorHit = WaterBucketFall.floorHit(view,feet,Vec3.atBottomCenterOf(feet.above(4)));
            if (floorHit != null) {
                source = WaterBucketFall.waterCell(view,floorHit,false); clicked = floorHit.getBlockPos();
            }
            if (source.equals(feet) && WaterBucketFall.acceptsWater(view,clicked)) source = clicked;
            if (WaterBucketFall.sourceWater(view.getBlockState(source)))
                return new LandingAssistPlan(Kind.WATER,feet,source,clicked,Direction.UP,true);
            if (!carried || protectedCell.test(source) || !canPlace(Kind.WATER,view,source)) return null;
            // Replacing/flowing through one half of a tall plant can remove its paired half.
            // Keep the whole observed plant column under the same terrain authorization.
            for (int y=feet.getY();source.getY() >= feet.getY() && y<plantTop.getY();y++)
                if (protectedCell.test(new BlockPos(feet.getX(),y,feet.getZ()))) return null;
            return new LandingAssistPlan(Kind.WATER,feet,source,clicked,
                    floorHit == null ? Direction.UP : floorHit.getDirection(),false,
                    floorHit == null ? null : floorHit.getLocation());
        }

        /** Carried aids come first; missing supplies remain conditional plans, never inventory evidence. */
        public List<LandingAssistPlan> automaticCandidates(BlockGetter view, BlockPos feet,
                                                          Predicate<BlockPos> protectedCell, boolean maySupply) {
            var result = new ArrayList<>(plans(view, feet, protectedCell));
            if (maySupply) {
                var potential = new InventorySnapshot(Set.of(Kind.values()), waterAllowed, othersAllowed,
                        ultraWarm, width, height);
                for (var plan : potential.plans(view, feet, protectedCell))
                    if (!result.contains(plan)) result.add(plan);
            }
            return List.copyOf(result);
        }
    }

    public static boolean existingSafe(Kind kind, BlockState state) {
        if (!kind.matches(state)) return false;
        return switch (kind) {
            case WATER -> state.getFluidState().isSource();
            case BERRIES -> state.getValue(BlockStateProperties.AGE_3) == 0;
            case COBWEB, TWISTING_VINES, WEEPING_VINES -> state.is(BlockTags.FALL_DAMAGE_RESETTING);
            case SLIME, HAY -> true; // Slime needs a safe rebound; hay needs a nonfatal damage budget.
            case BOAT -> false;
        };
    }

    public static boolean canPlace(Kind kind, BlockGetter view, BlockPos feet) {
        if (kind == Kind.BOAT) return LandingBoatRescue.plan(view,feet,.6,1.8) != null;
        if (kind == Kind.WATER) {
            if (WaterBucketFall.canWaterlog(view,feet)) return true;
            BlockState support = view.getBlockState(feet.below());
            return WaterBucketFall.replaceableByWater(view.getBlockState(feet))
                    && !WaterBucketFall.acceptsWater(view,feet.below())
                    && !support.getShape(view,feet.below()).isEmpty();
        }
        if (!view.getBlockState(feet).canBeReplaced()) return false;
        if (kind != Kind.WATER && !kind.solidSupport() && !kind.block.defaultBlockState().is(BlockTags.FALL_DAMAGE_RESETTING)) return false;
        if (view instanceof net.minecraft.world.level.LevelReader level)
            return kind.block.defaultBlockState().canSurvive(level,feet);
        // Vanilla Block.canSurvive is unconditional for web/slime/hay. BushBlock and
        // GrowingPlantBlock use these neighboring-block rules, also available in A* snapshots.
        BlockState support = view.getBlockState(feet.below());
        return switch (kind) {
            case COBWEB, SLIME, HAY -> true;
            case BERRIES -> support.is(BlockTags.DIRT) || support.is(Blocks.FARMLAND);
            case TWISTING_VINES -> support.is(Blocks.TWISTING_VINES) || support.is(Blocks.TWISTING_VINES_PLANT)
                    || support.isFaceSturdy(view,feet.below(),Direction.UP);
            case WEEPING_VINES -> view.getBlockState(feet.above()).is(Blocks.WEEPING_VINES)
                    || view.getBlockState(feet.above()).is(Blocks.WEEPING_VINES_PLANT)
                    || view.getBlockState(feet.above()).isFaceSturdy(view,feet.above(),Direction.DOWN);
            case WATER, BOAT -> throw new IllegalStateException("transport items use their native placement rules");
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
