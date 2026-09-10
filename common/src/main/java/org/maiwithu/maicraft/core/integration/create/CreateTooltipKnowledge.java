// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 优先用 Create 自己的提示词条名读取说明，接口不可用时退回物品默认词条名；语言资源更换后重新读取，基础提示另作有限提取。
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

    /** Read only for an explicitly requested item, never while searching every registered block. */
    public static List<String> baseTooltip(Item item) {
        try {
            List<Component> lines = new ArrayList<>();
            item.appendHoverText(new ItemStack(item), Item.TooltipContext.EMPTY, lines, TooltipFlag.NORMAL);
            return lines.stream().limit(32).map(Component::getString)
                    .map(line -> line.replaceAll("§[0-9A-FK-ORa-fk-or]", "").strip())
                    .filter(line -> !line.isBlank()).map(line -> line.length() > 1024 ? line.substring(0, 1024) + "…" : line)
                    .distinct().toList();
        } catch (RuntimeException | LinkageError unavailable) {
            // Some item implementations require a world/player or loader tooltip context.
            return List.of();
        }
    }
}
