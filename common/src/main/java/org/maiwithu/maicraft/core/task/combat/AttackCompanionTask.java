package org.maiwithu.maicraft.core.task.combat;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.Ballistics;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.combat.AttackPlan;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.combat.Haven;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.combat.Swing;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goals.GoalAvoidEntities;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code attack}:打掉指定的实体,<b>近战还是远程由身体判,不由模型判</b>。
 *
 * <h2>为什么合成一个工具</h2>
 * 模型在派发那一刻知道的是"打谁";不知道的是等她走到时还有多远、有没有视线、还剩几支箭、
 * 那东西够不够得着——这些每 tick 都在变,只有身体读得到。让模型选弓还是剑,等于要它拿着
 * 过期信息做决定,还顺带引入一整类错误(派远程攻击而背包里没箭)。
 *
 * <h2>三套武器学,一个判据</h2>
 * 挥击(冷却与无敌帧)、射击(弹道与拉弓)、躲避(势场),各自是真正不同的东西;
 * 选哪一套则只有一处判据 {@link AttackPlan},本能链用的也是同一处。
 *
 * <h2>会炸的东西</h2>
 * 爬行者<b>引信点着之前就是一只普通怪</b>:她够得着 4 格、它 3 格才点火,中间那条一格宽的带
 * 能打到它而不触发。点着了再退也来得及——引信 30 刻,而爆炸伤害到 6 格就归零,从 3 格退出去
 * 疾跑只要十来刻。末影水晶不适用:它没有引信,一打就炸。详见 {@link Menace}。
 */
public final class AttackCompanionTask extends AbstractCompanionTask<AttackTaskRecord> {

    private enum Phase { COMBAT, LOOT }

    private static final double CHASE_SPEED = 1.2;
    /** 退避的寻路连续失败几次算"退不掉"。 */
    private static final int MAX_RETREAT_FAILURES = 3;

    // 弹道常数:箭的物理与两种发射器的初速。
    private static final double MAX_FIRING_RANGE = 32.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    private static final double BOW_FULL_SPEED = 3.0;
    private static final double CROSSBOW_SPEED = 3.15;
    /** 连续几发没能真的射出去就判这把武器不顶用。 */
    private static final int MAX_MISFIRES = 2;
    /** 射击时与目标保持的最小距离——太近了弹道压得太平,而且白白挨打。 */
    private static final double RANGED_MIN_DISTANCE = 5.0;
    /** 组装局面看多远:势场要绕开谁、无差别模式打谁,都取这个半径。 */
    private static final double FIELD_RADIUS = 12.0;

    /**
     * 逃跑时扫多远。必须<b>大于</b> {@link Menace#FLEE_DISTANCE},否则她一边跑一边有新的怪
     * 进入视野,目标每几刻换一次,等于没有目标。
     */
    private static final double FLEE_SCAN_RADIUS = 40.0;

    /**
     * 弓战斗的环内沿:比这更近就拉不开弓 —— 弹道压得平,而且白白挨打。
     *
     * <p>它<b>就是</b>弓那一套的"危险半径",和剑那一套的 {@code Menace.rawDangerRadius}
     * 同一个位置、不同的数。以前它是散在判据里的一个 {@code if},和剑的环互相打架。
     *
     * <p>八格,不是五格:<b>拉满一张弓要二十刻</b>,这二十刻里僵尸能走四格半。五格的话她刚
     * 拉到一半人就贴脸了,只能中断重来 —— 实测她在 0.6~2.9 格里挣扎,最后被爬行者炸死。
     * 内沿要装得下"拉一次弓的工夫对方能走多远"。
     */
    private static final double BOW_MIN_DISTANCE = 8.0;

