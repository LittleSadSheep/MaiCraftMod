// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 顺手捡起的生产实现：附近几格有掉落物时朝它走过去，让原版的拾取自己吸附。
 *
 * <p>走过去就行，不伸手改背包：东西进没进包以地上的那件消失为准；时限内没捡到
 * 如实失败，不把"走过去了"当成"捡到了"。附近没有掉落物时给空，调用方跳过捡拾。
 */
public final class LiveDropGathering implements UseSeams.GathersDrops {

    /** 只捡近处的：交互掉出来的东西就在脚下，走远了的不算"顺手"。 */
    private static final double NEARBY_BLOCKS = 5.0;
    /** 捡拾的时限；到点没捡到就收手，不在一件东西上耗着。 */
    private static final int GIVE_UP_TICKS = 60;

    private final Supplier<PlayerContext> context;
    private final InputDriver input;

    public LiveDropGathering(Supplier<PlayerContext> context, InputDriver input) {
        this.context = Objects.requireNonNull(context, "context");
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public Optional<Action> nearby() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return Optional.empty();
        }
        ItemEntity nearest = nearestDrop(current.level(), current.localPlayer());
        if (nearest == null) {
            return Optional.empty();
        }
        return Optional.of(new WalkOver(nearest.getId()));
    }

    // 渲染范围内离角色最近的掉落物；五格开外的不算顺手捡。
    private ItemEntity nearestDrop(ClientLevel level, LocalPlayer player) {
        ItemEntity best = null;
        double bestDistance = NEARBY_BLOCKS * NEARBY_BLOCKS;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity drop)) continue;
            double distance = entity.distanceToSqr(player);
            if (distance < bestDistance) {
                best = drop;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 走向掉落物的动作：东西消失即捡到了；时限到了还在地上，如实失败。 */
    private final class WalkOver implements Action {

        private final int entityId;
        private int waited;

        WalkOver(int entityId) {
            this.entityId = entityId;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext current = context.get();
            if (current == null || current.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME,
                        "这一刻掌握不到角色，捡不了", null));
            }
            LocalPlayer player = current.localPlayer();
            Entity drop = player.level().getEntity(entityId);
            // 掉落物不在了：被原版吸进了包（也可能被别人拿走），这一步就此结束，捡到多少以清点为准。
            if (drop == null || !drop.isAlive()) {
                return ActionStatus.done();
            }
            if (++waited > GIVE_UP_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        describe() + "：走过去了东西还在地上，先不捡了", null));
            }
            input.stepToward(player, drop.position(), false);
            return ActionStatus.progressed();
        }

        @Override
        public String describe() {
            return "捡起脚边掉出的东西";
        }
    }
}
