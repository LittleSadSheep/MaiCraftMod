// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 扔末影之眼找要塞的一趟：扔一只 → 看它往哪飞 → 落下就走过去捡回 → 沿飞向走一段 → 再扔，
 * 直到眼往下钻（要塞就在脚下）或两次飞向交出位置，把要塞记成世界记忆里的产地线索。
 *
 * <p>眼是这件事本身要消耗的东西，不另看稀有消耗品的许可；碎了的、没捡回的如实记进变化。
 * 走的那几段交给走到，能不能挖垫按这次任务的许可；一段走不通但已经走出一截，就在那里再扔，
 * 一步都没挪动才按到不了收场。眼用完了按缺东西如实说，带上最后一只指的方向。
 */
public final class EnderEyeSearch implements Action {

    /** 原版末影之眼只指这一种结构（结构标签 eye_of_ender_located 里只有要塞）；客户端读不到结构注册表，按 ID 认。 */
    public static final String STRONGHOLD = "minecraft:stronghold";
    /** 扔的那样东西。 */
    public static final String ENDER_EYE = "minecraft:ender_eye";
    /** 一趟最多扔几只：远在一两千格外也够一路走到，再多就先停下说清眼指着哪。 */
    static final int MAX_THROWS = 20;
    /** 一段没走完、但已经离上一个出手点这么远（格）：就在这里再扔，前面那只眼不白扔。 */
    static final int WORTH_THROWING_AGAIN = 12;
    /** 眼不够时的建议：先拿几只再接着找。 */
    private static final String OBTAIN_EYES = "先用 obtain 拿几只 " + ENDER_EYE + "（末影珍珠加烈焰粉合成），"
            + "再 find(structure=" + STRONGHOLD + ") 接着找";

    /** 一趟里此刻在做的那一步。 */
    private enum Stage { THROW, FETCH, WALK, FINISHED }

    private final FollowsEnderEyes eyes;
    private final RemembersSightings memory;
    private final TerrainPermit permit;
    private final TaskRecords records;

    private Stage stage = Stage.THROW;
    private FollowsEnderEyes.EyeThrow throwing;
    private Action fetching;
    private WalkRun walk;
    /** 捡完眼之后接着做哪一步。 */
    private Stage afterFetch;
    /** 上一次读得出方向的那一扔；两次飞向交叉要拿它比。 */
    private EyeFlight earlier;
    private EyeFlight latest;
    /** 走过头过：要塞已经夹在走过的路上，之后每段收短。 */
    private boolean overshot;
    private EyeBearings.Verdict.Found found;
    private int targetX;
    private int targetZ;
    private int thrown;
    private int shattered;
    private int fetched;
    private int notFetched;
    /** 去捡之前身上有几只眼：捡没捡回按身上多没多出一只算，不按走没走过去算。 */
    private int carriedBeforeFetch;
    private WorldPosition conclusion;
    private String summary;

    /**
     * @param permit  走那几段被允许动多少地形，按这次任务的许可
     * @param records 发起它的任务的记账口：碎了、没捡回的眼与每一扔的经过记进那次任务的结果
     */
    public EnderEyeSearch(FollowsEnderEyes eyes, RemembersSightings memory, TerrainPermit permit,
            TaskRecords records) {
        this.eyes = eyes;
        this.memory = memory;
        this.permit = permit;
        this.records = records;
    }

    /** 末影之眼指不指得到这种结构。 */
    public static boolean locates(String structureId) {
        return structureId != null && STRONGHOLD.equals(structureId.toLowerCase(Locale.ROOT));
    }

    /** 找到的要塞位置（地面上的大概落点，要塞在地下）；还没找到为空。 */
    public Optional<WorldPosition> conclusion() {
        return Optional.ofNullable(conclusion);
    }

    /** 找到时的一句话结论：在哪、离多远、凭什么说、扔了几只碎了几只；还没找到为 null。 */
    public String summary() {
        return summary;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case THROW -> throwOne(context);
            case FETCH -> fetchEye(context);
            case WALK -> walkLeg(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 扔一只：身上没眼了就按缺东西收场；扔完按看到的经过判断下一步。
    private ActionStatus throwOne(TickContext context) {
        if (throwing == null) {
            if (eyes.eyesCarried() <= 0) {
                return ActionStatus.failed(outOfEyes());
            }
            throwing = eyes.throwEye();
        }
        ActionStatus status = throwing.tick(context);
        if (status instanceof ActionStatus.Running) {
            return status;
        }
        FollowsEnderEyes.EyeThrow done = throwing;
        throwing = null;
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(failed.problem().kind(),
                    "扔末影之眼没扔成：" + failed.problem().message(), failed.problem().suggestion()));
        }
        return afterThrow(done.flight());
    }

