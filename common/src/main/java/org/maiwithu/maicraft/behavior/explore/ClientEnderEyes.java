// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.InteractionAction;
import org.maiwithu.maicraft.behavior.inventory.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 扔末影之眼找要塞的生产现场：数身上的眼、扔一只并盯着它飞、捡回落下的那颗、朝一处走一段。
 *
 * <p>扔眼像真人：眼换到主手，抬头对着天空右键一下（准星不落在方块上，免得对着末地门框架变成插眼），
 * 看着眼从身边飞起，镜头一路跟着它，直到它消失；消失的地方冒出一颗末影之眼就是落下了，没冒出来就是碎了。
 * 捡回交给共用的捡掉落物，走一段交给走到。
 */
public final class ClientEnderEyes implements FollowsEnderEyes {

    /** 抬头的俯仰角（往上为负）：对着天空。 */
    private static final float LOOK_UP_PITCH = -60f;
    /** 镜头抬到这个角度以上才右键：还平视时准星可能落在面前的方块上。 */
    private static final float LOOKED_UP_ENOUGH = -45f;
    /** 镜头转上去的耐心（刻）。 */
    private static final int TURN_PATIENCE_TICKS = 40;
    /** 右键之后等眼飞起来的耐心（刻）：没有要塞的世界里眼根本不飞。 */
    private static final int APPEAR_TICKS = 20;
    /** 盯着眼飞的上限（刻）：原版飞八十刻后落下或碎掉，多留一截。 */
    private static final int FLIGHT_TICKS = 140;
    /** 眼消失以后等落下的那颗冒出来的耐心（刻）。 */
    private static final int DROP_TICKS = 15;
    /** 眼从角色身边飞起：离角色这么近（格）的新眼才是自己扔的。 */
    private static final double SPAWN_REACH = 6;
    /** 落下的那颗就在眼消失的地方：离那里这么近（格）的新末影之眼才算。 */
    private static final double DROP_REACH = 3;
    /** 走一段到了那一柱列这么近（格）就算到。 */
    private static final double LEG_ARRIVAL = 3;

    private final Supplier<PlayerContext> context;
    private final WalkTo walks;
    private final ClientMovesToMainhand toMainhand;
    private final ClientDropPickup drops;

    public ClientEnderEyes(Supplier<PlayerContext> context, WalkTo walks, ClientMovesToMainhand toMainhand,
            ClientDropPickup drops) {
        this.context = Objects.requireNonNull(context, "context");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.drops = Objects.requireNonNull(drops, "drops");
    }

