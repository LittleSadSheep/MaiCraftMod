// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 坐一段矿车：备车 → 走到起点轨道旁 → 放车（或直接用轨道旁停着的空车）→ 起点是通电的动力铁轨就坐上去等它自己走，
 * 不是就从后面推一下再坐上去 → 坐着每刻看车动没动、到了哪 → 停在半路（动力铁轨没电、撞上停着的车、轨道断了）
 * 就下车记下停在哪 → 到离目的地最近的轨道格潜行下车 → 车留在原地，位置记进世界记忆，交还行程。
 *
 * <p>坐没坐上以人真是那辆车的乘客为准；车到没到以车自己的位置为准。半路停车不算整趟失败：
 * 人好好站在某处，行程从那里接着规划。乘车中停下不安全（车还带着人），先刹稳下车再让。
 */
final class MinecartRide implements TravelLeg {

    /** 乘一段矿车的几步。 */
    private enum Step { CART, WALK, PLACE, PUSH, BOARD, RIDE, ALIGHT, SETTLE }

    /** 放车、上车的确认期限（刻）。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 30;
    /** 对着矿车最多点几次没坐上就算了。 */
    private static final int MAX_CLICKS = 6;
    /** 车被推走这么远（格）还没坐上就放弃，不再追。 */
    private static final double CART_GONE_AWAY = 12.0;
    /** 推车这么久（刻）车一点没动就放弃。 */
    private static final int MAX_PUSH_TICKS = 100;
    /** 车被推出这么多格就算推起来了，去坐。 */
    private static final double PUSHED_ENOUGH = 1.0;
    /** 坐着车几乎不动连续这么多刻，就当到站（或半路停了），下车。 */
    private static final int STATIONARY_TICKS = 30;
    /** 下车最多潜行多少刻。 */
    private static final int MAX_ALIGHT_TICKS = 40;
    /** 落地后脚下这么多刻没动算站稳；最多等这么久。 */
    private static final int STILL_TICKS = 10;
    private static final int MAX_SETTLE_TICKS = 60;
    /** 车的速度低于这个平方值算几乎停着。 */
    private static final double STILL_SPEED_SQR = 0.0004;

    private final BlockPos startCell;
    private final BlockPos endCell;
    private final Permissions permissions;
    private final DimensionTravelServices services;
    private final Facts facts = new Facts();

    private Step step = Step.CART;
    private Action current;
    private WalkRun walk;
    private PendingInteraction pending;
    private UUID cartId;
    private boolean usedParkedCart;
    private int clicks;
    private int pushTicks;
    private Vec3 pushAnchor;
    private int stationary;
    private double bestDistance = Double.MAX_VALUE;
    private int alightTicks;
    private BlockPos lastFeet;
    private int still;
    private int settleTicks;
    private BlockPos cartLeftAt;
    private String stoppedNote = "";
    private boolean stopRequested;
    /** 线路第一段的朝向：推车往这边顶；线路没算出来为 null，推车按整条线路的主轴猜，失败说明里写清没把握。 */
    private final Direction firstMove;

    /** @param startCell 要上车的起点轨道格 @param endCell 离目的地最近的轨道格 @param firstMove 线路第一段的朝向，算不出给 null */
    MinecartRide(BlockPos startCell, BlockPos endCell, Permissions permissions, DimensionTravelServices services,
            Direction firstMove) {
        this.startCell = startCell.immutable();
        this.endCell = endCell.immutable();
        this.permissions = permissions;
        this.services = services;
        this.firstMove = firstMove;
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        LocalPlayer player = context == null ? null : context.localPlayer();
        BlockPos feet = services.world().feet();
        if (context == null || player == null || feet == null) {
            return failed(Problem.Kind.INTERNAL_ERROR, "角色不在世界里，坐不了矿车", null);
        }
        // 被要求停下：乘车中先把车刹稳、下车站稳，别的步骤当场停在原地。
        if (stopRequested && step != Step.RIDE && step != Step.ALIGHT && step != Step.SETTLE) {
            dropCurrent();
            halt(context);
            return failed(Problem.Kind.STUCK, "按要求停下，停在 " + feet.toShortString() + "，还没坐上矿车", null);
        }
        if (current != null) return runCurrent(context, tick);
        return switch (step) {
            case CART -> cart(player);
            case WALK -> walkToCart(context, player);
            case PLACE -> place(context, player);
            case PUSH -> push(context, player);
            case BOARD -> board(context, player);
            case RIDE -> ride(context, player);
            case ALIGHT -> alight(context, player);
            case SETTLE -> settle(context, player, feet);
        };
    }

