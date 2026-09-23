// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.create.CreateFunnelPlacement;
import org.maiwithu.maicraft.core.integration.create.CreateKineticCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.create.CreateTooltipKnowledge;
import org.maiwithu.maicraft.core.integration.create.CreateProcessingCapabilities;
import org.maiwithu.maicraft.core.integration.ponder.ReflectivePonderAccess;
import org.maiwithu.maicraft.core.integration.ftbquests.ReflectiveFtbQuestsAccess;
import java.util.Arrays;

/** 仅在客户端线程读取注册表事实，并自动发现 Ponder 参考文档。 */
public final class MinecraftKnowledgeSource implements KnowledgeLibrary.Source {
    public static final String BLOCK = "maicraft://knowledge/block/";
    private final PonderKnowledgeSource ponder = new PonderKnowledgeSource(new ReflectivePonderAccess(), MinecraftKnowledgeSource::displayName);
    private final CreateTooltipKnowledge tooltips = new CreateTooltipKnowledge();
    private final RecipeKnowledgeSource recipes = new RecipeKnowledgeSource();
    private final FtbQuestsKnowledgeSource quests = new FtbQuestsKnowledgeSource(new ReflectiveFtbQuestsAccess());

    // 任务书和教程共用发现入口；这里只列玩家可见标题，任务正文等读到具体 URI 才展开。
    @Override public List<KnowledgeDocument.Entry> entries() {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>(ponder.entries()); entries.addAll(quests.entries()); return entries;
    }
    // 搜索没有查询EMI索引，不能把Ponder未安装的状态当作所有配方来源都不可用。
    @Override public String status() { return "ponder=" + ponder.status() + "; recipes=read_on_demand; ftbquests=" + quests.status(); }
    @Override public JsonArray templates() {
        JsonArray templates = ponder.templates();
        // 配方模板只发布查询格式；实际材料页等调用者指定物品后才读取当前EMI索引。
        templates.addAll(recipes.templates());
        templates.addAll(quests.templates());
        templates.add(PonderKnowledgeSource.template(BLOCK + "{namespace}/{+path}", "registry.block", "安装版本中的方块状态、物品说明、Create Shift/Ctrl 用法和 Ponder 场景链接"));
        return templates;
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>(entries());
        if (!query.isBlank()) {
            String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
            BuiltInRegistries.BLOCK.forEach(block -> {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                var entry = blockEntry(id, block);
                if (Arrays.stream(terms).allMatch(entry.searchable()::contains)) entries.add(entry);
            });
            // 粉末等没有方块形态的材料也能按名称或ID发现配方入口，搜索时不预读合成树。
            BuiltInRegistries.ITEM.forEach(item -> {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                String name = I18n.get(item.getDescriptionId());
                String searchable = (id + " " + name).toLowerCase(Locale.ROOT);
                if (Arrays.stream(terms).allMatch(searchable::contains)) entries.add(RecipeKnowledgeSource.entry(id, name));
            });
        }
        return entries;
    }

    @Override public KnowledgeDocument read(String uri) {
        // FTB 进度每次取当前连接的快照，不把上一次世界或队伍的结果当作长期知识缓存。
        if (uri.startsWith(FtbQuestsKnowledgeSource.PREFIX)) return quests.read(uri);
        if (uri.startsWith(RecipeKnowledgeSource.PREFIX)) return recipes.read(uri);
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
        text.append("\n属性来自当前注册表。属性名称不自动证明其物理含义；库存、过滤器、模式等也可能属于方块实体配置。\n\n");
        // 提供组件的外部工件接口，供模型比较传送带、置物台等承载方式；未知适配不能冒充运行证明。
        text.append("## 原生工件加工接口\n\n```json\n").append(CreateProcessingCapabilities.descriptor(block.defaultBlockState()))
                .append("\n```\n\n该接口仅描述外部工件加工与承载，不代表设备全部能力；完整机器组合契约见 ")
                .append(MachineAssemblyResources.URI).append("。\n\n");
        // 设计者同时看到动力接入与原生安装依赖，自动展开不会把轴向和最终形态藏在施工器内部。
        for (JsonObject descriptor : new JsonObject[]{CreateKineticCapabilities.describe(block.defaultBlockState()), CreateFunnelPlacement.describe(block.defaultBlockState())})
            if (descriptor != null) text.append("## 原生传动或安装契约\n\n```json\n").append(descriptor).append("\n```\n\n");
        // 工件加工与库存补料是两个接口；提供带版本的源码规则，避免把相邻摆放误当成自动供料。
        JsonObject transfer = NativeItemTransferContract.reference(id.toString());
        if (transfer != null) text.append("## 库存传输源码参考\n\n```json\n").append(transfer).append("\n```\n\n");
        List<String> baseTooltip = CreateTooltipKnowledge.baseTooltip(block.asItem());
        if (!baseTooltip.isEmpty()) {
            text.append("## 默认物品说明\n\n");
            baseTooltip.forEach(line -> text.append("- ").append(line).append('\n'));
            text.append('\n');
        }
        text.append(tooltips.description(block.asItem()).markdown()).append("\n## Ponder 用法\n\n");
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
    private KnowledgeDocument.Entry blockEntry(ResourceLocation id, Block block) {
        var description = tooltips.description(block.asItem());
        String summary = description.summary();
        if (summary.length() > 120) summary = summary.substring(0, 120) + "…";
        return new KnowledgeDocument.Entry(BLOCK + id.getNamespace() + "/" + id.getPath(), "block." + id,
                block.getName().getString(), "实际方块状态与使用资料 · " + id + (summary.isBlank() ? "" : " · " + summary),
                id + " " + description.searchText());
    }
    private static String displayName(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) return value;
        if (BuiltInRegistries.ITEM.containsKey(id)) return I18n.get(BuiltInRegistries.ITEM.get(id).getDescriptionId());
        if (BuiltInRegistries.BLOCK.containsKey(id)) return BuiltInRegistries.BLOCK.get(id).getName().getString();
        return value;
    }
}
