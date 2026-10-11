// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 应用能源2机器这一半的资料：认得哪些设备，蓝图里线缆与部件怎么写，频道与控制器的规矩。
 * 一篇总览就够；每种设备的配方、思索去物品资料页看。
 */
final class Ae2MachineKnowledge implements KnowledgeSource {

    static final String INDEX = "maicraft://knowledge/machine/ae2/index";

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "machine.ae2.index", "应用能源2设备与 ME 网络",
                "认得哪些 ME 设备、蓝图里线缆与部件怎么写、频道与控制器的规矩",
                "ae2 应用能源 ME 网络 线缆 终端 部件 控制器 频道 驱动器"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        boolean known = Ae2MachineCatalog.kinds().stream().anyMatch(kind -> kind.blocks().contains(registryId));
        return known || registryId.endsWith("_cable") && registryId.startsWith("ae2:") ? entries() : List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.equals(INDEX)) return null;
        StringBuilder text = new StringBuilder("# 应用能源2设备与 ME 网络\n\n");
        text.append("## 蓝图里怎么写\n\n")
                .append("- 线缆不是方块，是装在线缆格中间的部件：写成一段安装段 `{kind: ae2:fluix_glass_cable, cells: [一格接一格]}`")
                .append("（kind 写线缆物品 ID，不带颜色的福鲁伊克斯线缆和任何颜色都连），cells 里不写线缆格。\n")
                .append("- 终端、总线这类部件写进 parts：`{offset: 线缆那一格, side: 装在哪一面, item: ae2:terminal}`；")
                .append("宿主那一格要是线缆（写在线缆安装段里就行），施工先接线缆再装部件。\n")
                .append("- 设备方块（控制器、驱动器、接口、样板供应器……）照常写进 cells。\n\n");
        text.append("## 网络的规矩\n\n")
                .append("- 线缆贴着设备、设备贴着设备就连上；不同颜色的线缆互不相连。\n")
                .append("- 没有控制器的网最多 8 个频道，超了全网停摆；有控制器后每根普通线缆 8 个、致密线缆 32 个。\n")
                .append("- 网络要有电：能源接收器接外面的电、能源元件存电。\n")
                .append("- `machine_inspect` 看网络：控制器、频道用量与上限、能量、存储总量要服务器装了 MaiCraft 才读得到；")
                .append("部件亮不亮（有电、有频道）客户端就看得见。`machine_connect network=me` 会用福鲁伊克斯玻璃线缆接到最近的网。\n\n");
        text.append("## 认得的设备\n\n");
        for (Ae2MachineCatalog.Kind kind : Ae2MachineCatalog.kinds()) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）")
                    .append(kind.onGrid() ? "" : "：不接 ME 网的单机").append("\n");
        }
        return new KnowledgeDocument(INDEX, "machine.ae2.index", "应用能源2设备与 ME 网络", "AE2 机器资料",
                text.toString());
    }

    @Override public String status() {
        return "应用能源2设备资料：可用，" + Ae2MachineCatalog.kinds().size() + " 种设备";
    }
}
