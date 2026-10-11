// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 下船站稳后收回自己放的船：先看站得稳、背包装得下、船周围一圈都是走得到的干地面（船碎了掉落物会散开），
 * 再一下一下打船，打碎了走过去捡起掉的船。背包里那种船多了一件才算收回；条件不满足、掉落物不止一个、
 * 或五秒内没收回，就把结果如实说（船留着，或船碎了东西还在地上），不把"船不见了"当成拿回来了。
 */
final class BoatRecovery {

    /** 收船最多花这么久。 */
    private static final long LIMIT_TICKS = 100;
    /** 攻击冷却恢复到这么多才出手：打满力才算一下。 */
    private static final float FULL_SWING = 0.9F;

    private final UUID boatId;
    private final Item item;
    private final Set<UUID> dropsBefore = new HashSet<>();
    private PendingInteraction pending;
    private long started = -1;
    private int expected;
    private boolean done;
    private boolean recovered;
    private boolean boatLeft = true;
    private String detail = "看收不收得了船";
    private Vec3 aim;
    private PlayerInput.Movement movement = PlayerInput.Movement.STOPPED;

    BoatRecovery(UUID boatId, Item item) {
        this.boatId = boatId;
        this.item = item;
    }

    /** 推进一刻，收完（不论收没收回）返回 true。 */
    boolean tick(PlayerContext context) {
        movement = PlayerInput.Movement.STOPPED;
        if (done) return true;
        LocalPlayer player = context.localPlayer();
        Boat boat = boat(context);
        if (started < 0) {
            if (boat == null || !eligible(context, boat)) return finish("船留在原地：站不稳、背包装不下或船周围不好捡", false, boat != null);
            started = context.clientTick();
            expected = count(player) + 1;
            for (ItemEntity drop : context.level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(3))) {
                dropsBefore.add(drop.getUUID());
            }
        }
        if (pending != null) {
            pending = context.interactionSender().poll(context, pending);
            if (!pending.terminal()) return false;
            if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
                return finish("打船这一下没得到游戏确认，船" + (boat == null ? "没了，东西还没捡到" : "还在"), false, boat != null);
            }
            pending = null;
        }
        if (boat == null && count(player) >= expected) return finish("船收回来了", true, false);
        if (context.clientTick() - started > LIMIT_TICKS) {
            return finish(boat == null ? "船打碎了，但没确认捡回来" : "收船超时，船留在原地", false, boat != null);
        }
        if (!player.onGround() || player.isPassenger()) return finish("收船时站不稳了，停下", false, boat != null);
        return boat != null ? strike(context, player, boat) : pickUp(context, player);
    }

    // 打船：船还是那条、没人坐、停稳，准星正对着它、够得着、冷却满了才出手；以船受伤或消失为准。
    private boolean strike(PlayerContext context, LocalPlayer player, Boat boat) {
        if (!eligible(context, boat)) return finish("收船的条件变了，船留在原地", false, true);
        aim = boat.getBoundingBox().getCenter();
        if (!player.canInteractWithEntity(boat, 0) || !BoatSpace.aimedAt(context, boat)
                || !BoatSpace.visible(context.level(), context.level()::isLoaded, player.getEyePosition(), aim)
                || !context.canInteractThisTick() || player.getAttackStrengthScale(0) < FULL_SWING) return false;
        float damage = boat.getDamage();
        InteractionConfirmation hurt = live -> {
            Boat target = boat(live);
            return target == null || target.getDamage() > damage
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        };
        pending = context.interactionSender().attack(context, boat, hurt, 20);
        return false;
    }

    // 船碎了：找新掉出来的那一件船（只认一件），沿走得到的干地面慢慢走过去，靠原版拾取吸进包。
    private boolean pickUp(PlayerContext context, LocalPlayer player) {
        boatLeft = false;
        ItemEntity found = null;
        for (ItemEntity drop : context.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3))) {
            if (dropsBefore.contains(drop.getUUID()) || !drop.getItem().is(item)) continue;
            if (found != null) return finish("船打碎了，但地上有不止一件船，没去捡", false, false);
            found = drop;
        }
        if (found == null) return false;
        Vec3 destination = new Vec3(found.getX(), player.getY(), found.getZ());
        if (!walkable(context, destination)) return finish("船打碎了，掉的船落在走不过去的地方，留在地上", false, false);
        aim = found.position();
        Vec3 delta = destination.subtract(player.position());
        if (delta.horizontalDistance() > 0.35) {
            double bearing = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
            if (Math.abs(Mth.wrapDegrees((float) bearing - player.getYRot())) < 25) {
                movement = new PlayerInput.Movement(0.35F, 0, false, false, false);
            }
        }
        return false;
    }

    // 能收：不是创造模式（创造模式打碎船不掉东西）、船还是那条、停稳没人坐、离得近、背包装得下、船周围一圈都走得到。
    private boolean eligible(PlayerContext context, Boat boat) {
        LocalPlayer player = context.localPlayer();
        if (player.isCreative()) return false;
        if (!boatId.equals(boat.getUUID()) || !boat.getPassengers().isEmpty() || !BoatOptions.stationary(boat)
                || !player.onGround() || player.isPassenger() || player.distanceToSqr(boat) > 4
                || !room(player, new ItemStack(item))) return false;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!walkable(context, new Vec3(boat.getX() + x, boat.getY(), boat.getZ() + z))) return false;
            }
        }
        return true;
    }

    // 从人到这一点沿直线每五分之一格查一次：脚下有同高的支撑、身体放得下。
    private static boolean walkable(PlayerContext context, Vec3 to) {
        LocalPlayer player = context.localPlayer();
        Vec3 from = player.position();
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 5));
        if (steps > 30) return false;
        for (int i = 0; i <= steps; i++) {
            Vec3 at = from.lerp(to, (double) i / steps);
            BlockPos feet = BlockPos.containing(at.x, Math.ceil(at.y), at.z);
            Vec3 support = BoatSpace.support(context.level(), context.level()::isLoaded, feet);
            if (support == null || Math.abs(support.y - at.y) > 0.1) return false;
            double half = player.getBbWidth() / 2;
            if (!BoatSpace.clear(context.level(), context.level()::isLoaded, new AABB(at.x - half, at.y + 0.001,
                    at.z - half, at.x + half, at.y + player.getBbHeight(), at.z + half))) return false;
        }
        return true;
    }

    /** 被打断：没确认的那一下交还等待，按此刻的样子收场。 */
    void cancel(PlayerContext context, String reason) {
        if (pending != null && !pending.terminal()) {
            context.interactionSender().retireOneShotForTaskBoundary(context, pending, reason);
        }
        pending = null;
        boolean present = boat(context) != null;
        finish(reason, !present && expected > 0 && count(context.localPlayer()) >= expected, present);
    }

    private static boolean room(LocalPlayer player, ItemStack drop) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, drop)
                    && stack.getCount() + drop.getCount() <= stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private int count(LocalPlayer player) {
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private Boat boat(PlayerContext context) {
        for (Entity entity : context.level().entitiesForRendering()) {
            if (boatId.equals(entity.getUUID()) && entity instanceof Boat found) return found;
        }
        return null;
    }

    private boolean finish(String reason, boolean recovered, boolean left) {
        detail = reason;
        this.recovered = recovered;
        boatLeft = left;
        done = true;
        return true;
    }

    String detail() {
        return detail;
    }

    boolean recovered() {
        return recovered;
    }

    boolean boatLeft() {
        return boatLeft;
    }

    Vec3 aim() {
        return aim;
    }

    PlayerInput.Movement movement() {
        return movement;
    }
}
