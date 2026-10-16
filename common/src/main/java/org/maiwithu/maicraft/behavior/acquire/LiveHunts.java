// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎的生产实现：逐只走近、看准、交给战斗域的攻击动作出手，打倒了就地收掉落。
 *
 * <p>出手前必须看得见、够得着（射线核对把关）：被墙挡住或离得远就朝它现在的位置走，看见又够得着才出手；
 * 人和它泡在同一片水里时不走寻路，直接游过去追（寻路在水里到不了水下它跟前）。
 * 出手用和自卫同一个近战出手；最好的剑斧在锁定一只、追它之前就换到主手。出手后盯住这只，
 * 不因更近的同类反复改道。打斗中每刻核对目标：被拴绳、被命名、被驯服、圈起来了——变成别人的了
 * 就停手，记下原因换下一只（事实核对，不是拦截）。打倒了（它不在了）先回到它倒下的地方，
 * 把这次新掉出来的目标物品与副产物都捡起来；够不着的如实记下、不冒充收干净——
 * 目标物品够数就算这一单拿到。打了一只没见到要的东西，记下原因换下一只，不重复打同一只；
 * 每次放弃一只都交代现场：它最后在哪、多远、看得见还是被挡住。
 *
 * <p>附近没有候选而这次愿意走时，先出去找：按环带一圈一圈往外，走一片看一片，
 * 见到合格的候选就地转入狩猎，走满愿意走的距离还没见到就如实收场。
 */
public final class LiveHunts implements Hunts {

    /** 一只猎物身上的期限（刻）：围着一只打半天没打倒，先不追了，换下一只或如实收场。 */
    private static final long PER_TARGET_TICKS = 20L * 40;
    /** 出去找时，走向一个搜索点的期限（刻）；走不到就换下一个点。 */
    private static final long PER_POINT_TICKS = 20L * 30;
    /** 还没为哪一只换过武器。 */
    private static final int NOT_ARMED = Integer.MIN_VALUE;
    /** 换武器这件事在"正在推进的动作"里的名字。 */
    private static final String ARMING = "拿武器";
    /** 追猎物时按多大的方格认"它挪地方了"（格）：挪出这一格就按新位置重新走。 */
    private static final double APPROACH_CELL = 4.0;

    private final HuntMoves moves;
    private final PicksUpDrops drops;
    private final SeesCreatures sight;
    private final ReadsCreatureSituation creatures;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;

