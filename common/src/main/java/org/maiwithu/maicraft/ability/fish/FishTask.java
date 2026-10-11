// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 钓鱼任务：备竿 → 找钓位 → 走到 → 瞄准抛竿 → 等咬钩 → 收竿取获，钓满收竿或一直钓。
 *
 * <p>钓上来的东西照单全收（鱼、垃圾、宝藏都算一次），以背包实际增长如实记账，不挑拣不伪造；
 * 鱼钩消失、钩住实体、抛出没入水、一竿没咬钩都是失败重抛，连续五次结束（成功一次清零并清排除集）。
 * 等咬钩是正常干活，可以被打断；收竿或抛竿已提交、等游戏确认的那几刻停下不安全。
 */
class FishTask extends PhasedTask<FishTask.Phase> {

    /** 鱼竿的注册 ID：只认原版鱼竿。 */
    static final String FISHING_ROD = "minecraft:fishing_rod";
    /** 收竿后先等约 1 秒让原版把战利品抛向玩家，再走近去捡。F 游戏事实（收线冲量窗口）。 */
    private static final int LOOT_RETURN_GRACE_TICKS = 20;
    /** 连续 5 次失败抛竿就结束；成功一次清零。C 玩家常识。 */
    private static final int MAX_FAILED_CASTS = 5;
    /** 两竿之间的短暂冷却。F 游戏事实。 */
    private static final int COOLDOWN_TICKS = 10;
    /** 抛竿、收竿各等游戏确认的期限。 */
    private static final int CONFIRM_TICKS = 30;
    /** 连续多少刻没有真实进展算卡住：比一竿最长的静默期（等咬钩 60 秒加收抛确认）长一截。 */
    private static final long STUCK_AFTER_TICKS = 1400;
    /** 瞄准收敛的角度余弦阈值（约 2°）：真实视线与抛投方向差得比这小才算瞄准到位。 */
    private static final double AIM_TOLERANCE_COS = 0.99939;
    /** 瞄准方向连续保持这么多刻才出手。 */
    private static final int AIM_SETTLE_TICKS = 2;

    enum Phase { CHECK_ROD, GET_ROD, TAKE_ROD, FIND_SPOT, APPROACH, AIM, CAST, WAIT_BITE, REEL, COLLECT, COOLDOWN }

    /** 收竿是为了什么：钓到了要捡获，还是这一竿报废了要重抛。 */
    private enum ReelReason { KEEP, ABANDON }

    /** 收获段的内部次序：等冲量 → 捡 → 腾一格再捡。 */
    private enum CollectStep { GRACE, PICKUP, MAKEROOM }

    /** 一次抛竿到收竿的挂起事实：提交的确认、为什么收竿、报废时怎么交代。 */
    private static final class CastRun {
        PendingInteraction castPending;
        PendingInteraction reelPending;
        ReelReason reelReason;
        /** 报废这一竿的原因与水面要不要进排除集：收竿确认后照它交代。 */
        String scrapWhy;
        boolean scrapRejects;
        int aimSettled;
        /** 等咬钩推进了几刻：被打断的时间天然不计。 */
        int waitTicks;
        /** 上一次推进等咬钩的刻号：两刻隔了一截说明被生存需求插过队。 */
        long lastWaitTick = Long.MIN_VALUE;
        /** 最近一刻钩还挂着：被打断时据此决定恢复后要不要先收竿。 */
        boolean hookSeen;
    }

    /** 收获段的现场：背包基线、动手前的掉落物、捡与腾的动作。 */
    private static final class CollectRun {
        CollectStep step = CollectStep.GRACE;
        int waited;
        Action pickup;
        Action makeRoom;
        /** 收竿确认那一刻的背包按物品计数：增长部分按差集归到这一竿名下。 */
        Map<String, Integer> baseline = Map.of();
        /** 收竿确认那一刻脚边已有的掉落物：捡拾动作只捡这一竿新冒出来的。 */
        Set<Integer> lootBefore = Set.of();
    }

