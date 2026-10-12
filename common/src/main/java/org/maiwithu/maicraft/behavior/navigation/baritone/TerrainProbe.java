// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.utils.pathing.Favoring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 不许挖（change_blocks 为 none 或 temporary）时走不通的只读复算："假如允许挖开、垫方块，有没有到目标的路、要动哪些格"。
 * 只算不走：结论只进走不通的说明与建议，不建执行路线，世界一格不动，要不要放宽许可由 LLM 决定。
 *
 * <p>复算出的完整路线一格都不用改，说明刚才那次没路是偶然（区块刚加载好、挡路的生物走开了），
 * 就在原任务里重新算路接着走，不让 LLM 为同一个目的地重发一遍；同一趟最多这样重算两次，免得空转。
 */
final class TerrainProbe {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainProbe.class);


    /** 复算最多等这么多客户端刻：搜索本身有时限，再给区块读取留余量。 */
    private static final long WAIT_LIMIT_TICKS = 300;
    /** 在原任务里重新算路的次数上限："不改地形也有路"与复算期间被挪了位置合计。 */
    private static final int RESTART_LIMIT = 2;

    /** 这一刻怎么办：不用复算、等复算、重新算路、有结论了（按走不通收场）。 */
    enum Step { NOT_NEEDED, WAITING, RESTART, CONCLUDED }

    /**
     * 复算的结论。
     *
     * @param breaks 要挖的格子，按方块分组
     * @param places 要垫方块的格子
     */
    record Verdict(PathCalculationResult.Type type, Map<String, List<BlockPos>> breaks, Set<BlockPos> places,
                   Set<String> outsideWhitelist) {
        boolean reachesGoal() {
            return type == PathCalculationResult.Type.SUCCESS_TO_GOAL;
        }

        boolean changesNothing() {
            return breaks.isEmpty() && places.isEmpty();
        }
    }

    private final MaiCraftGoalAdapter goal;
    private final TerrainPermit permit;
    private CompletableFuture<Verdict> pending;
    private AbstractNodeCostSearch search;
    private BlockPos origin;
    private long startedTick;
    private int restarts;
    /** 最近一次复算的结论；没复算或没给出结论为 null。 */
    private Verdict verdict;
    /** 复算没给出结论的原因（超时、出错）；有结论或没复算为 null。 */
    private String unanswered;

    TerrainProbe(MaiCraftGoalAdapter goal, TerrainPermit permit) {
        this.goal = goal;
        this.permit = permit;
    }

    boolean waiting() {
        return pending != null;
    }

    /**
     * 引擎刚说没有路、或复算还在等的这一刻怎么办。许可本来就能挖的不复算；
     * 复算期间角色被挪了位置（传送、被推）就作废，从新位置重新算路。
     */
    Step step(IBaritone engine, BlockPos feet, long tick) {
        boolean mayDig = permit.changes() == Permissions.BlockChanges.NATURAL
                || permit.changes() == Permissions.BlockChanges.ANY;
        if (mayDig) return Step.NOT_NEEDED;
        if (pending == null) {
            start(engine, feet, tick);
            return Step.WAITING;
        }
        // 复算期间被挪了位置：旧起点的结论不算数，从新位置重新算路；在水里一直漂的也只重来有限几次，之后照旧等结论。
        if (!feet.equals(origin) && restarts < RESTART_LIMIT) {
            cancel();
            restarts++;
            return Step.RESTART;
        }
        if (!pending.isDone()) {
            if (tick - startedTick <= WAIT_LIMIT_TICKS) return Step.WAITING;
            cancel();
            unanswered = "等了 " + WAIT_LIMIT_TICKS / 20 + " 秒没算完";
            return Step.CONCLUDED;
        }
        Verdict result = collect();
        if (result == null) return Step.CONCLUDED;
        // 不改地形也有完整路线：在原任务里重新算路接着走；重算够次数还是走不起来，就按走不通如实说。
        if (result.reachesGoal() && result.changesNothing() && restarts < RESTART_LIMIT) {
            restarts++;
            return Step.RESTART;
        }
        verdict = result;
        return Step.CONCLUDED;
    }

    /** 运行结束、交出身体时调用：还在算的复算叫停，不留后台搜索。 */
    void cancel() {
        if (search != null) search.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        search = null;
    }

    /**
     * 把复算的结论补进走不通的问题：要挖哪些格、要垫哪些格，或复算也没找到路；
     * 有路可走时建议放宽到哪一档许可。没复算过的问题原样返回。
     *
     * @param carriesScaffold 手边有没有能垫的料
     */
    Problem explain(Problem problem, boolean carriesScaffold) {
        return explain(problem, verdict, unanswered, permit.changes(), carriesScaffold, restarts);
    }

    /** 同上，结论与许可都摊开传进来：离线测试直接喂一份结论。 */
    static Problem explain(Problem problem, Verdict verdict, String unanswered, Permissions.BlockChanges changes,
                           boolean carriesScaffold, int restarts) {
        if (verdict == null && unanswered == null) return problem;
        if (unanswered != null) {
            return Problem.of(problem.kind(), problem.message() + "；允许挖垫的只读复算没给出结论：" + unanswered,
                    problem.suggestion());
        }
        if (!verdict.reachesGoal()) {
            return Problem.of(problem.kind(), problem.message() + "；就算允许挖开、垫方块，只读复算也"
                    + unreached(verdict.type()) + "，什么都没动", problem.suggestion());
        }
        if (verdict.changesNothing()) {
            return Problem.of(problem.kind(), problem.message() + "；只读复算找到了不用改地形的完整路线，但在原任务里重算 "
                    + restarts + " 次仍没走起来", problem.suggestion());
        }
        String message = problem.message() + "；只读复算：允许挖开、垫方块就有到目标的完整路线，" + bill(verdict)
                + "；这次什么都没动";
        return Problem.of(problem.kind(), message, suggestion(verdict, changes, carriesScaffold));
    }

    // 后台按冻结的保护格与身体禁入格算一次允许动土的 A*；算出的路线只拿来数要挖、要垫的格子。
    private void start(IBaritone engine, BlockPos feet, long tick) {
        verdict = null;
        unanswered = null;
        origin = feet.immutable();
        startedTick = tick;
        CalculationContext context = CalculationContext.forTerrainProbe(engine, NavigationProtection.snapshot());
        Favoring favoring = new Favoring(engine.getPlayerContext(), null, context);
        BetterBlockPos start = new BetterBlockPos(origin);
        AbstractNodeCostSearch probe = new AStarPathFinder(start, start.getX(), start.getY(), start.getZ(),
                goal, favoring, context);
        long primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
        long failureTimeout = Baritone.settings().failureTimeoutMS.value;
        search = probe;
        pending = PathPlannerPool.submit(() -> verdictOf(probe.calculate(primaryTimeout, failureTimeout), context));
    }

    // 在算路线程上把路线摊成要挖、要垫的格子；读的是复算冻结时的方块。
    private static Verdict verdictOf(PathCalculationResult calculation, CalculationContext context) {
        Map<String, List<BlockPos>> breaks = new LinkedHashMap<>();
        Set<BlockPos> places = new LinkedHashSet<>();
        Set<String> outside = new LinkedHashSet<>();
        IPath path = calculation.getPath().map(IPath::postProcess).orElse(null);
        if (path != null) {
            for (IMovement raw : path.movements()) {
                if (!(raw instanceof Movement movement)) continue;
                for (BlockPos cell : movement.toBreak(context.bsi)) {
                    BlockState state = context.bsi.get0(cell);
                    String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    List<BlockPos> cells = breaks.computeIfAbsent(block, k -> new ArrayList<>());
                    if (!cells.contains(cell)) cells.add(cell.immutable());
                    if (!ClearanceWhitelist.allows(state)) outside.add(block);
                }
                for (BlockPos cell : movement.toPlace(context.bsi)) places.add(cell.immutable());
                PathPlannerPool.madeProgress("terrain_probe_movement");
            }
        }
        return new Verdict(calculation.getType(), breaks, places, outside);
    }

    private Verdict collect() {
        try {
            return pending.join();
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            unanswered = "搜索出错（" + cause.getClass().getSimpleName() + "）";
            // 只读复算出错是程序的问题：把异常留在日志里，结果里只交代一句。
            LOG.warn("只读复算出错", cause);
            return null;
        } catch (RuntimeException cancelled) {
            unanswered = "复算被取消";
            return null;
        } finally {
            pending = null;
            search = null;
        }
    }

    // 要挖哪些格（按方块分组，不在可挖名单的标出来）、要垫哪些格，全部列出。
    private static String bill(Verdict verdict) {
        List<String> parts = new ArrayList<>();
        int breakCount = verdict.breaks().values().stream().mapToInt(List::size).sum();
        if (breakCount > 0) {
            List<String> groups = new ArrayList<>();
            verdict.breaks().forEach((block, cells) -> groups.add(block + "×" + cells.size()
                    + (verdict.outsideWhitelist().contains(block) ? "（不在可挖名单）" : "") + " " + cellsText(cells)));
            parts.add("要挖 " + breakCount + " 格：" + String.join("；", groups));
        }
        if (!verdict.places().isEmpty()) {
            parts.add("要垫 " + verdict.places().size() + " 格：" + cellsText(verdict.places()));
        }
        return String.join("，", parts);
    }

    // 有路可走时放宽到哪一档：要挖的放到 natural（有不在可挖名单的方块得 any），只要垫的放到 temporary；没料要先带料。
    private static String suggestion(Verdict verdict, Permissions.BlockChanges changes, boolean carriesScaffold) {
        boolean needsScaffold = !verdict.places().isEmpty() && !carriesScaffold;
        String scaffold = needsScaffold ? "，身上还没有能垫的料，得先带些泥土、圆石这类垫脚料" : "";
        if (!verdict.breaks().isEmpty()) {
            String tier = verdict.outsideWhitelist().isEmpty() ? "natural" : "any（要挖的有不在可挖名单里的方块）";
            return "要走这条路就把 change_blocks 提到 " + tier + scaffold + "；不想动地形就换个目标点";
        }
        if (changes == Permissions.BlockChanges.NONE) {
            return "要走这条路就把 change_blocks 提到 temporary（只垫不挖）" + scaffold + "；不想动地形就换个目标点";
        }
        return needsScaffold ? "身上没有能垫的料：带些泥土、圆石这类垫脚料再走，或换个目标点"
                : "换个目标点或绕一段路再试";
    }

    private static String unreached(PathCalculationResult.Type type) {
        return switch (type) {
            case SUCCESS_SEGMENT -> "只在时限内算出朝目标的一段，没算到目标";
            case FAILURE -> "找不到到目标的路";
            case CANCELLATION -> "被取消，没算完";
            case EXCEPTION -> "搜索出错，没算完";
            case SUCCESS_TO_GOAL -> "算到了目标";
        };
    }

    private static String cellsText(Iterable<BlockPos> cells) {
        List<String> texts = new ArrayList<>();
        for (BlockPos cell : cells) texts.add("(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")");
        return String.join("、", texts);
    }
}
