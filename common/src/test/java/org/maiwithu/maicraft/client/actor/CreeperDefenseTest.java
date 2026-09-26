package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.combat.AttackPlan;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.pathing.goals.GoalAvoidEntities;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.field;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.survey;

/** 不伪造服务端攻击目标：用客户端可见距离、遮挡与引信变化验证主动警戒和撤离优先级。 */
public final class CreeperDefenseTest {
    public static void main(String[] args) throws Exception {
        visibleCreeperTriggersBeforeDamage();
        fuseOverridesTargetsAndPendingStrike();
        terminalAndUnauthorizedCreepersStillRequireEvasion();
        chargedBlastAndDefuseClearance();
        System.out.println("CreeperDefenseTest: proactive warning, priority and fuse evasion passed");
    }

    private static void visibleCreeperTriggersBeforeDamage() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var creeper = creeper(f, 11, 4.5);
            var defense = new MobDefenseChain();
            check(creeper.getTarget() == null && defense.canRun(f.h.player),
                    "a visible nearby creeper interrupts work before damage or server target synchronization");
            // 隔墙且尚未膨胀不主动开战，避免为了追怪拆穿建筑；开始膨胀后则保守避险。
            for (int y = 1; y <= 3; y++) f.h.set(new BlockPos(2, y, 3), Blocks.STONE.defaultBlockState());
            check(!defense.canRun(f.h.player), "an occluded idle creeper does not initiate pursuit");
            creeper.swell = 1;
            check(defense.canRun(f.h.player), "a nearby fuse remains dangerous even behind an obstruction");
            creeper.swell = 0;
            for (int y = 1; y <= 3; y++) f.h.set(new BlockPos(2, y, 3), Blocks.AIR.defaultBlockState());
            var shooter = f.mob(12, 6); f.hit(null, shooter);
            var task = MobDefenseDamageTest.fight(f);
            var battlefield = equipped(survey(task));
            check(battlefield.byId(11).authorized() && AttackPlan.decide(battlefield,
                    new AttackPlan.Move(AttackPlan.Action.SKIRMISH, 12)).foeId() == 11,
                    "a nearby creeper takes priority over a retained ordinary attacker");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void fuseOverridesTargetsAndPendingStrike() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var creeper = creeper(f, 11, 2);
            var task = MobDefenseDamageTest.fight(f);
            Method weapon = AttackCompanionTask.class.getDeclaredMethod("tickWeapon", Battlefield.class);
            weapon.setAccessible(true); weapon.invoke(task, survey(task));
            check(field(task, "meleeAction") != null, "an unfused authorized creeper can be attacked");
            creeper.swell = 1;
            weapon.invoke(task, survey(task));
            check(field(task, "meleeAction") == null && f.h.mode.attacks == 0,
                    "a newly observed fuse cancels a not-yet-submitted melee attack");
            // 战斗夹具没有完整渲染客户端；这里验证决策与实际待发刀取消，路线执行另由导航回归及实机覆盖。
            check(AttackPlan.decide(equipped(survey(task)), null).action() == AttackPlan.Action.EVADE_BLAST,
                    "an active fuse selects evasion even when both melee and ranged weapons are available");
            creeper.swell = -1; creeper.remaining = .3F;
            check(AttackPlan.decide(equipped(survey(task)), null).action() == AttackPlan.Action.EVADE_BLAST,
                    "a shrinking but not fully reset fuse must not invite an immediate melee approach");
            creeper.remaining = 0;
            check(AttackPlan.decide(equipped(survey(task)), null).foeId() == 11,
                    "ordinary combat can resume after the observed swelling fully resets");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void terminalAndUnauthorizedCreepersStillRequireEvasion() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var creeper = creeper(f, 11, 4.5); creeper.swell = 1; f.mob(12, 5);
            var record = new AttackTaskRecord("strict-blast", 1000, List.of(12), false, true);
            record.unreachable(11);
            var task = new AttackCompanionTask(f.h.player, record);
            var battlefield = survey(task);
            check(battlefield.byId(11) != null && !battlefield.byId(11).authorized()
                    && AttackPlan.decide(equipped(battlefield), new AttackPlan.Move(AttackPlan.Action.BOW, 12))
                    .action() == AttackPlan.Action.EVADE_BLAST,
                    "terminal bookkeeping and strict damage authorization never suppress blast evasion");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void chargedBlastAndDefuseClearance() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var creeper = creeper(f, 11, 4.5); creeper.swell = 1;
            var threat = Menace.field(f.h.player, List.of(creeper)).getFirst();
            var goal = new GoalAvoidEntities(Menace.AVOID_PENALTY, threat);
            check(threat.clearance() > 7 && !goal.isInGoal(11, 1, 3) && goal.isInGoal(12, 1, 3),
                    "the flee endpoint exceeds vanilla's seven-block defuse threshold with cell margin");
            f.h.level.entities.clear();
            creeper = creeper(f, 12, 12.9); creeper.swell = 1; creeper.powered = true;
            check(new MobDefenseChain().canRun(f.h.player) && Menace.rawDangerRadius(creeper, f.h.player) == 12,
                    "charged blast danger beyond twelve blocks is still scanned with its safety margin");
        }
    }

    private static Battlefield equipped(Battlefield b) {
        return new Battlefield(b.effectiveHealth(), b.meleeReach(), true, true, false, b.foes());
    }
    private static TestCreeper creeper(CombatThreatsTest.Fixture f, int id, double x) throws Exception {
        return f.mob(TestCreeper.class, EntityType.CREEPER, id, x);
    }
    // 只替代同步过来的引信和充能字段；距离、视线、危险扫描和任务选敌继续运行生产实现。
    static final class TestCreeper extends Creeper {
        int swell; boolean powered; float remaining;
        private TestCreeper() { super(EntityType.CREEPER, null); }
        @Override public float getHealth() { return 20; }
        @Override public int getSwellDir() { return swell; }
        @Override public float getSwelling(float partialTick) { return remaining; }
        @Override public boolean isIgnited() { return false; }
        @Override public boolean isPowered() { return powered; }
    }
}