    private final FishInput input;
    private final FishServices services;
    private final Permissions permissions;
    private final CastRun cast = new CastRun();
    private final CollectRun collect = new CollectRun();
    private final Set<BlockPos> excludedStances = new HashSet<>();
    private final Set<BlockPos> excludedLandings = new HashSet<>();
    /** 钓位重算只清一次排除集：清空再算仍找不到就如实结束，不来回打转。 */
    private boolean retriedAfterClear;
    private BlockPos spot;
    private BlockPos landing;
    private int failedCasts;
    private int casts;
    private int caught;
    private int cooldownWaited;

    FishTask(FishInput input, FishServices services, Permissions permissions) {
        super("钓鱼", Phase.CHECK_ROD, new ProgressTracker(STUCK_AFTER_TICKS,
                input.maxSeconds() > 0 ? input.maxSeconds() * 20 : Long.MAX_VALUE));
        this.input = input;
        this.services = services;
        this.permissions = permissions;
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            // 缺竿走拿到物品的引擎（身上、记得的容器、合成），弄不到以 NEED_ITEM 结束
            case GET_ROD -> services.obtain().actionFor(
                    new ItemRequest(WantedItem.ofItem(FISHING_ROD), 1, "钓鱼"), permissions, records());
            case TAKE_ROD -> services.toMainhand().moveToMainhand(FISHING_ROD).orElse(null);
            case APPROACH -> services.walks().start(GoalCompiler.standOn(spot), TerrainPermit.of(permissions));
            case COLLECT -> collect.step == CollectStep.PICKUP ? nextPickup() : null;
            default -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        // 竿是唯一会耗尽的用具：耐久打空消灭（gear_broken 事件照发）或被拿走后如实结束，已钓到的照算。
        if (phase != Phase.CHECK_ROD && phase != Phase.GET_ROD && !hasRod(context)) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "钓着钓着竿没了（耐久耗尽或不在身上了），已钓到 " + caught + " 次、抛了 " + casts + " 竿"));
        }
        return switch (phase) {
            case CHECK_ROD -> Next.go(hasRod(context) ? Phase.TAKE_ROD : Phase.GET_ROD,
                    hasRod(context) ? "鱼竿在身上" : "身上没有鱼竿，先弄一根");
            case GET_ROD -> runActionThen(context, () -> Next.go(Phase.TAKE_ROD, "弄到了鱼竿"));
            case TAKE_ROD -> takeRod(context);
            case FIND_SPOT -> findSpot(context);
            case APPROACH -> approach(context);
            case AIM -> aim(context);
            case CAST -> castAndConfirm(context);
            case WAIT_BITE -> waitBite(context);
            case REEL -> reel(context);
            case COLLECT -> collect(context);
            case COOLDOWN -> coolDown(context);
        };
    }

    private Next<Phase> takeRod(TickContext context) {
        if (action() == null) {
            // 身上有竿（上一阶段核对过）却换不到主手：换手这条还没接上，如实交代。
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, "鱼竿换不到主手，换手这条路还没接上"));
        }
        return runActionThen(context, () -> Next.go(Phase.FIND_SPOT, "鱼竿换到主手了"));
    }

    private Next<Phase> findSpot(TickContext context) {
        // 上一次被排除过的落点在小池塘里可能是唯一落点：全试过一轮后清空再算一次，不因一次不走运判这里钓不了。
        FishingSpot.Search search = FishingSpot.search(world(context), feet(context),
                excludedStances, excludedLandings);
        if (search instanceof FishingSpot.Search.NoLandingFromHere && !excludedLandings.isEmpty() && !retriedAfterClear) {
            retriedAfterClear = true;
            excludedLandings.clear();
            search = FishingSpot.search(world(context), feet(context), excludedStances, excludedLandings);
        }
        if (!(search instanceof FishingSpot.Search.Found found)) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, FishingSpot.messageOf(search),
                    "沿着岸走到更开阔的水边再试，或先 travel 去别的水域"));
        }
        spot = found.stance();
        landing = found.landing();
        recordProgress("选定钓位与落点");
        return feet(context).equals(spot) ? Next.go(Phase.AIM, "就站在钓位上") : Next.go(Phase.APPROACH, "选定钓位");
    }

    private Next<Phase> approach(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Done) return Next.go(Phase.AIM, "到钓位了");
        // 走不到这个钓位：换下一个候选（不撞同一条路），找不到就按钓位搜索的三分流如实结束。
        excludedStances.add(spot);
        spot = null;
        landing = null;
        return Next.go(Phase.FIND_SPOT, "这个钓位走不过去，换下一个");
    }

    private Next<Phase> aim(TickContext context) {
        if (!landingStillClear(context)) {
            return scrapCast(context, "选中的水面被挡住了", true);
        }
        Vec3 eye = context.player().localPlayer().getEyePosition();
        Vec3 aim = CastTrajectory.aimPoint(eye, landing);
        context.player().input().lookAt(context.player().localPlayer(), aim);
        // 等真实视角转到位再出手：按这个俯仰角抛出去的钩子才会落到选中的水面。
        if (aimOnTarget(context, eye, aim)) {
            cast.aimSettled++;
            if (cast.aimSettled >= AIM_SETTLE_TICKS) return Next.go(Phase.CAST, "瞄准到位");
        } else {
            cast.aimSettled = 0;
        }
        return Next.stay();
    }

    private Next<Phase> castAndConfirm(TickContext context) {
        if (cast.castPending == null) {
            if (!landingStillClear(context)) return scrapCast(context, "选中的水面被挡住了", true);
            if (!context.player().canInteractThisTick()) return Next.stay();
            cast.castPending = context.player().interactionSender().useItem(context.player(), InteractionHand.MAIN_HAND,
                    new HookConfirmation(services.hooks()::hasHook), CONFIRM_TICKS);
            return Next.stay();
        }
        cast.castPending = context.player().interactionSender().poll(context.player(), cast.castPending);
        if (!cast.castPending.terminal()) return Next.stay();
        PendingInteraction settled = cast.castPending;
        cast.castPending = null;
        if (settled.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
            casts++;
            recordProgress("抛出第 " + casts + " 竿");
            cast.waitTicks = 0;
            return Next.go(Phase.WAIT_BITE, "抛竿确认，角色关联上了新的鱼钩");
        }
        if (settled.status() == PendingInteraction.Status.UNCERTAIN) {
            // 抛没抛出去都没能确认：记录事实后结束，不盲目再抛一次把状态搅浑。
            recordUnconfirmed(new Change(Change.Kind.OTHER, "抛竿", 1, "提交后没能确认鱼钩有没有关联上"));
            return Next.fail(Problem.of(Problem.Kind.STUCK, "抛竿没能确认结果，不再重抛"));
        }
        return scrapCast(context, "游戏没有确认抛竿", false);
    }

    private Next<Phase> waitBite(TickContext context) {
        cast.hookSeen = services.hooks().hasHook();
        cast.waitTicks++;
        // 隔了一截才恢复推进：中间被生存需求插过队，挂着钩的那几刻咬了没收，先收掉再重抛。
        if (cast.lastWaitTick != Long.MIN_VALUE && context.gameTick() > cast.lastWaitTick + 1 && cast.hookSeen) {
            cast.lastWaitTick = context.gameTick();
            return beginReel(ReelReason.ABANDON, "让位期间咬了没收的一竿", true);
        }
        cast.lastWaitTick = context.gameTick();
        BiteWatch.Verdict verdict = BiteWatch.judge(new BiteWatch.BiteFacts(cast.hookSeen,
                services.hooks().isBiting(), services.hooks().hookedEntity(),
                services.hooks().hookInWater(), cast.waitTicks));
        return switch (verdict.step()) {
            case WAIT -> Next.stay();
            case REEL_IN -> {
                recordProgress("咬钩了，正在收竿");
                yield beginReel(ReelReason.KEEP, null, false);
            }
            case REEL_AND_SCRAP -> beginReel(ReelReason.ABANDON,
                    BiteWatch.reason(verdict.step(), cast.waitTicks), verdict.rejectsLanding());
            case SCRAP_GONE -> scrapCast(context, BiteWatch.reason(verdict.step(), cast.waitTicks), false);
        };
    }

    private Next<Phase> reel(TickContext context) {
        if (cast.reelPending == null) {
            if (!context.player().canInteractThisTick()) return Next.stay();
            cast.reelPending = context.player().interactionSender().useItem(context.player(), InteractionHand.MAIN_HAND,
                    new HookConfirmation(() -> !services.hooks().hasHook()), CONFIRM_TICKS);
            return Next.stay();
        }
        cast.reelPending = context.player().interactionSender().poll(context.player(), cast.reelPending);
        if (!cast.reelPending.terminal()) return Next.stay();
        PendingInteraction settled = cast.reelPending;
        cast.reelPending = null;
        if (settled.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
            if (cast.reelReason == ReelReason.KEEP) return startCollect();
            // 报废的一竿收干净了：按暂存的原因排除水面并计数，连续五次结束。
            return finishScrap(cast.scrapWhy, cast.scrapRejects);
        }
        if (settled.status() == PendingInteraction.Status.UNCERTAIN) {
            recordUnconfirmed(new Change(Change.Kind.OTHER, "收竿", 1, "提交后没能确认鱼钩消失"));
            return Next.fail(Problem.of(Problem.Kind.STUCK, "收竿没能确认结果，不再重发"));
        }
        return scrapCast(context, "收竿没有生效", false);
    }

    private Next<Phase> collect(TickContext context) {
        return switch (collect.step) {
            case GRACE -> {
                // 先等约 1 秒：原版收线把战利品抛向玩家，这段时间走近多半就能被拾取包络吸进包。
                if (++collect.waited < LOOT_RETURN_GRACE_TICKS) yield Next.stay();
                collect.baseline = itemCounts(context);
                collect.lootBefore = services.pickups().nearby();
                collect.step = CollectStep.PICKUP;
                yield Next.go(Phase.COLLECT, "冲量窗口过了，开始捡这一竿的钓获");
            }
            case PICKUP -> {
                ActionStatus status = runAction(context);
                if (status instanceof ActionStatus.Running) yield Next.stay();
                if (status instanceof ActionStatus.Done) yield settleLoot(context);
                // 捡不动多半是背包放不下：先腾一格再来一轮；腾不动以原问题如实结束，已钓到的照算。
                if (backpackFreeSlots(context) > 0) yield Next.fail(((ActionStatus.Failed) status).problem());
                collect.step = CollectStep.MAKEROOM;
                collect.makeRoom = services.space().makeRoom(1, "捡起钓获", Set.of(FISHING_ROD), permissions, records());
                yield Next.go(Phase.COLLECT, "背包放不下钓获，先腾一格");
            }
            case MAKEROOM -> runActionThen(context, () -> {
                collect.step = CollectStep.PICKUP;
                collect.pickup = null;
                return Next.go(Phase.COLLECT, "腾出了地方，接着捡");
            });
        };
    }

    /** 捡拾结算：以背包实际增长确认拿到；对得上才记一次收获，对不上如实结束，不冒充钓到。 */
    private Next<Phase> settleLoot(TickContext context) {
        collect.pickup = null;
        Map<String, Integer> now = itemCounts(context);
        int growth = 0;
        Map<String, Integer> gains = new HashMap<>();
        for (Map.Entry<String, Integer> entry : now.entrySet()) {
            int before = collect.baseline.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > before) {
                gains.put(entry.getKey(), entry.getValue() - before);
                growth += entry.getValue() - before;
            }
        }
        if (growth <= 0) {
            // 收竿确认了、钓获却一件都没进包：可归属的钓获与背包实收对不上，如实结束。
            return Next.fail(Problem.of(Problem.Kind.STUCK,
                    "收竿成功了，但没有钓获进背包（已钓到 " + caught + " 次、抛了 " + casts + " 竿）"));
        }
        // 钓到什么记什么：按背包差集一条 item_gained，垃圾和宝藏与鱼同样记账。
        gains.forEach((itemId, count) -> recordChange(Change.of(Change.Kind.ITEM_GAINED, itemId, count)));
        caught++;
        // 成功一竿清零失败计数并清排除集：小池塘的落点重新可用，清空重算的限额也重新给一次。
        failedCasts = 0;
        excludedLandings.clear();
        excludedStances.clear();
        retriedAfterClear = false;
        if (input.count() > 0 && caught >= input.count()) {
            return Next.done(TaskResult.done("钓到了 " + caught + " 次，抛了 " + casts + " 竿"));
        }
        cooldownWaited = 0;
        return Next.go(Phase.COOLDOWN, "第 " + caught + " 次收获进了背包");
    }

    private Next<Phase> coolDown(TickContext context) {
        if (++cooldownWaited < COOLDOWN_TICKS) return Next.stay();
        return Next.go(Phase.CHECK_ROD, "短暂冷却，来下一竿");
    }

    /** 报废这一竿：钩还挂着先收掉（收竿确认后再交代），钩已经没了直接结算。 */
    private Next<Phase> scrapCast(TickContext context, String why, boolean rejectLanding) {
        if (services.hooks().hasHook()) {
            cast.scrapWhy = why;
            cast.scrapRejects = rejectLanding;
            return beginReel(ReelReason.ABANDON, why, rejectLanding);
        }
        return finishScrap(why, rejectLanding);
    }

    /** 报废的结算：水面按原因进排除集，计数加一；连续五次结束（UNREACHABLE，建议换开阔的岸边）。 */
    private Next<Phase> finishScrap(String why, boolean rejectLanding) {
        recordAttempt("抛竿", why);
        if (rejectLanding && landing != null) excludedLandings.add(landing);
        failedCasts++;
        if (failedCasts >= MAX_FAILED_CASTS) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    why + "，连续 " + failedCasts + " 次抛竿失败；建议换到开阔的岸边再试"));
        }
        spot = null;
        landing = null;
        return Next.go(Phase.FIND_SPOT, why + "，重找钓位");
    }

    private Next<Phase> beginReel(ReelReason reason, String scrapWhy, boolean scrapRejects) {
        cast.reelReason = reason;
        cast.scrapWhy = scrapWhy;
        cast.scrapRejects = scrapRejects;
        return Next.go(Phase.REEL, reason == ReelReason.KEEP ? "咬钩，收竿" : "先收掉挂着的钩");
    }

    private Next<Phase> startCollect() {
        collect.step = CollectStep.GRACE;
        collect.waited = 0;
        collect.pickup = null;
        collect.makeRoom = null;
        collect.baseline = Map.of();
        return Next.go(Phase.COLLECT, "收竿确认，等战利品飞回来");
    }

    /** 落点此刻还开不开阔：水没了或弹道被挡住的落点不再抛第二次。 */
    private boolean landingStillClear(TickContext context) {
        if (landing == null) return false;
        FishingSpot.World world = world(context);
        return world.castableWater(landing)
                && CastTrajectory.clearsTerrain(context.player().localPlayer().getEyePosition(), landing,
                        world::terrainBlocked);
    }

    /** 真实视线是否已对准抛投方向：方向点积过线才算瞄准到位。 */
    private boolean aimOnTarget(TickContext context, Vec3 eye, Vec3 aim) {
        Vec3 wanted = aim.subtract(eye).normalize();
        var player = context.player().localPlayer();
        Vec3 looking = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        return wanted.dot(looking) >= AIM_TOLERANCE_COS;
    }

    private boolean hasRod(TickContext context) {
        WantedItem rod = WantedItem.ofItem(FISHING_ROD);
        for (BackpackStack stack : context.player().backpack().stacks()) {
            if (rod.matches(stack.itemId(), Set.of())) return true;
        }
        // 离线替身没有角色对象：副手查不了就只认背包，实机上有角色时连副手一起认。
        var player = context.player().localPlayer();
        return player != null && player.getOffhandItem().is(Items.FISHING_ROD);
    }

    private int backpackFreeSlots(TickContext context) {
        return context.player().backpack().freeSlots();
    }

    private Map<String, Integer> itemCounts(TickContext context) {
        Map<String, Integer> counts = new HashMap<>();
        for (BackpackStack stack : context.player().backpack().stacks()) {
            counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return counts;
    }

    private FishingSpot.World world(TickContext context) {
        return new LiveFishingWorld(context.player(), services.protectedCells());
    }

    private BlockPos feet(TickContext context) {
        return context.player().localPlayer().blockPosition();
    }

    private Action nextPickup() {
        collect.pickup = services.pickups().pickUpNewSince(collect.lootBefore);
        return collect.pickup;
    }

    /** 等咬钩可以被打断；收竿或抛竿已提交、等游戏确认的那几刻停下不安全。 */
    @Override public Interruptibility interruptibility(TickContext context) {
        if (phase() == Phase.REEL && cast.reelPending != null) return Interruptibility.UNSAFE_TO_STOP;
        if (phase() == Phase.CAST && cast.castPending != null) return Interruptibility.UNSAFE_TO_STOP;
        return super.interruptibility(context);
    }

    @Override protected FishDetails details() {
        return new FishDetails(caught, casts);
    }

    /** 抛出后钩要挂上、收线后钩要消失：以角色关联的鱼钩出现与消失确认这两下交互。 */
    private record HookConfirmation(BooleanSupplier expected) implements InteractionConfirmation {
        @Override public Verdict observe(PlayerContext context) {
            return expected.getAsBoolean() ? Verdict.APPLIED : Verdict.PENDING;
        }

        @Override public int stableTicksRequired() {
            return 1;
        }
    }

    /** 不给次数就一直钓：常驻任务豁免内核的兜底时限，卡没卡住由进度跟踪负责。 */
    static final class StandingFish extends FishTask implements Standing {
        StandingFish(FishInput input, FishServices services, Permissions permissions) {
            super(input, services, permissions);
        }
    }

    /** 钓位判断的生产读端：站位、水面、保护与弹道碰撞都从当刻的角色现场读。 */
    private static final class LiveFishingWorld implements FishingSpot.World {
        private final PlayerContext context;
        private final ProtectedCells protectedCells;

        LiveFishingWorld(PlayerContext context, ProtectedCells protectedCells) {
            this.context = context;
            this.protectedCells = protectedCells;
        }

        @Override public boolean isLoaded(BlockPos pos) {
            return context.level().isLoaded(pos);
        }

        @Override public boolean dryStance(BlockPos pos) {
            var level = context.level();
            return level.getFluidState(pos).isEmpty()
                    && level.getFluidState(pos.above()).isEmpty()
                    && BlockPassability.canWalkThrough(level, pos)
                    && BlockPassability.canWalkThrough(level, pos.above())
                    && BlockPassability.canWalkOn(level, pos.below());
        }

        @Override public boolean castableWater(BlockPos pos) {
            var level = context.level();
            var fluid = level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return false;
            if (level.getFluidState(pos.above()).is(FluidTags.WATER)) return false;
            return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
        }

        @Override public boolean protectedCell(BlockPos pos) {
            return protectedCells.contains(pos);
        }

        @Override public boolean terrainBlocked(Vec3 from, Vec3 to) {
            var hit = context.level().clip(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context.localPlayer()));
            return hit.getType() != HitResult.Type.MISS;
        }
    }
}
