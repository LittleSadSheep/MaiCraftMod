package org.maiwithu.maicraft.core.task.base;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 按实体编号、位置等调用方选定的键，记住本轮不再尝试的目标。
 * blacklist 与 skip 当前作用相同，都会一直排除到 reset；没有单独的临时跳过期限。
 */
public final class TargetSet<T> {

    private final Function<T, Object> key;
    private final Set<Object> excluded = new HashSet<>();

    public TargetSet(Function<T, Object> key) {
        this.key = key;
    }

    /** 永久排除 {@code t}，例如挖矿任务中确认不可达的矿物。 */
    public void blacklist(T t) {
        excluded.add(key.apply(t));
    }

    /** 永久排除 {@code t}。 */
    public void skip(T t) {
        excluded.add(key.apply(t));
    }

    /** 当前是否排除了 {@code t}？ */
    public boolean isExcluded(T t) {
        return excluded.contains(key.apply(t));
    }

    /**
     * 按 {@code preference} 从 {@code candidates} 中选择最优的未排除候选（比较器下最小者）；若列表为空或所有候选均被排除则返回空。
     */
    // 先按调用方给的键排除被跳过的目标，再按偏好选最小者；这里不改变原候选列表。
    public Optional<T> pick(List<T> candidates, Comparator<T> preference) {
        return candidates.stream()
                .filter(c -> !isExcluded(c))
                .min(preference);
    }

    /** 清除所有排除记录。 */
    public void reset() {
        excluded.clear();
    }
}
