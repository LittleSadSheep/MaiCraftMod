// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 用船接住一掉：把船拿到手上 → 够得着时把船放到落点（服务器那边真出现了这条船才算）→ 对准船坐上去
 * （真坐在这条船里才算）→ 船停稳、摔落清零 → 找一边能站住的地方按住潜行下船 → 站稳没掉血 →
 * 自己放的船打碎捡回来。坐的是现成的空船就不收，留在原地。
 *
 * <p>站在出发点就够得着落点时，放好船直接坐进去，人不用掉下去；够不着时边掉边放、边掉边坐。
 * 还没坐上船就落地了如实说，伤害由游戏结算。
 */
public final class BoatRescue implements CatchesFall {

    private enum Phase { ITEM, PLACE, MOUNT, RIDING, DISMOUNT, RECOVER, DONE }

    /** 放船、上船等服务器确认的期限。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 30;
    /** 按住潜行这么久还没下船就放弃。 */
    private static final long DISMOUNT_LIMIT_TICKS = 40;

    private BoatPlan plan;
    private final LandingItemInHand item;
    private PlayerContext current;
    private Phase phase;
    private UUID boatId;
    private PendingInteraction pending;
    private BoatRecovery recovery;
    private boolean failed, complete, owned, ready, mounted, airborneObserved, mountSubmitted;
    private String detail = "正在准备用船接住自己";
    private Vec3 aim;
    private float entryHealth = Float.NaN, entryAbsorption = Float.NaN;
    private long lastTick = Long.MIN_VALUE, boardedTick = Long.MIN_VALUE, stableTick = Long.MIN_VALUE;
    private long dismountStarted = Long.MIN_VALUE;
    private int stableTicks;
    private Set<UUID> boatsBefore = Set.of();
    private int itemsBefore;

    private BoatRescue(BoatPlan plan, boolean airborne) {
        this.plan = plan;
        this.boatId = plan.existingBoat();
        this.aim = plan.spawn().add(0, 0.3, 0);
        this.item = plan.item() == null ? null : new LandingItemInHand(plan.item(), () -> current);
        this.phase = boatId == null ? Phase.ITEM : Phase.MOUNT;
        this.airborneObserved = airborne;
    }

    /** 走路里有计划的一跳：出发点 {@link #prepare} 备好（够得着时直接坐进下面的船）。 */
    public static BoatRescue planned(BoatPlan plan) {
        return new BoatRescue(plan, false);
    }

    /** 已经在掉。 */
    public static BoatRescue airborne(BoatPlan plan) {
        return new BoatRescue(plan, true);
    }

    @Override
    public String itemId() {
        return plan.item() == null ? "已有的船" : BuiltInRegistries.ITEM.getKey(plan.item()).toString();
    }

    @Override
    public BlockPos landingFeet() {
        return plan.landing();
    }

    @Override
    public boolean complete() {
        return complete;
    }

    @Override
    public boolean failed() {
        return failed;
    }

    @Override
    public String detail() {
        return detail;
    }

    /** 船不是方块：改动的格子永远为空，船留没留在原地写在经过里。 */
    @Override
    public List<Change> drainChanges() {
        return List.of();
    }

    /**
     * 出发点备料：边掉边放的只要船拿到手上；站着够得着落点的，放船、坐进去、船停稳才算好。
     */
    @Override
    public boolean prepare(PlayerContext context) {
        tick(context);
        return !failed && (plan.airborne() ? phase.ordinal() >= Phase.PLACE.ordinal() : ready);
    }

    @Override
    public void tick(PlayerContext context) {
        if (complete || lastTick == context.clientTick()) return;
        lastTick = context.clientTick();
        current = context;
        LocalPlayer player = context.localPlayer();
        if (Float.isNaN(entryHealth)) {
            entryHealth = player.getHealth();
            entryAbsorption = player.getAbsorptionAmount();
        }
        airborneObserved |= !player.onGround() && !player.isPassenger();
        if (context.input().humanTookOver()) {
            fail("落地途中人接回了角色，用船接住停下");
            complete = true;
            return;
        }
        if (failed) {
            // 没接住：落地或停在船里之后才收场，空中还让转向继续往能活的地方推。
            if (player.onGround() || player.isPassenger()) complete = true;
            return;
        }
        if (airborneObserved && player.onGround() && !mounted && !mountSubmitted) {
            if (item != null) item.abandon(context, "还没坐上船就落地了");
            fail("还没坐上船就落地了，伤害由游戏结算");
            return;
        }
        if (pending != null && !settle(context)) return;
        switch (phase) {
            case ITEM -> takeItem(context, player);
            case PLACE -> place(context, player);
            case MOUNT -> mount(context, player);
            case RIDING -> ride(context, player);
            case DISMOUNT -> dismount(context, player);
            case RECOVER -> recover(context, player);
            case DONE -> complete = true;
        }
    }

