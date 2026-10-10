// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import java.util.Map;
import java.util.Set;

/**
 * 参数名表：同一个概念全接口只有一个名字，一个名字也只有一种意思。
 *
 * <p>能力声明参数时，参数名必须先登记在这里，否则创建参数规格时直接报错。
 * 这样 LLM 学会一个名字就能在所有能力里通用，不用逐个能力去猜"范围"叫 radius 还是 search_radius。
 *
 * <p>确实需要新概念时：先确认现有名字表达不了，在这里加一行写清含义，同步更新对外接口文档，再在能力里使用。
 */
public final class ParamNames {
    private static final Map<String, String> MEANINGS = Map.ofEntries(
            Map.entry("item", "物品 ID，或 # 开头的物品标签"),
            Map.entry("items", "多个物品 ID 或物品标签"),
            Map.entry("block", "方块 ID，或 # 开头的方块标签"),
            Map.entry("blocks", "多个方块 ID 或方块标签"),
            Map.entry("entity", "实体类型 ID，可给一个或几种"),
            Map.entry("structure", "要找的结构 ID（命名空间 id），例如 minecraft:village_plains"),
            Map.entry("count", "这次要多做多少：拿东西就是再多拿几件（不是背包里的总数），做动作就是做几次"),
            Map.entry("radius", "工作或搜索范围，单位格"),
            Map.entry("max_distance", "出行或搜索的最远距离，单位格"),
            Map.entry("max_seconds", "最多花多少秒"),
            Map.entry("operation", "一个能力内部的几种操作选哪一种，例如 enable、disable、status"),
            Map.entry("via", "指定途径，例如拿东西时指定合成、烧炼或采掘"),
            Map.entry("text", "要发送或书写的文字"),
            Map.entry("message", "要发到游戏聊天的一句话，对全体玩家可见"),
            Map.entry("name", "名字，例如要记住的地点名"),
            Map.entry("distance", "保持的距离，单位格，例如跟随时与目标相隔几格"),
            Map.entry("condition", "要等到的条件：elapsed / day / night / health_full / not_hungry"),
            Map.entry("after_seconds", "先至少经过多少秒，再开始做检查"),
            Map.entry("slot", "装备与卸下的目标栏位：mainhand / offhand / head / chest / legs / feet / armor（armor 只配合卸下）"),
            Map.entry("drawing", "建筑图纸的正文（JSON 对象）：材料表、对象、组件"),
            Map.entry("design_id", "设计编号：design 创建或修改图纸后返回的 UUID"),
            Map.entry("edits", "对图纸按名合并的修改（JSON 对象）：objects、materials、components、remove_objects、remove_components、block_state_axes、overlap_policy、name"),
            Map.entry("page", "分页看结果时的页码，从 0 起"),
            Map.entry("format", "导出文件的格式：json / nbt"),
            Map.entry("rotation", "蓝图落地时绕锚点转多少度：0 / 90 / 180 / 270"),
            Map.entry("cells", "逐格蓝图（JSON 数组）：每项 offset [x,y,z]、block、可选 properties；block 为 minecraft:air 表示清空"),
            Map.entry("file", "schematics 目录下的结构文件名"),
            Map.entry("properties", "只放一格时要求的方块状态属性（JSON 对象），例如 {\"facing\":\"north\"}"),
            Map.entry("quest", "任务书里任务的编号（16 位十六进制，FTB 任务）"),
            Map.entry("requirement", "任务里一条要求的编号（任务书资料的条目页里给的）"),
            Map.entry("reward", "一个任务自己的根奖励编号；奖池里的子奖励不是能单独领的奖励"),
            Map.entry("choice", "领选择奖励时选哪个候选（能力提问时列出的候选编号）"),
            Map.entry("blueprint", "机器蓝图（JSON 对象）：cells 逐格、parts 部件、installations 安装段、settings 装后设置、processes 声明工序"),
            Map.entry("settings", "要改成的设置（JSON 对象）：键是这台机器认的设置项，值写字符串"),
            Map.entry("collect", "做完要不要把出口的东西拿进背包"),
            Map.entry("network", "接哪种网络：取值由已登记的网络读取器自报，例如 kinetic / me / energy"),
            Map.entry("source", "接到哪：另一台机器或那张网里任一格的位置，写法与 target 相同"));

    private ParamNames() {}

    public static boolean contains(String name) {
        return MEANINGS.containsKey(name);
    }

    /** 参数名的含义；没登记时返回 null。 */
    public static String meaning(String name) {
        return MEANINGS.get(name);
    }

    public static Set<String> names() {
        return MEANINGS.keySet();
    }
}
