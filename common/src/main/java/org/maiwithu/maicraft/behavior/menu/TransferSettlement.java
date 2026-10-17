// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 搬运结算：一次搬运的收尾账。
 *
 * <p>只记确认过的事：每笔搬运经确认核对后才累计，之后的失败、打断或别的什么把物品又抽走，
 * 都不改写已经确认的量——点了七次不等于搬了七件，反过来搬进去的三件也不会被后面的失败抹掉。
 * 失败保留最早的原因：后来发生的岔子另有用处，但最先让这件事做不下去的原因不能被冲掉。
 */
public final class TransferSettlement {

    /** 已确认搬运的量，按物品分开记；只增不减。 */
    private final Map<String, Integer> confirmed = new HashMap<>();
    /** 已提交但没能确认的搬运，原样留着，不盲目重做。 */
    private final List<String> unconfirmed = new ArrayList<>();
    /** 最早的一个失败原因；null 表示还没有失败。 */
    private Problem earliestFailure;

    /** 一笔搬运确认结算：物品和件数记进收尾账，之后不会被改写或撤销。 */
    public void confirm(String itemId, int amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (amount < 1) throw new IllegalArgumentException("确认的搬运量必须为正：" + amount);
        confirmed.merge(itemId, amount, Integer::sum);
    }

    /** 一笔已提交但没能确认结果的搬运：写明现场事实，交给调用方如实上报，不盲目重做。 */
    public void unconfirmed(String fact) {
        unconfirmed.add(Objects.requireNonNull(fact, "fact"));
    }

    /** 记一次失败：只保留最早的原因，后来的失败不再顶掉它。 */
    public void fail(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        if (earliestFailure == null) earliestFailure = problem;
    }

    /** 是否已经失败。 */
    public boolean failed() {
        return earliestFailure != null;
    }

    /** 最早的失败原因；没有失败时为 null。 */
    public Problem earliestFailure() {
        return earliestFailure;
    }

    /** 已确认搬运的量，按物品分开；返回的是只读视图。 */
    public Map<String, Integer> confirmedByItem() {
        return Map.copyOf(confirmed);
    }

    /** 已提交但没能确认的搬运，原样的事实列表。 */
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }
}
