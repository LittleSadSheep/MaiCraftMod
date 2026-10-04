package org.maiwithu.maicraft.core.task.explore;

import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/** 一个有界的语义搜索与移动目标。 */
public final class SemanticExploreTaskRecord extends TaskRecord
        implements InternalPositionReceipt {
    public static final String TOOL_NAME = "explore";
    public static final int MIN_DISTANCE = 64;
    public static final int MAX_DISTANCE = 2_048;
    private static final int WAYPOINT_GRID = 64;
    /** 顺带兴趣白名单：新增值必须同步能力契约文本与伴随任务的匹配逻辑。 */
    public static final List<String> LEGAL_INTERESTS = List.of("lava_pool");

    /** 探索路段的存活租期；只要路段持续取得已核实进展，伴随任务会续期自己的任务记录。 */
    public static final int LEG_LEASE_TICKS = 90 * 20;

    static {
        TaskFactory.register(SemanticExploreTaskRecord.class,
                SemanticExploreCompanionTask::new);
    }

    /** 待询问发现：finding id、对象与发现时刻相对玩家的方向距离，语义层据此拉起决策。 */
    public record InterestFinding(String findingId, String targetId,
            int x, int y, int z, String direction, int distanceBlocks) {}

    public final String target;
    public final int maxDistance;
    public final int maxWaypoints;
    public final boolean mayAlterTerrain;
    public final TransportMode transportMode;
    public final ExplorationSector sector;
    /** 模型声明的顺带兴趣；本类任务单不进检查点，声明经由目标参数持久化，恢复时随重新适配回来。 */
    private final List<String> interests;
    private Position verifiedPosition;
    private InterestFinding pendingFinding;
    private final Set<String> askedFindingIds = new HashSet<>();
    private UUID interestDecisionId;
    private String interestAnswer;

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain) {
        this(toolCallId, deadlineGameTime, target, maxDistance, mayAlterTerrain, TransportMode.AUTO);
    }

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain, TransportMode transportMode) {
        this(toolCallId, deadlineGameTime, target, maxDistance, mayAlterTerrain, transportMode,
                null, null, null, null);
    }

    /** 探索请求只声明目标和方向扇区；相对方向在角色实际开始任务时才固定。 */
    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain, TransportMode transportMode,
            String direction, Integer angleDegrees, Integer minDistance) {
        this(toolCallId, deadlineGameTime, target, maxDistance, mayAlterTerrain, transportMode,
                direction, angleDegrees, minDistance, null);
    }

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain, TransportMode transportMode,
            String direction, Integer angleDegrees, Integer minDistance,
            List<String> interests) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.transportMode = transportMode == null ? TransportMode.AUTO : transportMode;
        if (this.transportMode == TransportMode.JETPACK || this.transportMode == TransportMode.ELEVATOR) {
            throw new IllegalArgumentException("explore transport_mode must be auto or ground; "
                    + "jetpack/elevator travel needs a located destination first.");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "explore target is required: coast, a namespaced biome id, or #biome_tag");
        }
        // 海岸按玩家约定就是沙滩群系，不再要求岸边另外满足海洋水域特征。
        this.target = "coast".equalsIgnoreCase(target.trim()) ? "minecraft:beach" : target.trim();
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        this.sector = ExplorationSector.of(direction, angleDegrees, minDistance, this.maxDistance);
        int rings = Math.max(1, (this.maxDistance + WAYPOINT_GRID - 1) / WAYPOINT_GRID);
        // 覆盖范围由目标区域决定，不使用任意尝试次数上限。在 MAX_DISTANCE 下仍是较小的有限网格，运行时会对已访问边界去重。
        this.maxWaypoints = Math.max(8, rings * rings * 4);
        this.mayAlterTerrain = mayAlterTerrain;
        // 兴趣值在语义计划期已核验；直接任务入口绕过计划校验，这里按同一白名单再拦一次。
        List<String> cleaned = interests == null ? List.of() : interests.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        for (String value : cleaned) {
            if (!LEGAL_INTERESTS.contains(value))
                throw new IllegalArgumentException(
                        "unknown explore interest '" + value + "'; legal values: " + LEGAL_INTERESTS);
        }
        this.interests = List.copyOf(cleaned);
    }

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    void retainVerifiedPosition(Position position) {
        verifiedPosition = position;
    }

    public List<String> interests() {
        return interests;
    }

    /** 声明了兴趣时新发现才暂停询问；未声明时调用方完全不会走到这条路径。 */
    public boolean declaredInterest(String targetId) {
        return interests.contains(targetId);
    }

    /**
     * 匹配兴趣的新发现置位待询问；同一 finding 只问一次，已有一条待询问时不覆盖。
     * 已问集合在置位时登记，即使决策尚未发出也不会对同一发现重复询问。
     */
    public boolean noteInterestFinding(InterestFinding finding) {
        if (finding == null || !interests.contains(finding.targetId())) return false;
        if (pendingFinding != null || !askedFindingIds.add(finding.findingId())) return false;
        pendingFinding = finding;
        return true;
    }

    public InterestFinding pendingInterestFinding() {
        return pendingFinding;
    }

    public boolean hasPendingInterestFinding() {
        return pendingFinding != null;
    }

    /** 语义层生成决策后回填编号，答复按编号路由回本任务单，不落进语义改参分支。 */
    public void beginInterestDecision(UUID decisionId) {
        interestDecisionId = decisionId;
    }

    /** 决策已发出但答复未到的观察窗口；恢复场景下据此避免对同一发现重复生成决策。 */
    public UUID interestDecisionId() {
        return interestDecisionId;
    }

    public boolean isInterestDecision(UUID decisionId) {
        return decisionId != null && decisionId.equals(interestDecisionId);
    }

    /**
     * 决策暂停期间伴随任务不被 tick、每刻续期停摆，期限冻结在暂停时刻；
     * 答复时刻按租期一次性补期，恢复后第一刻的截止检查不得用暂停时长否决模型的继续选择。
     */
    public void renewAfterInterestAnswer(long nowGameTime) {
        extendDeadlineTo(nowGameTime + LEG_LEASE_TICKS);
    }

    /** 只有 continue/stop 两个选项能进入决策快照；写回前由任务记录的选项白名单核对。 */
    public void applyInterestAnswer(String choice) {
        interestAnswer = choice;
    }

    /**
     * 伴随任务消费答复，只消费一次：continue 清掉待询问并恢复推进；
     * stop 保留待询问发现供收尾回执引用，随后由伴随任务直接进入终态。
     */
    public String consumeInterestAnswer() {
        if (interestAnswer == null) return null;
        String choice = interestAnswer;
        interestAnswer = null;
        interestDecisionId = null;
        if ("continue".equals(choice)) pendingFinding = null;
        return choice;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    @Override public String describe() {
        return "探索 " + target + "，范围 " + maxDistance + " 格"
                + (mayAlterTerrain ? "（允许开路）" : "")
                + (interests.isEmpty() ? "" : "，顺带关注 " + String.join("、", interests));
    }
}
