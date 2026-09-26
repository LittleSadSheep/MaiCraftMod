package org.maiwithu.maicraft.core.pathing.execute;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.transport.TransportNavigator;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import java.util.Map;
import org.maiwithu.maicraft.core.pathing.moves.Movement;

/** 给走路、挖矿、施工等任务用的统一导航入口，实际交给 TransportNavigator 协调步行与交通，再由 Baritone 走路线。 */
public final class PlayerNav {
    public enum Status { RUNNING, ARRIVED, FAILED }

    private final TransportNavigator navigator;

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

    /** 当前与普通 to 使用同一实现，每刻都会重新读取目标；这个名字保留给旧调用方。 */
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
        return trackGoal(player, goals, speed, reached, ContextProvider.DEFAULT);
    }

    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                      double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        // 追击和避让会不断收到实体的新坐标，不能把每次更新都解释为必须清空整条路线的新任务。
        var nav = toGoal(player, goals, speed, reached, contextProvider);
        nav.navigator.trackMovingGoal(); return nav;
    }

    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        // 普通目标不附加专门保护格；调用者有特殊保护要求时，要通过目标或 ContextProvider 明确传入。
        return () -> {
            NavGoal goal = goals.get();
            return goal == null ? null : new GoalCompiler.Compiled(goal, LongSets.emptySet());
        };
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                       Supplier<GoalCompiler.Compiled> compiledSupplier, ContextProvider contextProvider) {
        // speed 当前只用于判断是否允许冲刺（至少 1.0），并没有按这个小数直接调玩家的移动速度。
        navigator = new TransportNavigator(player, compiledSupplier, reached,
                contextProvider == null ? ContextProvider.DEFAULT : contextProvider, speed >= 1.0);
    }

    public PlayerNav withTerrainProbe() {
        navigator.withTerrainProbe();
        return this;
    }

    public PlayerNav withTransportMode(TransportMode mode) {
        navigator.mode(mode == null ? TransportMode.AUTO : mode);
        return this;
    }

    /** 交通适配器使用此方法执行自身的地面接近路线，避免递归地再次登乘。 */
    public PlayerNav walkingOnly() { return withTransportMode(TransportMode.GROUND); }

    public interface ContextProvider {
        // 当前导航只从这里读取地形许可和额外保护格，不创建另一套寻路成本上下文。
        /** 缺省:只走不改。接近类动作全部用它,忘了指定也只会更保守。 */
        ContextProvider DEFAULT = of(TerrainPermit.PRESERVE);
        ContextProvider WATER_ONLY = of(TerrainPermit.WATER_ONLY);
        ContextProvider LANDING_ONLY = of(TerrainPermit.LANDING_ONLY);
        /** 可改地形:挖矿、施工,以及模型显式授权的 goto。 */
        ContextProvider TERRAFORM = of(TerrainPermit.TERRAFORM);

        static ContextProvider of(TerrainPermit permit) {
            return new ContextProvider() {
                @Override
                public TerrainPermit permit() {
                    return permit;
                }
            };
        }

        /** 当前导航据此判断是否允许挖掘或放置。 */
        TerrainPermit permit();

        /**
         * 内嵌后端绝不能破坏或放置方块的额外格子。编译目标的 sacred 格会另行添加；此钩子保留施工占地等任务专属规则，
         * 不必重新构建已退役寻路器的成本上下文。
         */
        default LongSet embeddedProtectedMutationCells() {
            return NavigationSafetyContext.protectedMutationCells();
        }

        /** 内嵌第一人称角色绝不能进入的额外格子。 */
        default LongSet embeddedForbiddenBodyCells() {
            return NavigationSafetyContext.forbiddenBodyCells();
        }

        /** 可选的任务局部地面高度，用于保持既有建筑路线的施工高度。 */
        default int minimumFeetY() { return Integer.MIN_VALUE; }
    }

    /** 导航和交互站位共用的脚位约定。 */
    public static BlockPos playerFeet(LocalPlayer player) {
        return Movement.feet(player);
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
    public NavigationStep executionStep(long clientRevision) { return navigator.executionStep(clientRevision); }
    public void stop() { navigator.stop(); }
    public void pause() { navigator.pause(); }
    public void abandon() { navigator.abandon(); }
    public Map<String, Object> transportDiagnostics() { return navigator.diagnostics(); }
    public boolean yieldForExternalAction() { return navigator.yieldForExternalAction(); }
}
