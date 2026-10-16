// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.Battlefield;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.DragonArena;
import org.maiwithu.maicraft.behavior.combat.DragonCombat;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗任务：打点名目标，或清一片区域的敌对生物。怎么打全交给战斗引擎（{@link Battle}），和自卫是同一个；
 * 这里只管目标解析、许可、开打前的评估、谁倒下了、拾荒和结果结算。
 *
 * <p>不点名时清的是开打那一刻 radius 内看得见的这一批，途中真打了角色的一并处理；之后新刷出来的、
 * 墙后与地下看不见的不追，这一批打完、身边也没有在打我的就收手。点名时打点名的，途中真打了角色的也还手。
 *
 * <p>先评估再动手：打不过不开打，说清差在血、武器还是数量；打着打着血线破了引擎会撤，甩开所有追击的才算撤成，
 * 撤离了不冒充打赢。击败确认后就地捡一下掉落物，捡不到写进结果，不影响击败判定。
 *
 * <p>打末影龙（点名龙，或不点名、身边加载着活龙、没限定别的种类）：开打前不按普通怪评估，改查装备——
 * 近战与能射的都没有、或还有水晶却没有能射的，如实说缺什么；怎么打交给引擎里龙的那一套。看到龙的死亡动画就算击败，
 * 再等它消失、出口门亮起（最多二十秒）收经验；只看到龙不见了不算。回血一分钟不涨、打到一半箭用完而水晶还在、
 * 连着五分钟龙都没趴下也没打到什么，都如实收场。
 */
final class FightTask extends PhasedTask<FightTask.Phase> implements ThreatResponder {

    /** 战斗的阶段：核实目标 → 评估 → 打（撤离在打的阶段里由引擎做）→ 捡。 */
    enum Phase { CHECK, ASSESS, FIGHT, LOOT }

    /** 连续半分钟没打中也没走成才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    /** 看到龙的死亡动画后最多再等这么久（刻）：等它消失、出口门亮起，经验球飞过来。 */
    static final long DRAGON_SETTLE_TICKS = 20L * 20;
    /** 打龙时连着这么久（刻）龙一次都没趴下、也没真打到什么，算卡住。 */
    static final long DRAGON_IDLE_TICKS = 20L * 60 * 5;

    private final FightInput input;
    private final CombatSenses senses;
    private final SeenTargets seenTargets;
    private final FightMoves moves;
    private final NamedTargetConsent consent;

    /** 点名目标：观察编号、实体编号、类型；区域清扫时为空。 */
    private final List<SeenTargets.Locked> named = new ArrayList<>();
    /** 区域清扫要清的这一批：开打那一刻半径内看得见的敌对生物的实体编号；点名时为空。 */
    private final Set<Integer> batch = new HashSet<>();
    /** 打过的（批里的、途中还手的）：看它们有没有倒下。实体编号到类型。 */
    private final Map<Integer, String> engaged = new HashMap<>();
    /** 已确认击败的目标（"类型（实体 n）"）。 */
    private final Set<String> defeated = new LinkedHashSet<>();
    /** 点名目标在确认击败前就不见了（走远、被别的东西打死）：不算击败，单独交代。 */
    private final List<String> lostTrack = new ArrayList<>();
    /** 拾荒捡到的：物品。 */
    private final List<String> lootGained = new ArrayList<>();

    private ThreatAssessment.Verdict verdict;
    private String weaponUsed = "空手";
    /** 确认打中的下数（原生攻击得到服务器确认才算）。 */
    private int hits;
    /** 射出去几箭（弓、弩、联动远程）。 */
    private int shots;
    /** 为什么撤的：撤离收场时写进结果。 */
    private String retreatWhy;
    private Battle battle;
    /** 最后一个目标倒下的位置：拾荒以它为中心。 */
    private double[] lastKillSpot;
    private boolean fled;
    private TickContext currentContext;

    /** 打龙：这一仗打的那条龙（实体编号）；不打龙为 -1。 */
    private int dragonEntity = -1;
    /** 这条龙在结果里的叫法与类型。 */
    private String dragonName;
    private String dragonType;
    /** 看到了龙的死亡动画（击败）；没看到死亡动画龙就不见了（跟丢）。 */
    private boolean dragonDefeated;
    private boolean dragonLost;
    /** 开打前的经验：结果写这一仗得了多少。 */
    private FightMoves.Experience experienceBefore;

