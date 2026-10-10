// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从注册表与当前语言读物品说明：名字、鼠标悬停的说明、按住 Shift 与 Ctrl 的用法、方块状态。
 *
 * <p>按住 Shift 的用法是 Create 及其附属模组的写法：语言文件里 {@code <翻译键>.tooltip.summary} 是摘要，
 * {@code .conditionN} 与 {@code .behaviourN} 一对是"条件 → 效果"，{@code .controlN} 与 {@code .actionN} 一对是按住 Ctrl 的
 * "操作 → 效果"。按键名约定读，没写这一段的物品就没有，不另写名单。文字里用下划线包起来的是高亮，去掉下划线照读。
 * 只在客户端线程上调用：悬停说明要用角色所在的世界与角色本人，模组在这一步可能读它们。
 */
public final class ClientItemDescriptions implements ReadsItemDescriptions {
    private static final Logger LOG = LoggerFactory.getLogger(ClientItemDescriptions.class);

    private final Supplier<PlayerContext> context;

    public ClientItemDescriptions(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<ItemDescription> describe(String itemId) {
        Optional<Item> found = item(itemId);
        if (found.isEmpty()) return Optional.empty();
        Item item = found.get();
        ItemStack stack = new ItemStack(item);
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        String blockId = null;
        List<BlockProperty> properties = List.of();
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            properties = properties(block);
        }
        String key = stack.getDescriptionId() + ".tooltip.";
        Language language = Language.getInstance();
        String summary = language.has(key + "summary") ? plain(language.getOrDefault(key + "summary")) : "";
        return Optional.of(new ItemDescription(id, stack.getHoverName().getString(), blockId, properties, tooltip(stack),
                summary, pairs(language, key + "condition", key + "behaviour"), pairs(language, key + "control", key + "action")));
    }

    // 名字或 ID 包含全部关键词的物品：LLM 常写中文名（"搅拌器"），也会写 ID 的一段（"mixer"）。
    @Override public List<String> search(List<String> terms) {
        List<String> found = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            String searchable = (id + " " + item.getDescription().getString()).toLowerCase(Locale.ROOT);
            if (item != Items.AIR && terms.stream().allMatch(searchable::contains)) found.add(id);
        }
        return List.copyOf(found);
    }

    @Override public String nameOf(String itemId) {
        return item(itemId).map(item -> item.getDescription().getString()).orElse(itemId);
    }

    // 鼠标悬停时名字下面那几行：模组在这一步可能读世界与角色，读出错就当没有，不让一页资料因为它整页失败。
    private List<String> tooltip(ItemStack stack) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return List.of();
        try {
            List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(current.level()), current.localPlayer(),
                    TooltipFlag.Default.NORMAL);
            List<String> text = new ArrayList<>();
            for (Component line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                String plain = line.getString().strip();
                if (!plain.isEmpty()) text.add(plain);
            }
            return List.copyOf(text);
        } catch (RuntimeException failure) {
            LOG.debug("读 {} 的悬停说明出错，这一段不给", stack, failure);
            return List.of();
        }
    }

    // 一对一对读：条件 1 与效果 1、条件 2 与效果 2……读到第一个缺的序号就停。
    private static List<UsageLine> pairs(Language language, String whenKey, String resultKey) {
        List<UsageLine> lines = new ArrayList<>();
        for (int index = 1; language.has(whenKey + index); index++) {
            String result = language.has(resultKey + index) ? plain(language.getOrDefault(resultKey + index)) : "";
            lines.add(new UsageLine(plain(language.getOrDefault(whenKey + index)), result));
        }
        return List.copyOf(lines);
    }

    private static List<BlockProperty> properties(Block block) {
        List<BlockProperty> properties = new ArrayList<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            properties.add(property(block, property));
        }
        return List.copyOf(properties);
    }

    private static <T extends Comparable<T>> BlockProperty property(Block block, Property<T> property) {
        List<String> values = property.getPossibleValues().stream().map(property::getName).toList();
        return new BlockProperty(property.getName(), property.getName(block.defaultBlockState().getValue(property)), values);
    }

    // 语言文字里用下划线包起来的是高亮，去掉下划线照读。
    private static String plain(String text) {
        return text.replace("_", "").strip();
    }

    private static Optional<Item> item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.toLowerCase(Locale.ROOT));
        // 空气不是一件东西，查不到它的资料。
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR);
    }
}
