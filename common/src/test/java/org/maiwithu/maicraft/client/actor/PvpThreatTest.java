package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.combat.PvpEngagement;
import org.maiwithu.maicraft.core.combat.RetreatThreats;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.invoke;

/** 对手入场 -> 近战警戒 -> 遭远程还击继续撤离 -> 确认胜负后收场。 */
public final class PvpThreatTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var opponent = PvpTestPlayers.create(f, 21, 2);
            var bystander = PvpTestPlayers.create(f, 22, 3);
            var record = new AttackTaskRecord("duel-threats", 1000, List.of(21), false);
            var task = new AttackCompanionTask(f.h.player, record); task.start(f.h.player);
            check(Menace.rawDangerRadius(opponent, f.h.player) >= 3 && !Menace.threatens(bystander, f.h.player),
                    "对手使用玩家攻击范围，旁观者不加入危险集合");
            var field = MobDefenseDamageTest.survey(task);
            check(field.byId(21).engaging() && field.byId(21).authorized() && field.byId(22) == null,
                    "对手进入战斗快照并具备持续威胁事实");
            check(new RetreatThreats().observe(f.h.player, 40, 14).contains(opponent), "未挨第一刀也要为已交战玩家准备退路");
            PvpTestPlayers.position(opponent, new Vec3(50, 1, 3.5)); f.hit(null, opponent);
            check((Boolean) invoke(task, "retreatThreatsPresent"), "远处刚命中的玩家射手不能被判为已经脱离");
            f.h.level.time += 201;
            check(!(Boolean) invoke(task, "retreatThreatsPresent"), "拉开距离且没有新伤害后可以结束撤离");
            // 命中只接收本地身体造成的新伤害，第三方玩家的攻击不能冒充本次对战的输出。
            var hit = new ClientboundDamageEventPacket(opponent, new DamageSource(CombatThreatsTest.DAMAGE, f.h.player));
            CombatThreats.damaged(f.h.player, new ClientboundDamageEventPacket(opponent, new DamageSource(CombatThreatsTest.DAMAGE, bystander)));
            check(PvpEngagement.hitRevision(f.h.player, opponent) == 0, "不把旁观者造成的伤害计成本方命中");
            CombatThreats.damaged(f.h.player, hit);
            check(PvpEngagement.hitRevision(f.h.player, opponent) == 1, "对手生命未同步也能通过原生伤害包确认本方命中");
            invoke(task, "settleFinishedTargets"); opponent.health = 0; invoke(task, "settleFinishedTargets");
            check(record.defeated().contains(21) && MobDefenseDamageTest.field(task, "phase").toString().equals("COMBAT"),
                    "确认玩家死亡后结束对战，不追逐玩家背包掉落");
            task.result(TaskState.SUCCESS);
        } finally { PvpEngagement.clear(); }
        System.out.println("PvpThreatTest: 玩家威胁、撤离和伤害来源通过");
    }
}