    @Override
    public boolean touchedDown(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        return mounted || airborneObserved && player.onGround();
    }

    @Override
    public PlayerInput.Movement steering(PlayerContext context, float yaw) {
        LocalPlayer player = context.localPlayer();
        return switch (phase) {
            case DISMOUNT -> new PlayerInput.Movement(0, 0, false, true, false);
            case RECOVER -> recovery == null ? PlayerInput.Movement.STOPPED : recovery.movement();
            case RIDING, DONE -> PlayerInput.Movement.STOPPED;
            default -> player.onGround() || mountSubmitted ? PlayerInput.Movement.STOPPED
                    : AirSteering.movement(player, plan.landing(), yaw, false);
        };
    }

    @Override
    public void stop(PlayerContext context, String reason) {
        if (complete) return;
        current = context;
        fail(reason);
        if (item != null) item.abandon(context, reason);
        if (recovery != null) recovery.cancel(context, reason);
        if (pending != null && !pending.terminal()) {
            context.interactionSender().retireOneShotForTaskBoundary(context, pending, reason);
        }
        pending = null;
        // 已经坐在船里：按一下潜行下来，不把人留在船上交给下一件事。
        if (context.localPlayer().isPassenger()) {
            context.input().applyMovement(new PlayerInput.Movement(0, 0, false, true, false), context.clientTick());
        }
        complete = true;
    }

    // 船拿到手上：站在出发点不急；空中只剩几刻时背包里的来不及挪。
    private void takeItem(PlayerContext context, LocalPlayer player) {
        int remaining = player.onGround() && !airborneObserved ? Integer.MAX_VALUE : remainingActionTicks(player);
        if (!item.tick(context, remaining)) {
            if (item.failed()) fail(item.detail());
            return;
        }
        phase = Phase.PLACE;
    }