    /** 拾荒正在做的那一步（走向某件掉落物）。 */
    private Action current;
    private String currentKey;
    /** 开打那一刻的掉落物确认序号：这之后冒出来的掉落才是这一仗带出来的。 */
    private long fightCursor;
    /** 拾荒先回到最后一只倒下的地方：远处的掉落进不了"身边"的名单。 */
    private boolean atKillSpot;
    /** 捡这一仗掉出来的东西（共用的捡掉落物）。 */
    private Action pickup;

    FightTask(FightInput input, CombatSenses senses, SeenTargets seenTargets, FightMoves moves,
            NamedTargetConsent consent) {
        super("战斗", Phase.CHECK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.senses = Objects.requireNonNull(senses, "senses");
        this.seenTargets = Objects.requireNonNull(seenTargets, "seenTargets");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.consent = Objects.requireNonNull(consent, "consent");
    }

    private final Driver driver = new Driver();
    /** 这一刻决定换去的阶段：基类紧接着收尾外壳，靠它分清是换阶段还是任务真结束。 */
    private Phase goingTo;

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        currentContext = context;
        Next<Phase> next = switch (phase) {
            case CHECK -> check();
            case ASSESS -> assess(context);
            case FIGHT -> fight(context);
            case LOOT -> loot(context);
        };
        goingTo = next instanceof Next.Go<Phase> go ? go.phase() : null;
        return next;
    }

