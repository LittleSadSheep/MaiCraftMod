// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.maiwithu.maicraft.core.integration.ponder.PonderAccess;
import org.maiwithu.maicraft.core.integration.ponder.PonderBlueprintStore;
import org.maiwithu.maicraft.core.integration.ponder.PonderReplayRuntime;

/**
 * 把教程目录、单篇旁白、分章回放和结构 JSON 提供为知识资源；点到具体故事板才编译文字，读回放资源才启动结构提取。
 */
public final class PonderKnowledgeSource implements KnowledgeLibrary.Source {
    public static final String INDEX = "maicraft://knowledge/ponder/index";
    public static final String COMPONENT = "maicraft://knowledge/ponder/component/";
    public static final String SCENE = "maicraft://knowledge/ponder/scene/";
    public static final String REPLAY = "maicraft://knowledge/ponder/replay/";
    private final PonderAccess access;
    private final Function<String, String> displayName;
    private String status = "not_observed";

    public PonderKnowledgeSource(PonderAccess access, Function<String, String> displayName) {
        this.access = access; this.displayName = displayName;
    }

    private PonderAccess.Snapshot snapshot() {
        PonderAccess.Snapshot snapshot = access.snapshot(); status = snapshot.status(); return snapshot;
    }

    public static String componentUri(String id) { return COMPONENT + id.replace(':', '/'); }
    @Override public String status() { return status; }

