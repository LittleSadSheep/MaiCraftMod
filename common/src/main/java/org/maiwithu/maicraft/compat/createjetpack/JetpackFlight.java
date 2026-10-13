// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.behavior.landing.AirSteering;
import org.maiwithu.maicraft.behavior.navigation.FlightLandings;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞一段：（背包里的先换上）→ 打开飞行与悬停 → 沿空中的路飞，巡航按着疾跑 → 到落点正上方快速下降 → 落地 →
 * 开关拨回起飞前的样子 → 换回原来的胸甲。
 *
 * <p>路被挡（半天飞不近）重新找路；气不够飞完剩下的先落到近处能站的地方；飞着挨打就往离打的那家伙远的地方落下，
 * 落地后交给自卫。这三种都如实结束这一段，行程从落下的地方接着规划。开关读不回的那一笔记进说不清的里。
 */
final class JetpackFlight implements TravelLeg {

    private static final Logger LOG = LoggerFactory.getLogger(JetpackFlight.class);

    /** 飞的几步。 */
    enum Step { EQUIP, TAKEOFF, FLY, DESCEND, RESTORE, UNEQUIP, FINISHED }

    /** 离拐弯点这么近算到了（水平、上下）。 */
    static final double REACHED_HORIZONTAL = 0.25;
    static final double REACHED_VERTICAL = 0.35;
    /**
     * 往上、往下穿洞前要在这一列正中对准到这么近：身体 0.6 宽，一格宽的洞两边只各剩 0.2，
     * 偏了就顶着洞沿上不去（实机 10-13 在两块活板门中间的空当底下顶住，重新找了三次路也飞不近）。
     */
    static final double CENTRED = 0.15;
    /** 横着飞时高度差这么多以内才往前走：两格高的口子上下也只剩 0.2，先把高度对齐。 */
    static final double LEVEL = 0.15;
    /** 这么多刻没飞近就重新找路，最多找三次。 */
    static final int STALL_TICKS = 60;
    static final int MAX_REPLANS = 3;
    /** 每隔多少刻看一眼气够不够。 */
    static final int AIR_CHECK_TICKS = 20;
    /** 挨打后往外找落点的距离（格）。 */
    static final int[] ESCAPE_DISTANCES = {8, 16};

    private final CreateJetpackCompat compat;
    private final PlayerServices services;
    private final JetpackSwitches switches;
    private final List<Change> changes = new ArrayList<>();
    private final List<Change> unconfirmed = new ArrayList<>();

    private BlockPos landing;
    private List<BlockPos> route = List.of();
    private int waypoint;
    private Step step = Step.EQUIP;
    private Action current;
    private FastDrop drop;
    private String itemId = "";
    private String previousChest = "";
    private boolean equipped;
    private boolean wasActive;
    private boolean wasHovering;
    private boolean started;
    private int replans;
    private long lastGain;
    private double bestDistance = Double.MAX_VALUE;
    private float lastHealth = -1;
    private boolean stopRequested;
    /** 这一段要结束时报的问题（气不够、挨打、开关拨不动）；正常落到落点为 null。 */
    private Problem ending;
    private String summary = "";