    /**
     * 弓战斗的环外沿。<b>不是射程上限</b> —— 三十二格的话她能站在天边,而箭有下坠、目标
     * 会走,那么远基本射不中。十二格是"稳稳能中、又够得开"的量级:太远就往回走。
     */
    private static final double BOW_MAX_DISTANCE = 12.0;
    /** Strict end-crystal work must stay outside the complete blast span. */
    private static final double STRICT_CRYSTAL_MAX_DISTANCE = 96.0;
    private static final double STRICT_CRYSTAL_BLAST_MARGIN = 1.0;

    /** 离落点这么近就算到了,该重新挑下一个。 */
    private static final double HAVEN_ARRIVED = 2.0;

    /**
     * 逃跑路上多久重算一次路线(刻)。
     *
     * <p><b>落点不变,只重算路线</b>:重算时这一刻的怪会折进边成本,路径拐开而方向不变。
     * 不重算的话整段路只算一次——她起跑之后路上冒出来的怪一只都看不见,直接撞过去。
     *
     * <p>二十刻(一秒)是怪走四五格的量级。再密就是把路径反复拆了重建,疾跑的加速起不来。
     */
    private static final int FLEE_REPLAN_TICKS = 20;

    private Phase phase = Phase.COMBAT;
    private Entity target;
    private Vec3 lastTargetPosition;
    /**
     * 这一刻 {@link #FIELD_RADIUS} 内活着的敌对生物——<b>一刻只扫一次</b>,在 {@link #surveyField}
     * 里;举盾、走位的躲避场都读这一份。"场上有哪些怪"各算各的,就会出现判据说打、腿说没人的局面。
     */
    private List<Mob> hostiles = List.of();

    /**
     * 上一次搜索<b>搜不出路</b>的目标。够不着是拓扑性质,不是距离性质 —— 悬崖对面三格的
     * 骷髅离得很近却没有路,所以只有寻路自己说得清。
     *
     * <p>每次重搜刷新:搜出路了就移出去。它不是一次判死,是"上一段搜索的结论"。
     */
    private final java.util.Set<Integer> noPath = new java.util.HashSet<>();

    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private final LootSweep loot;

    /**
     * 这一场经手过的 id。无差别模式没有事先的名单,不记下来就无处结算战果
     * ——它打倒的东西会因为"不在请求清单里"而被整场吞掉。
     */
    private final java.util.Set<Integer> touchedIds = new java.util.LinkedHashSet<>();

    /** 退避的寻路连续失败次数。够了就是"退不掉",判据据此改判背水一战。 */
    private int retreatFailures;

    /** 上一行站位日志。数字没变就不再打,免得每 tick 一行把别的全冲掉。 */
    private String lastStandoffLog;

    private RangedShot shot;
    private final FirstPersonActionGate meleeSelection = new FirstPersonActionGate();
    private final FirstPersonActionGate rangedSelection = new FirstPersonActionGate();
    private Interaction meleeAction;
    private int meleeVictimId = -1;
    private Interaction shieldAction;
    private int misfires;
    private int lastPlanLogTick = -1000;
    private AttackPlan.Action lastLoggedAction;
    /** 上一刻的决定。判据靠它做迟滞与承诺,见 {@link AttackPlan#decide}。 */
    private AttackPlan.Move lastMove;

    /**
     * 逃跑的<b>落点</b>。一次挑定,跑到才换 —— 方向的连续性就是不绕圈的全部原因。
     *
     * <p>路径本身仍然每次重规划都重算,新冒出来的怪由边成本({@code Avoidance.forGoal})
     * 折进去,路线会拐开而<b>目标不变</b>。以前重算连方向一起重掷,所以既反应了也绕圈了。
     */
    private BlockPos haven;

    /** 这一段逃跑路线是哪一刻算的。到点就重算,见 {@link #FLEE_REPLAN_TICKS}。 */
    private long havenPlannedAt;

    public AttackCompanionTask(LocalPlayer player, AttackTaskRecord record) {
        super(player, record);
        this.loot = new LootSweep(player);
    }

