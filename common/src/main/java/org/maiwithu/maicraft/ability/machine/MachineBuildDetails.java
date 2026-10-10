// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 按机器蓝图施工的结果细节：档案落在哪、整机比对怎么讲、施工与机器条目各自做成没有。
 * 动作成没成（changes / unconfirmed）、结构对不对（check）分开给；机器能不能转不在这里判，留给 machine_run。
 *
 * @param archiveName 档案名
 * @param anchor      档案锚点
 * @param removed     这次拆掉了整台
 * @param checkComplete 整机比对下得了结论没有；有格没加载时为假；没跑到比对为 null
 * @param checkCounts 整机比对各结论几格，键是结论的小写名（matches、missing、checked_by_machine……）
 * @param cells       施工引擎各结局几格，键是结局的小写名（placed、cleared、matches……）
 * @param installations 每段安装段的结局
 * @param parts       每个部件的结局
 * @param settings    每条装后设置的读回
 * @param note        给 LLM 的一句说明（档案没存进去、有格没加载），没有为空字符串
 */
record MachineBuildDetails(String archiveName, WorldPosition anchor, boolean removed,
                           Boolean checkComplete, Map<String, Integer> checkCounts, Map<String, Integer> cells,
                           List<SegmentResult> installations, List<PartResult> parts,
                           List<SettingResult> settings, String note) implements ResultDetails {

    MachineBuildDetails {
        checkCounts = checkCounts == null ? Map.of() : Map.copyOf(checkCounts);
        cells = cells == null ? Map.of() : Map.copyOf(cells);
        installations = List.copyOf(installations);
        parts = List.copyOf(parts);
        settings = List.copyOf(settings);
        note = note == null ? "" : note;
    }

    /** 一段安装段：哪一种、占哪些格、装成没有、没装成的原因。 */
    record SegmentResult(String kind, List<String> cells, boolean installed, String note) {

        SegmentResult {
            cells = List.copyOf(cells);
            note = note == null ? "" : note;
        }
    }

    /** 一个部件：装在哪格哪一面、装上没有、没装上的原因。 */
    record PartResult(String item, String at, String side, boolean mounted, String note) {

        PartResult {
            note = note == null ? "" : note;
        }
    }

    /** 一条装后设置：哪一格的哪一项、读回的值；没改成的 applied 为假并带原因。 */
    record SettingResult(String key, String at, boolean applied, String readback) {

        SettingResult {
            readback = readback == null ? "" : readback;
        }
    }
}