    JetpackFlight(CreateJetpackCompat compat, PlayerServices services, BlockPos landing) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.switches = new JetpackSwitches(compat);
        this.landing = Objects.requireNonNull(landing, "landing").immutable();
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，飞不了"));
        }
        long now = level.getGameTime();
        if (!started && !begin(player)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有喷气背包了"));
        }
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return afterGearFailed(context, failed.problem());
            return afterGear(player);
        }
        // 起飞、飞、往下落是这一段自己管着上下：每刻说一声，落地防护不接手（快速下降时故意关包往下掉）。
        if (step == Step.TAKEOFF || step == Step.FLY || step == Step.DESCEND) ClientHooks.noteFlightControlled();
        else ClientHooks.clearFlightControlled();
        return switch (step) {
            case EQUIP -> equip(player);
            case TAKEOFF -> takeOff(level, player, now);
            case FLY -> fly(context, level, player, now);
            case DESCEND -> descend(context, level, player, now);
            case RESTORE -> restore(player, now);
            case UNEQUIP -> unequip(player);
            case FINISHED -> finish(context);
        };
    }

    // 记下起飞前的样子：背包穿着没有、两个开关怎样、胸甲槽原来穿着什么。
    private boolean begin(LocalPlayer player) {
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty()) return false;
        started = true;
        itemId = pack.get().itemId();
        wasActive = pack.get().active();
        wasHovering = pack.get().hovering();
        previousChest = pack.get().chestItemId();
        lastHealth = player.getHealth();
        step = pack.get().worn() ? Step.TAKEOFF : Step.EQUIP;
        return true;
    }

    // 背包里的先换上胸甲槽：原来的胸甲换下来进背包，落地后换回去。
    private ActionStatus equip(LocalPlayer player) {
        Optional<Action> wear = services.gear().wear(itemId, GearSlotName.CHEST);
        if (wear.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "背包里的 " + itemId + " 换不到胸甲槽上"));
        }
        current = wear.get();
        return ActionStatus.progressed();
    }

    private ActionStatus afterGear(LocalPlayer player) {
        if (step == Step.EQUIP) {
            equipped = true;
            changes.add(new Change(Change.Kind.OTHER, itemId, 1, "换上胸甲槽飞行"
                    + (previousChest.isEmpty() ? "" : "，原来的 " + previousChest + " 进了背包")));
            step = Step.TAKEOFF;
            return ActionStatus.progressed();
        }
        if (step == Step.UNEQUIP) {
            changes.add(new Change(Change.Kind.OTHER, previousChest.isEmpty() ? itemId : previousChest, 1,
                    previousChest.isEmpty() ? "落地后把喷气背包取下放回背包" : "落地后换回原来的胸甲"));
            summary += "；装备换回去了";
            step = Step.FINISHED;
            return ActionStatus.progressed();
        }
        return ActionStatus.progressed();
    }

    private ActionStatus afterGearFailed(PlayerContext context, Problem problem) {
        if (step == Step.EQUIP) return ActionStatus.failed(problem);
        // 落地后换回装备没成：飞这一段本身做完了，没换回去的那件如实记下。
        unconfirmed.add(new Change(Change.Kind.OTHER, itemId, 1, "落地后没能换回原来的胸甲：" + problem.headline()));
        summary += "；装备没换回去";
        step = Step.FINISHED;
        return ActionStatus.progressed();
    }

    // 起飞：打开飞行与悬停，读回一致才飞；开不了如实结束，开关状态不明的记进说不清的。
    private ActionStatus takeOff(ClientLevel level, LocalPlayer player, long now) {
        switches.want(true, true);
        JetpackSwitches.State state = switches.tick(player, now);
        if (state == JetpackSwitches.State.UNCONFIRMED) {
            unconfirmed.add(new Change(Change.Kind.OTHER, itemId, 1, "起飞时喷气背包开关状态不明：" + switches.unclear()));
            ending = Problem.of(Problem.Kind.REFUSED_BY_GAME, "喷气背包开不起来：" + switches.unclear());
            step = Step.RESTORE;
            return ActionStatus.running();
        }
        if (state != JetpackSwitches.State.DONE) return ActionStatus.running();
        if (!replan(level, player)) {
            ending = Problem.of(Problem.Kind.UNREACHABLE, "起飞后看过的空中找不到飞到 " + landing.toShortString() + " 的路");
            step = Step.RESTORE;
            return ActionStatus.running();
        }
        lastGain = now;
        step = Step.FLY;
        return ActionStatus.progressed();
    }

    // 飞：一段一段直着飞到拐弯点；落点正上方往下一大段就换快速下降；半天飞不近重新找路；看着气和挨打。
    private ActionStatus fly(PlayerContext context, ClientLevel level, LocalPlayer player, long now) {
        PlayerInput input = context.input();
        if (stopRequested && ending == null) {
            ending = Problem.of(Problem.Kind.STUCK, "按要求在空中停下，先落到近处");
            if (!emergency(level, player, null)) return descendHere(level, player);
        }
        if (ending == null && player.getHealth() < lastHealth - 0.01f) {
            LivingEntity attacker = player.getLastHurtByMob();
            ending = Problem.of(Problem.Kind.DANGER, "飞着挨打" + (attacker == null ? "" : "（"
                    + attacker.getType().getDescription().getString() + "）") + "，先落地交给自卫");
            if (!emergency(level, player, attacker)) return descendHere(level, player);
        }
        lastHealth = player.getHealth();
        if (ending == null && now % AIR_CHECK_TICKS == 0 && !enoughAir(player)) {
            ending = Problem.of(Problem.Kind.NEED_ITEM, "背罐里的气不够飞完剩下的路，先落到近处",
                    "给背罐打满气再飞，或走过去");
            if (!emergency(level, player, null)) return descendHere(level, player);
        }
        if (waypoint >= route.size()) return startDescent(level, player);
        Vec3 position = player.position();
        BlockPos next = route.get(waypoint);
        Vec3 aim = Vec3.atBottomCenterOf(next);
        double horizontal = horizontal(position, aim);
        double rise = aim.y - position.y;
        if (horizontal < REACHED_HORIZONTAL && Math.abs(rise) < REACHED_VERTICAL) {
            waypoint++;
            return ActionStatus.progressed();
        }
        // 落点正上方、往下是直直一大段：换快速下降，不慢慢飘。
        if (waypoint == route.size() - 1 && horizontal < REACHED_HORIZONTAL && rise < -FastDrop.SAFE_DROP) {
            return startDescent(level, player);
        }
        double toLanding = position.distanceTo(Vec3.atBottomCenterOf(landing));
        if (toLanding < bestDistance - 0.1) {
            bestDistance = toLanding;
            lastGain = now;
        } else if (now - lastGain > STALL_TICKS) {
            LOG.info("[喷气背包] 在 {} 飞了 {} 刻没飞近落点 {}，第 {} 次重新找路", player.blockPosition().toShortString(),
                    STALL_TICKS, landing.toShortString(), replans + 1);
            if (++replans > MAX_REPLANS || !replan(level, player)) {
                ending = Problem.of(Problem.Kind.STUCK, "飞到 " + player.blockPosition().toShortString()
                        + " 被挡住，重新找了 " + MAX_REPLANS + " 次路也飞不近");
                return descendHere(level, player);
            }
            lastGain = now;
        }
        if (horizontal > 0.3) input.lookAt(player, new Vec3(aim.x, player.getEyeY(), aim.z));
        // 路是一格一格的直段：竖着的一段先在这一列正中对准再升降，横着的一段先把高度对齐再往前，
        // 身体碰不到洞沿、口子边。往哪边推按世界方向算、按此刻真实朝向折成前后左右：镜头像真人一样慢慢转，
        // 原先按"朝前"推，镜头还没转过来就顺着旧朝向飞出去（实机 10-13 往反方向飞出七十多格）。
        BlockPos previous = waypoint == 0 ? null : route.get(waypoint - 1);
        boolean upright = previous == null || previous.getX() == next.getX() && previous.getZ() == next.getZ();
        boolean levelled = Math.abs(rise) < LEVEL;
        BlockPos steerTo = upright || levelled ? next : previous;
        boolean centred = horizontal(position, Vec3.atBottomCenterOf(steerTo)) < CENTRED;
        boolean up = rise > LEVEL && (!upright || centred);
        boolean down = rise < -LEVEL && (!upright || centred);
        boolean sprint = !upright && levelled && horizontal > 3;
        // 按键交给角色输入要用本刻的客户端刻号（不是世界时间）：原先传世界时间，飞第一段就内部错误
        // "input request is not for the active tick"。
        input.applySteering(yaw -> {
            PlayerInput.Movement push = AirSteering.movement(player, steerTo, yaw, false);
            return new PlayerInput.Movement(push.forward(), push.strafe(), up, down, sprint);
        }, player.getYRot(), context.clientTick());
        return ActionStatus.running();
    }

    // 落点正上方：下面是水或不高就直接关掉落，否则照快速下降的做法。
    private ActionStatus startDescent(ClientLevel level, LocalPlayer player) {
        input().halt(player);
        boolean soft = BlockPassability.isWater(level, landing) || player.getY() - landing.getY() <= FastDrop.SAFE_DROP;
        drop = new FastDrop(switches, landing.getY(), soft);
        step = Step.DESCEND;
        return ActionStatus.progressed();
    }

    // 找不到别的落点：就地往正下方落。
    private ActionStatus descendHere(ClientLevel level, LocalPlayer player) {
        landing = JetpackFallBrake.groundBelow(level, player.blockPosition());
        route = List.of();
        waypoint = 0;
        return startDescent(level, player);
    }

    // 快速下降：一边落一边把身子对准落点那一列；落地就去拨回开关。
    private ActionStatus descend(PlayerContext context, ClientLevel level, LocalPlayer player, long now) {
        FastDrop.Stage stage = drop.tick(context, now);
        if (drop.unclear() != null && ending == null) {
            unconfirmed.add(new Change(Change.Kind.OTHER, itemId, 1, "下降时喷气背包开关状态不明：" + drop.unclear()));
        }
        if (stage == FastDrop.Stage.DOWN) {
            if (!player.blockPosition().equals(landing)) summary += "（实际落在 " + player.blockPosition().toShortString() + "）";
            step = Step.RESTORE;
            return ActionStatus.progressed();
        }
        Vec3 aim = Vec3.atBottomCenterOf(landing);
        double horizontal = horizontal(player.position(), aim);
        if (horizontal > 0.4) context.input().lookAt(player, new Vec3(aim.x, player.getEyeY(), aim.z));
        context.input().applyMovement(new PlayerInput.Movement(horizontal > 0.4 ? 0.6f : 0.0f, 0.0f, false, false, false),
                context.clientTick());
        return ActionStatus.running();
    }

    // 收拾：开关拨回起飞前的样子；拨不回记进说不清的。
    private ActionStatus restore(LocalPlayer player, long now) {
        input().halt(player);
        switches.want(wasActive, wasHovering);
        JetpackSwitches.State state = switches.tick(player, now);
        if (state == JetpackSwitches.State.WORKING) return ActionStatus.running();
        if (state == JetpackSwitches.State.UNCONFIRMED) {
            unconfirmed.add(new Change(Change.Kind.OTHER, itemId, 1, "落地后喷气背包开关没拨回原样：" + switches.unclear()));
        }
        step = equipped ? Step.UNEQUIP : Step.FINISHED;
        return ActionStatus.progressed();
    }

    // 换回原来的胸甲；原来空着就把喷气背包取下放回背包。
    private ActionStatus unequip(LocalPlayer player) {
        Optional<Action> back = previousChest.isEmpty() ? services.gear().takeOff(GearSlotName.CHEST)
                : services.gear().wear(previousChest, GearSlotName.CHEST);
        if (back.isEmpty()) {
            unconfirmed.add(new Change(Change.Kind.OTHER, itemId, 1, "落地后没能换回原来的胸甲"));
            step = Step.FINISHED;
            return ActionStatus.progressed();
        }
        current = back.get();
        return ActionStatus.progressed();
    }

    private ActionStatus finish(PlayerContext context) {
        context.input().halt(context.localPlayer());
        return ending == null ? ActionStatus.done() : ActionStatus.failed(ending);
    }

    // 重新找路：从此刻的位置飞到落点。
    private boolean replan(ClientLevel level, LocalPlayer player) {
        Predicate<BlockPos> loaded = level::isLoaded;
        Optional<List<BlockPos>> found = JetpackMode.route(level, loaded, player.blockPosition(), landing);
        if (found.isEmpty()) return false;
        route = found.get();
        LOG.info("[喷气背包] 从 {} 飞到 {}：拐弯点 {}", player.blockPosition().toShortString(), landing.toShortString(),
                route.stream().map(BlockPos::toShortString).toList());
        waypoint = 0;
        bestDistance = Double.MAX_VALUE;
        return true;
    }

    // 紧急落地：往下、往前后左右 8 与 16 格找能落的地方；挨打时挑离打的那家伙最远的，否则挑最近的。
    private boolean emergency(ClientLevel level, LocalPlayer player, LivingEntity attacker) {
        Predicate<BlockPos> loaded = level::isLoaded;
        BlockPos here = player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        FlightLandings.near(level, loaded, here, 2, JetpackMode.CLEARANCE).ifPresent(candidates::add);
        for (int distance : ESCAPE_DISTANCES) {
            for (int[] direction : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                FlightLandings.near(level, loaded, here.offset(direction[0] * distance, 0, direction[1] * distance), 3,
                        JetpackMode.CLEARANCE).ifPresent(candidates::add);
            }
        }
        candidates.sort((a, b) -> attacker == null
                ? Double.compare(a.distSqr(here), b.distSqr(here))
                : Double.compare(attacker.distanceToSqr(Vec3.atCenterOf(b)), attacker.distanceToSqr(Vec3.atCenterOf(a))));
        for (BlockPos candidate : candidates) {
            Optional<List<BlockPos>> found = JetpackMode.route(level, loaded, here, candidate);
            if (found.isPresent()) {
                landing = candidate;
                route = found.get();
                waypoint = 0;
                bestDistance = Double.MAX_VALUE;
                return true;
            }
        }
        return false;
    }

    // 气够不够飞完剩下的路（加几秒余量）。
    private boolean enoughAir(LocalPlayer player) {
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty()) return false;
        double left = remainingLength(player.position()) / JetpackMode.CRUISE_BLOCKS_PER_TICK / 20 + 3;
        return pack.get().secondsLeft() >= left;
    }

    private double remainingLength(Vec3 from) {
        double total = 0;
        Vec3 at = from;
        for (int i = waypoint; i < route.size(); i++) {
            Vec3 next = Vec3.atBottomCenterOf(route.get(i));
            total += at.distanceTo(next);
            at = next;
        }
        return total;
    }

    private PlayerInput input() {
        return services.context().get().input();
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override public void requestSafeStop() {
        // 飞着的先落地再停：在近处找能落的地方落下，拨回开关、换回装备。
        stopRequested = true;
    }

    @Override public void pause() {
        PlayerContext context = services.context().get();
        if (current != null) current.pause();
        if (context != null && context.input() != null && context.localPlayer() != null) context.input().halt(context.localPlayer());
    }

    @Override public void close() {
        ClientHooks.clearFlightControlled();
        if (current != null) {
            current.close();
            current = null;
        }
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) context.input().halt(context.localPlayer());
    }

    @Override public Interruptibility interruptibility() {
        // 在天上停下不安全：松开就往下掉；落了地、拨开关、换装备时照常。
        if (step == Step.FLY || step == Step.DESCEND || step == Step.TAKEOFF) return Interruptibility.UNSAFE_TO_STOP;
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public List<Change> changes() {
        return List.copyOf(changes);
    }

    @Override public List<Change> unconfirmed() {
        return List.copyOf(unconfirmed);
    }

    @Override public String summary() {
        return "喷气背包飞到 " + landing.toShortString() + summary;
    }

    @Override public String describe() {
        String doing = switch (step) {
            case EQUIP -> "换上喷气背包";
            case TAKEOFF -> "起飞";
            case FLY -> "飞（第 " + Math.min(waypoint + 1, Math.max(route.size(), 1)) + "/" + route.size() + " 段）";
            case DESCEND -> "往下落";
            case RESTORE -> "拨回开关";
            case UNEQUIP -> "换回胸甲";
            case FINISHED -> "收尾";
        };
        return "喷气背包飞到 " + landing.toShortString() + "：" + doing;
    }
}