    @Override
    public int eyesCarried() {
        LocalPlayer player = player();
        if (player == null) return 0;
        int count = player.getOffhandItem().is(Items.ENDER_EYE) ? player.getOffhandItem().getCount() : 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.ENDER_EYE)) count += stack.getCount();
        }
        return count;
    }

    @Override
    public Optional<WorldPosition> feet() {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        return Optional.of(new WorldPosition(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                player.clientLevel.dimension().location().toString()));
    }

    @Override
    public EyeThrow throwEye() {
        return new Throw();
    }

    @Override
    public Action fetch(int dropEntity) {
        return drops.pickUpOne(dropEntity);
    }

    @Override
    public WalkRun walkToward(int x, int z, TerrainPermit permit) {
        // 眼指的那一段只约束柱列：高度到了附近再找能站的格子，不拿出手时的高度去硬凑。
        return walks.start(new GoalCompiler.Compiled(NavGoal.column(x, z, LEG_ARRIVAL), LongSets.emptySet()), permit);
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    /** 扔一只眼：换到主手 → 抬头 → 右键空气 → 等眼飞起 → 盯着它飞 → 看它落下还是碎了。 */
    private final class Throw implements EyeThrow {

        /** 扔一只眼的几步。 */
        private enum Step { HOLD, LOOK_UP, CLICK, AWAIT_EYE, FOLLOW, AWAIT_DROP, DONE }

        private Step step = Step.HOLD;
        private Action holding;
        private Action click;
        private int waited;
        /** 右键之前身边已经有的眼与地上已有的末影之眼：之后新冒出来的才是这一扔的。 */
        private Set<Integer> eyesBefore = Set.of();
        private Set<Integer> dropsBefore = Set.of();
        private int eyeId = -1;
        private Vec3 from;
        private Vec3 last;
        private EyeFlight flight;

        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext current = tick.player();
            LocalPlayer player = current == null ? null : current.localPlayer();
            if (player == null || current.interactionSender() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，扔不了眼", null));
            }
            return switch (step) {
                case HOLD -> hold(tick, player);
                case LOOK_UP -> lookUp(current, player);
                case CLICK -> click(tick, current, player);
                case AWAIT_EYE -> awaitEye(player);
                case FOLLOW -> follow(current, player);
                case AWAIT_DROP -> awaitDrop(player);
                case DONE -> ActionStatus.done();
            };
        }

        // 眼不在主手就换上来：快捷栏按数字键，主背包里的换进快捷栏，手里原来的东西留在原格。
        private ActionStatus hold(TickContext tick, LocalPlayer player) {
            if (player.getMainHandItem().is(Items.ENDER_EYE)) {
                holding = null;
                step = Step.LOOK_UP;
                return ActionStatus.running();
            }
            if (holding == null) {
                holding = toMainhand.actionToMainhand(EnderEyeSearch.ENDER_EYE).orElse(null);
                if (holding == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "末影之眼换不到主手（身上没有了）", null));
                }
            }
            ActionStatus status = holding.tick(tick);
            if (status instanceof ActionStatus.Done) holding = null;
            return status instanceof ActionStatus.Failed ? status : ActionStatus.running();
        }

        // 抬头对着天空：镜头真的抬上去了才右键，转不上去如实收场。
        private ActionStatus lookUp(PlayerContext current, LocalPlayer player) {
            current.input().halt(player);
            current.input().requestLook(player.getYRot(), LOOK_UP_PITCH, current.clientTick());
            if (player.getXRot() <= LOOKED_UP_ENOUGH) {
                step = Step.CLICK;
                return ActionStatus.running();
            }
            if (++waited > TURN_PATIENCE_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "镜头抬不起来，没法对着天空扔眼", null));
            }
            return ActionStatus.running();
        }

        // 右键空气扔出去：先记下身边已有的眼与地上已有的末影之眼，再经原生使用物品点一下。
        private ActionStatus click(TickContext tick, PlayerContext current, LocalPlayer player) {
            current.input().requestLook(player.getYRot(), LOOK_UP_PITCH, current.clientTick());
            if (click == null) {
                eyesBefore = eyesNear(player.clientLevel, player.position(), SPAWN_REACH * 4);
                dropsBefore = eyeDrops(player.clientLevel, player.getBoundingBox().inflate(64));
                click = new InteractionAction(Interaction.useInAir(current, current.interactionSender(),
                        current.menuActions(), current.input(), InteractionHand.MAIN_HAND, Interaction.Timing.once()),
                        "对着天空扔末影之眼");
            }
            ActionStatus status = click.tick(tick);
            if (status instanceof ActionStatus.Done) {
                step = Step.AWAIT_EYE;
                waited = 0;
                return ActionStatus.progressed();
            }
            return status;
        }

        // 等眼飞起来：身边冒出一只新眼就开始盯；等不到是这个世界里没有它能指的要塞。
        private ActionStatus awaitEye(LocalPlayer player) {
            for (Entity entity : player.clientLevel.getEntitiesOfClass(EyeOfEnder.class,
                    player.getBoundingBox().inflate(SPAWN_REACH))) {
                if (!eyesBefore.contains(entity.getId())) {
                    eyeId = entity.getId();
                    from = entity.position();
                    last = from;
                    step = Step.FOLLOW;
                    waited = 0;
                    return ActionStatus.progressed();
                }
            }
            if (++waited > APPEAR_TICKS) {
                step = Step.DONE;
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        // 盯着眼飞：镜头一路跟着它，记下最后看到它的位置；它消失了再看落下还是碎了。
        private ActionStatus follow(PlayerContext current, LocalPlayer player) {
            Entity eye = player.clientLevel.getEntity(eyeId);
            if (eye instanceof EyeOfEnder && !eye.isRemoved()) {
                last = eye.position();
                current.input().halt(player);
                current.input().lookAt(player, last);
                if (++waited > FLIGHT_TICKS) {
                    flight = flightEnding(EyeFlight.Landing.LOST_SIGHT, -1);
                    step = Step.DONE;
                    return ActionStatus.done();
                }
                return ActionStatus.progressed();
            }
            step = Step.AWAIT_DROP;
            waited = 0;
            return ActionStatus.running();
        }

        // 眼消失的地方冒出一颗新的末影之眼就是落下了（记下它好去捡），等不到就是碎了。
        private ActionStatus awaitDrop(LocalPlayer player) {
            for (int id : eyeDrops(player.clientLevel, new AABB(last, last).inflate(DROP_REACH))) {
                if (!dropsBefore.contains(id)) {
                    flight = flightEnding(EyeFlight.Landing.DROPPED, id);
                    step = Step.DONE;
                    return ActionStatus.done();
                }
            }
            if (++waited > DROP_TICKS) {
                flight = flightEnding(EyeFlight.Landing.SHATTERED, -1);
                step = Step.DONE;
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        private EyeFlight flightEnding(EyeFlight.Landing landing, int dropEntity) {
            return new EyeFlight(from.x, from.y, from.z, last.x, last.y, last.z, landing, dropEntity);
        }

        @Override
        public Optional<EyeFlight> flight() {
            return Optional.ofNullable(flight);
        }

        @Override
        public void pause() {
            if (holding != null) holding.pause();
        }

        @Override
        public void close() {
            if (holding != null) holding.close();
            if (click != null) click.close();
        }

        @Override
        public String describe() {
            return switch (step) {
                case HOLD -> "把末影之眼换到主手";
                case LOOK_UP, CLICK -> "抬头对着天空扔末影之眼";
                case AWAIT_EYE, FOLLOW -> "盯着飞出去的末影之眼";
                case AWAIT_DROP, DONE -> "看末影之眼落下还是碎了";
            };
        }
    }

    // 一片范围里的末影之眼（飞着的那种实体）。
    private static Set<Integer> eyesNear(ClientLevel level, Vec3 center, double reach) {
        Set<Integer> ids = new HashSet<>();
        for (Entity entity : level.getEntitiesOfClass(EyeOfEnder.class, new AABB(center, center).inflate(reach))) {
            ids.add(entity.getId());
        }
        return ids;
    }

    // 一片范围里躺在地上的末影之眼（掉落物）。
    private static Set<Integer> eyeDrops(ClientLevel level, AABB area) {
        Set<Integer> ids = new HashSet<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (item.getItem().is(Items.ENDER_EYE)) ids.add(item.getId());
        }
        return ids;
    }
}
