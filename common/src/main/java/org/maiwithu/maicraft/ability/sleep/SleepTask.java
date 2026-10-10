// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 睡觉的任务：选床 → （没床就放自带的或现做一张）→ 走到床边 → 躺下。夜间自动休息沿用这份流程，
 * 躺下之后等醒、收床、回站位的收尾由子类接管；公开睡觉（公开能力）躺下即完成，不保证睡到天亮。
 *
 * <p>床的过滤与排序在选床的纯函数里；这里只把结论变成靠近与点击，并把游戏的拒绝按提示语分流：
 * 被占用、床边有怪就换一张床（试过的不再试，有次数上限）；不在可睡时间就带着"现在睡不了"结束；
 * 太远或被挡先退回换一次站位，再不行换床。
 */
class SleepTask extends PhasedTask<SleepTask.Phase> {

    /** 任务的进度：选床 → 放床 → 拿床 → 走到床边 → 躺下 →（夜休接手：等醒 → 收床 → 回站位）。 */
    enum Phase { CHOOSE_BED, PLACE_BED, GET_BED, APPROACH, LIE_DOWN, WAIT_WAKE, COLLECT_BED, WALK_BACK }

    /** 换床次数上限：接连几张都不行说明这片床区整体不行，如实结束，不无限换。玩家常识。 */
    private static final int MAX_BED_TRIES = 3;
    /** 十秒没有真实进展算卡住：靠近一张床、一次放置都该在十秒里有动静。 */
    private static final int STUCK_AFTER_TICKS = 200;
    /** 最多做一刻钟：选床扫描加绕路走近，再长就是路线或床本身有问题。 */
    private static final long MAX_TICKS = 20L * 60 * 15;

    final SleepInput input;
    final Permissions permissions;
    private final BedScanner scanner;
    private final PlacesBed placer;
    private final ItemNeeds obtain;
    protected final BringsPlayerClose approaches;
    private final UsesBeds usesBed;
    /** 最近一条动作栏提示语的读端：游戏拒绝入睡时按它分流。 */
    private final Supplier<Optional<String>> refusalTexts;
    /** 身上带着的床：决定"没床"时走放床还是先弄一张。 */
    private final Supplier<Optional<String>> carriedBed;

    // 流程的现场状态：选中的床、床的来源、试过不行的床、躺下后的细节。
    BlockPos chosenBed;
    private SleepDetails.BedSource bedSource = SleepDetails.BedSource.WORLD;
    private final Set<BlockPos> excluded = new HashSet<>();
    private int bedTries;
    private boolean enteredSleep;
    private boolean nightSkippedBySleep;

    SleepTask(SleepInput input, Permissions permissions, BedScanner scanner, PlacesBed placer,
            ItemNeeds obtain, BringsPlayerClose approaches, UsesBeds usesBed,
            Supplier<Optional<String>> refusalTexts, Supplier<Optional<String>> carriedBed) {
        super("睡觉", Phase.CHOOSE_BED, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.input = input;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scanner = scanner;
        this.placer = placer;
        this.obtain = obtain;
        this.approaches = approaches;
        this.usesBed = usesBed;
        this.refusalTexts = refusalTexts;
        this.carriedBed = carriedBed;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            // 走到同时满足服务端床距离、交互距离和视线的站位；被挡住时由靠近模型内部换站位。
            case APPROACH -> approaches.toward(ApproachTarget.ofBed(chosenBed), permissions);
            case LIE_DOWN -> usesBed.use(chosenBed, lyingDownConfirmation());
            // 拿一张床回来：合成（3 块同色羊毛加 3 块木板）也在拿到物品引擎的途径里；弄不到如实失败。
            case GET_BED -> obtain.actionFor(
                    new ItemRequest(WantedItem.ofTag("minecraft:beds"), 1, "睡觉"), permissions);
            // 身上放好床了再放：放床动作自己挑格、换手、点支撑面。
            case PLACE_BED -> placer.placeCarriedBed()
                    .map(placement -> (Action) placement)
                    .orElse(null);
            default -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHOOSE_BED -> chooseBed(context);
            case PLACE_BED -> runActionThen(context, this::bedPlaced);
            case GET_BED -> runActionThen(context, () -> {
                // 现做的床要再放下才能睡：来源记成 crafted。
                bedSource = SleepDetails.BedSource.CRAFTED;
                return Next.go(Phase.PLACE_BED, "拿到了床，放下再睡");
            });
            case APPROACH -> {
                Next<Phase> skipped = onSleepTimePassed(context);
                if (skipped != null) {
                    yield skipped;
                }
                yield runActionThen(context, () -> Next.go(Phase.LIE_DOWN, "到床边了"));
            }
            case LIE_DOWN -> lieDown(context);
            case WAIT_WAKE -> tickWaitWake(context);
            case COLLECT_BED -> tickCollectBed(context);
            case WALK_BACK -> tickWalkBack(context);
        };
    }