    // 推进手上那个动作（走到、换手、放车）；做完按步骤接下一步，被停下（让给生存需求）就从原地再来。
    private ActionStatus runCurrent(PlayerContext context, TickContext tick) {
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) {
            if (current == walk && walk.report().state() == WalkReport.State.STOPPED) dropCurrent();
            return status;
        }
        dropCurrent();
        Problem problem = status instanceof ActionStatus.Failed failed ? failed.problem() : null;
        if (problem != null) return failed(problem.kind(), problem.message(), problem.suggestion());
        return switch (step) {
            case CART -> next(Step.WALK);
            case WALK -> next(cartId == null ? Step.PLACE : Step.BOARD);
            case PLACE -> afterPlace();
            default -> ActionStatus.progressed();
        };
    }

    // 备车：已经坐在矿车里（非它不可的那种）直接去坐；先看轨道旁停着的空车——
    // 世界记忆里记过上次留在哪的也查，到现场核实还在、没人坐就用它，不用消耗身上的；
    // 没有停着的才把身上的换到主手准备放；两样都没有如实失败，不现合成。
    private ActionStatus cart(LocalPlayer player) {
        if (ridingCart(player) != null) return next(Step.RIDE);
        Optional<AbstractMinecart> parked = MinecartMode.usableParkedCart(services.context().get(), startCell,
                services.memory());
        if (parked.isPresent()) {
            cartId = parked.get().getUUID();
            usedParkedCart = true;
            return next(Step.WALK);
        }
        if (carryingCart()) {
            Optional<Action> move = services.toMainhand().moveToMainhand(MinecartMode.CART_ITEM);
            if (move.isEmpty()) return next(Step.WALK);
            current = move.get();
            return ActionStatus.progressed();
        }
        return failed(Problem.Kind.NEED_ITEM, "报价时身上有矿车或轨道旁停着一辆，现在两样都没有了",
                "先弄到一辆矿车，或把车放回轨道上");
    }

    // 走到车旁：已经够得着车（或够得着要放车的起点轨道）就不走，否则走过去。
    private ActionStatus walkToCart(PlayerContext context, LocalPlayer player) {
        AbstractMinecart cart = findCart(context);
        if (cart == null && cartId != null) {
            return failed(Problem.Kind.TARGET_GONE, "要坐的那辆矿车不见了", null);
        }
        BlockPos target = cart != null ? cart.blockPosition() : startCell;
        boolean closeEnough = cart != null
                ? player.getEyePosition().distanceTo(cart.getBoundingBox().getCenter()) <= player.entityInteractionRange()
                : player.blockPosition().distSqr(startCell) <= 3 * 3;
        if (closeEnough) return next(cart != null ? Step.BOARD : Step.PLACE);
        dropCurrent();
        walk = services.walks().start(GoalCompiler.near(target, 1.5), TerrainPermit.of(permissions));
        current = walk;
        return ActionStatus.progressed();
    }

    // 放车：对着起点轨道格右键，以轨道旁恰好新出现一辆普通矿车为准（同时冒出两辆说不清是哪辆，按没放上算）。
    private ActionStatus place(PlayerContext context, LocalPlayer player) {
        if (cartId != null) return next(Step.PUSH);
        Set<UUID> before = new HashSet<>();
        for (AbstractMinecart cart : nearbyCarts(context, startCell, 2.0)) before.add(cart.getUUID());
        int itemsBefore = carriedCount();
        BlockPos rail = startCell.immutable();
        current = services.uses().useBlock(rail, ctx -> {
            List<AbstractMinecart> fresh = nearbyCarts(ctx, rail, 1.5).stream()
                    .filter(newCart -> !before.contains(newCart.getUUID())).toList();
            if (fresh.size() > 1) return InteractionConfirmation.Verdict.DIVERGED;
            if (fresh.isEmpty()) return InteractionConfirmation.Verdict.PENDING;
            cartId = fresh.getFirst().getUUID();
            // 背包里那种车少了一辆才算自己放的：放置成功记一笔消耗。
            if (carriedCount() < itemsBefore) {
                facts.change(Change.of(Change.Kind.ITEM_CONSUMED,
                        MinecartMode.CART_ITEM + "（放到 " + rail.toShortString() + " 的轨道上）", 1));
            }
            return InteractionConfirmation.Verdict.APPLIED;
        });
        if (current == null) {
            return failed(Problem.Kind.INTERNAL_ERROR, "右键一格方块的接缝没接上，放不了矿车", null);
        }
        return ActionStatus.progressed();
    }

    private ActionStatus afterPlace() {
        if (cartId == null) {
            return failed(Problem.Kind.REFUSED_BY_GAME, "放了矿车，但起点轨道旁没看到新车", "看看轨道上是不是已经有车挡着");
        }
        return next(Step.PUSH);
    }

    // 起点是通电的动力铁轨就直接去坐；不是就从后面推一下：绕到车尾朝线路方向顶，车动了一格就去坐。
    private ActionStatus push(PlayerContext context, LocalPlayer player) {
        AbstractMinecart cart = findCart(context);
        if (cart == null) return failed(Problem.Kind.TARGET_GONE, "要坐的那辆矿车不见了", null);
        if (services.world().stateAt(startCell).map(RailLine::powered).orElse(false)) return next(Step.BOARD);
        Vec3 direction = pushDirection();
        if (pushAnchor == null) pushAnchor = cart.position();
        if (cart.position().distanceTo(pushAnchor) >= PUSHED_ENOUGH) {
            halt(context);
            return next(Step.BOARD);
        }
        // 站到车尾（线路方向的反侧）再顶：从侧面或车头推只会把车推反。
        BlockPos behind = cart.blockPosition().relative(opposite(direction));
        if (player.blockPosition().distSqr(behind) > 2 * 2) {
            dropCurrent();
            walk = services.walks().start(GoalCompiler.near(behind, 1.0), TerrainPermit.of(permissions));
            current = walk;
            return ActionStatus.progressed();
        }
        dropCurrent();
        if (++pushTicks > MAX_PUSH_TICKS) {
            return failed(Problem.Kind.STUCK, firstMove == null
                    ? "推了 " + MAX_PUSH_TICKS / 20 + " 秒车都没动起来（线路方向没算出来，可能推反了；前面也可能被挡住）"
                    : "推了 " + MAX_PUSH_TICKS / 20 + " 秒车都没动起来（前面被挡住了？）", null);
        }
        lookAt(context, player, cart.getBoundingBox().getCenter().add(direction.scale(2)));
        context.input().applyMovement(new PlayerInput.Movement(1.0f, 0.0f, false, false, false),
                context.clientTick());
        return ActionStatus.running();
    }

    // 坐上去：够得着就对准车右键，以人真是这辆车的乘客为准；车自己溜远了（推过头）如实放弃。
    private ActionStatus board(PlayerContext context, LocalPlayer player) {
        AbstractMinecart cart = findCart(context);
        if (cart == null) return failed(Problem.Kind.TARGET_GONE, "要坐的那辆矿车不见了", null);
        if (ridingCart(player) == cart) return next(Step.RIDE);
        if (cart.position().distanceTo(player.position()) > CART_GONE_AWAY) {
            return failed(Problem.Kind.STUCK, "车推走了没坐上（车在 " + cart.blockPosition().toShortString() + "）",
                    "追上车再坐，或重新放一辆");
        }
        double reach = player.entityInteractionRange();
        if (cart.getBoundingBox().distanceToSqr(player.getEyePosition()) > reach * reach) {
            dropCurrent();
            walk = services.walks().start(GoalCompiler.near(cart.blockPosition(), 1.5),
                    TerrainPermit.of(permissions));
            current = walk;
            return ActionStatus.progressed();
        }
        dropCurrent();
        lookAt(context, player, cart.getBoundingBox().getCenter());
        if (pending == null) {
            if (++clicks > MAX_CLICKS) {
                return failed(Problem.Kind.STUCK, "对着矿车点了 " + MAX_CLICKS + " 次都没坐上", null);
            }
            pending = context.interactionSender().interact(context, cart, InteractionHand.MAIN_HAND,
                    InteractionConfirmation.ridingOn(cart), CONFIRM_TIMEOUT_TICKS);
        }
        pending = context.interactionSender().poll(context, pending);
        if (pending.terminal()) {
            boolean applied = pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED;
            pending = null;
            if (!applied) return ActionStatus.progressed();
        }
        return ridingCart(player) != null ? next(Step.RIDE) : ActionStatus.running();
    }

    // 坐着：每刻看车到了哪，离目的地最近的那格记下来；车几乎不动半秒就是到站（或半路停了），去下车。
    private ActionStatus ride(PlayerContext context, LocalPlayer player) {
        halt(context);
        AbstractMinecart cart = ridingCart(player);
        if (cart == null) {
            return failed(Problem.Kind.STUCK, stoppedNote.isEmpty()
                    ? "被弄下了车，停在 " + player.blockPosition().toShortString() : stoppedNote, null);
        }
        double distance = Math.sqrt(cart.blockPosition().distSqr(endCell));
        if (distance < bestDistance - 0.5) {
            bestDistance = distance;
            stationary = 0;
        }
        if (cart.blockPosition().distSqr(endCell) <= 1.0) {
            cartLeftAt = cart.blockPosition().immutable();
            return next(Step.ALIGHT);
        }
        if (cart.getDeltaMovement().lengthSqr() < STILL_SPEED_SQR) {
            stationary++;
        } else {
            stationary = 0;
        }
        if (stationary >= STATIONARY_TICKS) {
            cartLeftAt = cart.blockPosition().immutable();
            stoppedNote = "车在 " + cart.blockPosition().toShortString() + " 停住没再走"
                    + "（这段动力铁轨没电、前面被挡住或轨道断了）";
            return next(Step.ALIGHT);
        }
        return ActionStatus.running();
    }

    // 下车：按住潜行，直到不坐在车上。
    private ActionStatus alight(PlayerContext context, LocalPlayer player) {
        if (!player.isPassenger()) {
            halt(context);
            return next(Step.SETTLE);
        }
        if (++alightTicks > MAX_ALIGHT_TICKS) {
            return failed(Problem.Kind.STUCK, "按住潜行 " + MAX_ALIGHT_TICKS / 20 + " 秒还没下车", null);
        }
        context.input().applyMovement(new PlayerInput.Movement(0.0f, 0.0f, false, true, false),
                context.clientTick());
        return ActionStatus.running();
    }

    // 站稳收尾：脚下半秒没动算站稳；车留下的位置记进世界记忆，交还行程从这儿接着走。
    private ActionStatus settle(PlayerContext context, LocalPlayer player, BlockPos feet) {
        settleTicks++;
        still = feet.equals(lastFeet) ? still + 1 : 0;
        lastFeet = feet;
        if (still < STILL_TICKS && settleTicks < MAX_SETTLE_TICKS) return ActionStatus.running();
        if (cartLeftAt == null) cartLeftAt = feet.immutable();
        String dimension = services.world().currentDimension();
        if (dimension != null) {
            services.memory().remember(MinecartMode.REMEMBERED_CART,
                    new WorldPosition(cartLeftAt.getX(), cartLeftAt.getY(), cartLeftAt.getZ(), dimension));
        }
        facts.change(Change.of(Change.Kind.ENTITY_AFFECTED,
                "矿车留在 " + cartLeftAt.toShortString() + "，没有收回", 1));
        if (stopRequested) {
            return failed(Problem.Kind.STUCK, "按要求停下，人站在 " + feet.toShortString() + "，车在 "
                    + cartLeftAt.toShortString(), null);
        }
        return ActionStatus.done();
    }

    private ActionStatus next(Step value) {
        step = value;
        return ActionStatus.progressed();
    }

    private void dropCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
        walk = null;
    }

    private void halt(PlayerContext context) {
        if (context.input() != null) context.input().halt(context.localPlayer());
    }

    /** 认车：按编号在起点附近找。 */
    private AbstractMinecart findCart(PlayerContext context) {
        if (cartId == null) return null;
        for (AbstractMinecart cart : nearbyCarts(context, startCell, MinecartMode.PARKED_CART_RANGE * 2)) {
            if (cartId.equals(cart.getUUID())) return cart;
        }
        return null;
    }

    private static List<AbstractMinecart> nearbyCarts(PlayerContext context, BlockPos cell, double range) {
        if (context == null || context.level() == null) return List.of();
        return context.level().getEntitiesOfClass(AbstractMinecart.class, new AABB(cell).inflate(range));
    }

    /** 人此刻坐着的普通矿车；没坐在矿车里给空。 */
    private static AbstractMinecart ridingCart(LocalPlayer player) {
        return player.getVehicle() instanceof AbstractMinecart cart && cart.getType() == EntityType.MINECART
                ? cart : null;
    }

    private boolean carryingCart() {
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (MinecartMode.CART_ITEM.equals(stack.itemId()) && stack.count() > 0) return true;
        }
        return false;
    }

    private int carriedCount() {
        int count = 0;
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (MinecartMode.CART_ITEM.equals(stack.itemId())) count += stack.count();
        }
        return count;
    }

    // 推车方向：线路第一段的朝向（起点段就拐弯的线路按首段推，按主轴猜会推不动）；
    // 线路没算出来才退回整条线路的主轴方向。
    private Vec3 pushDirection() {
        if (firstMove != null) return new Vec3(firstMove.getStepX(), 0, firstMove.getStepZ());
        double dx = endCell.getX() - startCell.getX();
        double dz = endCell.getZ() - startCell.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return new Vec3(dx >= 0 ? 1 : -1, 0, 0);
        return new Vec3(0, 0, dz >= 0 ? 1 : -1);
    }

    private static Direction opposite(Vec3 direction) {
        if (direction.x != 0) return direction.x > 0 ? Direction.WEST : Direction.EAST;
        return direction.z > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    // 转头看一点：让视线对准要右键的车或要顶的方向。
    private static void lookAt(PlayerContext context, LocalPlayer player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double flat = Math.hypot(delta.x, delta.z);
        float yaw = flat < 0.001 ? player.getYRot() : (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, flat));
        context.input().requestLook(yaw, pitch, context.clientTick());
    }

    private static ActionStatus failed(Problem.Kind kind, String message, String suggestion) {
        return ActionStatus.failed(Problem.of(kind, message, suggestion));
    }

    // 乘车中、下车中停下不安全：车还带着人；别的步骤看手上那个动作自己的。
    @Override public Interruptibility interruptibility() {
        if (step == Step.RIDE || step == Step.ALIGHT) return Interruptibility.UNSAFE_TO_STOP;
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    // 要求先到安全处再停：坐着的刹稳下车、站稳，再结束这一段。
    @Override public void requestSafeStop() {
        stopRequested = true;
    }

    // 让给生存需求：手上的动作收掉、松开按键；回来从这一步重新开始（车停着等，人挪了按现场再看）。
    @Override public void pause() {
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) {
            context.input().halt(context.localPlayer());
        }
        dropCurrent();
    }

    @Override public void close() {
        pause();
        pending = null;
    }

    @Override public List<Change> changes() {
        return List.copyOf(facts.changes);
    }

    @Override public String summary() {
        StringBuilder text = new StringBuilder(usedParkedCart ? "坐轨道上停着的矿车" : "放了一辆矿车坐上");
        text.append("，从 ").append(startCell.toShortString()).append(" 到 ").append(endCell.toShortString());
        if (cartLeftAt != null) {
            text.append(stoppedNote.isEmpty() ? "，车留在 " : "；" + stoppedNote + "，车留在 ")
                    .append(cartLeftAt.toShortString());
        }
        return text.toString();
    }

    @Override public String describe() {
        return switch (step) {
            case CART -> "备矿车";
            case WALK -> "走到起点轨道 " + startCell.toShortString() + " 旁";
            case PLACE -> "对着轨道放矿车";
            case PUSH -> "从车尾把矿车推起来";
            case BOARD -> "对准矿车坐上去";
            case RIDE -> "坐着矿车沿轨道走";
            case ALIGHT -> "潜行下车";
            case SETTLE -> "下车站稳";
        };
    }

    /** 这一趟记下的变化（放掉的车、车留在哪）；备车没有多条路好试，失败原因随问题交回。 */
    private static final class Facts implements TaskRecords {
        private final List<Change> changes = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change);
        }

        @Override public void unconfirmed(Change change) {
            changes.add(change);
        }

        @Override public void attempt(String tried, String whatHappened) {
            // 没有可记的尝试过程。
        }
    }
}
