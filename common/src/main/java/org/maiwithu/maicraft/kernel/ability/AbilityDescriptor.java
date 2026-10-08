// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.platform.ModIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力描述符：关于一个能力的全部"静态知识"都在这里，取代 v1 散在十几个文件里的名单和 switch
 * （能力清单、概要表、可用性检查、知识引用、执行方式……）。
 *
 * <p>其他地方需要"某类能力"时，查描述符上的属性，不比对能力 ID。
 *
 * @param id            能力 ID，形如 maicraft:sleep
 * @param summary       能力目录里的一句话用途
 * @param contract      契约正文
 * @param params        参数规格：参数的唯一定义
 * @param targets       接受哪些种类的目标；不接受目标时为空
 * @param mode          执行方式
 * @param requires      可用的前提；全部满足才算可用
 * @param knowledgeRefs 相关的知识条目 URI
 * @param visibility    是否在能力目录里默认展示
 */
public record AbilityDescriptor(
        String id,
        String summary,
        ContractText contract,
        ParamSpec params,
        Set<TargetKind> targets,
        ExecutionMode mode,
        Set<Requirement> requires,
        List<String> knowledgeRefs,
        Visibility visibility) {

    private static final Pattern ID = Pattern.compile(Pattern.quote(ModIdentity.MOD_ID) + ":[a-z][a-z0-9_]*");

    public AbilityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(visibility, "visibility");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("能力 ID 应形如 " + ModIdentity.MOD_ID + ":小写名：" + id);
        }
        if (summary.isBlank()) throw new IllegalArgumentException("能力 " + id + " 缺少一句话用途");
        targets = targets == null ? Set.of() : Set.copyOf(targets);
        requires = requires == null ? Set.of() : Set.copyOf(requires);
        knowledgeRefs = knowledgeRefs == null ? List.of() : List.copyOf(knowledgeRefs);
    }

    /** 能力名（去掉命名空间），例如 sleep；契约正文资源按它命名。 */
    public String name() {
        return id.substring(id.indexOf(':') + 1);
    }
}
