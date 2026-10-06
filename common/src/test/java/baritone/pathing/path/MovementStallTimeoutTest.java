package baritone.pathing.path;

import org.maiwithu.maicraft.core.pathing.baritone.MovementStall;

// 单步超时沿用原有“预计耗时加宽限”；被撞回清零单步计时后，最远一步长期没越过也要判为卡住，正常推进不误判。
public final class MovementStallTimeoutTest {
    public static void main(String[] args) {
        expect(PathExecutor.timeoutCause(104, 4, 104, 4, 100) == null, "within the allowance is not a stall");
        expect(PathExecutor.timeoutCause(105, 4, 105, 4, 100) == MovementStall.Cause.TIMEOUT,
                "the current movement times out first when it is also the furthest");
        // 被撞回上一步后单步计时只有 30 刻，但最远那一步已经 150 刻没被越过。
        expect(PathExecutor.timeoutCause(30, 4, 150, 4, 100) == MovementStall.Cause.NO_ADVANCE,
                "knockback loops that reset the movement timer must still stall");
        expect(PathExecutor.timeoutCause(30, 4, 90, 4, 100) == null, "recent knockback is not yet a stall");
        // 预计耗时长的挖掘步骤按自己的估计放宽，不被短步的时限误杀。
        expect(PathExecutor.timeoutCause(30, 4, 300, 240, 100) == null, "slow planned movements keep their own estimate");
        System.out.println("MovementStallTimeoutTest: passed");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
