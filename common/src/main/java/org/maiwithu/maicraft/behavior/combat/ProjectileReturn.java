// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把飞过来的火球打回去：原版"能打回去的投射物"（恶魂火球、风弹，模组加进同一标签的也算）挨一下，
 * 就顺着出手者的视线飞回去。像玩家那样盯着火球，它飞进够得着的地方、准星压在它身上的那一刻挥一下——
 * 火球是从恶魂那边直直飞来的，顺着视线打回去正好砸回那只四格大的恶魂身上，一下炸死。
 * 不等攻击条回满（打回去不看充能）。实机 10-15 下界恶魂隔着十几格放火球，自卫只会往前凑近战，被炸死。
 */
public final class ProjectileReturn implements Action {

    /** 这么远以内朝我飞来的才去接。 */
    static final double WATCH_RADIUS = 24.0;
    /** 火球飞过身边最近不到这么远，算冲着我来的。 */
    private static final double HEADING_MISS = 2.0;
    /** 打出去以后等多久看它掉头。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 10;

    private final int projectileId;
    private PendingInteraction pending;
    private PlayerContext last;

    public ProjectileReturn(int projectileId) {
        this.projectileId = projectileId;
    }

    /** 这东西能不能打回去：原版按实体种类的标签认（准星点得中的投射物就是这一类）。 */
    public static boolean returnable(Entity entity) {
        return entity instanceof Projectile && entity.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE);
    }

    /**
     * 冲着身子飞来：在靠近，而且照现在的方向飞，掠过身子中心最近不到两格。纯函数，离线可测。
     *
     * @param body 身子中心
     */
    static boolean headingAt(Vec3 position, Vec3 velocity, Vec3 body) {
        Vec3 toBody = body.subtract(position);
        double speedSquared = velocity.lengthSqr();
        if (speedSquared < 1.0e-6) return false;
        double along = toBody.dot(velocity);
        if (along <= 0) return false;
        Vec3 closest = velocity.scale(along / speedSquared);
        return toBody.subtract(closest).length() < HEADING_MISS;
    }

    // 每刻：已经打出去的等它掉头；没打的盯着火球，够得着、准星压在它身上就挥。火球没了、不再冲着我就收手。
    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        last = player;
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null || player.interactionSender() == null || player.input() == null) return ActionStatus.done();
        InteractionSender sender = player.interactionSender();
        if (pending != null) {
            if (!pending.terminal()) pending = sender.poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            pending = null;
            return ActionStatus.done();
        }
        Entity projectile = player.level().getEntity(projectileId);
        if (projectile == null || !projectile.isAlive()
                || !headingAt(projectile.position(), projectile.getDeltaMovement(), self.getBoundingBox().getCenter())) {
            return ActionStatus.done();
        }
        Vec3 aim = projectile.getBoundingBox().getCenter();
        player.input().lookAt(self, aim);
        HitResult aimed = Interaction.nativeRaytrace(self, InteractionRange.entityReach(self));
        if (!(aimed instanceof EntityHitResult hit) || hit.getEntity() != projectile || !player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        try {
            pending = sender.attack(player, projectile, turnedBack(self, projectile), CONFIRM_TIMEOUT_TICKS);
        } catch (IllegalStateException busy) {
            // 这一刻交互入口被占着（盾还没结清）：下一刻再挥。
            return ActionStatus.running();
        }
        return ActionStatus.progressed();
    }

    // 打中的证据：火球掉头往外飞了。火球炸没了说不清是打回去炸的还是撞到别处，等超时如实算不确定。
    private static InteractionConfirmation turnedBack(LocalPlayer self, Entity projectile) {
        int id = projectile.getId();
        return context -> {
            Entity now = context.level().getEntity(id);
            if (now == null) return InteractionConfirmation.Verdict.PENDING;
            Vec3 toMe = self.getBoundingBox().getCenter().subtract(now.position());
            return now.getDeltaMovement().dot(toMe) < 0
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        };
    }

    @Override
    public void close() {
        // 打出去还没确认就收手：交还等待，结果按不确定算，不挡后面的出手。
        if (pending != null && !pending.terminal() && last != null && last.interactionSender() != null) {
            last.interactionSender().abandonOneShotForTaskBoundary(pending, "不再接这个火球");
        }
        pending = null;
    }

    @Override
    public String describe() {
        return "把飞来的火球打回去";
    }
}