    // 选床：会爆炸的维度不试；扫描没扫完不能当成"没有"；选中的床排除试过的，按近到远拿第一张。
    private Next<Phase> chooseBed(TickContext context) {
        if (context.player() != null && SleepRules.bedsExplodeHere(context.player().level())) {
            // 下界、末地的床一点就炸，无法事后补救：直接结束并说明，不提问。
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这个维度的床会爆炸，睡不了", null));
        }
        BedScanner.BedScan scan = scanner.scan(context, excluded, permissions.protectedLandmarks());
        Optional<BedCandidate> chosen = BedChooser.choose(scan.candidates(), excluded, input.bedArea());
        if (chosen.isPresent()) {
            chosenBed = chosen.get().head();
            bedTries++;
            bedSource = SleepDetails.BedSource.WORLD;
            recordProgress("选中了一张床");
            return Next.go(Phase.APPROACH, "选中了一张床，走过去");
        }
        if (!scan.complete()) {
            return Next.stay();
        }
        // 附近没有能用的床：身上有就放自带的，没有就先去弄一张；弄不到时拿床那条路自己给原因。
        if (carriedBed.get().isPresent() && placer.placeCarriedBed().isPresent()) {
            return Next.go(Phase.PLACE_BED, "附近没有能用的床，放自带的");
        }
        return Next.go(Phase.GET_BED, "附近没有床、身上也没有，先弄一张");
    }

    // 床放下了：从放床动作读实际床头，按床的来路记账。
    private Next<Phase> bedPlaced() {
        BlockPos head = action() instanceof PlacesBed.BedPlacement placement ? placement.placedHead() : null;
        if (head == null) {
            return Next.fail(Problem.of(Problem.Kind.STUCK, "床放下了但读不到床头在哪一格", null));
        }
        if (bedSource != SleepDetails.BedSource.CRAFTED) {
            bedSource = SleepDetails.BedSource.CARRIED;
        }
        chosenBed = head;
        recordChange(Change.of(Change.Kind.BLOCK_PLACED, carriedBed.get().orElse("床"), 1));
        return Next.go(Phase.APPROACH, "床放好了，走过去睡");
    }

    // 躺下：确认条件报告 APPLIED 就是躺下了（或一觉到天亮）；被拒按提示语分流。
    private Next<Phase> lieDown(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        if (status instanceof ActionStatus.Done) {
            enteredSleep = true;
            return afterFellAsleep(context);
        }
        Problem refusal = ((ActionStatus.Failed) status).problem();
        if (refusal.kind() == Problem.Kind.REFUSED_BY_GAME) {
            return onGameRefusal(refusal);
        }
        if (refusal.kind() == Problem.Kind.TARGET_GONE) {
            // 床在途中被拆了：回选床重挑，不把消失的床再点一遍。
            excluded.add(chosenBed);
            return Next.go(Phase.CHOOSE_BED, "床没了，换一张");
        }
        if (refusal.kind() == Problem.Kind.UNREACHABLE) {
            return repositionOrGiveUp(refusal, "从现在的站位够不着这张床");
        }
        return onFail(refusal);
    }

    // 游戏明确拒绝了这次入睡：按提示语分流，认不出的如实带原文上报。
    private Next<Phase> onGameRefusal(Problem refusal) {
        String text = refusalTexts.get().orElse(refusal.message());
        return switch (SleepRefusal.of(text)) {
            case OCCUPIED, MONSTERS_NEARBY -> {
                // 床被占用、床边有怪：这张床排除掉，换下一张；接连不行到上限就如实结束。
                excluded.add(chosenBed);
                yield giveUpAfterTries(Next.go(Phase.CHOOSE_BED, "这张床睡不上，换一张"), text);
            }
            case NOT_SLEEP_TIME -> onNoLongerSleepTime();
            case TOO_FAR_OR_BLOCKED -> repositionOrGiveUp(refusal, "床太远或被挡住");
            case OTHER -> onFail(refusal);
        };
    }

