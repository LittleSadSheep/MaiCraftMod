package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.combat.PvpTactics;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 原生攻击不会因为看见玩家就成功；必须有合法距离、实际准星、攻击充能和新的本方伤害证据。 */
public final class PvpMeleeTest {
    public static void main(String[] args) throws Exception {
        repeatedNativeStrikes();
        rayAndReachRemainAuthoritative();
        System.out.println("PvpMeleeTest: 持续原生出刀、伤害确认、距离和遮挡通过");
    }

    private static void repeatedNativeStrikes() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var other = PvpTestPlayers.create(f, 21, 2);
            f.h.inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            f.h.player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(1.6);
            var record = new AttackTaskRecord("pvp-melee", 1000, List.of(21), false);
            var task = new AttackCompanionTask(f.h.player, record); task.start(f.h.player); f.h.nextTick();
            weapon(task); f.h.nextTick(); weapon(task);
            check(f.h.mode.attacks == 0, "未对准时不能提交攻击");
            aim(f, other); f.h.nextTick(); weapon(task);
            check(f.h.mode.attacks == 1, "准星命中且武器稳定后提交第一刀");
            long first = f.h.level.getGameTime();
            // 模拟服务器只发送伤害来源而不公开远端玩家血量，仍能确认这次本方命中。
            CombatThreats.damaged(f.h.player, new ClientboundDamageEventPacket(other,
                    new DamageSource(CombatThreatsTest.DAMAGE, f.h.player)));
            f.h.nextTick(); weapon(task);
            check(record.strikes(21) == 1 && other.getHealth() == 20, "使用伤害事件确认命中，不依赖对方血条");
            while (f.h.level.getGameTime() < first + 12) {
                weapon(task); check(f.h.mode.attacks == 1, "确认很快也不能绕过剑的间隔"); f.h.nextTick();
            }
            weapon(task); f.h.nextTick(); weapon(task);
            check(f.h.mode.attacks == 2, "间隔结束后继续第二刀，任务没有因一次命中而停下");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void rayAndReachRemainAuthoritative() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var other = PvpTestPlayers.create(f, 21, 3.9);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("pvp-ray", 1000, List.of(21), false));
            task.start(f.h.player); f.h.nextTick();
            var attack = Interaction.attackEntity(f.h.player, other); aim(f, other); attack.tick();
            check(f.h.mode.attacks == 0, "不能使用方块交互的四点五格射程攻击玩家");
            // 进入射程后仍保留原生遮挡检查；只有移除真实遮挡并重新对准，才允许出刀。
            PvpTestPlayers.position(other, new Vec3(3, 1, 3.5));
            BlockPos wall = new BlockPos(1, 2, 3); f.h.set(wall, Blocks.STONE.defaultBlockState());
            f.h.nextTick(); aim(f, other); attack.tick();
            check(f.h.mode.attacks == 0, "近距离也不能隔墙攻击");
            f.h.set(wall, Blocks.AIR.defaultBlockState()); f.h.nextTick(); attack.tick();
            check(f.h.mode.attacks == 1, "射程和视线均满足时恢复原生攻击");
            attack.stop(); task.result(TaskState.CANCELLED);
        }
    }

    private static void weapon(AttackCompanionTask task) throws Exception {
        // 推进真实战斗手部流程，导航与距离环在战术回归中单独验证，避免替身虚构寻路成功。
        var method = AttackCompanionTask.class.getDeclaredMethod("tickWeapon", Battlefield.class); method.setAccessible(true);
        method.invoke(task, MobDefenseDamageTest.survey(task));
    }

    private static void aim(CombatThreatsTest.Fixture f, PvpTestPlayers.Opponent other) {
        // 同步测试中的实际视角，生产出刀仍需用该视角重做射线，而不是直接命中预想目标。
        Vec3 direction = PvpTactics.aimPoint(other).subtract(f.h.player.getEyePosition());
        f.h.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        f.h.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance())));
    }
}
