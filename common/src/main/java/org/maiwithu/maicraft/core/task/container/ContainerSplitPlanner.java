package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 只计算普通左右键分堆；目的格累计收到的物品绝不超过请求量，不借用第三个槽或临时多取。 */
public final class ContainerSplitPlanner {
    private static final int EXACT_LIMIT = 128, SOURCE_LIMIT = 4096;
    public enum Side { SOURCE, DESTINATION }
    public record State(int source, int cursor, int deposited) {}
    public record Step(Side side, int button, State before, State after) {}
    private ContainerSplitPlanner() {}

    /** returnCapacity 为源格允许放回的容量；输出槽不允许放入时传 -1。 */
    public static List<Step> plan(int source, int amount, int returnCapacity) {
        if (source < 1 || source > SOURCE_LIMIT || amount < 1 || amount > source)
            throw new IllegalArgumentException("split transfer count is outside its bounded source stack");
        if (source > EXACT_LIMIT) return large(new State(source, 0, 0), amount, returnCapacity);
        int width = source + 1, start = source * width, goal = (source - amount) * width;
        int[] previous = new int[width * width]; Arrays.fill(previous, -1);
        Step[] arrived = new Step[previous.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(start); previous[start] = start;
        while (!queue.isEmpty() && previous[goal] < 0) {
            int key = queue.removeFirst(), s = key / width, c = key % width;
            State before = new State(s, c, source - s - c);
            for (Side side : Side.values()) for (int button : side == Side.SOURCE ? new int[]{1, 0} : new int[]{0, 1}) {
                State after = apply(before, side, button, amount, returnCapacity);
                if (after == null) continue;
                int next = after.source * width + after.cursor;
                if (previous[next] >= 0) continue;
                previous[next] = key; arrived[next] = new Step(side, button, before, after); queue.addLast(next);
            }
        }
        if (previous[goal] < 0) throw new IllegalArgumentException("source slot cannot safely return this split remainder");
        List<Step> steps = new ArrayList<>();
        for (int key = goal; key != start; key = previous[key]) steps.add(arrived[key]);
        Collections.reverse(steps); return List.copyOf(steps);
    }

    // 空鼠标右键源格取向上取整的半堆；拿着物品时右键源格退一个、左键目的格放整份。
    static State apply(State state, Side side, int button, int amount, int capacity) {
        int s = state.source, c = state.cursor, d = state.deposited;
        if (side == Side.SOURCE) {
            if (c == 0) {
                if (s == 0) return null;
                int taken = button == 0 ? s : (s + 1) / 2;
                return new State(s - taken, taken, d);
            }
            int returned = button == 0 ? c : 1;
            return s + returned <= capacity ? new State(s + returned, c - returned, d) : null;
        }
        if (c == 0) return null;
        int placed = button == 0 ? c : 1;
        return d + placed <= amount ? new State(s, c - placed, d + placed) : null;
    }

    // 超大 Mod 堆不展开平方数量的搜索状态；比较直接分出与先放半堆两条有界路径。
    private static List<Step> large(State state, int amount, int capacity) {
        int remaining = amount - state.deposited;
        List<Step> best = direct(state, amount, capacity, 0, false);
        for (int button : new int[]{0, 1}) {
            List<Step> candidate = direct(state, amount, capacity, button, true);
            if (candidate != null && (best == null || candidate.size() < best.size())) best = candidate;
        }
        int taken = (state.source + 1) / 2;
        if (taken < remaining) {
            List<Step> prefix = new ArrayList<>();
            State cursor = append(prefix, state, Side.SOURCE, 1, amount, capacity);
            cursor = append(prefix, cursor, Side.DESTINATION, 0, amount, capacity);
            try {
                prefix.addAll(large(cursor, amount, capacity));
                if (best == null || prefix.size() < best.size()) best = prefix;
            } catch (IllegalArgumentException impossible) { /* 保留另一条不需要非法退回输出槽的路径。 */ }
        }
        if (best == null) throw new IllegalArgumentException("source slot cannot safely return this split remainder");
        return List.copyOf(best);
    }
    private static List<Step> direct(State state, int amount, int capacity, int button, boolean returnExcess) {
        List<Step> result = new ArrayList<>();
        State cursor = append(result, state, Side.SOURCE, button, amount, capacity);
        int remaining = amount - cursor.deposited;
        if (cursor.cursor < remaining) return null;
        if (returnExcess) {
            while (cursor.cursor > remaining) {
                cursor = append(result, cursor, Side.SOURCE, 1, amount, capacity);
                if (cursor == null) return null;
            }
            append(result, cursor, Side.DESTINATION, 0, amount, capacity);
        } else {
            if (cursor.cursor == remaining) append(result, cursor, Side.DESTINATION, 0, amount, capacity);
            else {
                for (int i = 0; i < remaining; i++) cursor = append(result, cursor, Side.DESTINATION, 1, amount, capacity);
                if (append(result, cursor, Side.SOURCE, 0, amount, capacity) == null) return null;
            }
        }
        return result;
    }
    private static State append(List<Step> steps, State before, Side side, int button, int amount, int capacity) {
        State after = apply(before, side, button, amount, capacity);
        if (after != null) steps.add(new Step(side, button, before, after));
        return after;
    }
}
