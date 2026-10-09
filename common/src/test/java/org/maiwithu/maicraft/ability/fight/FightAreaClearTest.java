// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.ThreatAssessment;
import org.maiwithu.maicraft.behavior.survival.WeaponChoice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 不点名的清扫：只清开打那一刻看得见的这一批，途中冲着角色来的一并处理；
 * 墙后看不见的、之后新刷出来的不追，这一批打完就收手。
 */
class FightAreaClearTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("战斗任务测试不碰角色对象");
        }
    }

    /** 战场替身：哪些敌人在、哪些看得见、谁刚打过角色，由测试摆；出手一下对面就倒。 */
    private static final class Field implements CombatSenses, SeenTargets, FightMoves {
        final Map<Integer, Threat> alive = new HashMap<>();
        final Set<Integer> dead = new HashSet<>();
        final List<Attacker> attackers = new ArrayList<>();
        final List<Integer> struck = new ArrayList<>();

        void zombie(int id, double distance, boolean visible) {
            alive.put(id, new Threat(id, new UUID(0, id), "minecraft:zombie", distance, 64, 0, distance,
                    ThreatAssessment.Kind.MELEE, false, visible, false));
        }

        @Override public List<Threat> threats(TickContext context, double radius) {
            return alive.values().stream().filter(threat -> threat.distance() <= radius).toList();
        }

        @Override public List<Attacker> recentAttackers(TickContext context) { return attackers; }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.SWORD_OR_AXE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }

        @Override public Locked lock(TickContext context, String observedId) { return null; }

        @Override public Observed observe(TickContext context, int entityId) {
            if (dead.contains(entityId)) return new Observed(0, 64, 0, 1, true);
            Threat threat = alive.get(entityId);
            return threat == null ? null : new Observed(threat.x(), threat.y(), threat.z(), threat.distance(), false);
        }

        @Override public Action strike(TickContext context, int entityId) {
            struck.add(entityId);
            // 一刀下去就倒：下一刻观察得到死亡，威胁列表里也没了它。
            alive.remove(entityId);
            dead.add(entityId);
            return done();
        }

        @Override public Action walkTo(double x, double y, double z) { return done(); }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }

        @Override public List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius) {
            return List.of();
        }

        @Override public int carriedItemCount(TickContext context) { return 0; }

        private static Action done() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }

                @Override public String describe() { return "一下"; }
            };
        }
    }

    private final Field field = new Field();
    private final FightTask task = new FightTask(
            new FightInput(List.of(), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
            field, field, field);

    private long tick;

    private TaskResult run(int ticks, Runnable afterFirstTicks) {
        task.start(new Tick(tick));
        for (int i = 0; i < ticks; i++) {
            if (i == 3) afterFirstTicks.run();
            if (task.tick(new Tick(tick++)) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        return null;
    }

    @Test
    void zombieBehindAWallIsNotChased() {
        // 身边一只看得见、墙后一只看不见：只清看得见的那只，清完就收手。
        field.zombie(1, 2, true);
        field.zombie(2, 2, false);

        TaskResult result = run(40, () -> {});

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(1), field.struck);
    }

    @Test
    void mobsSpawningAfterTheFightStartsAreLeftAlone() {
        // 开打后才冒出来的不算这一批：夜里怪不停刷，任务也收得了尾。
        field.zombie(1, 2, true);

        TaskResult result = run(40, () -> field.zombie(3, 2, true));

        assertNotNull(result);
        assertEquals(List.of(1), field.struck);
    }

    @Test
    void somethingThatAttacksMidwayIsDealtWithToo() {
        // 途中冲着角色来、真打了一下的：不在这一批里也一并处理。
        field.zombie(1, 2, true);

        TaskResult result = run(40, () -> {
            field.zombie(4, 2, false);
            field.attackers.add(new CombatSenses.Attacker(new UUID(0, 4), "minecraft:zombie", tick, "mob_attack"));
        });

        assertNotNull(result);
        assertTrue(field.struck.contains(4), field.struck.toString());
    }
}
