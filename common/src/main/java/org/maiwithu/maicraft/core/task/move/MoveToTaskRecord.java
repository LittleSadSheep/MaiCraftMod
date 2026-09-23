package org.maiwithu.maicraft.core.task.move;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/**
 * 移动任务单：给 x/z 就只要求水平位置，给 x/y/z 就再要求高度，只给 y 则改变高度，只给方块名则先找方块。
 * 是否必须准确站到一格由 exact 决定；普通公开移动默认允许附近到达，内部工作站位可使用严格构造方法。
 * 路上能否挖地形、搭路或临时放落地保护物品，各自有独立开关，不能从“要去那里”自动推断。
 */
public final class MoveToTaskRecord extends TaskRecord implements InternalPositionReceipt {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND }

    /** 可空；{@code null} 表示大模型未提供此坐标轴。 */
    public final Double x;
    public final Double y;
    public final Double z;
    /** 要前往最近实例的带命名空间方块 ID；由坐标指定目标时为空。 */
    public final String block;
    public final Kind kind;
    /** 是否授权途中挖掘、搭桥或垫高；临时桶装水另有独立开关。 */
    public final boolean mayAlterTerrain;
    /** 允许使用桶中临时水处理坠落，但不授权挖掘或搭设脚手架。 */
    public final boolean allowWaterBucketFall;
    /** 仅允许使用临时落地物品；与挖掘及搭设脚手架的许可相互独立。 */
    public final boolean allowLandingAssists;
    /** 交通方式偏好与地形修改及临时用水许可相互独立。 */
    public final TransportMode transportMode;
    /** 公开移动任务可接受一个区域；内部工作台或建筑站位仍要求精确到点。 */
    public final boolean exact;
    public final double horizontalRadius;
    public final double verticalTolerance;
    /** 成功的实时身体操作回执；不会复制到公开的 TaskResult。 */
    private Position verifiedPosition;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain) {
        this(toolCallId, deadlineGameTime, x, y, z, block, mayAlterTerrain, false);
    }

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain, boolean allowWaterBucketFall) {
        this(toolCallId, deadlineGameTime, x, y, z, block, mayAlterTerrain, allowWaterBucketFall, TransportMode.AUTO);
    }

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain,
                            boolean allowWaterBucketFall, TransportMode transportMode) {
        this(toolCallId, deadlineGameTime, x, y, z, block, mayAlterTerrain, allowWaterBucketFall, transportMode, false);
    }

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain,
                            boolean allowWaterBucketFall, TransportMode transportMode, boolean allowLandingAssists) {
        // 保留的内部构造入口默认精确；公开 MoveToTool 使用下面的完整参数入口，默认值不同。
        this(toolCallId, deadlineGameTime, x, y, z, block, mayAlterTerrain, allowWaterBucketFall,
                transportMode, allowLandingAssists, true, 0, 0);
    }

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain,
                            boolean allowWaterBucketFall, TransportMode transportMode, boolean allowLandingAssists,
                            boolean exact, double horizontalRadius, double verticalTolerance) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (!Double.isFinite(horizontalRadius) || horizontalRadius < 0
                || !Double.isFinite(verticalTolerance) || verticalTolerance < 0)
            throw new IllegalArgumentException("arrival tolerances must be finite and non-negative");
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.kind = resolveKind(x, y, z, this.block);
        this.mayAlterTerrain = mayAlterTerrain;
        this.allowWaterBucketFall = allowWaterBucketFall;
        this.allowLandingAssists = allowLandingAssists;
        this.transportMode = transportMode == null ? TransportMode.AUTO : transportMode;
        this.exact = exact;
        this.horizontalRadius = horizontalRadius;
        this.verticalTolerance = verticalTolerance;
    }

    /** 内部任务已选好必须站到的位置时使用，例如放方块和操作机器，不接受长途旅行的宽松到达误差。 */
    public static MoveToTaskRecord strictStance(
            String toolCallId, long deadlineGameTime, BlockPos target, boolean mayAlterTerrain) {
        return strictStance(toolCallId, deadlineGameTime, target, mayAlterTerrain, TransportMode.AUTO);
    }

    public static MoveToTaskRecord strictStance(
            String toolCallId, long deadlineGameTime, BlockPos target, boolean mayAlterTerrain, TransportMode transportMode) {
        if (target == null) throw new IllegalArgumentException("strict stance target is required");
        return new MoveToTaskRecord(toolCallId, deadlineGameTime,
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                mayAlterTerrain, false, transportMode);
    }

    boolean requiresStrictStance() {
        return kind == Kind.BLOCK && exact;
    }

    NavGoal coordinateGoal() {
        // 把坐标和误差转换成导航的“哪些位置算到了”；方块搜索先找到真实候选后才有这一目标。
        var target = new BlockPos(x == null ? 0 : (int) Math.floor(x), y == null ? 0 : (int) Math.floor(y),
                z == null ? 0 : (int) Math.floor(z));
        return switch (kind) {
            case BLOCK -> exact ? NavGoal.exact(target)
                    : NavGoal.nearGround(target, horizontalRadius, verticalTolerance);
            case COLUMN -> NavGoal.column(target.getX(), target.getZ(), horizontalRadius);
            case YLEVEL -> NavGoal.yLevel(target.getY());
            case FIND -> throw new IllegalStateException("block discovery supplies its own observed goal");
        };
    }

    void retainVerifiedPosition(Position position) {
        verifiedPosition = position;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    /** 按填写的字段确定移动种类；例如只给 x 或同时给方块名和坐标，会拒绝让调用者改清楚。 */
    private static Kind resolveKind(Double x, Double y, Double z, String block) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (block != null) {
            if (hasX || hasY || hasZ) {
                throw new IllegalArgumentException(
                        "block means 'walk to the nearest one of these' — no coordinates with"
                        + " it. To reach one specific block you know the position of, goto its"
                        + " location (x+z) and interact there.");
            }
            return Kind.FIND;
        }
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "goto needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), y alone (a target height), "
                + "or block alone (walk to the nearest block of that kind). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        // 这是状态面板的简短说明，不是到达证明；实际完成位置要等执行器确认后才保存。
        String where = switch (kind) {
            case BLOCK -> "走向 " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> "走向 x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> "下到 y=" + (int) (double) y;
            case FIND -> "去找 " + block;
        };
        return mayAlterTerrain ? where + "(可开路)" : allowWaterBucketFall ? where + "(可落地水)" : where;
    }
}
