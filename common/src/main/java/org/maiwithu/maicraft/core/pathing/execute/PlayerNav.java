package org.maiwithu.maicraft.core.pathing.execute;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.bridge.ContextFactory;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Task-facing navigation contract. The embedded Baritone runtime owns all route execution. */
public final class PlayerNav {
    public enum Status { RUNNING, ARRIVED, FAILED }

    private final EmbeddedBaritoneNavigator navigator;

    public PlayerNav(LocalPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, speed, reached, () -> GoalCompiler.block(player.level(), goal), ContextProvider.DEFAULT);
    }

    public PlayerNav(LocalPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, speed, reached, () -> {
            BlockPos goal = goalSupplier.get();
            return goal == null ? null : GoalCompiler.block(player.level(), goal);
        }, ContextProvider.DEFAULT);
    }

    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached) {
        return to(player, compiled, speed, reached, ContextProvider.DEFAULT);
    }

    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, contextProvider);
    }

    /** Goal suppliers are revalidated on every tick, including task-specific protection cells. */
    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                          double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return to(player, compiled, speed, reached, contextProvider);
    }

    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                          double speed, BooleanSupplier reached) {
        return to(player, compiled, speed, reached);
    }

    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                   double speed, BooleanSupplier reached) {
        return toGoal(player, goals, speed, reached, ContextProvider.DEFAULT);
    }

    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                   double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return to(player, bare(goals), speed, reached, contextProvider);
    }

    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                      double speed, BooleanSupplier reached) {
        return toGoal(player, goals, speed, reached);
    }

    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                      double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return toGoal(player, goals, speed, reached, contextProvider);
    }

    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal goal = goals.get();
            return goal == null ? null : new GoalCompiler.Compiled(goal, LongSets.emptySet());
        };
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier, ContextProvider contextProvider) {
        navigator = new EmbeddedBaritoneNavigator(player, compiledSupplier, reached,
                contextProvider == null ? ContextProvider.DEFAULT : contextProvider, speed >= 1.0);
    }

    public PlayerNav withTerrainProbe() {
        navigator.withTerrainProbe();
        return this;
    }

    public interface ContextProvider {
        /** 缺省:只走不改。接近类动作全部用它,忘了指定也只会更保守。 */
        ContextProvider DEFAULT = of(TerrainPermit.PRESERVE);
        /** 可改地形:挖矿、施工,以及模型显式授权的 goto。 */
        ContextProvider TERRAFORM = of(TerrainPermit.TERRAFORM);

        static ContextProvider of(TerrainPermit permit) {
            return new ContextProvider() {
                @Override
                public CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                                                    LongSet deniedPlace, LongSet forbiddenBodyCells) {
                    return ContextFactory.forSearch(player, sacred, deniedPlace,
                            forbiddenBodyCells, permit, CalculationContext::new);
                }

                @Override
                public CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                                                       LongSet deniedPlace, LongSet forbiddenBodyCells) {
                    return ContextFactory.forExecution(player, sacred, deniedPlace,
                            forbiddenBodyCells, permit, CalculationContext::new);
                }

                @Override
                public TerrainPermit permit() {
                    return permit;
                }
            };
        }

        CalculationContext forSearch(LocalPlayer player, LongSet sacred, LongSet deniedPlace,
                                     LongSet forbiddenBodyCells);
        CalculationContext forExecution(LocalPlayer player, LongSet sacred, LongSet deniedPlace,
                                        LongSet forbiddenBodyCells);
        /** 本提供者建出的上下文所带的地形许可(执行器据此决定顺手的放置能不能做)。 */
        TerrainPermit permit();

        /**
         * Extra cells the embedded backend must never break or place in. The compiled goal's
         * sacred cells are added separately; this hook preserves task-specific policies such as
         * a construction footprint without recreating the retired pathfinder's cost context.
         */
        default LongSet embeddedProtectedMutationCells() {
            return NavigationSafetyContext.protectedMutationCells();
        }

        /** Extra cells the embedded first-person body must never occupy. */
        default LongSet embeddedForbiddenBodyCells() {
            return NavigationSafetyContext.forbiddenBodyCells();
        }
    }

    /** Shared feet convention for navigation and interaction stances. */
    public static BlockPos playerFeet(LocalPlayer player) {
        return org.maiwithu.maicraft.core.pathing.moves.Movement.feet(player);
    }

    public Status tick() { return navigator.tick(); }
    public boolean isSafeToCancel() { return navigator.isSafeToCancel(); }
    public BlockPos pathStart() { return navigator.pathStart(); }
    public TerrainBill ledger() { return navigator.ledger(); }
    public String failReason() { return navigator.failReason(); }
    public FailureType failType() { return navigator.failType(); }
    public int stallTicks() { return navigator.stallTicks(); }
    public boolean hasRecentPhysicalProgress(int graceTicks) {
        return navigator.hasRecentPhysicalProgress(graceTicks);
    }
    public String outcomeSummary() { return navigator.outcomeSummary(); }
    public boolean planningInFlight() { return navigator.planningInFlight(); }
    public void stop() { navigator.stop(); }
    public void pause() { navigator.pause(); }
    public boolean yieldForExternalAction() { return navigator.yieldForExternalAction(); }
}
