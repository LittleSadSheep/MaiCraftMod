// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** 两份物品副本之间哪些组件变了；只给组件名，供失败诊断说明差在哪，不倾倒容器内容或自定义文本。 */
public final class ItemComponentDiff {
    private ItemComponentDiff() {}

    // 换手确认、开包核对失败时都用它留证据：两边任一侧出现的组件类型逐个比值，按名称排序后最多列 16 个。
    public static String changed(ItemStack actual, ItemStack expected) {
        return Stream.concat(actual.getComponents().stream(), expected.getComponents().stream())
                .map(component -> component.type()).distinct()
                .filter(type -> !Objects.equals(actual.get(type), expected.get(type)))
                .map(type -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)))
                .sorted().limit(16).toList().toString();
    }
}