    public LiveHunts(HuntMoves moves, PicksUpDrops drops, SeesCreatures sight,
            ReadsCreatureSituation creatures, BackpackView backpack, OffhandContents offhand, ReadsItemTags tags) {
        this.moves = Objects.requireNonNull(moves, "moves");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.sight = Objects.requireNonNull(sight, "sight");
        this.creatures = Objects.requireNonNull(creatures, "creatures");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override public Optional<Action> hunt(List<ScansCreatures.CreatureSpot> candidates, ItemRequest request,
            Permissions permissions) {
        return Optional.of(new Hunting(request, candidates.iterator(), null, permissions));
    }

    @Override public Optional<Action> searchAndHunt(ItemRequest request, SearchPlan plan, Permissions permissions) {
        // 出去找也要知道找什么、要几件：够数没有、失败说明里缺什么，都按这一单的请求算。
        return Optional.of(new Hunting(request, List.<ScansCreatures.CreatureSpot>of().iterator(), plan, permissions));
    }

    /** 一次狩猎的动作：逐只打；或先出去一片一片找，见到再打。 */
    private final class Hunting implements Action {

        private enum Stage { WALK_POINT, LOOK_AROUND, TARGET, LOOT }

        /** 出去找的打算；普通狩猎为 null。 */
        private final Hunts.SearchPlan plan;
        private final Permissions permissions;
        private final ItemRequest request;
        /** 还没打的候选；普通狩猎来自开场扫描，搜索模式每看一片就换一批。 */
        private Iterator<ScansCreatures.CreatureSpot> candidates;
        private Stage stage = Stage.TARGET;
        private ScansCreatures.CreatureSpot target;
        /** 出手了没有：出手就盯住这只，不再改道。 */
        private boolean struck;
        /** 出手前地上的掉落物：打倒了只捡这次新掉出来的。 */
        private Set<Integer> dropsBefore;
        /** 猎物此刻在哪：每刻跟着它刷新，倒下后掉落物散在这一片，收掉落先回到这里。 */
        private double[] lastKnownAt;
        /** 收掉落前要先走回去的地方；不知道它倒在哪就原地捡。 */
        private double[] lootSpot;
        /** 这次有掉落物没能收进包：结算时不把"没见到"和"没捡到"混成一句。 */
        private boolean lootMissed;
        /** 正在推进的动作（走近、出手、走向搜索点）：同一件事沿用，不每刻重建。 */
        private Action current;
        private String currentKey;
        private Action loot;
        /** 已经为哪一只换过武器：换一只猎物再看一眼手上，已经拿着最好的剑斧就不动。 */
        private int armedFor = NOT_ARMED;
        /** 目标与搜索点各自的计时：超了就换，不在一只猎物、一个点上耗死。 */
        private long stageTicks;
        /** 正在走向的搜索点；走到了看一片，看完换下一个。 */
        private SearchPoint point;
        /** 搜索点的环带序列与走到第几个：第一片看完接着排下一个。 */
        private List<SearchPoint> points;
        private int pointIndex;
        /** 打过却没见到想要东西的、停手的、没捡干净的：失败说明里逐条交代。 */
        private final List<String> notes = new ArrayList<>();
        private int carriedAtStart = -1;

        Hunting(ItemRequest request, Iterator<ScansCreatures.CreatureSpot> candidates, Hunts.SearchPlan plan,
                Permissions permissions) {
            this.request = request;
            this.candidates = candidates;
            this.plan = plan;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (carriedAtStart < 0) carriedAtStart = carriedWanted();
            if (enough()) return ActionStatus.done();
            return switch (stage) {
                case WALK_POINT -> walkToPoint(tick);
                case LOOK_AROUND -> lookAround(tick);
                case TARGET -> huntTarget(tick);
                case LOOT -> lootDrops(tick);
            };
        }

        /** 目标物品够数没有：count 是"这次再多几件"，按开始时的差算。 */
        private boolean enough() {
            return carriedWanted() - carriedAtStart >= request.count();
        }

        private int carriedWanted() {
            return CarriedItems.matching(backpack, offhand, request, tags);
        }

        // 走向下一个搜索点；走不到（超时）就换下一个点，不在一个走不到的地方耗死。
        private ActionStatus walkToPoint(TickContext tick) {
            if (point == null) {
                return nextPointOrGiveUp(tick);
            }
            keep("出去找 " + (int) point.x() + "," + (int) point.z(),
                    () -> moves.walkToArea(point.x(), point.y(), point.z()));
            boolean finished = stepCurrent(tick, "走出去看一片");
            if (++stageTicks > PER_POINT_TICKS && current == null) {
                notes.add("走不到搜索点 " + (int) point.x() + "," + (int) point.z() + "，换下一个");
                point = null;
                stageTicks = 0;
                return ActionStatus.progressed();
            }
            if (finished) {
                stage = Stage.LOOK_AROUND;
                point = null;
            }
            return ActionStatus.running();
        }

        // 到了一片：重新筛一遍这一带的候选，有就转入狩猎，没有就接着往下一个点走。
        private ActionStatus lookAround(TickContext tick) {
            if (plan == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "没有搜索打算却在找猎物", null));
            }
            double[] here = moves.selfPosition(tick);
            WorldPosition at = WorldPosition.here((int) Math.floor(here[0]), (int) Math.floor(here[1]),
                    (int) Math.floor(here[2]));
            HuntCandidates.Screen screen = HuntCandidates.screen(plan.typeIds(), plan.sheepColorNeeded(),
                    plan.woolRequest(), at, plan.scanRadiusBlocks(), plan.scans(), plan.creatures(),
                    plan.permission(), permissions);
            notes.addAll(screen.excluded());
            stageTicks = 0;
            if (screen.allowed().isEmpty()) {
                stage = Stage.WALK_POINT;
                return ActionStatus.progressed();
            }
            candidates = screen.allowed().iterator();
            stage = Stage.TARGET;
            return ActionStatus.progressed();
        }

