// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.knowledge;

import com.google.gson.JsonArray;
import java.util.List;

/**
 * 知识来源：一个可发现的只读资料集合（注册表事实、任务书、教程、配方……）。
 *
 * <p>启动时按模组是否安装登记实现；一个来源没装只影响自己的条目，不牵连其他来源。
 * 目录与搜索只比较元数据，正文由调用方按返回的 URI 按需读取。
 *
 * <p>目录和搜索分开：目录只列每个来源的几行索引（一个整合包的思索场景、任务书条目可能有几百上千条，
 * 全塞进目录 LLM 读不完），搜索才覆盖来源的全部条目。
 */
public interface KnowledgeSource {
    /** 放进资料目录的几行：通常是这个来源的索引；条目多的来源不要把全部条目放在这里，放进搜索候选。 */
    List<KnowledgeDocument.Entry> entries();

    /** 读取一个本来源管辖的 URI；不归它管时返回 null，由资源库统一回答未找到。 */
    KnowledgeDocument read(String uri);

    /** 搜索候选：这个来源里可能和查询词有关的全部条目；默认与目录相同。 */
    default List<KnowledgeDocument.Entry> searchCandidates(String query) {
        return entries();
    }

    /** 近似搜索候选：允许条目名有错字、漏字时的召回；候选不足时排序才能补救。 */
    default List<KnowledgeDocument.Entry> searchCandidates(String query, boolean approximate) {
        return searchCandidates(query);
    }

    /**
     * 跟这件物品或方块有关的条目（注册 ID，例如 create:mechanical_mixer）：它的思索场景、要交它的任务书条目……
     * 物品资料页把各来源的回答列在"相关资料"里；默认没有。
     */
    default List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        return List.of();
    }

    /** 资源模板，供支持模板发现的客户端展示查询格式。 */
    default JsonArray templates() {
        return new JsonArray();
    }

    /**
     * 来源此刻的现状，写给 LLM 看的一句话，带上来源的名字，例如"Create 思索：可用，312 个场景""任务书：服务器还没同步"；
     * 资料目录最后附各来源的这一句，空结果不冒充"没有这回事"。
     */
    default String status() {
        return "unavailable";
    }
}
