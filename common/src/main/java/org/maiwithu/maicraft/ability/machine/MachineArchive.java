// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 一台机器的档案：建成后留底的"这台机器按什么建的"。名字是 LLM 起的（和地标一样），
 * 蓝图冻结的是相对锚点的偏移，补丁合并后存合并结果；拆除只做标记，蓝图留着，随时可以照着再建。
 *
 * @param name          档案名，按名字指这台机器
 * @param dimension     机器所在的维度 ID
 * @param anchor        锚点：蓝图原点落在的那一格
 * @param blueprint     冻结的整机蓝图（相对锚点；含合并过的补丁）
 * @param designId      引用的设计编号，蓝图不是从设计来的为 null
 * @param removed       已拆除的标记：拆了的机器档案保留，指名字会说明它已拆除
 * @param networks      建档或接网时两端确认过的网络：种类 → 网络编号
 * @param lastCheckAt   最近一次整机比对的时间；没比对过为 null
 * @param lastCheckNote 最近一次整机比对的结论一句话；没比对过为 null
 */
record MachineArchive(String name, String dimension, BlockPos anchor, MachineBlueprint blueprint,
                      String designId, boolean removed, Map<String, String> networks,
                      Instant lastCheckAt, String lastCheckNote) {

    MachineArchive {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchor, "anchor").immutable();
        Objects.requireNonNull(blueprint, "blueprint");
        networks = Map.copyOf(networks);
    }

    /** 建档时的样子：蓝图刚落地，还没接过网、没比过对。 */
    static MachineArchive fresh(String name, String dimension, BlockPos anchor, MachineBlueprint blueprint,
            String designId) {
        return new MachineArchive(name, dimension, anchor, blueprint, designId, false, Map.of(), null, null);
    }

    /** 换一份蓝图（补丁合并结果）与其余现状：名字、维度、锚点不动。 */
    MachineArchive withBlueprint(MachineBlueprint merged, String designId, Instant checkedAt, String checkNote) {
        return new MachineArchive(name, dimension, anchor, merged,
                designId != null ? designId : this.designId, false, networks, checkedAt, checkNote);
    }

    /** 记下这次确认过的网络：种类 → 网络编号，旧的保留。 */
    MachineArchive joinedTo(Map<String, String> joined) {
        Map<String, String> merged = new LinkedHashMap<>(networks);
        merged.putAll(joined);
        return new MachineArchive(name, dimension, anchor, blueprint, designId, removed, merged,
                lastCheckAt, lastCheckNote);
    }

    /** 拆除标记：蓝图保留，再建时覆盖这个标记。 */
    MachineArchive markRemoved() {
        return new MachineArchive(name, dimension, anchor, blueprint, designId, true, networks, lastCheckAt, lastCheckNote);
    }
}
