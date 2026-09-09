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

    /** Permanently exclude {@code t} (mine's "unreachable ore" sense). */
    public void blacklist(T t) {
        excluded.add(key.apply(t));
    }

    /** Permanently exclude {@code t}. */
    public void skip(T t) {
        excluded.add(key.apply(t));
    }

    /** Is {@code t} currently excluded? */
    public boolean isExcluded(T t) {
        return excluded.contains(key.apply(t));
    }

    /**
     * The best non-excluded candidate from {@code candidates} by {@code preference}
     * (the smallest under the comparator), or empty if all are excluded or the
     * list is empty.
     */
    // 先按调用方给的键排除被跳过的目标，再按偏好选最小者；这里不改变原候选列表。
    public Optional<T> pick(List<T> candidates, Comparator<T> preference) {
        return candidates.stream()
                .filter(c -> !isExcluded(c))
                .min(preference);
    }

    /** Forget every exclusion. */
    public void reset() {
        excluded.clear();
    }
}