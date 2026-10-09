// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.knowledge;

import com.google.gson.JsonArray;
import java.util.List;

/**
 * 知识来源：一个可发现的只读资料集合（注册表事实、任务书、教程、配方……）。
 *
 * <p>启动时按模组是否安装登记实现；一个来源没装只影响自己的条目，不牵连其他来源。
 * 目录与搜索只比较元数据，正文由调用方按返回的 URI 按需读取。
 */
public interface KnowledgeSource {
    /** 当前可发现的条目；带状态前缀时由 status() 说明为什么不全。 */
    List<KnowledgeDocument.Entry> entries();

    /** 读取一个本来源管辖的 URI；不归它管时返回 null，由资源库统一回答未找到。 */
    KnowledgeDocument read(String uri);

    /** 搜索候选；默认与目录相同，来源可以按查询词给出更贴切的候选。 */
    default List<KnowledgeDocument.Entry> searchCandidates(String query) {
        return entries();
    }

    /** 近似搜索候选：允许条目名有错字、漏字时的召回；候选不足时排序才能补救。 */
    default List<KnowledgeDocument.Entry> searchCandidates(String query, boolean approximate) {
        return searchCandidates(query);
    }

    /** 资源模板，供支持模板发现的客户端展示查询格式。 */
    default JsonArray templates() {
        return new JsonArray();
    }

    /** 来源当前状态，如 available、not_installed、no_world；空结果不冒充“没有这回事”。 */
    default String status() {
        return "unavailable";
    }
}
