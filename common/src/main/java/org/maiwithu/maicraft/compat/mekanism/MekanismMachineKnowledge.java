// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.List;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 通用机械机器这一半的资料：认得哪些机器与管道，侧面配置的设置项怎么写，配置器怎么改，管道怎么接。
 */
final class MekanismMachineKnowledge implements KnowledgeSource {

    static final String INDEX = "maicraft://knowledge/machine/mekanism/index";

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "machine.mekanism.index", "通用机械的机器与侧面配置",
                "认得哪些 Mekanism 机器与管道、侧面配置的设置项怎么写、配置器怎么改、管网怎么接",
                "mekanism 通用机械 侧面配置 配置器 线缆 管道 导管 能量立方 冶金灌注机"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        boolean known = MekanismMachineCatalog.kinds().stream().anyMatch(kind -> kind.blocks().contains(registryId));
        return known || registryId.equals(ConfiguratorClicks.CONFIGURATOR) ? entries() : List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.equals(INDEX)) return null;
        StringBuilder text = new StringBuilder("# 通用机械的机器与侧面配置\n\n");
        text.append("## 侧面配置\n\n")
                .append("- 每台机器每一面、每种介质有一个模式：none 不通、input 进、output 出、input_output 进出都行，")
                .append("还有 input_1、output_2、energy、extra 等，能选哪几种看 `machine_inspect` 或 `machine_configure` 报的可选值。\n")
                .append("- `machine_configure` 的键写 `side.方向.介质`（方向 north/south/east/west/up/down，介质 item/fluid/energy），")
                .append("值写模式名，例如 `{\"side.north.item\": \"output\"}`。化学品与热只看不改。\n")
                .append("- 改的时候角色手持配置器、调到那种介质的模式、潜行右键那一面，每点一下往后换一种，读回对上为止；")
                .append("身上没有配置器会先去拿。\n\n");
        text.append("## 管网\n\n")
                .append("- 通用线缆送电、机械管道送流体、物流传输管道送物品、加压管道送化学品；管道贴着管道、贴着机器能收发的那一面就连上。\n")
                .append("- `machine_connect` 的 `network` 写 energy、fluid 或 item，会用基础那一级的线缆、管道、导管接；")
                .append("机器要有对应介质能进的那一面（侧面配置不是 none）。网里流着多少这里读不到，只数得出几段管道、连着几台机器。\n\n");
        text.append("## 认得的机器与管道\n\n");
        for (MekanismMachineCatalog.Kind kind : MekanismMachineCatalog.kinds()) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）\n");
        }
        text.append("- 别的能配侧面的 Mekanism 机器：认作\"其他 Mekanism 机器\"，侧面配置照样能看能改\n");
        return new KnowledgeDocument(INDEX, "machine.mekanism.index", "通用机械的机器与侧面配置", "Mekanism 机器资料",
                text.toString());
    }

    @Override public String status() {
        return "通用机械机器资料：可用，" + MekanismMachineCatalog.kinds().size() + " 种";
    }
}