    // 眼飞起来了：碎了记一笔消耗，再按判断找到收工、往眼指的方向走、或原地再扔；落下的先捡回再走。
    private ActionStatus afterThrow(Optional<EyeFlight> seen) {
        if (seen.isEmpty()) {
            // 原版在没有要塞的世界里扔眼不飞也不扣眼：如实说，不再扔第二只。
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "扔出一只末影之眼没有飞起来：这个世界里没有它能指的要塞（原版只有主世界有要塞）",
                    "回主世界再找要塞"));
        }
        EyeFlight flight = seen.get();
        thrown++;
        if (flight.landing() == EyeFlight.Landing.SHATTERED) {
            shattered++;
            records.change(Change.of(Change.Kind.ITEM_CONSUMED, ENDER_EYE, 1));
        }
        EyeBearings.Verdict verdict = EyeBearings.judge(earlier, flight, overshot);
        records.attempt("扔第 " + thrown + " 只末影之眼", describe(flight, verdict));
        latest = flight;
        return switch (verdict) {
            case EyeBearings.Verdict.Found hit -> {
                found = hit;
                yield fetchThen(flight, Stage.FINISHED);
            }
            case EyeBearings.Verdict.Walk leg -> {
                earlier = flight;
                overshot = leg.overshot();
                if (thrown >= MAX_THROWS) yield giveUp(flight);
                targetX = (int) Math.floor(flight.fromX() + leg.dx() * leg.leg());
                targetZ = (int) Math.floor(flight.fromZ() + leg.dz() * leg.leg());
                yield fetchThen(flight, Stage.WALK);
            }
            case EyeBearings.Verdict.Unreadable unreadable ->
                    thrown >= MAX_THROWS ? giveUp(flight) : fetchThen(flight, Stage.THROW);
        };
    }

    // 扔满一趟还没交出位置：这一只落下了也不再去捡（如实记成掉在地上），带着最后的方向收场。
    private ActionStatus giveUp(EyeFlight flight) {
        if (flight.landing() != EyeFlight.Landing.SHATTERED) {
            notFetched++;
            if (flight.landing() == EyeFlight.Landing.DROPPED) {
                records.change(Change.of(Change.Kind.ITEM_DROPPED, ENDER_EYE, 1));
            }
        }
        return ActionStatus.failed(tooManyThrows());
    }

    // 落下的眼先走过去捡回，再做下一步；碎了、没看到落下的直接做下一步。
    private ActionStatus fetchThen(EyeFlight flight, Stage next) {
        if (flight.landing() == EyeFlight.Landing.DROPPED && flight.dropEntity() >= 0) {
            carriedBeforeFetch = eyes.eyesCarried();
            fetching = eyes.fetch(flight.dropEntity());
            afterFetch = next;
            stage = Stage.FETCH;
            return ActionStatus.progressed();
        }
        if (flight.landing() == EyeFlight.Landing.LOST_SIGHT) {
            notFetched++;
        }
        return goTo(next);
    }

    // 捡回落下的眼：身上多出一只算捡回；没多出来的如实记成掉在地上，都接着做下一步。
    private ActionStatus fetchEye(TickContext context) {
        ActionStatus status = fetching.tick(context);
        if (status instanceof ActionStatus.Running) {
            return status;
        }
        fetching = null;
        if (eyes.eyesCarried() > carriedBeforeFetch) {
            fetched++;
        } else {
            notFetched++;
            records.change(Change.of(Change.Kind.ITEM_DROPPED, ENDER_EYE, 1));
            records.attempt("捡回落下的末影之眼", "没捡回"
                    + (status instanceof ActionStatus.Failed failed ? "：" + failed.problem().message() : ""));
        }
        return goTo(afterFetch);
    }

    private ActionStatus goTo(Stage next) {
        stage = next;
        return switch (next) {
            case FINISHED -> finish();
            case WALK -> {
                walk = eyes.walkToward(targetX, targetZ, permit);
                yield ActionStatus.progressed();
            }
            default -> ActionStatus.progressed();
        };
    }

    // 沿飞向走一段：走到了再扔；中途被停下就从原地接着走；走不通但已经走出一截就在那里再扔。
    private ActionStatus walkLeg(TickContext context) {
        ActionStatus status = walk.tick(context);
        if (walk.report().state() == WalkReport.State.STOPPED) {
            walk.close();
            walk = eyes.walkToward(targetX, targetZ, permit);
            return ActionStatus.running();
        }
        return switch (status) {
            case ActionStatus.Running running -> status;
            case ActionStatus.Done done -> {
                stage = Stage.THROW;
                yield ActionStatus.progressed();
            }
            case ActionStatus.Failed failed -> {
                int moved = movedSinceLastThrow();
                if (moved >= WORTH_THROWING_AGAIN) {
                    records.attempt("往末影之眼指的方向走一段",
                            "走了 " + moved + " 格走不过去（" + failed.problem().message() + "），就在这里再扔一只");
                    stage = Stage.THROW;
                    yield ActionStatus.progressed();
                }
                yield ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "往末影之眼指的" + EyeBearings.compassOf(latest) + "边走不过去：" + failed.problem().message()
                                + "；" + eyesUsed(),
                        "换个地方绕过去再找，或放宽这次的 change_blocks 让它能挖开、垫过去"));
            }
        };
    }

    // 找到了：记进世界记忆当要塞的产地线索（地面上的大概落点），写一句结论。
    private ActionStatus finish() {
        Optional<WorldPosition> feet = eyes.feet();
        int y = feet.map(WorldPosition::y).orElse((int) Math.floor(latest.fromY()));
        conclusion = new WorldPosition(found.x(), y, found.z(), feet.map(WorldPosition::dimension).orElse(null));
        memory.siteSeen(conclusion, List.of(STRONGHOLD), Instant.now());
        int away = feet.map(at -> (int) Math.round(Math.hypot(at.x() - found.x(), at.z() - found.z()))).orElse(0);
        summary = "末影之眼指路：要塞大概在 (" + found.x() + ", " + found.z() + ") 的地下，离这里约 " + away
                + " 格（" + found.how() + "）；" + eyesUsed();
        stage = Stage.FINISHED;
        return ActionStatus.done();
    }

    // 一扔的经过写成一句：往哪飞、落下还是碎了、这一扔说明了什么。
    private static String describe(EyeFlight flight, EyeBearings.Verdict verdict) {
        String where = EyeBearings.dives(flight) ? "往地下钻" : "往" + EyeBearings.compassOf(flight) + "飞";
        String end = switch (flight.landing()) {
            case DROPPED -> "落下了";
            case SHATTERED -> "碎了";
            case LOST_SIGHT -> "没看到它落在哪";
        };
        String then = switch (verdict) {
            case EyeBearings.Verdict.Found hit -> hit.how();
            case EyeBearings.Verdict.Walk leg -> "接着往那边走 " + leg.leg() + " 格";
            case EyeBearings.Verdict.Unreadable unreadable -> "看不出方向，原地再扔";
        };
        return where + "，" + end + "；" + then;
    }

    private int movedSinceLastThrow() {
        return eyes.feet().map(at -> (int) Math.round(Math.hypot(at.x() - latest.fromX(), at.z() - latest.fromZ())))
                .orElse(0);
    }

    private String eyesUsed() {
        return "扔了 " + thrown + " 只，碎了 " + shattered + " 只，捡回 " + fetched + " 只"
                + (notFetched > 0 ? "，" + notFetched + " 只没捡回" : "");
    }

    /** 身上一只末影之眼都没有：找要塞扔不了眼指路，按缺东西说，建议先去拿几只。 */
    public static Problem carriesNoEyes() {
        return Problem.of(Problem.Kind.NEED_ITEM, "附近没有要塞的线索；找要塞要扔末影之眼指路，身上一只都没有",
                OBTAIN_EYES);
    }

    private Problem outOfEyes() {
        if (thrown == 0) {
            return carriesNoEyes();
        }
        return Problem.of(Problem.Kind.NEED_ITEM, "末影之眼用完了：" + eyesUsed() + "；最后一只往"
                + EyeBearings.compassOf(latest) + "飞，要塞还在那边", OBTAIN_EYES);
    }

    private Problem tooManyThrows() {
        return Problem.of(Problem.Kind.NOT_FOUND, "这一趟扔了 " + thrown + " 只末影之眼还没交出要塞的位置（" + eyesUsed()
                        + "）；最后一只往" + EyeBearings.compassOf(latest) + "飞",
                "往" + EyeBearings.compassOf(latest) + "接着走一段，再 find 一次");
    }

    @Override
    public void pause() {
        Action now = now();
        if (now != null) now.pause();
    }

    @Override
    public void close() {
        Action now = now();
        if (now != null) now.close();
    }

    @Override
    public Interruptibility interruptibility() {
        Action now = now();
        return now == null ? Interruptibility.BETWEEN_ACTIONS : now.interruptibility();
    }

    // 此刻手上那一个动作：在扔、在捡、在走，或者都不在（两步之间）。
    private Action now() {
        return switch (stage) {
            case THROW -> throwing;
            case FETCH -> fetching;
            case WALK -> walk;
            case FINISHED -> null;
        };
    }

    @Override
    public String describe() {
        return switch (stage) {
            case THROW -> "扔第 " + (thrown + 1) + " 只末影之眼看它往哪飞";
            case FETCH -> "捡回落下的末影之眼";
            case WALK -> "往末影之眼指的" + EyeBearings.compassOf(latest) + "边走一段";
            case FINISHED -> "末影之眼指路做完了";
        };
    }
}
