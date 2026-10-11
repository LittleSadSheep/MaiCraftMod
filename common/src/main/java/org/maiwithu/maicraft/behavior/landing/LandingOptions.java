// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.world.BucketWater;

/**
 * 某一刻能用哪些落地办法：身上带着哪几种、许不许用、这个维度水会不会蒸发、身体多大。
 * 算路时在出发前取一份冻结下来，搜索全程一致；救援时每次重新取。
 *
 * @param carried         身上（背包三十六格或副手）带着的几种
 * @param allowed         许不许放东西接住自己（change_blocks 为 none 时不许；救命的反射不看任务许可）
 * @param waterEvaporates 这个维度放水会蒸发（下界）
 */
public record LandingOptions(Set<LandingAid> carried, boolean allowed, boolean waterEvaporates,
                             double width, double height) {

    public LandingOptions {
        carried = carried.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(carried));
    }

    /** 从角色身上取一份：背包三十六格与副手里有的几种。 */
    public static LandingOptions capture(LocalPlayer player, boolean allowed) {
        Set<LandingAid> kinds = EnumSet.noneOf(LandingAid.class);
        for (LandingAid aid : LandingAid.values()) {
            if (player.getOffhandItem().is(aid.item())) kinds.add(aid);
            for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
                if (player.getInventory().getItem(slot).is(aid.item())) {
                    kinds.add(aid);
                    break;
                }
            }
        }
        return new LandingOptions(kinds, allowed, player.level().dimensionType().ultraWarm(),
                player.getBbWidth(), Math.max(1.8, player.getBbHeight()));
    }

    /**
     * 落在这一格能用的办法：先列现成的（水源、蛛网、藤蔓、没长大的浆果丛、落点下面的黏液块或干草），
     * 再列身上带着、现场放得下的。水在会蒸发的维度不列；不许放东西时一样都不列（现成的也不算，那要看许可）。
     *
     * @param protectedCell 受保护、不许动的格子
     */
    public List<LandingPlan> plans(BlockGetter view, BlockPos feet, Predicate<BlockPos> protectedCell) {
        List<LandingPlan> plans = new ArrayList<>();
        if (!allowed) return plans;
        BlockState target = view.getBlockState(feet);
        BlockState support = view.getBlockState(feet.below());
        for (LandingAid aid : LandingAid.values()) {
            if (aid == LandingAid.WATER) {
                if (waterEvaporates) continue;
                LandingPlan water = waterPlan(view, feet, protectedCell, carried.contains(LandingAid.WATER));
                if (water != null) plans.add(water);
                continue;
            }
            if (!aid.raisesSupport() && aid.cushions(target)) {
                plans.add(new LandingPlan(aid, feet, feet, feet.below(), Direction.UP, true));
                continue;
            }
            if (aid.raisesSupport() && aid.matches(support)) {
                plans.add(new LandingPlan(aid, feet, feet.below(), feet.below(2), Direction.UP, true));
                continue;
            }
            LandingPlan placed = placedPlan(aid, view, feet, target, protectedCell);
            if (placed != null) plans.add(placed);
        }
        return List.copyOf(plans);
    }

    // 身上带着、落点那一格换得掉、没受保护、放得住：垂泪藤挂在上面一格的底面，其余放在下面一格的顶面；
    // 落点那一格本来有不满格的东西（草）就点它本身。高草这类两格的植物，另一半受保护也不动。
    private LandingPlan placedPlan(LandingAid aid, BlockGetter view, BlockPos feet, BlockState target,
                                   Predicate<BlockPos> protectedCell) {
        if (!carried.contains(aid) || !target.canBeReplaced() || protectedCell.test(feet)) return null;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos paired = target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? feet.above() : feet.below();
            if (view.getBlockState(paired).is(target.getBlock()) && protectedCell.test(paired)) return null;
        }
        if (!canPlace(aid, view, feet)) return null;
        BlockPos anchor = aid == LandingAid.WEEPING_VINES ? feet.above() : feet.below();
        Direction face = aid == LandingAid.WEEPING_VINES ? Direction.DOWN : Direction.UP;
        if (!target.isAir() && !target.getShape(view, feet).isEmpty()) {
            anchor = feet;
            face = Direction.UP;
        }
        return new LandingPlan(aid, feet, feet, anchor, face, false);
    }

    // 水：落点（越过会被冲掉的矮植物）已经是水源就现成用；否则找脚下真正露出来、点得到的地面，水进哪一格以原版规则为准。
    // 含水方块（半砖、楼梯）可以直接灌水。替换或流经高植物的一半会连带另一半，整根植物柱都不能受保护。
    private static LandingPlan waterPlan(BlockGetter view, BlockPos feet, Predicate<BlockPos> protectedCell,
                                         boolean carried) {
        BlockPos plantTop = BucketWater.exposedWaterCell(view, feet);
        BlockPos source = plantTop;
        BlockPos clicked = source.below();
        if (BucketWater.sourceWater(view.getBlockState(source))) {
            return new LandingPlan(LandingAid.WATER, feet, source, clicked, Direction.UP, true);
        }
        var floorHit = BucketWater.floorHit(view, feet, Vec3.atBottomCenterOf(feet.above(4)));
        if (floorHit != null) {
            source = BucketWater.waterCell(view, floorHit, false);
            clicked = floorHit.getBlockPos();
        }
        if (source.equals(feet) && BucketWater.acceptsWater(view, clicked)) source = clicked;
        if (BucketWater.sourceWater(view.getBlockState(source))) {
            return new LandingPlan(LandingAid.WATER, feet, source, clicked, Direction.UP, true);
        }
        if (!carried || protectedCell.test(source) || !canPlace(LandingAid.WATER, view, source)) return null;
        for (int y = feet.getY(); source.getY() >= feet.getY() && y < plantTop.getY(); y++) {
            if (protectedCell.test(new BlockPos(feet.getX(), y, feet.getZ()))) return null;
        }
        return new LandingPlan(LandingAid.WATER, feet, source, clicked,
                floorHit == null ? Direction.UP : floorHit.getDirection(), false,
                floorHit == null ? null : floorHit.getLocation());
    }

    /**
     * 这一格放不放得下：水要能灌进含水方块，或这一格能被水替换、下面有实心的面；
     * 其余要这一格能被替换、方块本身能清零摔落（或是黏液块、干草），并且按原版规则立得住
     * （浆果丛要长在土上，缠怨藤要往上长在实心面上，垂泪藤要挂在上面一格的底面）。
     */
    public static boolean canPlace(LandingAid aid, BlockGetter view, BlockPos feet) {
        if (aid == LandingAid.WATER) {
            if (BucketWater.canWaterlog(view, feet)) return true;
            BlockState support = view.getBlockState(feet.below());
            return BucketWater.replaceableByWater(view.getBlockState(feet))
                    && !BucketWater.acceptsWater(view, feet.below())
                    && !support.getShape(view, feet.below()).isEmpty();
        }
        if (!view.getBlockState(feet).canBeReplaced()) return false;
        if (!aid.raisesSupport() && !aid.block().defaultBlockState().is(BlockTags.FALL_DAMAGE_RESETTING)) return false;
        if (view instanceof LevelReader level) return aid.block().defaultBlockState().canSurvive(level, feet);
        // 算路线程上的快照不是 LevelReader：按原版这几种方块各自的立足规则判断。
        BlockState support = view.getBlockState(feet.below());
        BlockState above = view.getBlockState(feet.above());
        return switch (aid) {
            case COBWEB, SLIME, HAY -> true;
            case SWEET_BERRIES -> support.is(BlockTags.DIRT) || support.is(Blocks.FARMLAND);
            case TWISTING_VINES -> support.is(Blocks.TWISTING_VINES) || support.is(Blocks.TWISTING_VINES_PLANT)
                    || support.isFaceSturdy(view, feet.below(), Direction.UP);
            case WEEPING_VINES -> above.is(Blocks.WEEPING_VINES) || above.is(Blocks.WEEPING_VINES_PLANT)
                    || above.isFaceSturdy(view, feet.above(), Direction.DOWN);
            case WATER -> false;
        };
    }
}
