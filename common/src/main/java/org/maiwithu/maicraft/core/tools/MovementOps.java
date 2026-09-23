package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/**
 * 把内部移动参数整理成任务单，不在这里寻找路径或控制玩家。初始期限按正常游戏速度约三十秒，执行器会按距离和进展续期。
 */
public final class MovementOps {

    /** 基础预算按原版 20 tps 计算为 30 秒；运行时会依据目标距离延长。 */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;

    /**
     * @param mayAlterTerrain 模型是否授权路上挖/放方块;null 即 false——不说就是不许
     */
    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             ToolContext ctx) {
        return moveTo(x, y, z, block, mayAlterTerrain, false, ctx);
    }

    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             Boolean allowWaterBucketFall, ToolContext ctx) {
        return moveTo(x, y, z, block, mayAlterTerrain, allowWaterBucketFall, null, ctx);
    }

    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             Boolean allowWaterBucketFall, String transportMode, ToolContext ctx) {
        return moveTo(x, y, z, block, mayAlterTerrain, allowWaterBucketFall, transportMode, false, ctx);
    }

    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             Boolean allowWaterBucketFall, String transportMode, Boolean allowLandingAssists, ToolContext ctx) {
        // MoveToTaskRecord 会验证 x/y/z 与方块目标的组合；若参数有歧义（例如只提供 x，或同时提供方块和坐标），就返回教学性错误。
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS),
                x, y, z, block, Boolean.TRUE.equals(mayAlterTerrain), Boolean.TRUE.equals(allowWaterBucketFall),
                TransportMode.parse(transportMode), Boolean.TRUE.equals(allowLandingAssists));
    }

    /** 除非明确要求精确站位，公开移动任务会将提供的高度仅作为参考。 */
    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             Boolean allowWaterBucketFall, String transportMode, Boolean allowLandingAssists,
                             Boolean exact, Double horizontalRadius, Double verticalTolerance, ToolContext ctx) {
        if (Boolean.TRUE.equals(exact) && (x == null || y == null || z == null))
            throw new IllegalArgumentException("exact=true requires x, y and z; destination height cannot be guessed");
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS),
                x, y, z, block, Boolean.TRUE.equals(mayAlterTerrain), Boolean.TRUE.equals(allowWaterBucketFall),
                TransportMode.parse(transportMode), Boolean.TRUE.equals(allowLandingAssists), Boolean.TRUE.equals(exact),
                horizontalRadius == null ? 3 : horizontalRadius, verticalTolerance == null ? 2 : verticalTolerance);
    }
}
