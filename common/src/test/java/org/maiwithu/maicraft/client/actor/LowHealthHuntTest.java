package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.AttackPlan;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 低血缺粮时允许原生猎食；敌对目标和途中实际来袭的生物仍走原有撤离逻辑。 */
public final class LowHealthHuntTest {
    public static void main(String[] args) throws Exception {
        for (float health : new float[]{1, 4, 8}) {
            huntSheep(health, false);
            huntSheep(health, true);
        }
        hostileTargetsStillRetreat();
        ambushStillInterruptsHunting();
        retaliatingNeutralIsAThreat();
        passivePigAndChickenRemainHuntable();
        refusalNoticeDisclosesThresholdAndRelease();
        // 周围没有威胁时，低血本身不制造一次失败的“撤离”，也不阻止安全收尾。
        check(AttackPlan.decide(new Battlefield(4, 3, false, false, false, List.of()), null).action()
                == AttackPlan.Action.DONE, "an empty battlefield does not reject work solely for low health");
        System.out.println("LowHealthHuntTest: low-health food hunting and hostile retreat passed");
    }

    private static void huntSheep(float health, boolean acquisition) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.setHealth(health);
            var sheep = f.mob(FoodSheep.class, EntityType.SHEEP, 11, 2);
            // 直接点名攻击和取料器的严格狩猎子任务共用同一验证，不能只修其中一个入口。
            var task = new AttackCompanionTask(f.h.player,
                    new AttackTaskRecord("low-health-hunt", 1000, List.of(11), false, acquisition));
            task.start(f.h.player);
            var plan = AttackPlan.decide(MobDefenseDamageTest.survey(task), null);
            check(plan.action() == AttackPlan.Action.SKIRMISH && plan.foeId() == 11,
                    "a friendly sheep remains huntable at " + health + " health");
            Vec3 aim = sheep.getBoundingBox().getCenter().subtract(f.h.player.getEyePosition());
            f.h.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            f.h.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
            for (int tick = 0; tick < 4 && f.h.mode.attacks == 0; tick++) {
                check(MobDefenseDamageTest.tickCombatOnly(task, f) == TaskState.RUNNING,
                        "low health must not fail the live hunting task before native interaction");
                f.h.nextTick();
            }
            check(f.h.mode.attacks == 1, "the low-health hunt submits the native hit instead of only selecting a sheep");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void hostileTargetsStillRetreat() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.setHealth(4); f.mob(11, 2);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("hostile", 1000, List.of(11), false));
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.DISENGAGE,
                    "an explicitly selected idle zombie still requires health to engage");
            // 疣猪兽虽然继承 Animal，仍是 Enemy；不能把所有动物一概当成可安全猎食的友好生物。
            f.mob(HostileAnimal.class, EntityType.HOGLIN, 11, 2);
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.DISENGAGE,
                    "a hostile animal does not inherit the friendly hunting exemption");
        }
    }

    private static void ambushStillInterruptsHunting() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.setHealth(4); f.mob(FoodSheep.class, EntityType.SHEEP, 11, 2);
            var zombie = f.mob(12, 6);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("hunt-ambush", 1000, List.of(11), false, true));
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.SKIRMISH,
                    "an unrelated idle zombie does not turn the sheep hunt into hostile combat");
            f.hit(zombie, zombie);
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.DISENGAGE,
                    "real incoming damage still interrupts low-health hunting even under strict target authorization");
        }
    }

    private static void retaliatingNeutralIsAThreat() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.setHealth(4);
            var animal = f.mob(FoodCow.class, EntityType.COW, 11, 2);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("neutral-hunt", 1000, List.of(11), false));
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.SKIRMISH,
                    "other friendly food animals share the sheep behavior");
            // 模拟模组赋予原本友好动物的还击：实际受击证据优先于其非 Enemy 类型。
            f.hit(animal, animal);
            check(AttackPlan.decide(MobDefenseDamageTest.survey(task), null).action() == AttackPlan.Action.DISENGAGE,
                    "an observed retaliating animal is no longer treated as harmless");
        }
    }

    /** 拍板 092：威胁度分级要求话术披露具体阈值与解除条件，调用方才知道差多少、该做什么。 */
    private static void refusalNoticeDisclosesThresholdAndRelease() {
        String notice = AttackPlan.lowHealthRefusalNotice();
        check(notice.contains("8.0") && notice.contains("4 hearts"),
                "the refusal notice states the numeric low-health line");
        check(notice.contains("cannot fight back") && notice.contains("any health"),
                "the refusal notice states the passive-animal release condition");
        check(AttackPlan.outmatched(8.0) && !AttackPlan.outmatched(8.1),
                "the disclosed line is the same threshold the decision actually enforces");
    }

    private static void passivePigAndChickenRemainHuntable() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.player.setHealth(1);
            f.mob(FoodPig.class, EntityType.PIG, 11, 2);
            f.mob(FoodChicken.class, EntityType.CHICKEN, 12, 3);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("poultry", 1000, List.of(11, 12), false));
            var plan = AttackPlan.decide(MobDefenseDamageTest.survey(task), null);
            check(plan.action() == AttackPlan.Action.SKIRMISH && plan.foeId() == 11,
                    "passive pigs and chickens stay huntable at one health point");
            task.result(TaskState.CANCELLED);
        }
    }

    private static final class FoodSheep extends Sheep {
        private FoodSheep() { super(EntityType.SHEEP, null); }
        @Override public float getHealth() { return 8; }
    }
    private static final class FoodPig extends net.minecraft.world.entity.animal.Pig {
        private FoodPig() { super(EntityType.PIG, null); }
        @Override public float getHealth() { return 10; }
    }
    private static final class FoodChicken extends net.minecraft.world.entity.animal.Chicken {
        private FoodChicken() { super(EntityType.CHICKEN, null); }
        @Override public float getHealth() { return 4; }
    }
    private static final class FoodCow extends Cow {
        private FoodCow() { super(EntityType.COW, null); }
        @Override public float getHealth() { return 10; }
    }
    private static final class HostileAnimal extends Hoglin {
        private HostileAnimal() { super(EntityType.HOGLIN, null); }
        @Override public float getHealth() { return 40; }
        // 夹具不运行脑活动；这里明确模拟尚未仇恨角色的疣猪兽，Enemy 身份本身仍应限制主动交战。
        @Override public LivingEntity getTarget() { return null; }
    }
}
