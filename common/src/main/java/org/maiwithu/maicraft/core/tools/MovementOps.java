package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/**
 * 把内部移动参数整理成任务单，不在这里寻找路径或控制玩家。初始期限按正常游戏速度约三十秒，执行器会按距离和进展续期。
 */
public final class MovementOps {

    /** Base budget: 30 seconds at vanilla 20 tps (the goal extends it by distance at runtime). */
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
        // MoveToTaskRecord validates the x/y/z/block combination, throwing a
        // teaching error for an ambiguous one (e.g. only x given, or block
        // combined with coordinates).
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS),
                x, y, z, block, Boolean.TRUE.equals(mayAlterTerrain), Boolean.TRUE.equals(allowWaterBucketFall),
                TransportMode.parse(transportMode), Boolean.TRUE.equals(allowLandingAssists));
    }

    /** Public travel keeps any supplied height as a hint unless exact standing was requested. */
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
