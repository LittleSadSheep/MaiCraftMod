// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.maiwithu.maicraft.behavior.explore.StructureClues;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 本实例的 ID 目录页：这个实例注册了哪些群系、能叫出名字的结构有哪些，按命名空间分组列全。
 * LLM 要探索某种群系、留意某种结构又拿不准 ID 时读它；探索认不出群系时的报错也指到群系这一页。
 *
 * <p>群系注册表随进世界同步到客户端，列的就是本实例真实的全部群系（含模组与数据包加的）。
 * 结构注册表留在服务端、不同步给客户端：读不到时如实说明，改列认得出地表特征的那些结构，
 * 它们正是探索的留意清单能发出线索、寻找能按线索找的结构。
 */
public final class IdCatalogPages implements KnowledgeSource {
    /** 群系目录页的地址；lookup 写末段名 biomes 也能读到。 */
    public static final String BIOMES = "maicraft://knowledge/ids/biomes";
    /** 结构目录页的地址；lookup 写末段名 structures 也能读到。 */
    public static final String STRUCTURES = "maicraft://knowledge/ids/structures";

    private final ReadsRegisteredIds registered;

    /** @param registered 本实例注册了哪些 ID：群系、结构从角色所在世界的注册表读 */
    public IdCatalogPages(ReadsRegisteredIds registered) {
        this.registered = Objects.requireNonNull(registered, "registered");
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(
                new KnowledgeDocument.Entry(BIOMES, "ids.biomes", "本实例的群系目录",
                        "这个实例注册了哪些群系 ID 与 # 标签（含模组与数据包加的），按命名空间分组；explore 的 target 从这里挑",
                        "biome biomes 群系 生物群系 目录 列表 ID explore target 海岸 beach 森林 forest 沙漠 desert 海洋 ocean 下界 nether"),
                new KnowledgeDocument.Entry(STRUCTURES, "ids.structures", "本实例的结构目录",
                        "能叫出名字的结构 ID，按命名空间分组；explore 的留意清单与 find 的 structure 从这里挑",
                        "structure structures 结构 目录 列表 ID 村庄 village 要塞 stronghold 神殿 temple 下界要塞 fortress find explore interests"));
    }

    @Override public KnowledgeDocument read(String uri) {
        if (BIOMES.equals(uri)) {
            return new KnowledgeDocument(BIOMES, "ids.biomes", "本实例的群系目录", "按命名空间分组的群系 ID 与标签", biomePage());
        }
        if (STRUCTURES.equals(uri)) {
            return new KnowledgeDocument(STRUCTURES, "ids.structures", "本实例的结构目录", "按命名空间分组的结构 ID", structurePage());
        }
        return null;
    }

    // 资料目录最后附的现状：一句话说清这次读到几个群系、结构读不读得到。
    @Override public String status() {
        int biomes = registered.ids(IdKind.BIOME).size();
        if (biomes == 0) {
            return "本实例的群系与结构目录：群系要进了世界才读得到";
        }
        int structures = registered.ids(IdKind.STRUCTURE).size();
        return "本实例的群系与结构目录：" + biomes + " 个群系，"
                + (structures > 0 ? structures + " 个结构" : "结构注册表客户端读不到，列认得出地表特征的 "
                        + StructureClues.knownIds().size() + " 种");
    }

    /** 群系目录：全部群系 ID 按命名空间分组，后面是挂着群系的 # 标签；一个不省，多了也只是分组给总数。 */
    String biomePage() {
        List<String> ids = registered.ids(IdKind.BIOME);
        if (ids.isEmpty()) {
            return "# 本实例的群系目录\n\n这次读不到群系注册表：角色要进了世界才读得到，进世界后再查这一页。\n";
        }
        List<String> tags = registered.tags(IdKind.BIOME);
        StringBuilder page = new StringBuilder("# 本实例的群系目录\n\n");
        page.append("本实例注册了 ").append(ids.size()).append(" 个群系，分在 ").append(byNamespace(ids).size())
                .append(" 个命名空间里。explore 的 target 写其中一个 ID，或写一个 # 标签（走进标签下任一群系都算到）。\n");
        appendGroups(page, "##", ids, "");
        if (!tags.isEmpty()) {
            page.append("\n## 群系标签（").append(tags.size()).append(" 个）\n");
            appendGroups(page, "###", tags, "#");
        }
        return page.toString();
    }

    /** 结构目录：读得到注册表就列全；读不到如实说明，改列认得出地表特征的结构。 */
    String structurePage() {
        List<String> ids = registered.ids(IdKind.STRUCTURE);
        StringBuilder page = new StringBuilder("# 本实例的结构目录\n\n");
        if (!ids.isEmpty()) {
            page.append("本实例注册了 ").append(ids.size()).append(" 种结构。find 的 structure 与 explore 的留意清单写其中一个 ID；")
                    .append("角色要靠地表特征认结构，没有特征档案的结构路过也认不出来。\n");
            appendGroups(page, "##", ids, "");
            return page.toString();
        }
        List<String> known = StructureClues.knownIds().stream().sorted().toList();
        page.append("结构注册表留在服务端、不同步给客户端，这里读不到本实例的完整结构清单（模组与数据包加的结构查不到）。")
                .append("下面是认得出地表特征的 ").append(known.size()).append(" 种结构：explore 的留意清单与 find 的 structure 写它们，")
                .append("路过时看得出线索；名单之外的结构，探索认不出。\n");
        appendGroups(page, "##", known, "");
        return page.toString();
    }

    // 一个命名空间一节：标题写命名空间和个数，正文是完整 ID（照抄就能用），按 ID 排序。原版排最前，其余按命名空间名排。
    private static void appendGroups(StringBuilder page, String heading, List<String> ids, String prefix) {
        for (Map.Entry<String, List<String>> group : byNamespace(ids).entrySet()) {
            page.append('\n').append(heading).append(' ').append(group.getKey())
                    .append("（").append(group.getValue().size()).append(" 个）\n");
            List<String> written = group.getValue().stream().map(id -> prefix + id).toList();
            page.append(String.join("、", written)).append('\n');
        }
    }

    private static Map<String, List<String>> byNamespace(List<String> ids) {
        Comparator<String> vanillaFirst = Comparator.comparing((String namespace) -> !namespace.equals("minecraft"))
                .thenComparing(Comparator.naturalOrder());
        Map<String, List<String>> groups = new TreeMap<>(vanillaFirst);
        for (String id : ids) {
            int colon = id.indexOf(':');
            String namespace = colon >= 0 ? id.substring(0, colon) : "minecraft";
            groups.computeIfAbsent(namespace, key -> new ArrayList<>()).add(id);
        }
        groups.values().forEach(list -> list.sort(Comparator.naturalOrder()));
        return groups;
    }
}
