// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.behavior.combat.MeleeStrike;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.GroundSteering;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎出手的生产实现：原生攻击与走到。
 *
 * <p>出手和自卫、点名战斗是同一个近战出手：先把剑斧里最好的那把换到主手，再原生攻击——充能够格才挥、
 * 换手当刻与目标无敌帧不挥，这些逐刻确认都在交互里。剑斧在追之前就换到手上：出手时贴着猎物，不为换武器开背包。
 * 够不够得着按角色的实体触及距离量，够不着由狩猎先走近；走位只走不改，追猎物不挖路不垫路。
 * 人和猎物泡在同一片水里时不走寻路，直接游过去：朝它下潜或上浮，镜头一直跟着它，氧气不够先上去换满。
 */
public final class LiveHuntMoves implements HuntMoves {

    private static final Logger LOG = LoggerFactory.getLogger(LiveHuntMoves.class);

    /** 触及距离留的余量（格）：量到包围盒刚好在边上时准星常打不中，先多走半步。 */
    private static final double REACH_MARGIN = 0.4;

    private final WalkTo walks;
    /** 上一次"是不是同一片水"的三项事实：变了才记一行日志。 */
    private String lastWaterFacts;
    /** 换武器时用的这一刻的角色：换手动作开背包、关背包要读它。 */
    private PlayerContext current;
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.current);

    public LiveHuntMoves(WalkTo walks) {
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override public Optional<Action> readyWeapon(TickContext context) {
        current = context.player();
        if (current == null || current.localPlayer() == null) return Optional.empty();
        // 还没追到跟前：主背包里的剑斧也拿出来，换进快捷栏的空格再选中；猎物不还手，开一下背包没关系。
        return MeleeStrike.weaponWorthTaking(current.localPlayer(), false, false).flatMap(hands::actionToMainhand);
    }

    @Override public Action strike(TickContext context, int entityId) {
        var player = context.player();
        var target = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null || player.interactionSender() == null) {
            // 目标已经不在或这一刻交不出交互：返回一个立刻结束的空动作，下一刻重新决定。
            return AlreadyOver.INSTANCE;
        }
        // 换武器 -> 出手：拿着镐打羊也能打，但有剑斧就先换上，和自卫、点名战斗同一套出手。
        return new MeleeStrike(entityId);
    }

    @Override public boolean inReach(TickContext context, int entityId) {
        var player = context.player();
        var self = player == null ? null : player.localPlayer();
        var target = self == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null) return false;
        // 眼睛到它包围盒最近一点的距离，留一点余量：贴着触及边缘时准星常常差一丝打不到。
        var eye = self.getEyePosition();
        double reach = InteractionRange.entityReach(self) - REACH_MARGIN;
        return target.getBoundingBox().distanceToSqr(eye) <= reach * reach;
    }

    @Override public Optional<double[]> positionOf(TickContext context, int entityId) {
        var player = context.player();
        var target = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null || target.isRemoved()) return Optional.empty();
        return Optional.of(new double[] {target.getX(), target.getY(), target.getZ()});
    }

    @Override public boolean sameWater(TickContext context, int entityId) {
        var player = context.player();
        var self = player == null ? null : player.localPlayer();
        var target = self == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null) return false;
        // 眼睛到它身体中心一路没有方块挡着（水不算挡）：同一片水里直着游得过去；隔着岸、隔着墙的照旧交给寻路绕。
        Vec3 eye = self.getEyePosition();
        Vec3 body = target.getBoundingBox().getCenter();
        boolean clear = player.level().clip(new ClipContext(eye, body, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
        // 三项事实变了才记一行：实机里对得上为什么这一只是游过去还是走寻路。
        String facts = "自己在水里 " + self.isInWater() + "，它在水里 " + target.isInWater() + "，中间没挡着 " + clear;
        if (!facts.equals(lastWaterFacts)) {
            lastWaterFacts = facts;
            LOG.info("[狩猎] 和 {} 是不是同一片水：{}", EntityType.getKey(target.getType()).getPath(), facts);
        }
        return self.isInWater() && target.isInWater() && clear;
    }

    @Override public Action swimTo(int entityId) {
        return new SwimAfter(entityId);
    }

    @Override public Action walkTo(double x, double y, double z) {
        return new WalkMove(walks, new double[] {x, y, z});
    }

    @Override public Action walkToArea(double x, double y, double z) {
        return new Action() {
            private Action walk;

            @Override public ActionStatus tick(TickContext context) {
                if (walk == null) {
                    // 到那一列附近（四格内）哪个高度都算；可以临时垫块翻过小坎，事后收回。
                    walk = walks.start(new GoalCompiler.Compiled(NavGoal.column((int) Math.floor(x), (int) Math.floor(z),
                            AREA_RADIUS), LongSets.emptySet()), TerrainPermit.TEMPORARY);
                }
                return walk.tick(context);
            }

            @Override public void pause() {
                if (walk != null) walk.pause();
            }

            @Override public void close() {
                if (walk != null) walk.close();
            }

            @Override public String describe() {
                return walk == null ? "走出去找猎物" : walk.describe();
            }
        };
    }

    /** 搜索点到这么近（格）就算到了那一片。 */
    private static final double AREA_RADIUS = 4.0;

    @Override public double[] selfPosition(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? new double[] {0, 0, 0}
                : new double[] {self.getX(), self.getY(), self.getZ()};
    }

    /** 第一刻才拿得到上下文的走到动作：把当刻上下文带进来再开走。 */
    private static final class WalkMove implements Action {
        private final WalkTo walks;
        private final double[] target;
        private Action walk;

        WalkMove(WalkTo walks, double[] target) {
            this.walks = walks;
            this.target = target;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (walk == null) {
                BlockPos cell = new BlockPos((int) Math.floor(target[0]), (int) Math.floor(target[1]),
                        (int) Math.floor(target[2]));
                walk = walks.start(GoalCompiler.near(cell, 1.0), TerrainPermit.WALK_ONLY);
            }
            return walk.tick(context);
        }

        @Override public void pause() {
            if (walk != null) walk.pause();
        }

        @Override public void close() {
            if (walk != null) walk.close();
        }

        @Override public String describe() {
            return walk == null ? "走向猎物" : walk.describe();
        }
    }

    /**
     * 在水里游过去追一只：每刻按 {@link SwimChase} 的判断转镜头、按键。它不在了、人出了水就收手，交回狩猎重新决定
     * （上了岸改走寻路，打倒了去收掉落）；不会自己判失败，追多久由狩猎每只猎物的期限管。
     */
    private static final class SwimAfter implements Action {
        /** 往上找水面最多看几格：再深按这么深预留氧气，宁可早点上去。 */
        private static final int SURFACE_SCAN = 24;
        /** 离它近了这么多才算追近了一截。 */
        private static final double PROGRESS_GAIN = 0.25;

        private final int entityId;
        private final SwimChase chase = new SwimChase();
        private PlayerContext player;
        private double best = Double.MAX_VALUE;
        private String what = "游过去追猎物";
        /** 上一次按的键：变了、或隔两秒，记一行日志，实机对得上游的时候按了什么、人挪没挪。 */
        private String lastKeys;
        private long lastKeysTick;

        SwimAfter(int entityId) {
            this.entityId = entityId;
        }

        @Override public ActionStatus tick(TickContext context) {
            player = context.player();
            LocalPlayer self = player == null ? null : player.localPlayer();
            if (self == null || player.input() == null || player.level() == null) return ActionStatus.done();
            Entity target = player.level().getEntity(entityId);
            if (target == null || !target.isAlive() || !self.isInWater() && !chase.surfacing()) {
                // 它不在了，或人出了水：停下按键，交回狩猎决定是收掉落、走寻路还是换下一只。
                release();
                return ActionStatus.done();
            }
            if (screenInTheWay(player)) {
                // 背包、容器、模组界面开着时原版不让人挪（镜头照转）：和走路一样先把它关上，关好了再游。
                return ActionStatus.running();
            }
            Vec3 eye = self.getEyePosition();
            Vec3 body = target.getBoundingBox().getCenter();
            long tick = player.clientTick();
            SwimChase.Stroke stroke = chase.next(new SwimChase.Scene(tick, body.x - eye.x, body.y - eye.y, body.z - eye.z,
                    self.getY() - body.y, self.isEyeInFluid(FluidTags.WATER), self.onGround(), self.getAirSupply(),
                    self.getMaxAirSupply(), surfaceAbove(self) - eye.y), self.getYRot());
            PlayerInput input = player.input();
            input.requestLook(stroke.yaw(), stroke.pitch(), tick);
            PlayerInput.Movement keys = keys(self, body, stroke);
            input.applyMovement(keys, tick);
            logKeys(self, keys, stroke, eye.distanceTo(body), tick);
            what = stroke.surfacing() ? "浮上去换口气再回来追"
                    : "游过去追 " + EntityType.getKey(target.getType()).getPath();
            double distance = eye.distanceTo(body);
            if (distance < best - PROGRESS_GAIN) {
                best = distance;
                return ActionStatus.progressed();
            }
            return ActionStatus.running();
        }

        // 有界面挡着就请它关上，关好前算挡着；关不掉照常游，挪不动由狩猎每只猎物的期限收场。
        private static boolean screenInTheWay(PlayerContext player) {
            MenuActions menus = player.menuActions();
            if (menus == null || !menus.blocksWorld(player)) return false;
            try {
                LOG.info("[狩猎] 游之前先关上挡着的界面");
                return !menus.ensureWorldVisible(player);
            } catch (RuntimeException stuck) {
                LOG.info("[狩猎] 开着的界面关不掉，照常游：{}", stuck.getMessage());
                return false;
            }
        }

        // 按的键变了、或者隔了两秒，记一行：前进、横移、跳、潜行、疾跑，人在哪、眼睛进没进水、离它多远。
        private void logKeys(LocalPlayer self, PlayerInput.Movement keys, SwimChase.Stroke stroke, double distance,
                long tick) {
            String now = String.format("前进 %.2f 横移 %.2f 跳 %s 潜行 %s 疾跑 %s 换气 %s", keys.forward(), keys.strafe(),
                    keys.jumping(), keys.sneaking(), keys.sprinting(), stroke.surfacing());
            if (now.equals(lastKeys) && tick - lastKeysTick < LOG_EVERY_TICKS) return;
            lastKeys = now;
            lastKeysTick = tick;
            // 上一刻写进原版输入的前进量也记上：按了前进人却不挪时，分得清是键没送到还是被挡住。
            LOG.info("[狩猎] 游：{}；人在 {}, {}, {}，眼睛在水里 {}，踩着地 {}，离它 {} 格，上一刻送到的前进 {}，横移 {}，"
                    + "撞墙 {}", now, String.format("%.2f", self.getX()), String.format("%.2f", self.getY()),
                    String.format("%.2f", self.getZ()), self.isEyeInFluid(FluidTags.WATER), self.onGround(),
                    String.format("%.1f", distance), String.format("%.2f", self.input.forwardImpulse),
                    String.format("%.2f", self.input.leftImpulse), self.horizontalCollision);
        }

        /** 按键没变时多久再记一行（刻）。 */
        private static final long LOG_EVERY_TICKS = 40;

        // 眼睛进了水：按着前进与疾跑，原版顺着镜头游（镜头正对着它）；水面上、浅水里站着：前进只管水平，
        // 按此刻实际朝向折成前后左右，镜头还没转过去也朝它挪。上浮按跳、下沉按潜行由判断给。
        private static PlayerInput.Movement keys(LocalPlayer self, Vec3 body, SwimChase.Stroke stroke) {
            if (stroke.forward() <= 0.0f) {
                return new PlayerInput.Movement(0.0f, 0.0f, stroke.jump(), stroke.sneak(), false);
            }
            if (stroke.sprint()) {
                return new PlayerInput.Movement(1.0f, 0.0f, stroke.jump(), stroke.sneak(), true);
            }
            PlayerInput.Movement flat = GroundSteering.keys(body.x - self.getX(), body.z - self.getZ(), self.getYRot(),
                    stroke.jump());
            return new PlayerInput.Movement(flat.forward(), flat.strafe(), stroke.jump(), stroke.sneak(), false);
        }

        // 眼睛往上数到第一格不是水的：那一格的底就是水面。看了二十四格还全是水，就按那么深算。
        private static double surfaceAbove(LocalPlayer self) {
            BlockPos.MutableBlockPos cell = BlockPos.containing(self.getEyePosition()).mutable();
            for (int i = 0; i < SURFACE_SCAN; i++) {
                if (!self.level().getFluidState(cell).is(FluidTags.WATER)) break;
                cell.move(0, 1, 0);
            }
            return cell.getY();
        }

        @Override public void pause() {
            release();
        }

        @Override public void close() {
            release();
        }

        private void release() {
            if (player != null && player.input() != null && player.localPlayer() != null) {
                player.input().halt(player.localPlayer());
            }
        }

        @Override public String describe() {
            return what;
        }
    }

    /** 目标已经不在：本动作立刻完成，不打空拳。 */
    private enum AlreadyOver implements Action {
        INSTANCE;

        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "目标已不在";
        }
    }
}
