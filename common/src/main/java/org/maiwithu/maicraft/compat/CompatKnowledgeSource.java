// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 联动模组交来的知识来源，登记表给它包的一层：模组停用后目录为空、读不到正文、状态写明停用原因；
 * 来源自己碰到模组接口对不上也按同样方式回答，查资料不会因为一个模组装错版本而整体报错。
 */
public final class CompatKnowledgeSource implements KnowledgeSource {

    private final CompatModule module;
    private final KnowledgeSource source;

    public CompatKnowledgeSource(CompatModule module, KnowledgeSource source) {
        this.module = Objects.requireNonNull(module, "module");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        if (!module.active()) {
            return List.of();
        }
        try {
            return source.entries();
        } catch (ModApiMismatch broken) {
            return List.of();
        }
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!module.active()) {
            return null;
        }
        try {
            return source.read(uri);
        } catch (ModApiMismatch broken) {
            return null;
        }
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        if (!module.active()) {
            return List.of();
        }
        try {
            return source.searchCandidates(query);
        } catch (ModApiMismatch broken) {
            return List.of();
        }
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query, boolean approximate) {
        if (!module.active()) {
            return List.of();
        }
        try {
            return source.searchCandidates(query, approximate);
        } catch (ModApiMismatch broken) {
            return List.of();
        }
    }

    @Override public JsonArray templates() {
        if (!module.active()) {
            return new JsonArray();
        }
        try {
            return source.templates();
        } catch (ModApiMismatch broken) {
            return new JsonArray();
        }
    }

    @Override public String status() {
        if (!module.active()) {
            return "disabled: " + module.disabledReason().orElse("");
        }
        return source.status();
    }
}
