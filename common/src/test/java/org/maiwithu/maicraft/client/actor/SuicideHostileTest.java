// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.suicide.SuicideHazards;
import org.maiwithu.maicraft.core.task.suicide.SuicideRequest;
import org.maiwithu.maicraft.core.task.suicide.SuicideTask;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskRecord;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import org.maiwithu.maicraft.task.TaskState;

/** 主动接近真敌对类别并保留挨打证据；寻死期间不还手，取消后现有自卫仍能接管。 */
public final class SuicideHostileTest {
    public static void main(String[] args) throws Exception {
        try (var fixture = new CombatThreatsTest.Fixture()) {
            var world = fixture.h; SuicideTaskTest.prepare(world);
            world.position(new Vec3(8.5, 1, 3.5));
            var mob = fixture.mob(11, 10.5);
            ActorControlTestHarness.field(Entity.class, "uuid").set(mob, UUID.randomUUID());
            var request = new SuicideRequest("hostile", 4, 10, true);
            var survey = new SuicideHazards(world.player, request); survey.scan();
            var candidate = survey.choose(world.player, Set.of());
            check(candidate != null && candidate.entityUuid().equals(mob.getUUID()), "靠怪必须绑定活体身份");
            var task = new SuicideTask(world.player, new SuicideTaskRecord("hostile-test", request));
            check(task.tick(world.player) == TaskState.RUNNING, "靠怪不能在受伤前就冒称死亡");
            fixture.hit(null, mob);
            var defense = new MobDefenseChain();
            check(defense.canRun(world.player), "实际挨打仍应产生可恢复的自卫证据");
            check(TaskSelector.select(List.of(defense), null, task, List.of(), world.player) == task,
                    "主动寻死期间不能因挨打反过来攻击怪物");
            check(world.mode.attacks == 0 && world.itemUses() == 0, "靠怪动作不得攻击、举盾或自动进食");
            task.stop(world.player, Task.StopReason.REPLACED);
            check(TaskSelector.select(List.of(defense), null, task, List.of(), world.player) == defense,
                    "取消寻死后原有自卫应立即恢复");
            ActorControlTestHarness.field(Entity.class, "uuid").set(mob, UUID.randomUUID());
            check(candidate.destination(world.player) == null, "实体编号复用不能继续追逐另一只怪");
        }
        System.out.println("SuicideHostileTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
