// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.DragonArena;
import org.maiwithu.maicraft.behavior.combat.DragonGeometry;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 打末影龙：缺武器缺弓箭如实说，看到死亡动画算打赢、只看到不见了不算，等龙落不判卡住，五分钟没动静才判。 */
class FightDragonTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("打龙测试不碰角色对象");
        }
    }

    /** 龙场由测试每刻摆：此刻的龙（null 为不在了）与水晶。 */
    private DragonArena.Activity activity = DragonArena.Activity.CIRCLING;
    private boolean dragonPresent = true;
    private List<DragonArena.Crystal> crystals = List.of();

    private Optional<DragonArena> arena(long tick, int id) {
        if (!dragonPresent || id >= 0 && id != 1) return Optional.empty();
        AABB head = new AABB(0, 100, -86.5, 1, 101, -85.5);
        DragonArena.Dragon dragon = new DragonArena.Dragon(1, "minecraft:ender_dragon", 0.5, 100, -80, 0, 0, 0, head, head,
                activity, 0, 150, 200, -1, true, 80);
        return Optional.of(new DragonArena(tick, dragon, crystals, List.of(), List.of(), List.of(),
                new DragonArena.Portal(0.5, 64, 0.5, false), 20, false));
    }

    private final class Senses implements CombatSenses {
        @Override public List<Threat> threats(TickContext context, double radius) { return List.of(); }
        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:diamond_sword", WeaponChoice.Weapon.MELEE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }

        @Override public Optional<DragonArena> dragonArena(TickContext context, int dragonId, Set<String> landmarks) {
            return arena(context.gameTick(), dragonId);
        }
    }

    /** e1 是 1 号末影龙。 */
    private static final class Targets implements SeenTargets {
        @Override public Locked lock(TickContext context, String observedId) {
            return "e1".equals(observedId) ? new Locked(1, new UUID(0, 1), "minecraft:ender_dragon") : null;
        }

        @Override public Observed observe(TickContext context, int entityId) { return null; }
    }

    private static final class Moves implements FightMoves {
        boolean melee = true;
        boolean bow = true;
        final List<String> started = new ArrayList<>();

        @Override public Action strike(TickContext context, int entityId) { return action(ActionStatus::done); }
        @Override public Action shoot(TickContext context, int entityId) { return action(ActionStatus::running); }
        @Override public boolean canShoot(TickContext context) { return bow; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return melee; }
        @Override public boolean attackReady(TickContext context) { return true; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}

        @Override public Action walk(GoalCompiler.Compiled goal) {
            started.add("走");
            return action(ActionStatus::progressed);
        }

        @Override public double[] selfPosition(TickContext context) { return new double[] {10.5, 64, 0.5}; }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:diamond_sword"; }

        @Override public DragonGeometry.Surface surface(TickContext context) {
            return (x, z) -> Math.hypot(x + 0.5, z + 0.5) <= 60 ? OptionalInt.of(64) : OptionalInt.empty();
        }

        @Override public long dropCursor(TickContext context) { return 0; }
        @Override public Set<Integer> dropsLyingBefore(TickContext context, long cursor) { return Set.of(); }
        @Override public Action pickUpNewSince(Set<Integer> before) { return action(ActionStatus::done); }
        @Override public List<Gained> pickedUpSince(TickContext context, long cursor) { return List.of(); }

        private static Action action(Supplier<ActionStatus> status) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return status.get(); }
                @Override public String describe() { return "替身"; }
            };
        }
    }

    private final Moves moves = new Moves();

    private FightTask task(List<String> named) {
        return new FightTask(new FightInput(named, null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
                new Senses(), new Targets(), moves, (target, permissions) -> Optional.empty());
    }

    // 从 from 推进到 to 刻；结束了返回结果，没结束返回 null。
    private static TaskResult run(FightTask task, long from, long to) {
        for (long t = from; t < to; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) return finished.result();
        }
        return null;
    }

    @Test
    void noWeaponAtAllNeedsASwordAndABow() {
        moves.melee = false;
        moves.bow = false;
        FightTask fight = task(List.of("e1"));
        fight.start(new Tick(0));
        TaskResult result = run(fight, 0, 5);
        assertNotNull(result);
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("剑或斧"), result.problem().message());
    }

    @Test
    void crystalsWithoutABowNeedArrowsAndSayWhere() {
        moves.bow = false;
        crystals = List.of(new DragonArena.Crystal(5, 42.5, 90, 0.5, false, null, false),
                new DragonArena.Crystal(6, -12.5, 82, 40.5, true, null, false));
        FightTask fight = task(List.of());
        fight.start(new Tick(0));
        TaskResult result = run(fight, 0, 5);
        assertNotNull(result, "不点名、身边有龙：按打龙查装备");
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("2 颗末影水晶") && result.problem().message().contains("铁栏笼里"),
                result.problem().message());
    }

    @Test
    void seeingTheDeathAnimationIsAWinAndTheVanishingCorroboratesIt() {
        FightTask fight = task(List.of("e1"));
        fight.start(new Tick(0));
        assertNull(run(fight, 0, 40), "龙在天上绕：去出口门旁等它落");
        activity = DragonArena.Activity.DYING;
        assertNull(run(fight, 40, 60), "死亡动画里：站在门边收经验，先不收工");
        dragonPresent = false;
        TaskResult result = run(fight, 60, 80);
        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().startsWith("打败了末影龙"), result.summary());
        FightTask.FightDetails details = (FightTask.FightDetails) result.details();
        assertEquals(List.of("minecraft:ender_dragon（实体 1）"), details.defeated());
        assertEquals(List.of("看到了死亡动画", "死亡动画后龙消失了"), details.dragon().defeat());
    }

    @Test
    void aDragonThatVanishesWithoutDyingIsLostNotKilled() {
        FightTask fight = task(List.of("e1"));
        fight.start(new Tick(0));
        assertNull(run(fight, 0, 20));
        dragonPresent = false;
        TaskResult result = run(fight, 20, 60);
        assertNotNull(result);
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
        FightTask.FightDetails details = (FightTask.FightDetails) result.details();
        assertTrue(details.defeated().isEmpty());
        assertEquals(List.of("minecraft:ender_dragon（实体 1）"), details.lostTrack());
    }

    @Test
    void waitingForAPerchIsNotStuckButFiveIdleMinutesAre() {
        FightTask fight = task(List.of("e1"));
        fight.start(new Tick(0));
        assertNull(run(fight, 0, 1200), "等龙落一分钟：不按半分钟没进展判卡住");
        TaskResult result = run(fight, 1200, FightTask.DRAGON_IDLE_TICKS + 40);
        assertNotNull(result);
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("盘旋"), result.problem().message());
    }
}