        // 打当前目标：核对处境与视线，看得见才出手；打倒了先回它倒下的地方收掉落，再换下一只。
        private ActionStatus huntTarget(TickContext tick) {
            if (++stageTicks > PER_TARGET_TICKS) {
                notes.add((target == null ? "这一片" : target.describe()) + "：在它身上耗了太久，先不追了"
                        + targetWhereabouts(tick));
                dropTarget();
                return ActionStatus.progressed();
            }
            if (target == null) {
                return pickNextTarget(tick);
            }
            // 它此刻在哪先记下来：放弃时要交代现场，倒下后要回这里收掉落。
            Optional<double[]> where = moves.positionOf(tick, target.entityId());
            where.ifPresent(at -> lastKnownAt = at);
            Optional<Boolean> seen = sight.visible(target.entityId());
            if (seen.isEmpty()) {
                // 它不在了：出过手的就是打倒了，回它倒下的地方把这次掉出来的收走；没出过手的直接换。
                if (struck) {
                    startLoot();
                    return ActionStatus.progressed();
                }
                notes.add(target.describe() + "不见了，没打成");
                dropTarget();
                return ActionStatus.progressed();
            }
            // 打斗中的事实核对：被拴绳、被命名、被驯服、圈起来了——变成别人的了就停手，不拦在出手前。
            var situation = creatures.situationOf(target.uuid());
            if (situation.isPresent() && situation.get().bondedToSomeone()) {
                notes.add(target.describe() + "变成了别人的（被命名、拴绳、驯服或圈起来），停手不打了");
                dropTarget();
                return ActionStatus.progressed();
            }
            if (arming(tick)) {
                return ActionStatus.running();
            }
            if (!seen.get() || !moves.inReach(tick, target.entityId())) {
                if (moves.sameWater(tick, target.entityId())) {
                    // 人和它泡在同一片水里、中间一路是水：寻路在水里只在水面那一层找路，到不了水下它跟前，只会停在它头顶上
                    // 够不着。直接游过去追，镜头跟着它往下扎或往上浮；氧气不够先上去换满再回来，进了出手距离就砍。
                    keep("游过去追 " + target.entityId(), () -> moves.swimTo(target.entityId()));
                    stepCurrent(tick, "游过去追 " + target.describe());
                    return ActionStatus.running();
                }
                // 被墙挡住或还够不着：朝它现在的位置走过去，看得见又够得着再出手；一直走不到就超时换下一只。
                // 它挪出原来那片（四格见方）就按新位置重新走，不追着扫描时的旧位置跑。
                double[] at = where.orElse(new double[] {
                        target.at().x() + 0.5, target.at().y(), target.at().z() + 0.5});
                keep("走近 " + target.entityId() + "@" + (int) Math.floor(at[0] / APPROACH_CELL)
                                + "," + (int) Math.floor(at[2] / APPROACH_CELL),
                        () -> moves.walkTo(at[0], at[1], at[2]));
                stepCurrent(tick, "走近 " + target.describe());
                return ActionStatus.running();
            }
            keep("打 " + target.entityId(), () -> moves.strike(tick, target.entityId()));
            if (!struck) {
                // 出手了就盯住这只：掉落核对也从这一刻起算，只捡这只带出来的。
                struck = true;
                dropsBefore = drops.nearby();
            }
            stepCurrent(tick, "打 " + target.describe());
            return ActionStatus.running();
        }

