package org.maiwithu.maicraft.core;

import java.util.List;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 给失败原因一个固定名字，让上层按原因决定下一步，不必从报错文字里猜。
 * TaskState 表示任务还在跑还是已结束；这里说明为何失败，例如缺材料、背包满或视线被挡。
 * 枚举本身不会自动重试、补材料或请求决定；具体能力和任务各自选择如何处理。
 * 每个失败码可携带指路内容：alternatives 是"对账后可考虑的其他入口"一句话提示（英文，
 * 只列入口名，不给坐标、不承诺成功、不授予重试或改地形许可）；knowledgeRefs 指向
 * game_mechanics 机制常识卡，解释这类失败的成因。没有把握的码留空——错误的指路比不指路更糟。
 */
public enum FailureType {
    /** 缺要放置或使用的材料，例如要搭桥却没有方块。 */
    NO_MATERIAL("harvest_block for one observed source, craft from carried materials, or travel + may_alter_terrain to reach supplies", List.of()),
    /** 背包没有足够空位或可叠加容量，不能再放入物品。 */
    NO_SPACE("manage_container deposits carried items into a nearby container, or drop releases an explicit quantity", List.of()),
    /** 目标附近找不到可以借来放置的支撑面。 */
    NO_SUPPORT(null, List.of()),
    /** 目标格里有实体挡住放置，例如动物站在要砌墙的位置。 */
    ENTITY_BLOCKED(null, List.of()),
    /** 视线被挡住，够得着的距离内也没有点到目标。 */
    OCCLUDED("re-observe from a different vantage via perceive sections or find_block/find_entity", List.of()),
    /** 身体被困住，当前尝试找不到脱离位置的方法。 */
    BOXED_IN("travel to an open destination with may_alter_terrain to dig out", List.of()),
    /** 本次寻路没有找到到达目标的路线；不代表允许任意放宽原目标。 */
    NO_PATH("travel + may_alter_terrain digs/bridges/pillars a route, or pick a nearer destination", List.of("maicraft://knowledge/game_mechanics/gravity-blocks")),
    /** 搜索未能按预算产出结论且有界恢复已耗尽，不能当作地形确实无路。 */
    PLANNING_STALL("the search hit its budget rather than proving a dead end; re-submitting or approaching from another direction can still succeed", List.of()),
    /** 当前不允许改地形的条件挡住了路线；是否放宽要由上层按任务要求决定。 */
    TERRAIN_BLOCKED("may_alter_terrain grants dig/bridge/pillar when the player authorized terrain changes", List.of()),
    /** 没走到手能碰到目标的距离。 */
    OUT_OF_REACH(null, List.of()),
    /** 寻路认为站位到了，但实际还不能干活，例如手够不到、视线被挡或尚未落地。 */
    STANCE_DUD(null, List.of()),
    /** 工具不合要求，例如镐的等级不够，挖矿也不会掉材料。 */
    WRONG_TOOL("acquire_items supplies and equips a suitable tool; the tool-tier card lists required levels", List.of("maicraft://knowledge/game_mechanics/tool-tiers")),
    /** 原先要操作的实体或方块已无法继续定位或使用。 */
    TARGET_LOST("re-locate first with perceive, find_block or find_entity before re-submitting", List.of()),
    /** 空桶点名的液态格已是流水而非源格，尚未提交取水动作。 */
    NOT_A_SOURCE_BLOCK("aim the bucket at a still source block; flowing water or lava cannot be picked up", List.of("maicraft://knowledge/game_mechanics/fluid-flow")),
    /** 本次允许查询的范围内没有更多匹配来源。 */
    MINED_OUT("widen the search radius, move closer, or locate sources with find_block", List.of("maicraft://knowledge/game_mechanics/mine-source-scope")),
    /** 当前做法会遇到岩浆、虚空等已识别危险。 */
    HAZARD("retreat and re-plan around the hazard; the fluid card explains what water and lava do to dig sites", List.of("maicraft://knowledge/game_mechanics/fluid-flow")),
    /** 操作被停止或打断，例如玩家要求停止或身体失效。 */
    INTERRUPTED(null, List.of()),
    /** 超出这项任务允许的执行时间。 */
    TIMED_OUT(null, List.of()),
    /** 当前没有支持这类任务或操作的实现。 */
    UNSUPPORTED(null, List.of()),
    /**
     * 执行代码出现了内部异常，需要保留诊断信息；不能伪装成普通缺材料或没路。
     * 是否能重试要看异常与已发动作，不由这个标签保证。
     */
    INTERNAL(null, List.of()),
    /** 没有归入以上类别；不是“什么也没有发生”的保证。 */
    UNKNOWN(null, List.of());

    private final String alternatives;
    private final List<String> knowledgeRefs;

    FailureType(String alternatives, List<String> knowledgeRefs) {
        this.alternatives = alternatives;
        this.knowledgeRefs = List.copyOf(knowledgeRefs);
    }

    /** 一句话替代入口提示；null 表示这类失败没有把握的指路，回执不得编造建议。 */
    public String alternatives() { return alternatives; }

    /** 失败成因相关的机制常识卡 URI；空列表表示没有对应卡片。 */
    public List<String> knowledgeRefs() { return knowledgeRefs; }
}
