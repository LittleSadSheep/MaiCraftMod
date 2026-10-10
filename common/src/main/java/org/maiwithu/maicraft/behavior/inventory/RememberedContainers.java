// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 已知容器读端：把世界记忆里记过的容器读成腾地方能用的候选。
 *
 * <p>只拿记忆里"这里有只箱子"的事实，按角色当前位置取给定方块距离以内的，由近及远排好；
 * 别人的箱子（不是自己或自家人放的）不往里塞东西，直接不进候选。记忆可能过时：
 * 箱子可能已经搬走，真正开箱存取时以现场为准，开不了由存取如实失败。
 */
public final class RememberedContainers implements KnownContainers {

    private final WorldMemory memory;
    private final ReadsCharacterPosition position;
    private final Protection protection;

    public RememberedContainers(WorldMemory memory, ReadsCharacterPosition position, Protection protection) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.position = Objects.requireNonNull(position, "position");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public List<KnownContainer> within(int blockRange) {
        // 记录时刻近的在前（recordsNear 的排序），腾地方先试新近记下的容器，过时的更可能已经不在了。
        return memory.recordsNear(position.currentPosition(), blockRange).stream()
                .filter(record -> record.kind() == MemoryKind.CONTAINER)
                // 只往能用的容器里存：自己或自家人放的、野外无主的；别人的记忆再近也不碰。
                .filter(record -> protection.mayUseStorage(record.position(), blockTypeOf(record), Set.of()))
                .map(RememberedContainers::toKnown)
                .toList();
    }

    // 记忆里没记下方块类型的按箱子对待：只是名字给人看，开箱时以现场为准。
    private static String blockTypeOf(MemoryRecord record) {
        return record.blockType() == null ? "minecraft:chest" : record.blockType();
    }

    private static KnownContainer toKnown(MemoryRecord record) {
        return new KnownContainer(describe(record), record.position().x(), record.position().y(), record.position().z());
    }

    // 容器的一句话说法与找容器读端一致：方块种类加坐标，例如"chest（120, 64, -8）"。
    private static String describe(MemoryRecord record) {
        String blockType = blockTypeOf(record);
        String kind = blockType.substring(blockType.indexOf(':') + 1);
        return kind + "（" + record.position().x() + ", " + record.position().y() + ", " + record.position().z() + "）";
    }
}