    // 太远或被挡：先退回靠近换站位；换过了还不行就换床，次数到顶如实结束。
    private Next<Phase> repositionOrGiveUp(Problem refusal, String why) {
        if (bedTries < MAX_BED_TRIES) {
            return Next.go(Phase.APPROACH, why + "，换一次站位再点");
        }
        excluded.add(chosenBed);
        return giveUpAfterTries(Next.go(Phase.CHOOSE_BED, why + "，换一张床"), refusal.message());
    }

    // 次数到顶：把给定的走向换成一次带事实的结束，不无限换下去。
    private Next<Phase> giveUpAfterTries(Next<Phase> otherwise, String lastFact) {
        if (bedTries >= MAX_BED_TRIES) {
            String fact = "床区里的床接连睡不上，最近一次：" + lastFact;
            recordAttempt("换一张床", fact);
            return onFail(Problem.of(Problem.Kind.NOT_FOUND, fact, null));
        }
        return otherwise;
    }

    /** 确认条件：角色正在睡就是躺下了；确认期间日期前进说明一觉睡到了天亮，同样算成功。 */
    private InteractionConfirmation lyingDownConfirmation() {
        // 点床那一刻还没睡：把那天的日期记下来，之后哪一刻日期变了，就是睡过了一夜。
        return new InteractionConfirmation() {
            private Long dayAtClick;

            @Override
            public Verdict observe(PlayerContext context) {
                if (context.localPlayer().isSleeping()) {
                    return Verdict.APPLIED;
                }
                long day = WorldTime.dayIndexOf(context.level().getDayTime());
                if (dayAtClick == null) {
                    dayAtClick = day;
                } else if (day != dayAtClick) {
                    nightSkippedBySleep = true;
                    return Verdict.APPLIED;
                }
                return Verdict.PENDING;
            }
        };
    }

    /** 躺下成功后的走向：公开能力躺下即完成；夜休在这里接手等醒。 */
    protected Next<Phase> afterFellAsleep(TickContext context) {
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                nightSkippedBySleep ? "躺下后一觉睡到了天亮" : "躺下了").build());
    }

    /** 不再在可睡窗口里（天亮了、雷停了）：以时间不对结束，入睡留给下一次。 */
    protected Next<Phase> onNoLongerSleepTime() {
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "现在睡不了：已经不在可睡的时间")
                .problem(Problem.of(Problem.Kind.WRONG_TIME, "现在已经不是能睡的时间了", null))
                .remaining("入睡").build());
    }

    /** 去床途中可睡窗口关了的统一收口；夜休把它当"夜晚被别人睡过"。默认按时间不对结束。 */
    protected Next<Phase> onSleepTimePassed(TickContext context) {
        return null;
    }

    /** 流程走不下去时的收口：公开能力如实失败；夜休先回站位再交代。 */
    protected Next<Phase> onFail(Problem problem) {
        return Next.fail(problem);
    }

    // 以下三个阶段只有夜休走进来；公开能力躺下即完成，不会到达。
    protected Next<Phase> tickWaitWake(TickContext context) {
        return Next.done(TaskResult.done("躺下了"));
    }

    protected Next<Phase> tickCollectBed(TickContext context) {
        return Next.done(TaskResult.done("躺下了"));
    }

    protected Next<Phase> tickWalkBack(TickContext context) {
        return Next.done(TaskResult.done("躺下了"));
    }

    @Override
    protected ResultDetails details() {
        return new SleepDetails(enteredSleep, bedSource, null, nightSkippedBySleep, false);
    }

    /** 给子类读的"床试了几张"；回站位收尾的判断用。 */
    protected final int bedTries() {
        return bedTries;
    }

    /** 给子类读的床的来源：夜休判断醒后要不要收床。 */
    protected final SleepDetails.BedSource bedSource() {
        return bedSource;
    }

    /** 给子类读的"一觉睡到了天亮"：确认期间日期前进时为真。 */
    protected final boolean nightSkippedBySleep() {
        return nightSkippedBySleep;
    }
}
