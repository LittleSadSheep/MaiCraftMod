// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Comparator;
import java.util.List;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 取物的前置需求沿用当前许可；补工具、燃料或工作台都不能成为扩大取材范围的理由。 */
final class AcquisitionSources {
    private AcquisitionSources() {}

    record Readiness(boolean craftReady, boolean cookReady, boolean naturalMine, boolean directHunt) {}

    static List<Source> order(AcquisitionNeed need, Readiness facts) {
        // 先用背包和现货，再考虑已经能做的加工与有线索的采集；填写来源的顺序不决定角色动作。
        // 无线终端存在只是现场条件，不能在“只挖矿”等明确来源列表里偷偷追加仓库。
        return need.allowedSources.stream().filter(need::canTry)
                .sorted(Comparator.comparingInt(source -> switch (source) {
                    case INVENTORY -> 0;
                    // 随身终端不需要离开工位：带着已绑定的无线终端时先取网络现货，再去翻附近箱子或走向其他世界来源。
                    case WIRELESS -> 2;
                    case NEARBY -> 10;
                    // 可见普通箱子先于掉落物和加工；具体开箱顺序由各箱最近的真实观察决定。
                    case STORAGE -> 3;
                    case HARVEST -> 24; // 有明确成熟作物时先收田，避免缺钱还先走贸易链。
                    case CRAFT -> facts.craftReady() ? 25 : 50;
                    case COOK -> facts.cookReady() ? 26 : 55;
                    case MINE -> facts.naturalMine() ? 30 : 60;
                    case HUNT -> facts.directHunt() ? 35 : 80;
                    case TRADE -> 70;
                })).toList();
    }

    static List<Source> forTool(List<Source> parent, boolean stockOnlyUpgrade) {
        // 必需工具继承原来源；富余材料带来的升级只取现货或合成，失败后回到便宜工具，不另开采集链。
        return stockOnlyUpgrade
                ? parent.stream().filter(source -> source == Source.INVENTORY
                        || source == Source.WIRELESS || source == Source.STORAGE || source == Source.CRAFT).toList()
                : List.copyOf(parent);
    }

    static List<Source> forCookingInputs(List<Source> parent) {
        // 平滑石等需要先加工原料；保留已有COOK许可，用祖先链拒绝循环，不能直接切断所有多段工艺。
        return List.copyOf(parent);
    }
}
