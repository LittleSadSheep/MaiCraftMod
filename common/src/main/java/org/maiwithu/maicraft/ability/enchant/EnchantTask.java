// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.travel.LandmarkResolver;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 附魔任务：解析台子 → 选料与凑青金石（缺的走受限补齐）→ 走到并点开 → 界面里装料、
 * 看三档报价、按选定档、取回成品 → 关上并列出实际附上的全部条目。
 *
 * <p>一次附魔后随机种子改变、报价会变；本能力不提供「洗掉重来」——台子对已附魔物品
 * 本来就不收第二次。缺料不为一次附魔下矿：身上与记得的容器够不着就 NEED_ITEM 交回 LLM。
 */
final class EnchantTask extends PhasedTask<EnchantTask.Phase> {

    /** 不给 target 时找台子的默认范围（格）。 */
    static final int DEFAULT_RADIUS = 32;
    /** 找台子的分刻扫描一次最多扫几个区块半径（与寻找同级，约 128 格）；radius 再大也只扫这么远。 */
    private static final int MAX_SCAN_CHUNK_RADIUS = 8;
    private static final int QUERY_BUDGET = 200;
    /** 连续多少刻没有真实进展算卡住：装料与报价每步都有界，宽限给到几分钟。 */
    private static final long STUCK_AFTER_TICKS = 20L * 150;
    /** 青金石的注册 ID。 */
    private static final String LAPIS = "minecraft:lapis_lazuli";

    enum Phase { FIND_TABLE, GET_ITEM, MAKE_ROOM, GET_LAPIS, OPEN, MENU_WORK }

    /** 开工前的现场：解析出的台子、点开的界面与里面的活。 */
    private static final class Prepare {
        BlockPos table;
        MenuOpening opening;
        OpenedMenu menu;
    }

    private final EnchantInput input;
    private final EnchantServices services;
    private final Permissions permissions;
    private final Prepare prepare = new Prepare();
    /** 界面里的活；任务结束时动作会被收起，引用留在这里给结果细节用。 */
    private EnchantMenuWork work;

    EnchantTask(EnchantInput input, EnchantServices services, Permissions permissions) {
        super("附魔", Phase.FIND_TABLE, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = input;
        this.services = services;
        this.permissions = permissions;
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            // 缺可附魔物品与青金石都走受限的补齐：身上、记得的容器；要下矿才够就如实以 NEED_ITEM 结束
            case GET_ITEM -> services.obtain().actionFor(
                    new ItemRequest(WantedItem.ofItem(input.item()), 1, "附魔备料"), permissions, records());
            case GET_LAPIS -> services.obtain().actionFor(
                    new ItemRequest(WantedItem.ofItem(LAPIS),
                            input.tier() - carriedLapis(), "附魔的青金石"), permissions, records());
            // 背包放不下成品：腾一格；要动贵重品它会以 NEED_APPROVAL 停下来问
            case MAKE_ROOM -> services.space().makeRoom(1, "接附魔成品", Set.of(), permissions, records());
            case OPEN -> prepare.opening = services.openings().apply(prepare.table, permissions);
            case MENU_WORK -> this.work = new EnchantMenuWork(prepare.menu, services.reads(), input.item(),
                    input.tier(), records());
            default -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND_TABLE -> findTable(context);
            case GET_ITEM -> runActionThen(context, () -> Next.go(Phase.FIND_TABLE, "拿到了要附魔的东西"));
            case MAKE_ROOM -> runActionThen(context, () -> Next.go(Phase.FIND_TABLE, "腾出了接成品的空格"));
            case GET_LAPIS -> runActionThen(context, () -> Next.go(Phase.FIND_TABLE, "青金石凑齐了"));
            case OPEN -> runActionThen(context, () -> {
                prepare.menu = prepare.opening.opened().orElseThrow();
                return Next.go(Phase.MENU_WORK, "附魔台界面开着、内容同步完了");
            });
            case MENU_WORK -> menuWork(context);
        };
    }

