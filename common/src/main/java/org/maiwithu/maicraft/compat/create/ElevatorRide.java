// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.navigation.StraightWalk;
import org.maiwithu.maicraft.behavior.navigation.baritone.NavigationObstacles;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 坐一趟电梯：呼梯 → 等轿厢停稳 → 走进轿厢里面一点 → 在控制器上选层 → 站着别动 → 到了走出去 → 在固定平台上站稳。
 *
 * <p>呼梯的办法（{@link ElevatorCalls}）：触点旁的按钮、和触点旁无线接收端同频的发射端旁的按钮、身上遥控器同频的键，
 * 近的先试；一种没用成（走不到、遥控器拿错）换下一种，都按过了轿厢还不来，再按一次按成过的，还不来就如实结束。
 * 轿厢已经停在这层、或已经叫过正往这层来，就不再按按钮；10 秒轿厢一点没动就停在当前安全处如实结束。
 * 轿厢里的路寻路看不见（轿厢不是世界方块），进出都直着走；坐着的这段时间轿厢和电梯井不当障碍。
 * 出口挨个试：最近的门口被挡就换下一个。选中的轿厢不同步了（走远、被拆）也停下如实说。
 */
final class ElevatorRide implements TravelLeg {

    /** 坐电梯的几步。 */
    enum Step { CALL, WAIT, BOARD, SELECT, RIDE, EXIT, SETTLE }

    /** 轿厢这么多刻一点没动就不等了（10 秒）。 */
    static final int NO_MOVE_TICKS = 200;
    /** 选完层这么多刻电梯还没反应就再选一次，最多选两次。 */
    static final int SELECT_WAIT_TICKS = 40;
    static final int MAX_SELECTS = 2;
    /** 呼梯最多按成几次：几种办法都按过了轿厢还不来，按成过的再按一次。 */
    static final int MAX_PRESSES = 2;
    /** 按成了记的那句。 */
    private static final String PRESSED = "按了";
    /** 出来后在固定平台上站稳这么多刻才算到。 */
    static final int SETTLE_TICKS = 3;
    /** 进轿厢、出轿厢每个出口最多直着走多少刻。 */
    static final int WALK_TICKS = 100;

    private final CreateCompat compat;
    private final PlayerServices services;
    private final Permissions permissions;
    private final int entityId;
    private final int targetContactY;

    private Step step = Step.CALL;
    private Action current;
    private String structureId;
    private boolean riding;
    private boolean stopRequested;
    private int presses;
    /** 试过的呼梯办法 → 怎么样了（按了，或为什么没用成）；这一次正在试的那个。 */
    private final Map<String, String> triedCalls = new LinkedHashMap<>();
    private final Map<String, String> callHows = new LinkedHashMap<>();
    private ElevatorCalls.Call trying;
    private int selects;
    private long selectedAt;
    private long lastMoveTick = Long.MIN_VALUE;
    private double lastCabinY = Double.NaN;
    private int settled;
    private List<BlockPos> exits;
    private int exitIndex;
    private String fromName = "";
    private String toName = "";

