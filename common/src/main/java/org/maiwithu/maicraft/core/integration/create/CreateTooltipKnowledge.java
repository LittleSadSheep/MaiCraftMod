// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 优先用 Create 自己的提示词条名读取说明，接口不可用时退回物品默认词条名；语言资源更换后重新读取。
 */
public final class CreateTooltipKnowledge {
    private final CreateTooltipDescription.Cache descriptions = new CreateTooltipDescription.Cache();
    private boolean resolved;
    private Method keyResolver;

    public CreateTooltipDescription description(Item item) {
        String key = translationKey(item);
        return descriptions.read(Language.getInstance(), key, value -> I18n.exists(value) ? I18n.get(value) : null);
    }

    private String translationKey(Item item) {
        if (!resolved) {
            resolved = true;
            try {
                keyResolver = Class.forName("com.simibubi.create.foundation.item.ItemDescription")
                        .getMethod("getTooltipTranslationKey", Item.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { keyResolver = null; }
        }
        if (keyResolver != null) {
            try {
                Object key = keyResolver.invoke(null, item);
                if (key instanceof String text && !text.isBlank()) return text;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Default key below. */ }
        }
        return item.getDescriptionId() + ".tooltip";
    }

    public record Tooltip(String status, List<String> lines) {}

    /** 查询具体配方涉及的物品时才读原生提示；依赖世界的提示失败必须与没有说明区分。 */
    public static Tooltip readTooltip(Item item) {
        return readTooltip(lines -> item.appendHoverText(new ItemStack(item), Item.TooltipContext.EMPTY, lines, TooltipFlag.NORMAL));
    }

    // 将原生回调与完整转录放在同一失败边界，物品需要现场上下文时仍可返回其他知识来源。
    static Tooltip readTooltip(Consumer<List<Component>> reader) {
        try {
            List<Component> lines = new ArrayList<>();
            reader.accept(lines);
            // 配方决策需要完整的操作条件；仅清除显示格式和重复行，不截掉末尾注意事项。
            return new Tooltip("available", lines.stream().map(Component::getString)
                    .map(line -> line.replaceAll("§[0-9A-FK-ORa-fk-or]", "").strip())
                    .filter(line -> !line.isBlank()).distinct().toList());
        } catch (RuntimeException | LinkageError unavailable) {
            return new Tooltip("api_unavailable", List.of());
        }
    }

    /** 方块正文沿用纯文本接口；配方提示另外保留读取状态。 */
    public static List<String> baseTooltip(Item item) {
        return readTooltip(item).lines();
    }
}
