package org.maiwithu.maicraft.core.task.move;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/**
 * Typed task descriptor for the {@code goto} tool. The goal type is chosen
 * by WHICH inputs are supplied: the LLM picks its intent by filling only the
 * fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 *   <li>{@code block} only (no coordinates) → {@link Kind#FIND}:
 *       scan for the nearest block of that kind and walk up beside it,
 *       never touching it.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 *
 * <p>{@code mayAlterTerrain} is the model's explicit consent to dig through, bridge
 * or pillar on the way. Without it the walk never changes a block (see
 * {@code TerrainPermit}); when the only route would, the failure lists exactly which
 * blocks and the model decides whether to re-send with consent.
 */
public final class MoveToTaskRecord extends TaskRecord implements InternalPositionReceipt {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    public final Kind kind;
    /** Consent to dig / bridge / pillar en route; temporary bucket water has a separate flag. */
    public final boolean mayAlterTerrain;
    /** Permit temporary bucket water for a fall without authorizing excavation or scaffolding. */
    public final boolean allowWaterBucketFall;
    /** Temporary landing items only; independent of excavation and scaffolding permission. */
    public final boolean allowLandingAssists;
    /** Transport preference is independent of terrain and temporary water permission. */
    public final TransportMode transportMode;
    /** Public travel may accept a region; internal workstation/build stances remain exact. */
    public final boolean exact;
    public final double horizontalRadius;
    public final double verticalTolerance;
    /** Successful live body receipt; never copied into the public TaskResult. */
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

    /**
     * Build an internal exact-body movement contract without adding another LLM-visible field to
     * {@code goto}. The supplied cell is already a process-owned, observed stance candidate.
     */
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

    org.maiwithu.maicraft.core.pathing.calc.NavGoal coordinateGoal() {
        var target = new BlockPos(x == null ? 0 : (int) Math.floor(x), y == null ? 0 : (int) Math.floor(y),
                z == null ? 0 : (int) Math.floor(z));
        return switch (kind) {
            case BLOCK -> exact ? org.maiwithu.maicraft.core.pathing.calc.NavGoal.exact(target)
                    : org.maiwithu.maicraft.core.pathing.calc.NavGoal.nearGround(target, horizontalRadius, verticalTolerance);
            case COLUMN -> org.maiwithu.maicraft.core.pathing.calc.NavGoal.column(target.getX(), target.getZ(), horizontalRadius);
            case YLEVEL -> org.maiwithu.maicraft.core.pathing.calc.NavGoal.yLevel(target.getY());
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

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
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
        String where = switch (kind) {
            case BLOCK -> "走向 " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> "走向 x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> "下到 y=" + (int) (double) y;
            case FIND -> "去找 " + block;
        };
        return mayAlterTerrain ? where + "(可开路)" : allowWaterBucketFall ? where + "(可落地水)" : where;
    }
}