    @Override public List<KnowledgeDocument.Entry> entries() {
        PonderAccess.Snapshot snapshot = snapshot();
        Map<String, KnowledgeDocument.Entry> rows = new LinkedHashMap<>();
        rows.put(INDEX, new KnowledgeDocument.Entry(INDEX, "ponder.index", "已注册 Ponder 教程", "自动发现组件和故事板；按页阅读原始说明。", "ponder 思索 教程"));
        for (PonderAccess.Entry entry : snapshot.entries()) {
            String label = displayName.apply(entry.component());
            String keywords = entry.component() + " " + String.join(" ", entry.tags());
            rows.putIfAbsent(componentUri(entry.component()), new KnowledgeDocument.Entry(componentUri(entry.component()),
                    "ponder.component." + entry.component(), label + " · Ponder 场景", "组件 " + entry.component() + " 的教程目录；尚未展开正文。", keywords));
            rows.put(SCENE + entry.key(), new KnowledgeDocument.Entry(SCENE + entry.key(), "ponder.scene." + entry.key(),
                    label + " · " + entry.schematic(), "注册故事板 " + entry.schematic() + "；读取时提取旁白和控制提示。", keywords));
        }
        return List.copyOf(rows.values());
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.startsWith("maicraft://knowledge/ponder/")) return null;
        String[] parts = uri.split("\\?", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid Ponder resource query");
        int offset = 0;
        if (parts.length == 2) {
            if (!parts[1].matches("offset=(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Only offset is supported by Ponder resources");
            try { offset = Integer.parseInt(parts[1].substring(7)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid Ponder offset"); }
        }
        String base = parts[0];
        if (base.startsWith(PonderBlueprintStore.PREFIX)) {
            if (parts.length != 1) throw new IllegalArgumentException("Structure resources do not accept query parameters");
            PonderReplayRuntime.refreshEnvironment();
            JsonObject blueprint = PonderBlueprintStore.read(base);
            return blueprint == null ? null : new KnowledgeDocument(uri, "ponder.structure", "Ponder 章节结构",
                    "Blueprint blocks and separate demonstration evidence", blueprint.toString(), "application/json");
        }
        PonderAccess.Snapshot snapshot = snapshot();
        if (base.equals(INDEX)) return index(uri, snapshot, offset);
        if (base.startsWith(COMPONENT)) {
            if (offset != 0) throw new IllegalArgumentException("Component indexes do not accept an offset");
            List<PonderAccess.Entry> scenes = snapshot.entries().stream().filter(entry -> componentUri(entry.component()).equals(base)).toList();
            if (scenes.isEmpty()) return null;
            String title = displayName.apply(scenes.getFirst().component());
            StringBuilder text = new StringBuilder("# ").append(title).append(" 的 Ponder 场景\n\n")
                    .append("组件：`").append(scenes.getFirst().component()).append("`。以下仅列目录，读取一个场景才展开正文。\n\n");
            for (var scene : scenes) text.append("- [").append(scene.schematic()).append("](").append(SCENE).append(scene.key()).append(")\n");
            text.append("\n来源：当前 Ponder 注册表。场景名称来自注册的演示结构标识，正式标题在读取该场景时提取。\n");
            return new KnowledgeDocument(uri, "ponder.component", title, "Registered scene index", text.toString());
        }
        if (base.startsWith(SCENE) || base.startsWith(REPLAY)) {
            boolean replay = base.startsWith(REPLAY);
            String key = base.substring((replay ? REPLAY : SCENE).length());
            PonderAccess.Entry entry = snapshot.entries().stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
            if (entry == null) return null;
            if (replay) return new KnowledgeDocument(uri, "ponder.replay." + key, "Ponder 章节结构",
                    "On-demand chapter end snapshots with narration", access.replay(entry).markdown(base, offset));
            // 当前每次读取旁白页都会重新编译该故事板；分页没有缓存上次已编译的全文。
            var transcript = access.compile(entry);
            return new KnowledgeDocument(uri, "ponder.scene." + key, transcript.title(), "Original Ponder narration and control hints",
                    transcript.markdown(entry, base, offset) + "\n[按需提取章节结构](" + REPLAY + key
                            + ")：在独立演示世界中回放，只保留章节末状态和拆除/替换前状态；后续客户端 tick 分批完成。\n");
        }
        return null;
    }

    private KnowledgeDocument index(String uri, PonderAccess.Snapshot snapshot, int offset) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        snapshot.entries().forEach(entry -> counts.merge(entry.component(), 1, Integer::sum));
        List<String> ids = counts.keySet().stream().sorted().toList();
        if (offset < 0 || offset > ids.size() || offset > 0 && offset == ids.size()) throw new IllegalArgumentException("Ponder index offset is out of range");
        int end = Math.min(ids.size(), offset + 40);
        StringBuilder text = new StringBuilder("# 已注册 Ponder 教程\n\n状态：`").append(snapshot.status()).append("`。")
                .append(snapshot.detail()).append("\n\n组件 ").append(ids.size()).append(" 个，故事板 ").append(snapshot.entries().size()).append(" 个。\n\n");
        for (String id : ids.subList(offset, end)) text.append("- [").append(displayName.apply(id)).append(" · ").append(id)
                .append("](").append(componentUri(id)).append(")：").append(counts.get(id)).append(" 个场景\n");
        if (end < ids.size()) text.append("\n[下一页](").append(INDEX).append("?offset=").append(end).append(")\n");
        if (ids.isEmpty()) text.append("未取得已注册教程；这不代表方块没有功能。Ponder 未安装、尚未完成注册或接口不可用时，不会猜测教程内容。\n");
        text.append("\n无需预加载全部场景。可用 perceive(view=knowledge, focus=物品ID或名称) 定位组件。\n");
        return new KnowledgeDocument(uri, "ponder.index", "Ponder 教程索引", "Registered Ponder components", text.toString());
    }

    @Override public JsonArray templates() {
        JsonArray templates = new JsonArray();
        templates.add(template(COMPONENT + "{namespace}/{+path}", "ponder.component", "Ponder 组件教程目录"));
        templates.add(template(SCENE + "{scene}{?offset}", "ponder.scene", "从目录取得的故事板；offset 按说明/提示条目分页"));
        templates.add(template(REPLAY + "{scene}{?offset}", "ponder.replay", "按需回放并读取章节摘要；offset 按章节分页"));
        JsonObject structure = template(PonderBlueprintStore.PREFIX + "{scene}/{snapshot}", "ponder.structure", "从章节摘要取得的结构，包含蓝图方块与独立演示证据");
        structure.addProperty("mimeType", "application/json"); templates.add(structure);
        return templates;
    }
    static JsonObject template(String uri, String name, String description) {
        JsonObject result = new JsonObject(); result.addProperty("uriTemplate", uri);
        result.addProperty("name", name); result.addProperty("description", description); result.addProperty("mimeType", "text/markdown");
        return result;
    }
}
