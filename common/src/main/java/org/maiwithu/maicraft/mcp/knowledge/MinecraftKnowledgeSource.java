// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.ponder.ReflectivePonderAccess;

/** Client-thread-only registry facts plus automatically discovered Ponder reference documents. */
public final class MinecraftKnowledgeSource implements KnowledgeLibrary.Source {
    public static final String BLOCK = "maicraft://knowledge/block/";
    private final PonderKnowledgeSource ponder = new PonderKnowledgeSource(new ReflectivePonderAccess(), MinecraftKnowledgeSource::displayName);

    @Override public List<KnowledgeDocument.Entry> entries() { return ponder.entries(); }
    @Override public String status() { return ponder.status(); }
    @Override public JsonArray templates() {
        JsonArray templates = ponder.templates();
        templates.add(PonderKnowledgeSource.template(BLOCK + "{namespace}/{+path}", "registry.block", "安装版本中的方块状态属性和 Ponder 场景链接"));
        return templates;
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>(ponder.entries());
        if (!query.isBlank()) {
            String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
            BuiltInRegistries.BLOCK.forEach(block -> {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                var entry = blockEntry(id, block);
                if (java.util.Arrays.stream(terms).allMatch(entry.searchable()::contains)) entries.add(entry);
            });
        }
        return entries;
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.startsWith(BLOCK)) return ponder.read(uri);
        String tail = uri.substring(BLOCK.length()); int slash = tail.indexOf('/');
        if (slash <= 0) return null;
        ResourceLocation id = ResourceLocation.tryParse(tail.substring(0, slash) + ":" + tail.substring(slash + 1));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        var entry = blockEntry(id, block);
        StringBuilder text = new StringBuilder("# ").append(entry.title()).append("\n\n")
                .append("- 注册 ID：`").append(id).append("`\n")
                .append("- 物品形式：`").append(BuiltInRegistries.ITEM.getKey(block.asItem())).append("`\n")
                .append("- 普通 BlockItem：").append(block.asItem() instanceof BlockItem).append("。这只说明物品类型，不证明全部放置副作用可由现有施工接口完成。\n\n")
                .append("## 实际状态属性\n\n| 属性 | 默认值 | 允许值 |\n| --- | --- | --- |\n");
        for (Property<?> property : block.getStateDefinition().getProperties()) appendProperty(text, block, property);
        if (block.getStateDefinition().getProperties().isEmpty()) text.append("该方块没有方块状态属性。\n");
        text.append("\n属性来自当前注册表。属性名称不自动证明其物理含义；库存、过滤器、模式等也可能属于方块实体配置。\n\n")
                .append("## Ponder 用法\n\n");
        KnowledgeDocument component = ponder.read(PonderKnowledgeSource.componentUri(id.toString()));
        if (component != null) text.append("[查看该组件的原始教程](").append(component.uri()).append(")\n");
        else text.append("当前未取得该方块的 Ponder 场景（").append(ponder.status()).append("）；不能据此推断其功能或可操作性。\n");
        text.append("\n本页没有读取世界方块、库存或服务器配方；它不是施工授权或运行验收。\n");
        return new KnowledgeDocument(uri, entry.name(), entry.title(), entry.description(), text.toString());
    }

    private static <T extends Comparable<T>> void appendProperty(StringBuilder text, Block block, Property<T> property) {
        text.append("| `").append(property.getName()).append("` | `").append(property.getName(block.defaultBlockState().getValue(property)))
                .append("` | ").append(String.join(", ", property.getPossibleValues().stream().map(property::getName).toList())).append(" |\n");
    }
    private static KnowledgeDocument.Entry blockEntry(ResourceLocation id, Block block) {
        return new KnowledgeDocument.Entry(BLOCK + id.getNamespace() + "/" + id.getPath(), "block." + id,
                block.getName().getString(), "实际方块状态与使用资料 · " + id, id.toString());
    }
    private static String displayName(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) return value;
        if (BuiltInRegistries.ITEM.containsKey(id)) return I18n.get(BuiltInRegistries.ITEM.get(id).getDescriptionId());
        if (BuiltInRegistries.BLOCK.containsKey(id)) return BuiltInRegistries.BLOCK.get(id).getName().getString();
        return value;
    }
}
