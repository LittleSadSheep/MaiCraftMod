package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.maiwithu.maicraft.core.combat.AttackPlan;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.combat.RetreatProgress;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.invoke;

public final class CombatOutcomeTest {
    public static void main(String[] args) throws Exception {
        lostIsNotDead();
        loadedCrystalReceiptStillWorks(true); loadedCrystalReceiptStillWorks(false);
        partialIsNotSuccess();
        selectableLoadout();
        retreatRequiresMovement();
        minorRecoveryDoesNotReverseWithdrawal();
        asynchronousStockIsNotCombatLoot();
        retreatPrecedesLootSettlement();
        retreatAcceptsAnyReachableSafeDirection();
        retreatCameraCannotStarveMeleeAim();
        meleeApproachUsesThreeDimensionalRange();
        System.out.println("CombatOutcomeTest: disappearance, partial completion, loadout and retreat passed");
    }

    private static void meleeApproachUsesThreeDimensionalRange() throws Exception {
        // 水平三格但低两格时仍超出近战距离；旧水平环会误报到达，角色只能站在楼下挨箭。
        var focus = new Vec3(.5, 3, .5);
        NavGoal range = NavGoal.distanceBand(focus, 2.02, 3.3);
        var below = new BlockPos(3, 1, 0); var aligned = new BlockPos(3, 3, 0);
        check(NavGoal.ring(BlockPos.containing(focus), 2.02, 3.3).isAt(below) && !range.isAt(below),
                "horizontal proximity cannot admit an out-of-range lower floor");
        check(range.isAt(aligned) && range.heuristic(below) > range.heuristic(aligned),
                "the search keeps approaching until a reachable three-dimensional band is entered");
        NavGoal fractional = NavGoal.distanceBand(new Vec3(.01, 1, .5), 2.02, 3.3);
        check(!fractional.isAt(new BlockPos(3, 1, 0)), "target block rounding cannot add hidden melee range");
        check(!fractional.semanticFingerprint().equals(NavGoal.distanceBand(new Vec3(.9, 1, .5), 2.02, 3.3).semanticFingerprint()),
                "moving inside one block still updates the precise combat goal");
        check(NavGoal.distanceBand(focus, 4, 3.3).isAt(new BlockPos(0, 3, 0)),
                "an impossible no-damage inner band still permits approaching a larger enemy");
        // 直接核对战斗任务选择的目标，避免只测试几何辅助类而执行入口仍沿用旧水平环。
        try (var f = new CombatThreatsTest.Fixture()) {
            var mob = f.mob(11, 3.5);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("vertical-melee", 1000, List.of(11), false));
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, mob);
            NavGoal actual = (NavGoal) invoke(task, "standoffGoal");
            check(actual.isAt(new BlockPos(0, 1, 3)) && !actual.isAt(new BlockPos(0, -1, 3)),
                    "the live melee approach uses both the target height and precise position");
        }
    }

    private static void lostIsNotDead() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var mob = f.mob(11, 2);
            var record = new AttackTaskRecord("lost", 1000, List.of(11), false);
            var task = new AttackCompanionTask(f.h.player, record); task.start(f.h.player);
            invoke(task, "settleFinishedTargets"); record.strike(11); f.h.level.entities.remove(11);
            invoke(task, "settleFinishedTargets");
            check(record.defeated().isEmpty() && record.lost().contains(11),
                    "a swing or fired arrow cannot prove that a disappeared living target died");
            check(invoke(task, "finish") == TaskState.FAILED, "lost target must fail the exact goal");
            task.result(TaskState.FAILED);
            var replaced = new AttackTaskRecord("identity", 1000, List.of(12), false);
            var other = new AttackCompanionTask(f.h.player, replaced);
            f.mob(12, 2); invoke(other, "settleFinishedTargets");
            f.mob(12, 2).dead = true; invoke(other, "settleFinishedTargets");
            check(replaced.lost().contains(12) && replaced.defeated().isEmpty(), "reused ids cannot inherit a death");
        }
    }

    private static void partialIsNotSuccess() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var record = new AttackTaskRecord("partial", 1000, List.of(11, 12), false);
            record.defeated(11); record.defeated(99); record.lost(12);
            var task = new AttackCompanionTask(f.h.player, record);
            check(invoke(task, "finish") == TaskState.FAILED, "one requested kill and an extra kill are not two requested kills");
            var result = task.result(TaskState.FAILED);
            check("partial".equals(result.data().get("completion"))
                    && ((Number) result.data().get("remaining_requested_targets")).intValue() == 1,
                    "callers receive structured partial progress");
            var complete = new AttackTaskRecord("complete", 1000, List.of(11, 12), false);
            complete.defeated(11); complete.defeated(12);
            var done = new AttackCompanionTask(f.h.player, complete);
            check(invoke(done, "finish") == TaskState.SUCCESS, "all confirmed targets still succeed");
            done.result(TaskState.SUCCESS);
        }
    }

    private static void loadedCrystalReceiptStillWorks(boolean loaded) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var crystal = f.h.h.allocate(EndCrystal.class);
            crystal.setId(13);
            ActorControlTestHarness.field(crystal.getClass(), "position").set(crystal, new Vec3(3, 1, 3));
            ActorControlTestHarness.field(crystal.getClass(), "blockPosition").set(crystal, new BlockPos(loaded ? 3 : 33, 1, 3));
            f.h.level.entities.put(13, crystal);
            var record = new AttackTaskRecord("crystal", 1000, List.of(13), false, true);
            var task = new AttackCompanionTask(f.h.player, record);
            invoke(task, "settleFinishedTargets"); record.strike(13); f.h.level.entities.remove(13);
            invoke(task, "settleFinishedTargets");
            check(record.defeated().contains(13) == loaded && record.lost().contains(13) != loaded,
                    "strict crystal removal requires both an attack receipt and a still-loaded observation cell");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void selectableLoadout() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.getAbilities().instabuild = true; // 此夹具中，创造模式玩家自带箭矢，不会读取物品标签。
            var charged = new ItemStack(Items.CROSSBOW);
            charged.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            f.h.inventory.setItem(40, charged);
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.inventory.setItem(1, new ItemStack(Items.ARROW));
            check(Loadout.forTarget(f.h.player, f.h.player).ranged().slot() == 0,
                    "a charged offhand crossbow must not shadow an available main-inventory bow");
            f.h.inventory.setItem(0, ItemStack.EMPTY);
            check(!Loadout.forTarget(f.h.player, f.h.player).hasRanged(), "unsupported slots cannot advertise a usable ranged weapon");
        }
    }

    private static void retreatRequiresMovement() {
        var progress = new RetreatProgress(); progress.observe(Vec3.ZERO);
        for (int attempt = 0; attempt < 3; attempt++) {
            for (int tick = 0; tick < 10; tick++) progress.observe(Vec3.ZERO);
            progress.failed();
        }
        check(progress.failures() == 3, "waiting for three failed searches cannot reset cornered detection");
        progress.observe(new Vec3(.1, 0, 0));
        check(progress.failures() == 3, "position jitter is not a successful retreat");
        progress.observe(new Vec3(2, 0, 0));
        check(progress.failures() == 0, "physical progress renews retreat attempts");
    }

    // 实机曾在生命八点撤离、回到九点就重新追击；还没走出威胁圈时，回血和爆炸避险都不能丢失撤离承诺。
    private static void minorRecoveryDoesNotReverseWithdrawal() {
        var foe = new Battlefield.Foe(11, 10.4, false, false, true, true, true, false, false);
        var progress = new RetreatProgress(); progress.commit(); progress.observe(Vec3.ZERO);
        var recovering = new Battlefield(33, 9, 3.3, true, false, false, List.of(foe));
        var flee = new AttackPlan.Move(AttackPlan.Action.DISENGAGE, AttackPlan.NO_FOE);
        check(AttackPlan.decide(recovering, flee, progress.committed()).action() == AttackPlan.Action.DISENGAGE,
                "minor natural healing must not turn a withdrawing body back toward its pursuer");
        progress.observe(new Vec3(12, 0, 0));
        check(progress.committed(), "real forward movement resets failures but preserves withdrawal");
        var creeper = new Battlefield.Foe(12, 3, true, true, true, true, true, true, true);
        var blast = AttackPlan.decide(new Battlefield(33, 9, 3.3, true, false, false, List.of(creeper)), flee, progress.committed());
        check(blast.action() == AttackPlan.Action.EVADE_BLAST
                && AttackPlan.decide(recovering, blast, progress.committed()).action() == AttackPlan.Action.DISENGAGE,
                "an immediate blast overrides the movement without cancelling the ongoing retreat");
        check(AttackPlan.decide(new Battlefield(33, 9, 3.3, true, false, true, List.of(foe)), flee, true).action() == AttackPlan.Action.SKIRMISH,
                "verified repeated path failures must still permit cornered self-defense");
        progress.complete();
        check(!progress.committed() && AttackPlan.decide(recovering, flee, progress.committed()).action() == AttackPlan.Action.SKIRMISH,
                "confirmed separation releases the commitment for the next encounter");
    }

    // 回放防卫抢占取物时延迟到达的石英：库存增加是真实事实，但没有任何死亡掉落归属就不能算战利品。
    private static void asynchronousStockIsNotCombatLoot() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("stock-during-defense", 1000, List.of(), true));
            task.start(f.h.player);
            f.h.inventory.setItem(8, new ItemStack(Items.QUARTZ, 3));
            var result = task.result(TaskState.SUCCESS);
            check(result.data().get("loot_gained").equals(Map.of()) && !result.message().contains("quartz"),
                    "a late storage transfer cannot become loot from zero defeated hostiles");
            check(f.h.inventory.getItem(8).getCount() == 3, "correcting attribution never removes the actual acquired stock");
        }
    }
    private static void retreatPrecedesLootSettlement() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            // 正在低血量撤离时，近圈临时没有敌人也要完成逃生判据，不能先让拾取流程接走导航。
            f.h.player.setHealth(6);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("retreat-loot", 1000, List.of(), true));
            task.start(f.h.player);
            var phase = ActorControlTestHarness.field(AttackCompanionTask.class, "phase");
            phase.set(task, Arrays.stream(phase.getType().getEnumConstants()).filter(v -> v.toString().equals("LOOT")).findFirst().orElseThrow());
            ActorControlTestHarness.field(AttackCompanionTask.class, "lastMove").set(task, new AttackPlan.Move(AttackPlan.Action.DISENGAGE, AttackPlan.NO_FOE));
            TaskState state = task.tick(f.h.player);
            check(state == TaskState.FAILED && task.result(state).message().contains("too hurt"),
                    "retreat settles through the wide-area safety check before any loot-only completion or failure");
        }
    }
    private static void retreatAcceptsAnyReachableSafeDirection() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.mob(11, 2);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("directional-retreat", 1000, List.of(), true));
            // 左右两侧的真实安全位置都能作为逃生出口，敌人身旁则不能宣称已脱离。
            var goal = (NavGoal) invoke(task, "retreatGoal");
            check(goal instanceof NavGoal.Avoid && goal.isAt(new BlockPos(-40, 1, 3)) && goal.isAt(new BlockPos(40, 1, 3))
                    && !goal.isAt(new BlockPos(3, 1, 3)), "retreat goal describes safety rather than one randomly sampled endpoint");
        }
    }

    private static void retreatCameraCannotStarveMeleeAim() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var mob = f.mob(11,2);
            var action = Interaction.attackEntity(f.h.player,mob);
            var task = new AttackCompanionTask(f.h.player,new AttackTaskRecord("moving-retaliation",1000,List.of(11),false));
            ActorControlTestHarness.field(AttackCompanionTask.class,"meleeAction").set(task,action);
            var finishMovement = AttackCompanionTask.class.getDeclaredMethod("afterCombatMovement",TaskState.class);
            finishMovement.setAccessible(true);
            // 回放“先瞄准，再由逃跑导航朝反方向看”的实际顺序；动作必须最终通过真实射线提交，且续瞄不清掉移动。
            for(int tick=0;tick<80 && f.h.mode.attacks==0;tick++) {
                action.tick();
                InputDriver.applyMovement(f.h.player,1,0,false,false,true);
                InputDriver.look(f.h.player,90,8);
                var before = ActorControlTestHarness.field(DefaultBodyControlPort.class,"movement").get(f.h.h.body);
                check(finishMovement.invoke(task,TaskState.RUNNING)==TaskState.RUNNING,"aim renewal cannot finish combat");
                check(before.equals(ActorControlTestHarness.field(DefaultBodyControlPort.class,"movement").get(f.h.h.body)),
                        "renewing attack aim preserves navigation's movement input");
                ActorControlTestHarness.field(DefaultBodyControlPort.class,"lastLookUpdateNanos")
                        .setLong(f.h.h.body,System.nanoTime()-50_000_000L);
                f.h.h.body.endTick(f.h.h.context); f.h.nextTick();
            }
            check(f.h.mode.attacks==1,"moving-camera requests cannot indefinitely starve a reachable native melee attack");
            check(f.h.blockUses()==0 && f.h.itemUses()==0,"retaliation sends no block or item use");
            task.result(TaskState.CANCELLED);
        }
    }
}