        // 锁定一只后、追它之前，先把最好的剑斧拿到手上（主背包里的也开背包拿出来）：等追到跟前才出手，那时贴着它
        // 不为换武器开背包，手里拿着终端、镐就只能用它敲。换完或换不成都只做一次，换不成照样追，出手时有什么用什么。
        private boolean arming(TickContext tick) {
            if (armedFor == target.entityId()) return false;
            if (current == null || !ARMING.equals(currentKey)) {
                Optional<Action> weapon = moves.readyWeapon(tick);
                if (weapon.isEmpty()) {
                    armedFor = target.entityId();
                    return false;
                }
                keep(ARMING, weapon::get);
            }
            if (stepCurrent(tick, "把剑斧拿到手上")) {
                armedFor = target.entityId();
            }
            return true;
        }

        /** 挑下一只：候选里还有就接着打；搜索模式下这一片打完了，接着走下一个点。 */
        private ActionStatus pickNextTarget(TickContext tick) {
            if (candidates.hasNext()) {
                target = candidates.next();
                struck = false;
                stageTicks = 0;
                return ActionStatus.progressed();
            }
            if (plan == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        describeHunt("附近的猎物都试完了"), null));
            }
            stage = Stage.WALK_POINT;
            return ActionStatus.progressed();
        }

        /** 下一个搜索点：从角色脚下按环带一圈一圈往外排，到头了就如实收场。 */
        private ActionStatus nextPointOrGiveUp(TickContext tick) {
            double[] here = moves.selfPosition(tick);
            if (points == null) {
                points = buildPoints(here);
                pointIndex = 0;
            }
            while (pointIndex < points.size()) {
                SearchPoint next = points.get(pointIndex++);
                if (next.distance() > plan.maxDistanceBlocks()) continue;
                point = next;
                stageTicks = 0;
                return ActionStatus.progressed();
            }
            return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                    describeHunt("走出 " + (long) plan.maxDistanceBlocks() + " 格也没见到会掉出"
                            + request.wanted().describe() + "的生物"), null));
        }

        /** 搜索点的环带序列：八个方向各一圈一圈往外，直到愿意走的距离。 */
        private List<SearchPoint> buildPoints(double[] here) {
            List<SearchPoint> built = new ArrayList<>();
            double[][] directions = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
            int rings = (int) Math.ceil(plan.maxDistanceBlocks() / plan.stepBlocks());
            for (int ring = 1; ring <= rings; ring++) {
                for (double[] direction : directions) {
                    double x = here[0] + direction[0] * plan.stepBlocks() * ring;
                    double z = here[2] + direction[1] * plan.stepBlocks() * ring;
                    built.add(new SearchPoint(x, here[1], z, Math.hypot(x - here[0], z - here[2])));
                }
            }
            return built;
        }

        private void startLoot() {
            // 掉落物散在它倒下的地方那一片：先走回那里，远处（八格外）的才进得了"附近"的名单。
            lootSpot = lastKnownAt;
            loot = drops.pickUpNewSince(dropsBefore == null ? Set.of() : dropsBefore);
            lootMissed = false;
            stage = Stage.LOOT;
            stageTicks = 0;
        }

        // 收掉落：先回到它倒下的地方（走不到就到此为止），再一件件捡这次新掉出来的；
        // 捡完（或没捡到如实认了）再看身上，够了就收工，不够换下一只。
        private ActionStatus lootDrops(TickContext tick) {
            if (lootSpot != null) {
                keep("回到倒下的地方 " + (int) lootSpot[0] + "," + (int) lootSpot[2],
                        () -> moves.walkTo(lootSpot[0], lootSpot[1], lootSpot[2]));
                boolean finished = stepCurrent(tick, "回到倒下的地方收掉落");
                if (finished || ++stageTicks > PER_POINT_TICKS) {
                    // 走不到也照样捡：站在原地，够得着的先收，收不到的由捡的动作如实记。
                    lootSpot = null;
                }
                return ActionStatus.running();
            }
            if (loot == null) {
                return afterLoot();
            }
            ActionStatus status = loot.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                // 没收回的如实记下，不冒充收干净：目标物品够不够由身上的清点说了算。
                notes.add("有掉落物没能收进包：" + failed.problem().message());
                lootMissed = true;
            }
            loot.close();
            loot = null;
            return afterLoot();
        }

        private ActionStatus afterLoot() {
            if (enough()) return ActionStatus.done();
            if (carriedWanted() == carriedAtStart && struck && !lootMissed) {
                // 打倒了、地上也没见着它的掉落，才说"没见到"：捡到了别的或没捡到都另有交代，不混成一句。
                notes.add("打倒了一只" + target.describe() + "，没见到" + request.wanted().describe());
            }
            dropTarget();
            stage = Stage.TARGET;
            return ActionStatus.progressed();
        }

        /** 放弃一只时的现场：它最后在哪（方位、几格、高低差），此刻看得见还是被挡住了、还是已经不在。
         *  与出行走不通时报剩余距离是同一件事：只说"耗了太久"，旁观者看不出它到底卡在哪。 */
        private String targetWhereabouts(TickContext tick) {
            if (target == null) {
                return "";
            }
            double[] self = moves.selfPosition(tick);
            double[] at = lastKnownAt != null ? lastKnownAt
                    : new double[] {target.at().x() + 0.5, target.at().y(), target.at().z() + 0.5};
            double dx = at[0] - self[0];
            double dz = at[2] - self[2];
            String spot = DirectionWords.compassOf(dx, dz) + " " + Math.round(Math.hypot(dx, dz) * 10) / 10.0
                    + " 格、" + DirectionWords.heightWord((int) Math.round(at[1] - self[1]));
            return sight.visible(target.entityId())
                    .map(visible -> "；它在" + spot + "，" + (visible ? "看得见" : "视线被挡住"))
                    .orElse("；它最后在" + spot + "，现在已经不在了");
        }

        /** 放下当前目标：换个候选从头来。 */
        private void dropTarget() {
            target = null;
            struck = false;
            dropsBefore = null;
            lastKnownAt = null;
            lootMissed = false;
            stageTicks = 0;
            dropCurrent();
        }

        private String describeHunt(String why) {
            StringBuilder message = new StringBuilder("想打猎弄 ").append(request.count()).append(" 个")
                    .append(request.wanted().describe()).append("，").append(why);
            for (String note : notes) {
                message.append("；").append(note);
            }
            return message.toString();
        }

        // 沿用进行中的动作：同一件事不每刻重建；换了一件事才收尾旧的再起新的。
        // 做完（或做不成）收尾放下，返回它这一刻是不是做完了。
        private boolean stepCurrent(TickContext tick, String what) {
            if (current == null) return true;
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
                dropCurrent();
                return true;
            }
            return false;
        }

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

        @Override public void pause() {
            if (current != null) current.pause();
            if (loot != null) loot.pause();
        }

        @Override public void close() {
            dropCurrent();
            if (loot != null) {
                loot.close();
                loot = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return struck ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return switch (stage) {
                case WALK_POINT -> "走出去找会掉出" + request.wanted().describe() + "的生物";
                case LOOK_AROUND -> "看看这一片有没有猎物";
                case TARGET -> target == null ? "在挑猎物"
                        : (struck ? "打 " + target.describe() : "走近 " + target.describe());
                case LOOT -> (lootSpot != null ? "回它倒下的地方捡" : "捡")
                        + (target == null ? "猎物" : target.describe()) + "掉出来的东西";
            };
        }
    }

    /** 一个搜索点：走过去看一片的地方。 */
    private record SearchPoint(double x, double y, double z, double distance) {}
}
