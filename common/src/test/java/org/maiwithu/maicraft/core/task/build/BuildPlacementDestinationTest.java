package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** The native slab replacement position is part of a placement proof, not just its block state. */
public final class BuildPlacementDestinationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(3.12, 1, 4.8));
            var support = new BlockPos(4, 1, 4);
            var bottom = Blocks.OAK_SLAB.defaultBlockState();
            var full = bottom.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
            h.set(support, bottom);
            var above = new BuildTaskRecord.Target(full, Items.OAK_SLAB,
                    support.above(), "double slab above existing half", null, null, null);
            var topHit = new BlockHitResult(new Vec3(4.5, 1.5, 4.5), Direction.UP, support, false);
            check(BuildPlacementGeometry.predict(h.player, above, topHit, 0, 30) == null,
                    "merging the support cannot prove a double slab at the adjacent target");
            check(BuildPlacementGeometry.currentGesture(h.player, above, Map.of()) == null,
                    "current-position shortcut must discard every hit that merges the support");
            check(!BuildPlacementGeometry.hasAnyGesture(h.player, above, Map.of()),
                    "stance enumeration cannot certify the right state in the wrong cell");
            check(!BuildPlacementGeometry.hasAnyGesture(h.player, above.asItemPlace(), Map.of()),
                    "relaxed state matching must still require the actual placement destination");

            var merge = new BuildTaskRecord.Target(full, Items.OAK_SLAB,
                    support, "complete existing half", null, null, null);
            check(full.equals(BuildPlacementGeometry.predict(h.player, merge, topHit, 0, 30)),
                    "a requested merge at the clicked cell remains valid");
            var beside = new BuildTaskRecord.Target(bottom, Items.OAK_SLAB,
                    support.south(), "neighboring lower half", null, null, null);
            var sideHit = new BlockHitResult(new Vec3(4.5, 1.25, 5), Direction.SOUTH, support, false);
            check(bottom.equals(BuildPlacementGeometry.predict(h.player, beside, sideHit, 0, 30)),
                    "a lower-side click that places beside the half slab remains valid");
            h.set(above.pos().east(), Blocks.STONE.defaultBlockState());
            check(BuildPlacementGeometry.currentGesture(h.player, above, Map.of()) != null,
                    "a different real support face can still begin the target double slab");
            var solidSupport = new BlockPos(6, 1, 4);
            h.set(solidSupport, Blocks.STONE.defaultBlockState());
            var sideTarget = new BuildTaskRecord.Target(bottom, Items.OAK_SLAB,
                    solidSupport.south(), "side hit height threshold", null, null, null);
            var centerHit = new BlockHitResult(new Vec3(6.5, 1.5, 5), Direction.SOUTH, solidSupport, false);
            var aboveCenter = new BlockHitResult(new Vec3(6.5, 1.500001, 5), Direction.SOUTH, solidSupport, false);
            check(bottom.equals(BuildPlacementGeometry.predict(h.player, sideTarget, centerHit, 0, 30)),
                    "an exact mid-plane side hit predicts a bottom slab");
            check(!sideTarget.acceptsPlacedState(BuildPlacementGeometry.predict(h.player, sideTarget, aboveCenter, 0, 30)),
                    "microscopic ray error above that same mid-plane instead predicts the wrong top slab");
            var sidePoints = BuildPlacementGeometry.facePoints(solidSupport,
                    Blocks.STONE.defaultBlockState().getShape(h.level, solidSupport), Direction.SOUTH);
            check(sidePoints.stream().allMatch(point -> Math.abs(point.y - 1.5) >= .125),
                    "horizontal face samples must leave margin around the placement height threshold");
            check(sidePoints.stream().anyMatch(point -> point.y < 1.5)
                            && sidePoints.stream().anyMatch(point -> point.y > 1.5),
                    "stable side sampling must retain both upper and lower placement bands");
            var candlePos = new BlockPos(8, 1, 4);
            var oneCandle = Blocks.CANDLE.defaultBlockState();
            var twoCandles = oneCandle.setValue(BlockStateProperties.CANDLES, 2);
            h.set(candlePos, oneCandle);
            var candleHit = new BlockHitResult(new Vec3(8.5, 1.3, 4.5), Direction.UP, candlePos, false);
            var anotherCandle = new BuildTaskRecord.Target(oneCandle, Items.CANDLE,
                    candlePos.above(), "a separate candle", null, null, null);
            check(BuildPlacementGeometry.predict(h.player, anotherCandle, candleHit, 0, 30) == null,
                    "aggregating an existing candle cannot place a separate neighboring candle");
            var candleGesture = new BuildPlacementGeometry.Gesture(h.player.blockPosition(), candlePos,
                    Direction.UP, candleHit.getLocation(), 0, 30, false, "aggregation probe");
            var prove = BuildPlacementGeometry.class.getDeclaredMethod("provesGesture",
                    net.minecraft.client.player.LocalPlayer.class, BuildTaskRecord.Target.class,
                    BuildPlacementGeometry.Gesture.class);
            prove.setAccessible(true);
            check(!(Boolean) prove.invoke(null, h.player, anotherCandle, candleGesture),
                    "the accepts-every-state fallback must not override a known wrong destination");
            var aggregate = new BuildTaskRecord.Target(twoCandles, Items.CANDLE,
                    candlePos, "aggregate candles at the clicked cell", null, null, null);
            check(twoCandles.equals(BuildPlacementGeometry.predict(h.player, aggregate, candleHit, 0, 30)),
                    "native aggregation at the explicitly requested cell remains supported");
            check(h.blockUses() == 0 && h.level.getBlockState(support).equals(bottom),
                    "all destination proofs are read-only and leave the existing half untouched");
        }
        System.out.println("BuildPlacementDestinationTest: native replacement destination is required");
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
