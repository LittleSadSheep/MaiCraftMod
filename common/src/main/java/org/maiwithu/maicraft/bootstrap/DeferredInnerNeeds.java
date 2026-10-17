// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 拿到物品引擎的内需入口：来源备料缺原料缺工具时回到引擎，但引擎要等来源清单
 * 建好才建得出来。这里先接住内需、引擎建好后接上，来源用它时不觉得有先后。
 */
final class DeferredInnerNeeds implements ItemNeeds {

    private ItemNeeds engine;

    /** 引擎建好后接上；这之后内需才真的往下问。 */
    void attach(ItemNeeds engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override public Action actionFor(ItemRequest request, Permissions permissions) {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine.actionFor(request, permissions);
    }

    @Override public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine.actionFor(request, permissions, records);
    }

    // 引擎还没建好时谁也没在拿东西，没有已知拿不到的。
    @Override public boolean knownUnobtainable(WantedItem wanted) {
        return engine != null && engine.knownUnobtainable(wanted);
    }

    // 缺料一样多的几种做法按缺的原料远近排：引擎还没建好时问不出远近，排法退回原来的先后。
    @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
        return engine == null ? Optional.empty() : engine.nearestDistanceBlocks(request, permissions);
    }

    // 几种做法分不出远近时先看一眼 AE 这类"开了才知道"的来源；引擎还没建好时没得看。
    @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
        return engine == null ? Optional.empty() : engine.lookAround(request, permissions);
    }

    // 挑可以互换的原料时问最省的一条路；引擎还没建好时问不出，按配方里的先后。
    @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions,
            boolean includeMaking) {
        return engine == null ? Optional.empty() : engine.cheapestCost(request, permissions, includeMaking);
    }
}