    private Next<Phase> check() {
        for (String observedId : input.seenTargets()) {
            SeenTargets.Locked locked = seenTargets.lock(currentContext, observedId);
            if (locked == null) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        "观察编号 " + observedId + " 对不上任何东西：先 observe 确认它在不在",
                        "重新观察后用新的观察编号再打"));
            }
            // 点名就是许可，但别人的宠物、有名字的动物和玩家点了名也要再确认一次：许可没开到 any 不动手。
            Optional<Problem> refusal = consent.check(locked, input.permissions());
            if (refusal.isPresent()) {
                return Next.fail(refusal.get());
            }
            named.add(locked);
        }
        noticeDragon();
        if (dragonEntity >= 0) return Next.go(Phase.ASSESS, "打末影龙");
        return Next.go(Phase.ASSESS, named.isEmpty()
                ? "清扫 " + input.radius() + " 格内的敌对生物" : "核实了 " + named.size() + " 个点名目标");
    }

    // 打龙模式：点名的里有一条龙；或者不点名、身边加载着活龙、没限定别的种类（限定了 ender_dragon 也算）。
    private void noticeDragon() {
        for (SeenTargets.Locked locked : named) {
            Optional<DragonArena> arena = senses.dragonArena(currentContext, locked.entityId(), landmarks());
            if (arena.isPresent() && arena.get().dragon().id() == locked.entityId()) {
                dragonEntity = locked.entityId();
                dragonName = describeOf(locked);
                dragonType = locked.type();
                return;
            }
        }
        if (!named.isEmpty()) return;
        Optional<DragonArena> nearby = senses.dragonArena(currentContext, -1, landmarks());
        if (nearby.isEmpty()) return;
        DragonArena.Dragon dragon = nearby.get().dragon();
        if (input.entityType() != null && !input.entityType().equals(dragon.type())) return;
        dragonEntity = dragon.id();
        dragonType = dragon.type();
        dragonName = dragon.type() + "（实体 " + dragon.id() + "）";
    }

    private Next<Phase> assess(TickContext context) {
        if (dragonEntity >= 0) return assessDragon(context);
        if (named.isEmpty()) {
            // 区域清扫先认下这一批：此刻半径内看得见的；墙后、地下看不见的不算，之后新刷的也不算。
            for (CombatSenses.Threat threat : senses.threats(context, input.radius())) {
                if (threat.visible() && threat.kind() != ThreatAssessment.Kind.PLAYER
                        && (input.entityType() == null || threat.type().equals(input.entityType()))) {
                    batch.add(threat.entityId());
                    engaged.put(threat.entityId(), threat.type());
                }
            }
        }
        ThreatAssessment.Assessment assessment = ThreatAssessment.assess(mySide(context), foesOf(context));
        verdict = assessment.verdict();
        if (verdict == ThreatAssessment.Verdict.OUTMATCHED) {
            // 点名目标开始时已经倒下：不必打，直接收场。
            noteDefeats(context);
            if (!named.isEmpty() && remainingNamed().isEmpty() && lostTrack.isEmpty()) {
                return Next.done(TaskResult.builder(TaskResult.Status.DONE, "开始时点名目标已被击败，不用打")
                        .details(details()).build());
            }
            String gaps = assessment.gaps().isEmpty() ? "整体劣势" : String.join("、", assessment.gaps());
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "打不过，先不动手：差在" + gaps
                            + "；吃点东西回血、换把更好的武器，或减少同时面对的敌人再来",
                    "先解决血量与武器的问题再开战"));
        }
        double radius = named.isEmpty() ? input.radius() : ThreatAssessment.VIGILANCE_RADIUS;
        battle = new Battle(senses, moves, new Roster(), radius);
        // 夜里退无可退、打不过：引擎报要挖坑时这一仗停下，交给自卫接挖三填一。
        battle.offerShelter();
        fightCursor = moves.dropCursor(context);
        return Next.go(Phase.FIGHT, verdict == ThreatAssessment.Verdict.RISKY
                ? "有风险，打的时候盯着血线" : "评估能赢，开打");
    }

    // 打龙开打前：龙不按普通怪评估（打不过不开打那套按怪的种类和数量算，套不到龙身上），改查装备，
    // 缺什么如实说；血低不拒战，引擎会先退开回血。这一批别的敌对生物照常认下、照常评估写进结果。
    private Next<Phase> assessDragon(TickContext context) {
        Optional<DragonArena> arena = senses.dragonArena(context, dragonEntity, landmarks());
        if (arena.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, dragonName + " 开打前就不见了（走远或卸载了）",
                    "回到主岛出口门附近，看得到龙再打"));
        }
        boolean melee = moves.hasMeleeWeapon(context);
        boolean shoot = moves.canShoot(context);
        if (!melee && !shoot) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "打末影龙手上什么都没有：要一把剑或斧（龙趴下时砍龙头），以及弓和箭（射末影水晶、射飞着的龙）",
                    "备好剑或斧、弓和箭再来"));
        }
        List<DragonArena.Crystal> crystals = arena.get().crystals();
        if (!crystals.isEmpty() && !shoot) return Next.fail(needArrows(crystals.size(), positions(crystals)));
        if (!melee) {
            recordAttempt("龙趴着时砍龙头", "身上没有近战武器：趴着的龙箭射不动，只能等它飞起来再射");
        }
        if (named.isEmpty()) {
            for (CombatSenses.Threat threat : senses.threats(context, input.radius())) {
                if (threat.visible() && threat.kind() != ThreatAssessment.Kind.PLAYER
                        && (input.entityType() == null || threat.type().equals(input.entityType()))) {
                    batch.add(threat.entityId());
                    engaged.put(threat.entityId(), threat.type());
                }
            }
        }
        verdict = ThreatAssessment.assess(mySide(context), foesOf(context)).verdict();
        experienceBefore = moves.experience(context);
        battle = new Battle(senses, moves, new Roster(),
                named.isEmpty() ? input.radius() : ThreatAssessment.VIGILANCE_RADIUS);
        battle.offerShelter();
        fightCursor = moves.dropCursor(context);
        return Next.go(Phase.FIGHT, crystals.isEmpty() ? "开打末影龙" : "开打末影龙：先射 " + crystals.size() + " 颗末影水晶");
    }

    // 还有水晶却没有能射的：说清还剩几颗、在哪。
    private static Problem needArrows(int count, List<String> where) {
        return Problem.of(Problem.Kind.NEED_ITEM,
                "还有 " + count + " 颗末影水晶没打掉（" + String.join("；", where) + "），身上没有弓和箭（或弩）射它们",
                "带上弓和箭再来；铁栏笼里的要先搭上去拆开一根铁栏");
    }

    private static List<String> positions(List<DragonArena.Crystal> crystals) {
        List<String> where = new ArrayList<>();
        for (DragonArena.Crystal crystal : crystals) {
            where.add(Math.round(Math.floor(crystal.x())) + ", " + Math.round(Math.floor(crystal.y())) + ", "
                    + Math.round(Math.floor(crystal.z())) + (crystal.caged() ? "（铁栏笼里）" : ""));
        }
        return where;
    }

    // 打龙途中要收场的几种：回血一分钟不涨；箭用完了水晶还立着；连着五分钟龙没趴下、也没打到什么；
    // 看到死亡动画后等够了（龙消失、门亮，或二十秒到了）。都不是返回 null，接着打。
    private Next<Phase> dragonEnding(TickContext context) {
        DragonCombat.Report report = battle == null ? null : battle.dragonReport().orElse(null);
        if (report == null) return null;
        if (dragonDefeated) {
            boolean settled = report.goneAfterDying() || report.portal() != null && report.portal().lit()
                    || context.gameTick() - report.dyingAt() >= DRAGON_SETTLE_TICKS;
            if (!settled) return null;
            // 龙收场了：点名的就收工；清扫的这一批还有别的，交回引擎接着打，龙场没了它会按普通打法来。
            if (!named.isEmpty() && remainingNamed().isEmpty()) {
                return lastKillSpot == null ? Next.done(settled()) : Next.go(Phase.LOOT, "龙打完了，就地捡一下");
            }
            return null;
        }
        if (dragonLost) return null;
        if (report.restStalled()) {
            CombatSenses.CombatProfile profile = senses.profile(context);
            if (profile.foodCount() == 0) {
                return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                        "退开回血一分钟血一点没涨（" + Math.round(profile.health()) + " 点血），身上没有能吃的",
                        "带上吃的再来"));
            }
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "退开回血一分钟血一点没涨（" + Math.round(profile.health()) + " 点血，身上 " + profile.foodCount()
                            + " 份口粮）", "看看饱食度与身上的吃的，回满血再来"));
        }
        if (report.standingCrystals() > 0 && !moves.canShoot(context)) {
            List<String> standing = new ArrayList<>();
            for (String left : report.crystalsLeft()) {
                if (left.endsWith(DragonCombat.NOT_YET)) standing.add(left);
            }
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "箭用完了，还有 " + report.standingCrystals() + " 颗末影水晶没打掉（" + String.join("；", standing) + "）",
                    "补些箭再来"));
        }
        if (context.gameTick() - report.lastEngagement() > DRAGON_IDLE_TICKS) {
            String doing = report.dragon() == null ? "看不到龙" : "最后看到龙在" + activityName(report.dragon().activity());
            return Next.fail(Problem.of(Problem.Kind.STUCK,
                    "打末影龙连着五分钟，龙一次都没趴下，也没射中什么：" + doing + "，还剩 " + report.standingCrystals()
                            + " 颗没打掉的水晶（水晶多时龙很少落地）",
                    "先把能射的水晶射掉，或带上弓箭趁它飞近时射"));
        }
        return null;
    }

    private static String activityName(DragonArena.Activity activity) {
        return switch (activity) {
            case CIRCLING -> "盘旋";
            case STRAFING -> "扫射";
            case CHARGING -> "冲撞";
            case LANDING -> "降落";
            case WATCHING -> "趴着扫视";
            case ROARING -> "趴着吼";
            case BREATHING -> "趴着喷龙息";
            case TAKING_OFF -> "起飞";
            case DYING -> "死亡动画里";
        };
    }

    private Next<Phase> fight(TickContext context) {
        noteDefeats(context);
        if (dragonEntity >= 0) {
            Next<Phase> ending = dragonEnding(context);
            if (ending != null) return ending;
        }
        if (!named.isEmpty() && remainingNamed().isEmpty() && !dragonSettling(context)) {
            // 点名目标都有了着落（打倒或跟丢）：就地捡一下掉落物再收场。
            return lastKillSpot == null ? Next.done(settled()) : Next.go(Phase.LOOT, "点名的都打倒了，就地捡一下");
        }
        Battle.Moment moment = battle.tick(context);
        // 打龙：这一刻引擎刚看到龙倒下或不见了，收场前先记上，免得把跟丢的龙当成够不着。
        noteDragon();
        if (moment.struck() || moment.fired()) weaponUsed = moves.mainHand(context);
        if (moment.struck()) hits++;
        // 射出去的箭另记：箭中没中服务器不单独回话，不混进"确认打中"的下数里（实机 10-14 拿弓清掉三只尸壳，hits 写 0 看着像没打）。
        if (moment.fired()) shots++;
        if (moment.targetId() >= 0 && moment.state() != Battle.State.EVADING) rememberEngaged(context, moment.targetId());
        if (moment.progressed()) recordProgress(moment.doing());
        return switch (moment.state()) {
            case ESCAPED -> {
                // 甩开了：撤离成功不等于任务成功，目标没死就以失败收场，写明为什么撤与出路。
                fled = true;
                retreatWhy = moment.retreatWhy();
                yield Next.fail(Problem.of(Problem.Kind.DANGER,
                        "撤离成功，甩开了追兵（" + moment.retreatWhy() + "），但目标没有击败——这一仗算没打赢",
                        "吃点东西回血到拒战线以上，或换把武器再来"));
            }
            case NEEDS_SHELTER -> Next.fail(Problem.of(Problem.Kind.DANGER,
                    "撤不掉又打不过，天黑露天：这一仗停下，交给自保挖坑熬到天亮",
                    "天亮后回血、换把武器再来"));
            case FINISHED -> named.isEmpty()
                    ? Next.go(Phase.LOOT, lastKillSpot == null ? "附近没有要打的了" : "这一批清完了，就地捡一下")
                    : lastKillSpot == null ? Next.done(settled()) : Next.go(Phase.LOOT, "打完了，就地捡一下");
            default -> Next.stay();
        };
    }

    // 看到死亡动画后还在等龙消失、门亮：点名的龙已算击败，但先别收工，站在门边收经验。
    private boolean dragonSettling(TickContext context) {
        if (!dragonDefeated || battle == null) return false;
        DragonCombat.Report report = battle.dragonReport().orElse(null);
        return report != null && !report.goneAfterDying() && (report.portal() == null || !report.portal().lit())
                && context.gameTick() - report.dyingAt() < DRAGON_SETTLE_TICKS;
    }

    // 名单：点名的（还没倒下、没跟丢）或清扫的这一批，外加途中真打了角色的；给了 count 打够了就只还手。
    // 打龙时龙不在名单里，由引擎里龙的那一套打。
    private final class Roster implements Battlefield.Roster {
        @Override
        public boolean fightsDragon() {
            return dragonEntity >= 0;
        }

        @Override
        public int dragonId() {
            return dragonEntity;
        }

        // 打龙时水晶爆炸范围碰到这次任务额外保护的地标，就不射那颗。
        @Override
        public Set<String> protectedLandmarks() {
            return landmarks();
        }

        @Override
        public boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing) {
            if (threat.entityId() == dragonEntity) return false;
            // 途中真打了角色的怪一并处理；没点名的玩家不自动还手，打不打他由 LLM 定。
            if (attacker && threat.kind() != ThreatAssessment.Kind.PLAYER) return true;
            if (countReached()) return false;
            if (!named.isEmpty()) return remainingNamed().contains(threat.entityId());
            return batch.contains(threat.entityId());
        }

        @Override
        public boolean primary(CombatSenses.Threat threat) {
            if (threat.entityId() == dragonEntity) return false;
            return named.isEmpty() ? batch.contains(threat.entityId()) : remainingNamed().contains(threat.entityId());
        }

        @Override
        public List<Integer> namedIds() {
            if (countReached()) return List.of();
            List<Integer> ids = new ArrayList<>(remainingNamed());
            ids.remove(Integer.valueOf(dragonEntity));
            return ids;
        }
    }

    // 这次任务额外保护的地标名（permissions.protected_landmarks）。
    private Set<String> landmarks() {
        return input.permissions().protectedLandmarks();
    }

    private boolean countReached() {
        return input.count() != null && defeated.size() >= input.count();
    }

    // 点名目标里还没倒下、没跟丢的实体编号。
    private List<Integer> remainingNamed() {
        List<Integer> ids = new ArrayList<>();
        for (SeenTargets.Locked locked : named) {
            if (!defeated.contains(describeOf(locked)) && !lostTrack.contains(describeOf(locked))) ids.add(locked.entityId());
        }
        return ids;
    }

    // 谁倒下了：点名的逐个核对（不见了记跟丢），打过的逐个看有没有倒下；倒下的地方留给拾荒。
    private void noteDefeats(TickContext context) {
        noteDragon();
        for (SeenTargets.Locked locked : named) {
            if (locked.entityId() == dragonEntity) continue;
            String name = describeOf(locked);
            if (defeated.contains(name) || lostTrack.contains(name)) continue;
            SeenTargets.Observed observed = seenTargets.observe(context, locked.entityId());
            if (observed == null) {
                // 消失的点名目标：不算击败，记进"跟丢了"，结果里按没打成交代。
                lostTrack.add(name);
            } else if (observed.dead()) {
                markDefeated(name, locked.type(), observed);
            }
        }
        for (var entry : List.copyOf(engaged.entrySet())) {
            SeenTargets.Observed observed = seenTargets.observe(context, entry.getKey());
            if (observed == null) {
                engaged.remove(entry.getKey());
            } else if (observed.dead()) {
                engaged.remove(entry.getKey());
                markDefeated(entry.getValue() + "（实体 " + entry.getKey() + "）", entry.getValue(), observed);
            }
        }
    }

    // 龙：看到死亡动画算击败（原版不会回头）；没看到死亡动画龙就不见了，算跟丢，不冒充打死。龙不掉东西，不拾荒。
    private void noteDragon() {
        if (dragonEntity < 0 || battle == null || dragonDefeated || dragonLost) return;
        DragonCombat.Report report = battle.dragonReport().orElse(null);
        if (report == null) return;
        if (report.dyingSeen()) {
            dragonDefeated = true;
            defeated.add(dragonName);
            recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, dragonType, 1));
            recordProgress("末影龙倒下了");
        } else if (report.goneWithoutDying()) {
            dragonLost = true;
            lostTrack.add(dragonName);
        }
    }

    private void markDefeated(String name, String type, SeenTargets.Observed observed) {
        if (!defeated.add(name)) return;
        recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, type, 1));
        lastKillSpot = new double[] {observed.x(), observed.y(), observed.z()};
        recordProgress("击败了 " + type);
    }

    // 打过的记下来看它倒没倒：点名的另有核对，这里收清扫批之外途中还手的那些。
    private void rememberEngaged(TickContext context, int entityId) {
        if (engaged.containsKey(entityId) || named.stream().anyMatch(locked -> locked.entityId() == entityId)) return;
        senses.living(context, entityId).ifPresent(threat -> engaged.put(entityId, threat.type()));
        if (!engaged.containsKey(entityId)) {
            for (CombatSenses.Threat threat : senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS)) {
                if (threat.entityId() == entityId) engaged.put(entityId, threat.type());
            }
        }
    }

    // 拾荒：先回到最后一只倒下的地方，再交给共用的捡掉落物捡这一仗新冒出来的东西（开打前就躺着的不碰），
    // 收没收进包按服务器的拾取包认；走不到、捡不到的如实写进结果，不影响击败判定，不站着等尸体刷掉落。
    private Next<Phase> loot(TickContext context) {
        if (lastKillSpot == null) {
            return Next.done(settled());
        }
        if (!atKillSpot) {
            double[] self = moves.selfPosition(context);
            if (Math.hypot(self[0] - lastKillSpot[0], self[2] - lastKillSpot[2]) <= KILL_SPOT_NEAR) {
                atKillSpot = true;
                dropCurrent();
            } else {
                keep("回到倒下的地方", () -> moves.walk(GoalCompiler.near(
                        BlockPos.containing(lastKillSpot[0], lastKillSpot[1], lastKillSpot[2]), KILL_SPOT_NEAR)));
                if (current.tick(context) instanceof ActionStatus.Running running) {
                    if (running.progressed()) recordProgress("回到倒下的地方捡掉落");
                    return Next.stay();
                }
                // 走到了或走不到都照样捡：站在原地，够得着的先收，收不到的由捡的动作如实交代。
                atKillSpot = true;
                dropCurrent();
            }
        }
        if (pickup == null) pickup = moves.pickUpNewSince(moves.dropsLyingBefore(context, fightCursor));
        ActionStatus status = pickup.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) recordProgress(pickup.describe());
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("捡这一仗掉出来的东西", failed.problem().message());
        }
        pickup.close();
        pickup = null;
        for (FightMoves.Gained gained : moves.pickedUpSince(context, fightCursor)) {
            lootGained.add(gained.item());
            recordChange(Change.of(Change.Kind.ITEM_GAINED, gained.item(), gained.count()));
        }
        return Next.done(settled());
    }

    /** 离最后一只倒下的地方这么近就开始捡（格）。 */
    private static final double KILL_SPOT_NEAR = 3.0;

    private ThreatAssessment.MySide mySide(TickContext context) {
        CombatSenses.CombatProfile profile = senses.profile(context);
        int weaponScore = profile.weapon().map(WeaponChoice::scoreOf).orElse(0);
        return new ThreatAssessment.MySide(profile.health(), profile.armorPoints(),
                weaponScore, profile.foodCount(), true);
    }

    private List<ThreatAssessment.Foe> foesOf(TickContext context) {
        List<ThreatAssessment.Foe> foes = new ArrayList<>();
        double radius = named.isEmpty() ? input.radius() : ThreatAssessment.VIGILANCE_RADIUS;
        Set<UUID> attackers = new HashSet<>();
        for (CombatSenses.Attacker attacker : senses.recentAttackers(context)) {
            attackers.add(attacker.uuid());
        }
        for (CombatSenses.Threat threat : senses.threats(context, radius)) {
            // 区域清扫只评估要清的这一批：看不见的、不是这一种的不算进对面的人数。
            if (named.isEmpty() && !batch.contains(threat.entityId())) continue;
            // 追着我的按真的在追算（打过我、点着引信、看得见我亮着攻击标记），"打不打得过"才不偏轻。
            foes.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), threat.comingAt(attackers), threat.armed()));
        }
        return ThreatAssessment.sortedByThreat(foes);
    }

    // 收场：点名目标有跟丢的、够不着的就不算全打完——一个都没确认击败是没做成，打掉一部分是做成一部分。
    private TaskResult settled() {
        String summary = defeated.isEmpty() ? "打完了，没有确认击败的目标"
                : "击败了 " + defeated.size() + " 个目标" + (lootGained.isEmpty() ? "，没捡到掉落物" : "，捡到 " + lootGained.size() + " 组掉落物");
        // 打龙：龙不掉东西，不说"没捡到掉落物"；另外顺手打倒的照数。
        if (dragonDefeated) {
            summary = "打败了末影龙" + (defeated.size() > 1 ? "，另外击败了 " + (defeated.size() - 1) + " 个目标" : "")
                    + (lootGained.isEmpty() ? "" : "，捡到 " + lootGained.size() + " 组掉落物");
        }
        List<String> unreached = new ArrayList<>();
        for (SeenTargets.Locked locked : named) {
            if (remainingNamed().contains(locked.entityId())) unreached.add(describeOf(locked));
        }
        if (lostTrack.isEmpty() && unreached.isEmpty()) {
            return TaskResult.builder(TaskResult.Status.DONE, summary).details(details()).build();
        }
        TaskResult.Status status = defeated.isEmpty() ? TaskResult.Status.FAILED : TaskResult.Status.PARTIAL;
        if (!lostTrack.isEmpty()) {
            String lost = String.join("、", lostTrack);
            return TaskResult.builder(status, summary + "；" + lost + " 在确认击败前不见了")
                    .problem(Problem.of(Problem.Kind.TARGET_GONE, lost + " 在确认击败前不见了（走远或被别的东西打死）",
                            "重新 observe 看它还在不在，在的话用新的观察编号再打"))
                    .remaining(lostTrack)
                    .details(details()).build();
        }
        String far = String.join("、", unreached);
        return TaskResult.builder(status, summary + "；够不着 " + far)
                .problem(Problem.of(Problem.Kind.UNREACHABLE, far + " 走不到跟前、也射不到（隔着悬崖、水或墙）",
                        "先搭路或绕到它那边；身上有弓的走到它二十格内再射"))
                .remaining(unreached)
                .details(details()).build();
    }

    private String verdictName() {
        return verdict == null ? "未评估" : verdict.name().toLowerCase(Locale.ROOT);
    }

    private static String describeOf(SeenTargets.Locked locked) {
        return locked.type() + "（实体 " + locked.entityId() + "）";
    }

    // 沿用进行中的动作：同一件事不每刻重建；换了一件事才收尾旧的再起新的。
    private void keep(String key, Supplier<Action> make) {
        if (current != null && !key.equals(currentKey)) {
            dropCurrent();
        }
        if (current == null) {
            current = make.get();
            currentKey = key;
        }
    }

    private void dropCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
        currentKey = null;
    }

    // 推进当前动作：做完或失败都收尾，下一刻重新决定。
    private void stepCurrent(TickContext context, String what) {
        if (current == null) {
            return;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            recordProgress(status instanceof ActionStatus.Failed failed
                    ? what + "没成：" + failed.problem().message() : what + "：一轮结束");
            dropCurrent();
        } else if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(what);
        }
    }

    @Override
    protected ResultDetails details() {
        List<String> unreached = new ArrayList<>();
        for (SeenTargets.Locked locked : named) {
            if (battle != null && battle.unreachable().contains(locked.entityId())) unreached.add(describeOf(locked));
        }
        return new FightDetails(List.copyOf(defeated), lostTrack, fled, verdictName(), weaponUsed, hits, shots, unreached,
                retreatWhy, lootGained, dragonDetails());
    }

    // 打龙这一节：射掉几颗、没打掉的在哪为什么、龙的血、死的证据、出口门、经验、回血与躲闪次数。不打龙为 null。
    private DragonDetails dragonDetails() {
        if (dragonEntity < 0 || battle == null) return null;
        DragonCombat.Report report = battle.dragonReport().orElse(null);
        if (report == null) return null;
        List<String> defeat = new ArrayList<>();
        if (report.dyingSeen()) defeat.add("看到了死亡动画");
        if (report.goneAfterDying()) defeat.add("死亡动画后龙消失了");
        if (report.portal() != null && report.portal().lit()) defeat.add("出口门亮了");
        String health = report.dragon() == null ? null
                : Math.round(report.dragon().health()) + " / " + Math.round(report.dragon().maxHealth());
        String portal = report.portal() == null ? null
                : Math.round(Math.floor(report.portal().x())) + ", " + Math.round(report.portal().y()) + ", "
                        + Math.round(Math.floor(report.portal().z())) + (report.portal().lit() ? "（亮了）" : "（还没亮）");
        FightMoves.Experience now = currentContext == null ? null : moves.experience(currentContext);
        String experience = experienceBefore == null || now == null ? null
                : "+" + (now.level() - experienceBefore.level()) + " 级（+" + (now.points() - experienceBefore.points()) + " 点）";
        return new DragonDetails(report.crystalsDestroyed(), report.crystalsLeft(), report.crystalsGoneOtherwise(),
                health, defeat, portal, experience, report.rests(),
                new Dodges(report.breathDodges(), report.sweepDodges(), report.fireballDodges()));
    }

    /** 正在打的仗自己接得住吗：评估不是"打不过"、没在撤离，被攻击的生存需求就让位。 */
    @Override
    public boolean confidentAgainstCurrentThreats() {
        return verdict != ThreatAssessment.Verdict.OUTMATCHED && !fled && (battle == null || !battle.retreating());
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        if (current != null) return current.interruptibility();
        return battle != null ? battle.interruptibility() : Interruptibility.WORKING;
    }

    /** 引擎与拾荒的动作接进基类的暂停与收尾：基类持有的是这个外壳。 */
    private final class Driver implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.running();
        }

        @Override
        public void pause() {
            if (current != null) current.pause();
            if (pickup != null) pickup.pause();
            if (battle != null) battle.pause();
        }

        @Override
        public void close() {
            // 换阶段或任务结束时基类收尾外壳：拾荒的走到、引擎的出手与寻路、拉了一半的弓都收尾。
            // 评估完换去开打时，引擎是刚为这一仗建好的，不关它，开打第一刻就能出手、走位。
            boolean battleGoesOn = goingTo == Phase.FIGHT;
            goingTo = null;
            dropCurrent();
            if (pickup != null) pickup.close();
            pickup = null;
            if (battle != null && !battleGoesOn) battle.close();
        }

        @Override
        public String describe() {
            if (pickup != null) return pickup.describe();
            if (current != null) return current.describe();
            return battle == null ? "观察战场" : battle.describe();
        }
    }

    /**
     * 战斗的结果细节。
     *
     * @param weapon     这场实际拿着打的那件
     * @param hits       确认打中的下数
     * @param reachIssue 点名目标里走不到跟前、也射不到的
     * @param retreat    撤离收场时为什么撤；没撤为空
     */
    record FightDetails(List<String> defeated, List<String> lostTrack, boolean fled, String threatVerdict,
                        String weapon, int hits, int shots, List<String> reachIssue, String retreat,
                        List<String> lootGained, DragonDetails dragon) implements ResultDetails {
        FightDetails {
            defeated = List.copyOf(defeated);
            lostTrack = List.copyOf(lostTrack);
            reachIssue = List.copyOf(reachIssue);
            lootGained = List.copyOf(lootGained);
        }
    }

    /**
     * 打龙这一节。
     *
     * @param crystalsDestroyed     确认是我射掉的水晶数
     * @param crystalsLeft          没打掉的水晶：在哪、为什么
     * @param crystalsGoneOtherwise 不是我射掉却没了的水晶数
     * @param health                最后看到的龙血量与上限
     * @param defeat                龙死的证据（有几样写几样）
     * @param exitPortal            出口门的坐标与亮没亮
     * @param experience            这一仗得到的经验
     * @param rests                 退开回血了几次
     * @param dodges                躲开龙息、冲撞、火球各几次
     */
    record DragonDetails(int crystalsDestroyed, List<String> crystalsLeft, int crystalsGoneOtherwise, String health,
                         List<String> defeat, String exitPortal, String experience, int rests, Dodges dodges) {
        DragonDetails {
            crystalsLeft = List.copyOf(crystalsLeft);
            defeat = List.copyOf(defeat);
        }
    }

    /** 躲开龙息、冲撞、火球各几次。 */
    record Dodges(int breath, int charge, int fireball) {}
}
