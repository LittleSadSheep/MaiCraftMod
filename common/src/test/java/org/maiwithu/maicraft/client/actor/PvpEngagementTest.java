package org.maiwithu.maicraft.client.actor;

import java.util.List;
import org.maiwithu.maicraft.core.combat.PvpEngagement;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 玩家名单仅在这一次任务与身体有效，打怪和结束后的来袭不能自动变成 PVP。 */
public final class PvpEngagementTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var opponent = PvpTestPlayers.create(f, 21, 2);
            var stranger = PvpTestPlayers.create(f, 22, 5);
            var record = new AttackTaskRecord("pvp", 1000, List.of(21), false);
            var task = new AttackCompanionTask(f.h.player, record); task.start(f.h.player);
            // 点名后只允许同一个玩家还击；附近陌生人没有从这份许可中获益。
            check(PvpEngagement.accepts(f.h.player, opponent) && !PvpEngagement.accepts(f.h.player, stranger), "只接受点名对手");
            task.stop(f.h.player, Task.StopReason.PREEMPTED);
            check(PvpEngagement.accepts(f.h.player, opponent), "临时避险不把对手还击误判为停工请求");
            // 对手重生或编号复用时需要新的点名，不能向后来出现的实体继续挥刀。
            PvpTestPlayers.create(f, 21, 2);
            check(PvpEngagement.opponents(f.h.player).isEmpty(), "编号复用终止旧对战身份");
            f.h.level.entities.put(21, opponent); opponent.health = 0;
            check(!PvpEngagement.accepts(f.h.player, opponent), "死亡对手不保留对战许可");
            opponent.health = 20; task.result(TaskState.CANCELLED);
            check(!PvpEngagement.accepts(f.h.player, opponent), "任务取消后撤销许可");
            var automatic = new AttackCompanionTask(f.h.player, new AttackTaskRecord("defense", 1000, List.of(), true));
            automatic.start(f.h.player);
            check(!PvpEngagement.accepts(f.h.player, stranger), "自动自卫不授权攻击陌生玩家");
            automatic.result(TaskState.CANCELLED);
        } finally { PvpEngagement.clear(); }
        System.out.println("PvpEngagementTest: 玩家对战身份与生命周期通过");
    }
}