    // 放船：对准落点那一面（空中低头），准星正落在落点顶面、放下去不卡、下得了船，才用船；
    // 以附近恰好新出现一条普通船为准，背包里那种船少了一件才算自己放的（之后才收它）。
    private void place(PlayerContext context, LocalPlayer player) {
        aim = plan.spawn();
        if (player.onGround()) look(context, player, aim, false);
        else lookDown(context, player);
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(plan.spawn()) > player.blockInteractionRange()) return;
        if (!BoatSpace.placeable(context.level(), context.level()::isLoaded, eye, plan.spawn())) {
            fail("放船的那一面被挡住了或放不下了");
            return;
        }
        var hit = context.level().clip(new ClipContext(eye, eye.add(player.getViewVector(1)
                .scale(player.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        BlockPos floor = plan.landing().below();
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP || !hit.getBlockPos().equals(floor)
                || Math.abs(hit.getLocation().y - plan.spawn().y) > .01
                || hit.getLocation().x < plan.landing().getX() || hit.getLocation().x > plan.landing().getX() + 1
                || hit.getLocation().z < plan.landing().getZ() || hit.getLocation().z > plan.landing().getZ() + 1) return;
        if (!BoatSpace.clear(context.level(), context.level()::isLoaded, BoatSpace.boatBox(hit.getLocation()))
                || !BoatSpace.hasExit(context.level(), context.level()::isLoaded, hit.getLocation(), player.getBbWidth(),
                player.getBbHeight(), player.getYRot())) return;
        plan = plan.at(hit.getLocation());
        if (!context.level().noCollision(null, BoatSpace.boatBox(plan.spawn())) || !context.canInteractThisTick()) return;
        boatsBefore = new HashSet<>();
        for (Boat boat : context.level().getEntitiesOfClass(Boat.class, BoatSpace.boatBox(plan.spawn()).inflate(2))) {
            boatsBefore.add(boat.getUUID());
        }
        itemsBefore = count(player);
        detail = "船放出去了，等服务器那边出现这条船";
        pending = context.interactionSender().useItem(context, item.hand(),
                InteractionConfirmation.serverObservedEntity(this::observePlacement), CONFIRM_TIMEOUT_TICKS);
    }

    // 落点附近恰好新出现一条普通船就是它；同时冒出两条说不清是哪条，按对不上收场。
    private InteractionConfirmation.Verdict observePlacement(PlayerContext context) {
        List<UUID> fresh = new ArrayList<>();
        for (Boat boat : context.level().getEntitiesOfClass(Boat.class, BoatSpace.boatBox(plan.spawn()).inflate(0.5))) {
            if (boat.getType() == EntityType.BOAT && !boatsBefore.contains(boat.getUUID())) fresh.add(boat.getUUID());
        }
        if (fresh.size() != 1) {
            return fresh.size() > 1 ? InteractionConfirmation.Verdict.DIVERGED : InteractionConfirmation.Verdict.PENDING;
        }
        boatId = fresh.getFirst();
        LocalPlayer player = context.localPlayer();
        owned |= player.getAbilities().instabuild || count(player) < itemsBefore;
        return InteractionConfirmation.Verdict.APPLIED;
    }

    // 上船：船还在、停稳、没人坐；伸手够得着、准星正对着它、没按着潜行，才右键它；以真坐在这条船里为准。
    private void mount(PlayerContext context, LocalPlayer player) {
        Boat boat = find(context);
        if (boat == null) {
            fail("要坐的那条船不见了");
            return;
        }
        aim = boat.getBoundingBox().getCenter();
        if (!supported(context, boat) || !boat.getPassengers().isEmpty()) {
            fail("那条船在动、在掉或者有人坐了");
            return;
        }
        if (!stable(context, player, boat)) return;
        Vec3 eye = player.getEyePosition();
        double reach = player.entityInteractionRange();
        boolean outOfReach = boat.getBoundingBox().distanceToSqr(eye) > reach * reach;
        boolean belowAlready = boat.getBoundingBox().clip(eye, eye.add(0, -reach, 0)).isPresent();
        if (plan.airborne() && (outOfReach || belowAlready)) lookDown(context, player);
        else look(context, player, aim, plan.airborne());
        if (!player.canInteractWithEntity(boat, 0) || !BoatSpace.aimedAt(context, boat) || !context.canInteractThisTick()
                || player.isShiftKeyDown()) return;
        mountSubmitted = true;
        detail = "对准船坐上去，等服务器确认";
        UUID target = boatId;
        pending = context.interactionSender().interact(context, boat, item == null ? InteractionHand.MAIN_HAND : item.hand(),
                InteractionConfirmation.serverObservedEntity(live -> target.equals(vehicleId(live.localPlayer()))
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING),
                CONFIRM_TIMEOUT_TICKS);
    }

    // 坐稳：船停住、上船后过了一刻、摔落清零了，才找下船的那一边；四周都下不去就转头找，等一个能站住的方向。
    private void ride(PlayerContext context, LocalPlayer player) {
        Boat boat = find(context);
        if (boat == null || !boatId.equals(vehicleId(player))) {
            fail("没坐在那条船里");
            return;
        }
        if (!stable(context, player, boat) || lastTick <= boardedTick || player.fallDistance > 0.01F) return;
        ready = true;
        Vec3 exit = BoatSpace.exit(context.level(), context.level()::isLoaded, boat.position(), boat.getBbWidth(),
                player.getBbWidth(), player.getBbHeight(), player.getYRot());
        if (exit == null) {
            for (int offset = -90; offset <= 90; offset += 30) {
                float yaw = boat.getYRot() + offset;
                if (BoatSpace.exit(context.level(), context.level()::isLoaded, boat.position(), boat.getBbWidth(),
                        player.getBbWidth(), player.getBbHeight(), yaw) != null) {
                    aim = player.getEyePosition().add(-Math.sin(Math.toRadians(yaw)) * 2, 0, Math.cos(Math.toRadians(yaw)) * 2);
                    look(context, player, aim, false);
                    return;
                }
            }
            detail = "坐稳了，等转到一个能下船站住的方向";
            return;
        }
        phase = Phase.DISMOUNT;
        dismountStarted = context.clientTick();
        detail = "坐稳了，按住潜行下船";
    }

    // 下船：按住潜行（按键由 steering 给）；下来了等站稳，脚下有支撑、没掉血才算接住，自己放的船接着收。
    private void dismount(PlayerContext context, LocalPlayer player) {
        if (player.isPassenger()) {
            if (context.clientTick() - dismountStarted > DISMOUNT_LIMIT_TICKS) fail("按住潜行两秒还没下船");
            return;
        }
        if (!player.onGround() || player.getDeltaMovement().lengthSqr() > 0.01) return;
        if (BoatSpace.support(context.level(), context.level()::isLoaded, player.blockPosition()) == null) {
            fail("下船的地方脚下没有支撑");
            return;
        }
        if (player.getHealth() < entryHealth || player.getAbsorptionAmount() < entryAbsorption) {
            fail("用船接住了，但中途掉了血，不算免伤");
            return;
        }
        if (owned) {
            recovery = new BoatRecovery(boatId, plan.item());
            phase = Phase.RECOVER;
            return;
        }
        phase = Phase.DONE;
        detail = "坐现成的船接住了，没掉血；那条船留在原地";
        complete = true;
    }

    private void recover(PlayerContext context, LocalPlayer player) {
        if (!recovery.tick(context)) {
            if (recovery.aim() != null) look(context, player, recovery.aim(), false);
            return;
        }
        if (!player.onGround() || player.isPassenger()) {
            fail("收船时站不稳了");
            return;
        }
        phase = Phase.DONE;
        detail = "用船接住了，没掉血；" + recovery.detail();
        complete = true;
    }

    // 等放船、上船的确认；没确认就如实失败，不再放第二条、不再右键别的东西。
    private boolean settle(PlayerContext context) {
        pending = context.interactionSender().poll(context, pending);
        if (!pending.terminal()) return false;
        if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            fail((phase == Phase.PLACE ? "放船" : "上船") + "没得到服务器确认：" + pending.detail());
            pending = null;
            return false;
        }
        pending = null;
        if (phase == Phase.PLACE) {
            phase = Phase.MOUNT;
        } else if (phase == Phase.MOUNT) {
            phase = Phase.RIDING;
            mounted = true;
            boardedTick = lastTick;
        }
        return true;
    }

    private Boat find(PlayerContext context) {
        if (boatId == null) return null;
        for (Boat boat : context.level().getEntitiesOfClass(Boat.class, BoatSpace.boatBox(plan.spawn()).inflate(3))) {
            if (boatId.equals(boat.getUUID())) return boat;
        }
        return null;
    }

    // 船稳：有支撑、不在水里、没在掉、几乎不动；人站着时还要连续两刻都稳（空中等不起）。
    private boolean stable(PlayerContext context, LocalPlayer player, Boat boat) {
        if (!supported(context, boat)) {
            stableTicks = 0;
            return false;
        }
        if (stableTick != context.clientTick()) {
            stableTick = context.clientTick();
            stableTicks++;
        }
        return plan.airborne() || !player.onGround() || stableTicks >= 2;
    }

    private static boolean supported(PlayerContext context, Boat boat) {
        return boat.isAlive() && !boat.isInWater() && boat.fallDistance <= .01F
                && boat.getDeltaMovement().lengthSqr() < .0004
                && BoatSpace.supportedAt(context.level(), context.level()::isLoaded, boat.position());
    }

    // 离必须放船还有几刻：脚下离落点那一面还高出够得着的距离时，按重力往下推。
    private int remainingActionTicks(LocalPlayer player) {
        double y = player.getY();
        double velocity = player.getDeltaMovement().y;
        double target = plan.spawn().y + player.blockInteractionRange() - player.getEyeHeight();
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        for (int ticks = 0; ticks < 200; ticks++) {
            if (y <= target) return ticks;
            y += velocity;
            velocity = (velocity - gravity) * .98;
        }
        return 200;
    }

    private int count(LocalPlayer player) {
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(plan.item())) result += player.getInventory().getItem(slot).getCount();
        }
        return result;
    }

    private static UUID vehicleId(LocalPlayer player) {
        return player.getVehicle() == null ? null : player.getVehicle().getUUID();
    }

    // 转头：空中、离得近时立即转过去，平时平滑地转。
    private static void look(PlayerContext context, LocalPlayer player, Vec3 point, boolean urgent) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double flat = Math.hypot(delta.x, delta.z);
        float yaw = flat < .001 ? player.getYRot() : (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, flat));
        if (urgent || !player.onGround() && delta.length() <= player.blockInteractionRange()) {
            context.input().requestImmediateLook(yaw, pitch, context.clientTick());
        } else {
            context.input().requestLook(yaw, pitch, context.clientTick());
        }
    }

    private void lookDown(PlayerContext context, LocalPlayer player) {
        if (player.getEyePosition().y - plan.spawn().y <= player.blockInteractionRange()) {
            context.input().requestImmediateLook(player.getYRot(), 90, context.clientTick());
        } else {
            context.input().requestLook(player.getYRot(), 90, context.clientTick());
        }
    }

    private void fail(String reason) {
        failed = true;
        detail = reason;
    }
}
