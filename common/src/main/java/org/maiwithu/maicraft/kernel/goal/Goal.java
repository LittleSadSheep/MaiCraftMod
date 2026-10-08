// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.param.Params;

import java.util.List;
import java.util.Objects;

/**
 * 目标：LLM 想要的一个结果（docs/design/07 第 3 节）。只描述"要什么"，不描述"怎么做"。
 *
 * <p>参数在入口就已按该能力的参数规格解析、校验并规范化，这里拿到的是类型化取值，
 * 能力不再自己解析 JSON。
 *
 * @param ability   能力 ID
 * @param outcome   一句话说明想要什么，只用于日志和直播解说，Mod 不据此行动；可以为 null
 * @param target    目标位置或对象；能力不需要时为 null
 * @param params    解析后的参数
 * @param mandate   本次任务的授权，未覆盖时为默认档
 * @param children  只用于顺序执行几件事的 sequence：按顺序的子目标
 * @param onFailure 只用于 sequence 的子目标：这一步失败后整件事停下还是继续
 */
public record Goal(
        String ability,
        String outcome,
        Target target,
        Params params,
        Mandate mandate,
        List<Goal> children,
        OnFailure onFailure) {

    /** sequence 子目标失败后的走向。 */
    public enum OnFailure {
        STOP,
        CONTINUE
    }

    public Goal {
        Objects.requireNonNull(ability, "ability");
        params = params == null ? Params.EMPTY : params;
        mandate = mandate == null ? Mandate.DEFAULT : mandate;
        children = children == null ? List.of() : List.copyOf(children);
        onFailure = onFailure == null ? OnFailure.STOP : onFailure;
    }

    /** 最常见的写法：一个能力、一个目标位置、一组参数，授权用默认档。 */
    public static Goal of(String ability, Target target, Params params) {
        return new Goal(ability, null, target, params, Mandate.DEFAULT, List.of(), OnFailure.STOP);
    }
}
