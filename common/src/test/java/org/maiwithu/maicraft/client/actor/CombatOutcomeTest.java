package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.combat.RetreatProgress;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.invoke;

public final class CombatOutcomeTest {
    public static void main(String[] args) throws Exception {
        lostIsNotDead();
        loadedCrystalReceiptStillWorks();
        partialIsNotSuccess();
        selectableLoadout();
        retreatRequiresMovement();
        System.out.println("CombatOutcomeTest: disappearance, partial completion, loadout and retreat passed");
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

    private static void loadedCrystalReceiptStillWorks() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var crystal = f.h.h.allocate(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class);
            crystal.setId(13);
            ActorControlTestHarness.field(crystal.getClass(), "position").set(crystal, new Vec3(3, 1, 3));
            ActorControlTestHarness.field(crystal.getClass(), "blockPosition").set(crystal, new net.minecraft.core.BlockPos(3, 1, 3));
            f.h.level.entities.put(13, crystal);
            var record = new AttackTaskRecord("crystal", 1000, List.of(13), false, true);
            var task = new AttackCompanionTask(f.h.player, record);
            invoke(task, "settleFinishedTargets"); record.strike(13); f.h.level.entities.remove(13);
            invoke(task, "settleFinishedTargets");
            check(record.defeated().contains(13), "strict crystal removal retains the existing loaded-position destruction receipt");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void selectableLoadout() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.getAbilities().instabuild = true; // Creative players supply arrows without loading item tags in this fixture.
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
}