    ElevatorRide(CreateCompat compat, PlayerServices services, Permissions permissions, int entityId, int targetContactY) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.entityId = entityId;
        this.targetContactY = targetContactY;
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，坐不了电梯"));
        }
        Optional<CreateElevators.Elevator> read = compat.elevator(level, player, entityId);
        if (read.isEmpty() && step != Step.SETTLE) {
            return fail(context, Problem.of(Problem.Kind.TARGET_GONE, "选中的轿厢不同步了（走远或被拆），停在 "
                    + player.blockPosition().toShortString()));
        }
        CreateElevators.Elevator elevator = read.orElse(null);
        if (elevator != null) {
            structureId = elevator.id();
            watchMotion(elevator, tick.gameTick());
            names(elevator);
        }
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return afterFailedStep(context, level, failed.problem());
            return afterStep(context, level, player, elevator, tick.gameTick());
        }
        return switch (step) {
            case CALL -> call(context, level, player, elevator, tick.gameTick());
            case WAIT -> waitFor(context, level, player, elevator, tick.gameTick());
            case BOARD -> board(context, player, elevator);
            case SELECT -> select(context, player, elevator, tick.gameTick());
            case RIDE -> ride(context, player, elevator, tick.gameTick());
            case EXIT -> exit(context, level, player, elevator);
            case SETTLE -> settle(context, player, elevator);
        };
    }

    // 呼梯：已经在轿厢里就直接选层；轿厢停在这层就进去；已经叫过、正往这层来就等，不重复按；
    // 否则挑一个呼梯办法——走到按钮跟前按一下，或拿遥控器按那个键。
    private ActionStatus call(PlayerContext context, ClientLevel level, LocalPlayer player,
            CreateElevators.Elevator elevator, long now) {
        if (elevator.carriesPlayer()) return go(Step.SELECT);
        Optional<CreateElevators.Floor> here = ElevatorFloors.floorOf(elevator, player.blockPosition());
        if (here.isEmpty()) {
            return fail(context, Problem.of(Problem.Kind.UNREACHABLE, "不在这台电梯的任何一层，也不在轿厢里，停在 "
                    + player.blockPosition().toShortString()));
        }
        if (stopRequested) return done(context);
        if (elevator.arrived() && elevator.currentContactY() == here.get().contactY()) return go(Step.BOARD);
        if (elevator.targetContactY() == here.get().contactY() && presses == 0) {
            lastMoveTick = now;
            return go(Step.WAIT);
        }
        ElevatorCalls.Found found = ElevatorCalls.find(compat, level, player, elevator.contact(here.get()),
                ElevatorFloors.describe(here.get()));
        if (found.calls().isEmpty()) {
            return fail(context, Problem.of(Problem.Kind.UNREACHABLE, found.looked() + "，叫不来轿厢"));
        }
        Optional<ElevatorCalls.Call> next = nextCall(found.calls());
        if (next.isEmpty()) {
            String tried = triedText();
            return fail(context, presses > 0
                    ? Problem.of(Problem.Kind.STUCK, "呼了 " + presses + " 次梯（" + tried + "），轿厢没往这层（"
                            + ElevatorFloors.describe(here.get()) + "）来")
                    : Problem.of(Problem.Kind.UNREACHABLE, "呼梯的办法都没用成：" + tried));
        }
        trying = next.get();
        callHows.put(trying.key(), trying.how());
        if (trying.remote()) {
            current = new RemotePress(compat, services, trying.remoteKey(), trying.remoteFrequency());
            return ActionStatus.progressed();
        }
        BlockPos at = trying.button();
        current = new StepsInOrder("呼梯", List.of(
                new StepsInOrder.Step("走到呼梯按钮跟前", () -> services.approach().toward(ApproachTarget.ofBlock(at), permissions)),
                new StepsInOrder.Step("按呼梯按钮", () -> {
                    BlockState before = level.getBlockState(at);
                    return services.interactions().useBlock(at, InteractionConfirmation.blockChanged(at, before));
                })));
        return ActionStatus.progressed();
    }

    // 等轿厢：停稳在这层就进去；轿厢一直不动 10 秒，或停在别的层又不往这层来，就回去再按一次。
    private ActionStatus waitFor(PlayerContext context, ClientLevel level, LocalPlayer player,
            CreateElevators.Elevator elevator, long now) {
        Optional<CreateElevators.Floor> here = ElevatorFloors.floorOf(elevator, player.blockPosition());
        if (here.isPresent() && elevator.arrived() && elevator.currentContactY() == here.get().contactY()) {
            return go(Step.BOARD);
        }
        if (stopRequested) return done(context);
        boolean coming = here.isPresent() && elevator.targetContactY() == here.get().contactY();
        // 轿厢不来：回去换一种呼梯办法（或把按成过的再按一次），都用过了由呼梯那一步如实结束。
        if (now - lastMoveTick >= NO_MOVE_TICKS || (!coming && elevator.arrived() && now - lastMoveTick >= SELECT_WAIT_TICKS)) {
            return go(Step.CALL);
        }
        return ActionStatus.running();
    }

    // 进轿厢：坐着的这段轿厢与电梯井不当障碍；挑轿厢里有地板、离门远一点的那格，直着走进去。
    private ActionStatus board(PlayerContext context, LocalPlayer player, CreateElevators.Elevator elevator) {
        ignoreCabin(elevator.id());
        if (elevator.carriesPlayer() && current == null && inside(player, elevator)) return go(Step.SELECT);
        Optional<BlockPos> spot = innerSpot(elevator, player.position());
        if (spot.isEmpty()) {
            return fail(context, Problem.of(Problem.Kind.UNREACHABLE, "轿厢里没有能站的地方（地板上面两格都被占着）"));
        }
        current = new StraightWalk(services.context(), Vec3.atBottomCenterOf(spot.get()), 0.35, WALK_TICKS, "走进轿厢");
        return ActionStatus.progressed();
    }

    // 选层：看着装置控制器选目标层；已经在去那层的路上就不用选；选了没反应再选一次。
    private ActionStatus select(PlayerContext context, LocalPlayer player, CreateElevators.Elevator elevator, long now) {
        if (elevator.arrived() && elevator.currentContactY() == targetContactY) return go(Step.EXIT);
        if (selects > 0 && (elevator.targetContactY() == targetContactY || !elevator.arrived())) {
            lastMoveTick = now;
            return go(Step.RIDE);
        }
        if (selects > 0 && now - selectedAt < SELECT_WAIT_TICKS) {
            context.input().halt(player);
            return ActionStatus.running();
        }
        if (selects >= MAX_SELECTS) {
            return fail(context, Problem.of(Problem.Kind.REFUSED_BY_GAME, "在控制器上选了 " + toName + "，电梯没有动（服务器没接受）"));
        }
        Optional<BlockPos> control = elevator.controls().stream()
                .min(Comparator.comparingDouble(cell -> Vec3.atCenterOf(cell).distanceToSqr(player.getEyePosition())));
        if (control.isEmpty()) {
            return fail(context, Problem.of(Problem.Kind.UNSUPPORTED, "轿厢里没有装置控制器，选不了层"));
        }
        context.input().halt(player);
        context.input().lookAt(player, Vec3.atCenterOf(control.get()));
        compat.selectElevatorFloor(entityId, targetContactY);
        selects++;
        selectedAt = now;
        return ActionStatus.progressed();
    }

    // 坐着：站着别动、跟着轿厢走；到了那层停稳就出去；10 秒一点没动就停在轿厢里如实结束。
    private ActionStatus ride(PlayerContext context, LocalPlayer player, CreateElevators.Elevator elevator, long now) {
        context.input().halt(player);
        if (elevator.arrived() && (elevator.currentContactY() == targetContactY || stopRequested)) return go(Step.EXIT);
        if (now - lastMoveTick >= NO_MOVE_TICKS) {
            return fail(context, Problem.of(Problem.Kind.STUCK, "坐在轿厢里，轿厢 " + NO_MOVE_TICKS / 20
                    + " 秒没动，停在 " + player.blockPosition().toShortString()));
        }
        return ActionStatus.running();
    }

    // 出轿厢：到的那层轿厢外面能站的固定格从近到远挨个试，直着走出去；都走不出去如实说。
    private ActionStatus exit(PlayerContext context, ClientLevel level, LocalPlayer player, CreateElevators.Elevator elevator) {
        if (exits == null) {
            exits = landings(level, elevator.cabin(), player.blockPosition());
            exitIndex = 0;
        }
        if (exitIndex >= exits.size()) {
            return fail(context, Problem.of(Problem.Kind.UNREACHABLE, "到了 " + toName + "，出不了轿厢：门口 "
                    + exits.size() + " 个能站的地方都没走到"));
        }
        current = new StraightWalk(services.context(), Vec3.atBottomCenterOf(exits.get(exitIndex)), 0.3, WALK_TICKS,
                "走出轿厢");
        return ActionStatus.progressed();
    }

    // 站稳：不在轿厢里、踩在地上连着 3 刻才算到，免得把还在轿厢边上当成出来了。
    private ActionStatus settle(PlayerContext context, LocalPlayer player, CreateElevators.Elevator elevator) {
        boolean out = elevator == null || !elevator.carriesPlayer();
        settled = out && player.onGround() ? settled + 1 : 0;
        if (settled < SETTLE_TICKS) return ActionStatus.running();
        return done(context);
    }

    // 一步做完：呼梯按完去等，进了轿厢去选层，走出来了去站稳。
    private ActionStatus afterStep(PlayerContext context, ClientLevel level, LocalPlayer player,
            CreateElevators.Elevator elevator, long now) {
        return switch (step) {
            case CALL -> {
                // 按成了一次：记下，去等轿厢。
                if (trying != null) triedCalls.put(trying.key(), PRESSED);
                presses++;
                lastMoveTick = now;
                yield go(Step.WAIT);
            }
            case BOARD -> elevator != null && (elevator.carriesPlayer() || inside(player, elevator)) ? go(Step.SELECT)
                    : fail(context, Problem.of(Problem.Kind.UNREACHABLE, "走到轿厢里那一格了，但没站上轿厢的地板"));
            case EXIT -> go(Step.SETTLE);
            default -> ActionStatus.running();
        };
    }

    // 一步失败：出口被挡换下一个出口；这种呼梯办法没用成（按钮走不到、遥控器拿错）记下换下一种；别的照它的问题结束。
    private ActionStatus afterFailedStep(PlayerContext context, ClientLevel level, Problem problem) {
        if (step == Step.EXIT) {
            exitIndex++;
            return ActionStatus.running();
        }
        if (step == Step.CALL && trying != null) {
            triedCalls.put(trying.key(), problem.headline());
            trying = null;
            return ActionStatus.running();
        }
        return fail(context, problem);
    }

    // 下一种呼梯办法：没试过的先试（近的在前）；都试过了，按成过的那个再按一次，按成的次数到顶就没有了。
    private Optional<ElevatorCalls.Call> nextCall(List<ElevatorCalls.Call> calls) {
        for (ElevatorCalls.Call call : calls) {
            if (!triedCalls.containsKey(call.key())) return Optional.of(call);
        }
        if (presses >= MAX_PRESSES) return Optional.empty();
        return calls.stream().filter(call -> PRESSED.equals(triedCalls.get(call.key()))).findFirst();
    }

    // 试过的呼梯办法一句话一个：怎么叫的、怎么样了。
    private String triedText() {
        List<String> lines = new ArrayList<>();
        triedCalls.forEach((key, outcome) -> lines.add(callHows.getOrDefault(key, key) + "：" + outcome));
        return String.join("；", lines);
    }

    // 轿厢在动没有：轿厢底挪了一点就算动了（呼梯、坐着都按它判断有没有进展）。
    private void watchMotion(CreateElevators.Elevator elevator, long now) {
        double y = elevator.cabin().minY;
        if (Double.isNaN(lastCabinY) || Math.abs(y - lastCabinY) > 0.01) lastMoveTick = now;
        lastCabinY = y;
        if (lastMoveTick == Long.MIN_VALUE) lastMoveTick = now;
    }

    private void names(CreateElevators.Elevator elevator) {
        for (CreateElevators.Floor floor : elevator.floors()) {
            if (floor.contactY() == targetContactY) toName = ElevatorFloors.describe(floor);
        }
        if (fromName.isEmpty() && step.ordinal() <= Step.WAIT.ordinal() && elevator.arrived()) {
            elevator.floors().stream().filter(floor -> floor.contactY() == elevator.currentContactY()).findFirst()
                    .ifPresent(floor -> fromName = ElevatorFloors.describe(floor));
        }
    }

    /** 轿厢里有地板、上面两格空着的格，挑离轿厢中心最近的（站里面一点，不站门口），一样近挑离角色远的。 */
    static Optional<BlockPos> innerSpot(CreateElevators.Elevator elevator, Vec3 from) {
        Vec3 center = elevator.cabin().getCenter();
        return elevator.standCells().stream().min(Comparator
                .comparingDouble((BlockPos cell) -> horizontal(Vec3.atBottomCenterOf(cell), center))
                .thenComparingDouble(cell -> -horizontal(Vec3.atBottomCenterOf(cell), from)));
    }

    /** 到的那层轿厢外面能站的固定格：和脚下同高、在轿厢范围外两格以内，按离角色从近到远。 */
    static List<BlockPos> landings(BlockGetter level, AABB cabin, BlockPos feet) {
        List<BlockPos> cells = new ArrayList<>();
        int y = feet.getY();
        for (int x = (int) Math.floor(cabin.minX) - 2; x <= (int) Math.ceil(cabin.maxX) + 1; x++) {
            for (int z = (int) Math.floor(cabin.minZ) - 2; z <= (int) Math.ceil(cabin.maxZ) + 1; z++) {
                BlockPos cell = new BlockPos(x, y, z);
                if (cabin.contains(Vec3.atBottomCenterOf(cell).add(0, 0.5, 0))) continue;
                if (BlockPassability.isDryStandable(level, cell)) cells.add(cell);
            }
        }
        cells.sort(Comparator.comparingDouble(cell -> cell.distSqr(feet)));
        return cells;
    }

    private static boolean inside(LocalPlayer player, CreateElevators.Elevator elevator) {
        return elevator.cabin().inflate(0.1).contains(player.position());
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private ActionStatus go(Step next) {
        step = next;
        if (next == Step.EXIT) exits = null;
        if (next == Step.SETTLE) settled = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus done(PlayerContext context) {
        release(context);
        return ActionStatus.done();
    }

    private ActionStatus fail(PlayerContext context, Problem problem) {
        release(context);
        return ActionStatus.failed(problem);
    }

    // 这一段结束：松开按键，轿厢与电梯井重新当障碍。
    private void release(PlayerContext context) {
        if (current != null) {
            current.close();
            current = null;
        }
        if (context != null && context.input() != null && context.localPlayer() != null) {
            context.input().halt(context.localPlayer());
        }
        restoreCabin();
    }

    // 进轿厢起这台电梯的轿厢与电梯井不挡自己；这一段结束就恢复。
    private void ignoreCabin(String id) {
        if (riding || id == null) return;
        NavigationObstacles.ride(id);
        riding = true;
    }

    private void restoreCabin() {
        if (!riding) return;
        NavigationObstacles.leave(structureId);
        riding = false;
    }

    @Override public void requestSafeStop() {
        // 还没进轿厢就在原地停；进了轿厢等它停稳在某层再出去。
        stopRequested = true;
    }

    @Override public void pause() {
        if (current != null) current.pause();
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) {
            context.input().halt(context.localPlayer());
        }
    }

    @Override public void close() {
        release(services.context().get());
    }

    @Override public Interruptibility interruptibility() {
        // 进了轿厢到出来站稳之间停下不安全：轿厢会带着人走，半截停下会被夹或掉进电梯井。
        if (step.ordinal() >= Step.BOARD.ordinal()) return Interruptibility.UNSAFE_TO_STOP;
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String summary() {
        return "坐电梯" + (fromName.isEmpty() ? "" : "从 " + fromName) + (toName.isEmpty() ? "" : " 到 " + toName);
    }

    @Override public String describe() {
        String doing = switch (step) {
            case CALL -> "呼梯";
            case WAIT -> "等轿厢";
            case BOARD -> "进轿厢";
            case SELECT -> "选层";
            case RIDE -> "坐着";
            case EXIT -> "出轿厢";
            case SETTLE -> "站稳";
        };
        return "坐电梯去 " + (toName.isEmpty() ? "y=" + targetContactY : toName) + "：" + doing
                + (current == null ? "" : "，" + current.describe());
    }
}