    @Override
    protected void onStart() {
        snapshotInventory(inventoryBaseline);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;
        if (phase == Phase.LOOT) return tickLoot();

        Battlefield field = surveyField();
        for (var f : field.foes()) {
            if (f.authorized()) {
                touchedIds.add(f.id());
            }
        }
        settleFinishedTargets();
        AttackPlan.Move move = AttackPlan.decide(field, lastMove);
        lastMove = move;
        logMove(move, field);

        Entity chosen = move.foeId() == AttackPlan.NO_FOE ? null : liveEntity(move.foeId());
        if (chosen != target) {
            stopNav();
            abortShot();
            target = chosen;
        }
        if (target != null) {
            lastTargetPosition = target.position();
            loot.rememberPreexisting(BlockPos.containing(lastTargetPosition));
        }
        // 攻击与移动<b>正交</b>:每刻先问一次"冷却好了吗、够得着谁吗",够得着就打 ——
        // 不管这一刻在靠近、在拉开、还是站着。攻击不影响寻路,最多让她回个头。
        tickShield();
        tickWeapon(field);
        if (move.action() != AttackPlan.Action.DISENGAGE && haven != null) {
            haven = null;   // 不再逃跑了:落点作废,下次要跑再重新挑
            stopNav();
        }
        return switch (move.action()) {
            case SKIRMISH -> {
                bowFighting = false;
                yield closeIn();
            }
            case BOW -> {
                bowFighting = true;
                yield bowFight();
            }
            case DISENGAGE -> tickFlee();
            case DONE -> finish();
        };
    }

    // ==================== 局面 ====================

    /**
     * 把这一刻的世界折成 {@link Battlefield}。
     *
     * <p>点名模式下"被授权"是模型给的那份清单;无差别模式下是"这一刻在追我的"——会分裂的怪
     * 裂出来的新 id 因此自动进场,而点名的清单一裂开就作废了。
     */
    private Battlefield surveyField() {
        hostiles = Menace.hostilesAround(player, FIELD_RADIUS);
        List<Battlefield.Foe> foes = new ArrayList<>();
        for (var mob : hostiles) {
            boolean engaging = mob.getTarget() == player || mob == player.getLastHurtByMob();
            boolean authorized = r.indiscriminate ? engaging : r.entityIds.contains(mob.getId());
            if (r.terminal(mob.getId())) {
                // 打完了、丢了、或者走不到又射不到的:<b>整只移出局面</b>。留着当"还有东西在
                // 追我"的话,判据会永远喊走位 —— 一只在悬崖对面射她的骷髅就能把任务钉死。
                // 躲它归寻路的势场管,那一层看的是场上的怪,不是这份名单。
                continue;
            }
            foes.add(new Battlefield.Foe(
                    mob.getId(),
                    player.distanceTo(mob),
                    Menace.explodes(mob),
                    Menace.armed(mob),
                    engaging,
                    reachable(mob.getId()),
                    authorized));
        }
        // 点名模式还可能被要求打不敌对的东西(一只鸡、一个末影水晶),它们不在敌对扫描里。
        if (!r.indiscriminate) {
            for (int id : r.entityIds) {
                if (r.terminal(id) || containsId(foes, id)) {
                    continue;
                }
                Entity e = liveEntity(id);
                if (e != null) {
                    foes.add(new Battlefield.Foe(id, player.distanceTo(e),
                            Menace.explodes(e), Menace.armed(e),
                            false, reachable(id), true));
                }
            }
        }
        Loadout loadout = Loadout.forTarget(player, player);
        return new Battlefield(
                Menace.effectiveHealth(player),
                reachToTarget(),
                loadout.hasMelee(), loadout.hasRanged(),
                retreatFailures >= MAX_RETREAT_FAILURES, foes);
    }

    private static boolean containsId(List<Battlefield.Foe> foes, int id) {
        for (var f : foes) {
            if (f.id() == id) return true;
        }
        return false;
    }

    private boolean reachable(int id) {
        return !noPath.contains(id);
    }

    private Entity liveEntity(int id) {
        Entity e = player.clientLevel.getEntity(id);
