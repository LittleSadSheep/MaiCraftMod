package org.maiwithu.maicraft.core.task.combat;

import org.maiwithu.maicraft.task.TaskRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 保存要打哪些实体、是否自动自卫、是否严格限制攻击对象，以及每个目标的当前处理结果。
 * 这份任务单只记编号和统计，不自己观察死亡或判断能否攻击；结果分类由 AttackCompanionTask 写入。
 */
public final class AttackTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "attack";

    public final List<Integer> entityIds;

    /**
     * 不点名,打退附近所有敌对生物。
     *
     * <p>按 id 授权在会分裂的怪面前根本行不通:打一只大史莱姆,它裂成四只<b>全新 id</b> 的
     * 小史莱姆,原来那份清单当场作废,任务判"目标丢失"收工,而她还站在史莱姆堆里。
     */
    public final boolean indiscriminate;

    /**
     * When true, even opportunistic in-reach melee may only touch an entity in
     * {@link #entityIds}. The normal attack tool keeps its historical defensive
     * behaviour by using the four-argument constructor, which defaults this to false.
     */
    public final boolean strictAuthorized;

    private final Set<Integer> defeated = new LinkedHashSet<>();
    private final Set<Integer> lost = new LinkedHashSet<>();
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Map<Integer, Integer> strikesByEntity = new LinkedHashMap<>();
    private int strikes;

    public AttackTaskRecord(String toolCallId, long deadlineGameTime,
                            List<Integer> entityIds, boolean indiscriminate) {
        this(toolCallId, deadlineGameTime, entityIds, indiscriminate, false);
    }

    public AttackTaskRecord(String toolCallId, long deadlineGameTime,
                            List<Integer> entityIds, boolean indiscriminate,
                            boolean strictAuthorized) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.entityIds = List.copyOf(entityIds);
        this.indiscriminate = indiscriminate;
        this.strictAuthorized = strictAuthorized;
    }

    public Set<Integer> defeated() { return Set.copyOf(defeated); }
    public Set<Integer> lost() { return Set.copyOf(lost); }
    public Set<Integer> unreachable() { return Set.copyOf(unreachable); }
    public int strikes() { return strikes; }

    public long requestedDefeatedCount() { return entityIds.stream().filter(defeated::contains).count(); }
    public boolean allRequestedDefeated() {
        return !entityIds.isEmpty() && requestedDefeatedCount() == entityIds.size();
    }

    public void defeated(int id) { defeated.add(id); }
    public void lost(int id) { lost.add(id); }
    public void unreachable(int id) { unreachable.add(id); }

    /** 出手一次(挥击或射出一箭)。 */
    // 记录一次攻击动作。当前调用方对近战在点击确认后记数，对远程在箭发射后记数，因此不是统一的命中次数。
    public void strike(int id) {
        strikes++;
        strikesByEntity.merge(id, 1, Integer::sum);
    }

    public int strikes(int id) { return strikesByEntity.getOrDefault(id, 0); }

    // 按击败、丢失、不可达的顺序给状态；这些集合没有互斥校验，重复登记时按这个优先顺序显示。
    public String status(int id) {
        if (defeated.contains(id)) return "defeated";
        if (lost.contains(id)) return "lost";
        if (unreachable.contains(id)) return "unreachable";
        return "pending";
    }

    // 只要已被归到上述任一结束类别，就不再主动选择这个目标。
    public boolean terminal(int id) {
        return defeated.contains(id) || lost.contains(id) || unreachable.contains(id);
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return indiscriminate
                ? "清场 已放倒 " + defeated.size()
                : "战斗 " + defeated.size() + "/" + entityIds.size();
    }
}
