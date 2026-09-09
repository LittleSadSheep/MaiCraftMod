package org.maiwithu.maicraft.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reflex-chain registry. The single local-player brain instantiates each
 * registered factory once and asks them in ascending {@code order}.
 * {@code order} 决定同 tick 平局时的先后(小者先,
 * 惯例:意图越硬的越小)。
 *
 * <p>Registration is init-time only; {@link #build()} runs when the local
 * client runtime binds a body.
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