    // 在附魔台前干活：被怪打断、界面被关掉了，还没按下去就回去重新打开接着附，像人打完怪回到台子前；
    // 已经按下去的不重来。实机 10-14 附魔到一半被自卫打断，回来报"界面被换掉了"整单失败。
    private Next<Phase> menuWork(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> finishEnchant();
            case ActionStatus.Failed failed -> {
                if (failed.problem().kind() == Problem.Kind.TARGET_GONE && work != null && work.spentLevels() == 0
                        && reopens < MAX_REOPENS) {
                    reopens++;
                    yield Next.go(Phase.OPEN, "附魔台界面被打断关掉了，还没按下去，回去重新打开");
                }
                yield Next.fail(failed.problem());
            }
        };
    }

    /** 界面被打断关掉后最多回去重开几次。 */
    private static final int MAX_REOPENS = 2;
    private int reopens;

    /** 台子解析：点名的那格就是那一个，不给就在半径内找最近的；分刻扫描没完不说没有。 */
    private Next<Phase> findTable(TickContext context) {
        if (prepare.table == null) {
            Next<Phase> resolved = resolveTarget(context);
            if (resolved != null) return resolved;
        }
        // 那格现在不是附魔台：TARGET_GONE，附上它现在的样子。
        var level = context.player().level();
        if (!level.getBlockState(prepare.table).is(Blocks.ENCHANTING_TABLE)) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, prepare.table.toShortString()
                    + " 那格现在是 " + level.getBlockState(prepare.table).getBlock().getName().getString()
                    + "，不是附魔台"));
        }
        return pickStock(context);
    }

    /** 目标对象的几种写法各归各；解析不出或没扫完都如实交代。 */
    private Next<Phase> resolveTarget(TickContext context) {
        var target = input.target();
        if (target == null) {
            // 不给 target：按 radius 换算成区块半径扫已加载的地方，找最近的；没扫完下一刻再问。
            // 扫完没有时写实际扫过的范围：radius 超过一次能扫的就照实说只扫了多远，不把没扫过的说成扫过了。
            int chunks = Math.max(1, Math.min(MAX_SCAN_CHUNK_RADIUS, input.radius() / 16 + 1));
            var found = services.scans().query(context.player().level(),
                    context.player().localPlayer().blockPosition(), Set.of(Blocks.ENCHANTING_TABLE),
                    1, chunks, QUERY_BUDGET);
            if (!found.complete()) return Next.stay();
            if (found.hits().isEmpty()) {
                String range = input.radius() <= chunks * 16 ? "半径 " + input.radius() + " 格内"
                        : "只扫了约 " + chunks * 16 + " 格内（一次最多扫这么远，要的是 " + input.radius() + " 格）";
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        range + "已加载的地方没找到附魔台；不会就地造一个，先 travel 或自己去建"));
            }
            prepare.table = found.hits().get(0);
            return null;
        }
        if (target instanceof Target.Seen seen) {
            var at = services.seen().positionOf(seen.id());
            if (at.isEmpty()) {
                return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + seen.id() + " 对应的东西不在了，重新 observe 再下"));
            }
            return fromPosition(at.get(), context);
        }
        if (target instanceof Target.Landmark landmark) {
            // 地标名：记过的地点优先，没记过认附近唯一写着它的告示牌；那一格不是附魔台，照常说它现在是什么。
            var located = LandmarkResolver.resolve(landmark.name(), services.places(), services.seen()::signs);
            if (located instanceof LandmarkResolver.Resolution.Missing missing) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, missing.reason(), missing.suggestion()));
            }
            return fromPosition(((LandmarkResolver.Resolution.Found) located).position(), context);
        }
        if (target instanceof Target.Position position) {
            // 坐标没给 y 是参数缺了，不当成 y=0 去看那格是什么，免得报"台子没了"让 LLM 以为台子被拆。
            if (position.y() == null) {
                return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        "附魔台的坐标没写 y", "补上附魔台那一格的 y，或用观察编号指它"));
            }
            return fromPosition(new WorldPosition(position.x(), position.y(), position.z(), position.dimension()),
                    context);
        }
        return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                "附魔台的目标对象只认观察编号（b#）、坐标或记过的地点"));
    }

    private Next<Phase> fromPosition(WorldPosition at, TickContext context) {
        // 维度是角色此刻所在的维度；一次附魔不跨维度，跨维度出行是另一件事。
        String here = currentDimension(context);
        if (at.dimension() != null && !at.dimension().equals(here)) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "那张附魔台在 " + at.dimension() + "，跟角色不在一个维度"));
        }
        prepare.table = new BlockPos(at.x(), at.y(), at.z());
        return null;
    }

    /** 选料判据：身上第一件匹配且台子肯收的；凑青金石；背包要留得出接成品的空格。 */
    private Next<Phase> pickStock(TickContext context) {
        var carried = services.reads().carriedSlots();
        int pick = StockPick.pick(carried, WantedItem.ofItem(input.item()), services.tags());
        if (pick < 0) return Next.go(Phase.GET_ITEM, "身上没有台子肯收的「" + input.item() + "」，先去拿一件");
        if (carriedLapis() < input.tier()) return Next.go(Phase.GET_LAPIS, "青金石不够这一档，先去凑");
        if (context.player().backpack().freeSlots() == 0) {
            return Next.go(Phase.MAKE_ROOM, "背包放不下成品，先腾一格");
        }
        recordProgress("选好料与台子，开工");
        return Next.go(Phase.OPEN, "料齐了，去附魔台");
    }

    private int carriedLapis() {
        int total = 0;
        for (var slot : services.reads().carriedSlots()) {
            if (slot.itemId().equals(LAPIS)) total += slot.count();
        }
        return total;
    }

    /** 界面里的活做完了：只附过魔（按钮确认）才列条目；没做成附 offers 与已扣费用的事实。 */
    private Next<Phase> finishEnchant() {
        if (work.enchantments().isEmpty()) {
            return Next.done(TaskResult.builder(TaskResult.Status.FAILED, "附魔没有做成")
                    .details(details()).build());
        }
        String summary = "附好了：" + String.join("、", work.enchantments())
                + "（扣了 " + work.spentLevels() + " 级、" + work.spentLapis() + " 个青金石）";
        return Next.done(TaskResult.done(summary));
    }

    @Override protected ResultDetails details() {
        return new EnchantDetails(
                work != null && work.quote() != null ? work.quote().offers() : List.of(),
                input.tier(),
                work != null ? work.enchantments() : List.<String>of(),
                work != null ? work.spentLevels() : 0,
                work != null ? work.spentLapis() : 0);
    }

    private String currentDimension(TickContext context) {
        return context.player().level().dimension().location().toString();
    }
}
