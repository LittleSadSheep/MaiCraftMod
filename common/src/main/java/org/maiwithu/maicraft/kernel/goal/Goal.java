// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.param.Params;

import java.util.List;
import java.util.Objects;

/**
 * 目标：LLM 想要的一个结果。只说"要什么"，不说"怎么做"。
 *
 * <p>参数在 MCP 入口就已按该能力的参数规格解析、校验并整理好，这里拿到的是带类型的取值，
 * 能力不再自己解析 JSON。
 *
 * @param ability     能力 ID
 * @param purpose     一句话说明为什么要做这件事，只用于日志和直播解说，Mod 不据此行动；可以为 null
 * @param target      目标对象：要去的地方或要处理的东西；能力不需要时为 null
 * @param params      解析后的参数
 * @param permissions 这次任务的许可，没给时用默认值
 * @param steps       只用于按顺序做几件事的 sequence：每一步的目标
 * @param onFailure   只用于 sequence 里的一步：这一步失败后整件事停下还是继续
 */
public record Goal(
        String ability,
        String purpose,
        Target target,
        Params params,
        Permissions permissions,
        List<Goal> steps,
        OnFailure onFailure) {

    /** sequence 里某一步失败后的走向。 */
    public enum OnFailure {
        STOP,
        CONTINUE
    }

    public Goal {
        Objects.requireNonNull(ability, "ability");
        params = params == null ? Params.EMPTY : params;
        permissions = permissions == null ? Permissions.DEFAULT : permissions;
        steps = steps == null ? List.of() : List.copyOf(steps);
        onFailure = onFailure == null ? OnFailure.STOP : onFailure;
    }

    /** 最常见的写法：一个能力、一个目标对象、一组参数，许可用默认值。 */
    public static Goal of(String ability, Target target, Params params) {
        return new Goal(ability, null, target, params, Permissions.DEFAULT, List.of(), OnFailure.STOP);
    }
}
