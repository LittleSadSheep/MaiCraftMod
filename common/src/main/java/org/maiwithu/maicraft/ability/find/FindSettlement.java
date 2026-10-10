// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 寻找的结算：找满即收工；扫完仍不足就写清找到几个、查了什么范围。
 * "查过已加载区没有"不等于"世界里没有"——这句话术只在这里出现一次，
 * 成功的结算里不出现。纯计算，不读世界；任务在收束时调用。
 */
final class FindSettlement {

    private FindSettlement() {}

    /** 找满了：把命中交给 LLM，下一步去那里还是采掉由它决定，不写死指路话术。 */
    static TaskResult done(FindInput input, List<FindDetails.Found> found, Map<String, Integer> byType) {
        return TaskResult.builder(TaskResult.Status.DONE, summary(input, found.size()))
                .details(new FindDetails(found, input.radius(), true, byType))
                .build();
    }

    /** 扫完了却不够数：有收获按部分完成，一个都没有按没找到失败；两种都写明查了多大范围。 */
    static TaskResult insufficient(FindInput input, List<FindDetails.Found> found,
            Map<String, Integer> byType) {
        Problem problem = Problem.of(Problem.Kind.NOT_FOUND, searched(input),
                "走近一些再找，或先用出行到大概念有的地方再找；要搜更大的范围要靠勘察，还没有");
        if (found.isEmpty()) {
            return TaskResult.failed("寻找：这一片没有找到" + String.join("、", input.selectors()), problem);
        }
        return TaskResult.builder(TaskResult.Status.PARTIAL, summary(input, found.size()))
                .problem(problem)
                .details(new FindDetails(found, input.radius(), true, byType))
                .build();
    }

    /** 结算共用的查证话术：查了多大范围、查过没有不等于没有。 */
    static String searched(FindInput input) {
        return "半径 " + input.radius() + " 格的已加载区已经找完，没有更多看得见的"
                + input.selectors().get(0) + "；查过没有不等于世界里没有";
    }

    // 一句话结论：找到了几个、最近的一个在哪，直播解说可以直接念。
    private static String summary(FindInput input, int foundCount) {
        return "找到 " + foundCount + " 个（要 " + input.count() + " 个）";
    }
}
