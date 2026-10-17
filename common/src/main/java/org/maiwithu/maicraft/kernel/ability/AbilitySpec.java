// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力规格：关于一个能力、不随运行变化的全部信息都在这里——ID、用途、能力说明、参数、接受的目标对象、
 * 执行方式、需要的模组、相关知识条目，以及是否列出。
 *
 * <p>其他地方需要"某类能力"时，查规格上的属性，不比对能力 ID。
 *
 * @param id            能力 ID，形如 maicraft:sleep
 * @param summary       能力列表里的一句话用途
 * @param doc           能力说明：写给 LLM 看的用法
 * @param paramSpecs    全部参数规格：参数只在这里定义一次
 * @param targets       接受哪些种类的目标对象；不接受目标对象时为空
 * @param mode          执行方式
 * @param requiredMods  需要的联动模组；全部装了才可用
 * @param knowledgeUris 相关知识条目的 URI
 * @param listing       是否出现在默认的能力列表里
 */
public record AbilitySpec(
        String id,
        String summary,
        AbilityDoc doc,
        ParamSpecs paramSpecs,
        Set<TargetKind> targets,
        ExecutionMode mode,
        Set<RequiredMod> requiredMods,
        List<String> knowledgeUris,
        Listing listing) {

    private static final Pattern ID = Pattern.compile(Pattern.quote(ModIdentity.MOD_ID) + ":[a-z][a-z0-9_]*");

    public AbilitySpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(paramSpecs, "paramSpecs");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(listing, "listing");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("能力 ID 应形如 " + ModIdentity.MOD_ID + ":小写名：" + id);
        }
        if (summary.isBlank()) throw new IllegalArgumentException("能力 " + id + " 缺少一句话用途");
        targets = targets == null ? Set.of() : Set.copyOf(targets);
        requiredMods = requiredMods == null ? Set.of() : Set.copyOf(requiredMods);
        knowledgeUris = knowledgeUris == null ? List.of() : List.copyOf(knowledgeUris);
    }

    /** 能力名（去掉命名空间），例如 sleep；能力说明的资源文件按它命名。 */
    public String name() {
        return id.substring(id.indexOf(':') + 1);
    }
}
