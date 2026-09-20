package org.maiwithu.maicraft.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 启动时登记自救行为；绑定本地玩家身体时，{@link #build()} 为调度器各创建一个执行实例。
 * 同一游戏刻出现多种危险时，按 {@code order} 从小到大检查，第一个能执行的行为优先接管。
 */
public final class BrainChains {

    private record Entry(int order, Supplier<Task> factory) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private BrainChains() {}

    /** 登记一种自动自救行为；order 越小越先检查，例如同一刻既缺氧又遇怪时，先检查的行为先执行。 */
    public static synchronized void register(int order, Supplier<Task> factory) {
        ENTRIES.add(new Entry(order, factory));
    }

    /** 为一个新 Brain 实例化全部注册链(按 order 排序)。 */
    static synchronized List<Task> build() {
        List<Task> out = new ArrayList<>();
        ENTRIES.stream()
                .sorted(Comparator.comparingInt(Entry::order))
                .forEach(e -> out.add(e.factory().get()));
        return out;
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }
}
