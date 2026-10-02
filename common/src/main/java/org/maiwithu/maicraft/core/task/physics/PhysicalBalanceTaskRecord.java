package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceParameters;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 任务单保留主人选定的局部补丁；查询、试算和推荐绝不会自行进入施工阶段。 */
public final class PhysicalBalanceTaskRecord extends TaskRecord {
    static { TaskFactory.register(PhysicalBalanceTaskRecord.class,PhysicalBalanceTask::new); }
    public final PhysicsBalanceParameters parameters;
    public PhysicalBalanceTaskRecord(String callId,long deadline,JsonObject parameters) {
        super("physical_balance",callId,deadline); this.parameters=PhysicsBalanceParameters.parse(parameters);
    }
    @Override public String describe() { return "起飞前受力分析与配平："+parameters.operation(); }
}
