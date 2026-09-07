// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import sun.misc.Unsafe;

/** Hollow 18-cube support exceptions are bounded by authored air, real receipts and inherited protection. */
public final class BuildScaffoldLedgerTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<Long, BuildTaskRecord.Target> targets = new HashMap<>();
        LongSet base = new LongOpenHashSet(), air = new LongOpenHashSet();
        TestWorld world = new TestWorld();
        for (int y = 63; y < 81; y++) for (int z = 0; z < 18; z++) for (int x = 0; x < 18; x++) {
            BlockPos pos = new BlockPos(x, y, z);
            boolean interior = x > 0 && x < 17 && y > 63 && y < 80 && z > 0 && z < 17;
            BlockState state = interior ? Blocks.AIR.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
            targets.put(pos.asLong(), new BuildTaskRecord.Target(state, interior ? Items.AIR : Items.IRON_BLOCK,
                    pos, "hollow matrix", null, null, null));
            base.add(pos.asLong()); if (interior) air.add(pos.asLong());
        }
        var ledger = new BuildScaffoldLedger();
        BlockPos scaffold = new BlockPos(9, 70, 9), shell = new BlockPos(0, 70, 9);
        LongSet allowed = ledger.navigationProtection(base, LongSets.emptySet(), LongSets.emptySet(), air,
                targets, world, pos -> true, true);
        check(allowed.size() == base.size() - 16 * 16 * 16, "every interior air cell is available; the entire shell stays protected");
        check(!allowed.contains(scaffold.asLong()) && allowed.contains(shell.asLong()), "exceptions never include the casing");
        check(ledger.navigationProtection(base, LongSets.emptySet(), LongSets.emptySet(), air,
                targets, world, pos -> true, false).contains(scaffold.asLong()), "no exception before full preflight");
        LongSet inherited = new LongOpenHashSet(); inherited.add(scaffold.asLong());
        LongSet protectedCells = ledger.navigationProtection(base, inherited, LongSets.emptySet(), air,
                targets, world, pos -> true, true);
        check(protectedCells.contains(scaffold.asLong()), "inherited player protection cannot be subtracted");
        check(ledger.navigationProtection(base, LongSets.emptySet(), inherited, air,
                targets, world, pos -> true, true).contains(scaffold.asLong()), "goal sacred cells cannot be subtracted");
        check(ledger.navigationProtection(base, LongSets.emptySet(), LongSets.emptySet(), air,
                targets, world, pos -> !pos.equals(scaffold), true).contains(scaffold.asLong()), "unknown cells remain protected");
        double openCost = cost(targets, allowed, scaffold);
        double protectedCost = cost(targets, protectedCells, scaffold);
        check(openCost < org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF
                && protectedCost == org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF,
                "real BuildCalculationContext offers a finite air-scaffold route but rejects protected placement");
        var policy = EmbeddedBaritonePolicy.capture(LongSets.emptySet(), allowed, LongSets.emptySet());
        check(!policy.protects(9, 70, 9) && policy.protects(0, 70, 9), "embedded backend receives the same bounded permission");
        world.cells.put(scaffold, Blocks.DIRT.defaultBlockState());
        check(!ledger.permits(targets.get(scaffold.asLong()), world.getBlockState(scaffold), false),
                "an unowned real obstruction cannot become a scaffold exception");
        ledger.confirmed(scaffold, Blocks.DIRT.defaultBlockState());
        check(ledger.permits(targets.get(scaffold.asLong()), world.getBlockState(scaffold), false),
                "only a confirmed own support can be navigated and removed");
        check(!targets.get(scaffold.asLong()).matches(world.getBlockState(scaffold)), "temporary support never satisfies final air");
        world.cells.put(scaffold, Blocks.OBSIDIAN.defaultBlockState());
        check(!ledger.owns(scaffold, world.getBlockState(scaffold)), "later replacement revokes cleanup ownership");
        batchOwnership(scaffold);
        registryOwnership(scaffold);
        ledger.cleared(scaffold); world.cells.remove(scaffold);
        check(ledger.snapshot().isEmpty() && targets.get(scaffold.asLong()).matches(world.getBlockState(scaffold)),
                "only actual air restoration clears the temporary ledger and satisfies the plan");
        System.out.println("BuildScaffoldLedgerTest: passed; 4096 bounded hollow-matrix support cells");
    }

    private static void batchOwnership(BlockPos pos) {
        var source = new BuildTaskRecord("source", 100, List.of(), false, true);
        var first = new BuildTaskRecord("first", 100, List.of(), false, true);
        var second = new BuildTaskRecord("second", 100, List.of(), false, true);
        source.copyExecutionContextTo(first);
        first.scaffoldLedger().confirmed(pos, Blocks.DIRT.defaultBlockState());
        source.copyExecutionContextTo(second);
        check(second.scaffoldLedger().owns(pos, Blocks.DIRT.defaultBlockState()), "next material batch inherits confirmed scaffolds");
        second.scaffoldLedger().cleared(pos);
        check(source.scaffoldLedger().snapshot().isEmpty(), "cleanup updates the shared authoritative ledger");
    }

    private static void registryOwnership(BlockPos pos) {
        var owner = new Owner();
        var replacement = new Owner();
        BuildPlacementRegistry.register(null, owner);
        BuildPlacementRegistry.recordConfirmedScaffold(owner, pos, Blocks.DIRT.defaultBlockState());
        check(owner.ledger.owns(pos, Blocks.DIRT.defaultBlockState()), "confirmed native placement reaches the owner ledger");
        BuildPlacementRegistry.register(null, replacement);
        BuildPlacementRegistry.recordConfirmedScaffold(owner, pos, Blocks.DIRT.defaultBlockState());
        check(replacement.ledger.snapshot().isEmpty(), "old native receipt cannot grant cleanup ownership to a replacement task");
        BuildPlacementRegistry.unregister(null, replacement);
    }

    private static double cost(Map<Long, BuildTaskRecord.Target> targets, LongSet sacred, BlockPos pos) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Unsafe memory = (Unsafe) singleton.get(null);
        var context = (BuildCalculationContext) memory.allocateInstance(BuildCalculationContext.class);
        assign(context, BuildCalculationContext.class, "activeTargets", targets);
        assign(context, CalculationContext.class, "sacred", sacred);
        assign(context, CalculationContext.class, "deniedPlace", LongSets.emptySet());
        assign(context, CalculationContext.class, "hasThrowaway", true);
        assign(context, CalculationContext.class, "placeBlockCost", 1.0);
        assign(context, CalculationContext.class, "worldBorder", new CalculationContext.BorderSnapshot(-100, 100, -100, 100));
        return context.costOfPlacingAt(pos.getX(), pos.getY(), pos.getZ(), Blocks.AIR.defaultBlockState());
    }
    private static void assign(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static final class Owner implements BuildPlacementRegistry.Provider {
        final BuildScaffoldLedger ledger = new BuildScaffoldLedger();
        @Override public BlockState desiredState(BlockPos pos) { return null; }
        @Override public void confirmedScaffold(BlockPos pos, BlockState state) { ledger.confirmed(pos, state); }
    }
    private static final class TestWorld implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
