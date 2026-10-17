// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用背包界面里普通的左右键，从一堆里分出恰好几件放进一个空格：点得最少的那一串。
 * 规则就是原版的：空着手右键一堆拿起一半（向上取整）、左键拿起整堆；拿着东西右键放一件、左键放全部。
 * 放进空格的累计不超过要分的数，原格放回不超过它装得下的。纯计算，不点。
 */
public final class StackSplit {

    /** 原堆这么多件以内按全部状态找最少点击；再大的模组堆走两条有界的直路。 */
    private static final int EXACT_LIMIT = 128;
    private static final int SOURCE_LIMIT = 4096;

    /** 点哪一格：原格还是空格。 */
    public enum Side { SOURCE, DESTINATION }

    /** 点之前或之后：原格几件、鼠标上几件、空格里几件。 */
    public record State(int source, int cursor, int deposited) {}

    /** 一下点击：点哪一格、左键（0）还是右键（1）、点之前和点之后的样子。 */
    public record Step(Side side, int button, State before, State after) {}

    private StackSplit() {}

    /**
     * 从 source 件里分出 amount 件放进空格的点击串。
     *
     * @param returnCapacity 原格最多装得下几件（放回多拿的用）
     * @throws IllegalArgumentException 数目不合法，或分不出来
     */
    public static List<Step> plan(int source, int amount, int returnCapacity) {
        if (source < 1 || source > SOURCE_LIMIT || amount < 1 || amount > source) {
            throw new IllegalArgumentException("要分的数目不在这一堆里：" + amount + " / " + source);
        }
        if (source > EXACT_LIMIT) return large(new State(source, 0, 0), amount, returnCapacity);
        int width = source + 1;
        int start = source * width;
        int goal = (source - amount) * width;
        int[] previous = new int[width * width];
        Arrays.fill(previous, -1);
        Step[] arrived = new Step[previous.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        previous[start] = start;
        while (!queue.isEmpty() && previous[goal] < 0) {
            int key = queue.removeFirst();
            State before = new State(key / width, key % width, source - key / width - key % width);
            for (Side side : Side.values()) {
                for (int button : side == Side.SOURCE ? new int[] {1, 0} : new int[] {0, 1}) {
                    State after = apply(before, side, button, amount, returnCapacity);
                    if (after == null) continue;
                    int next = after.source() * width + after.cursor();
                    if (previous[next] >= 0) continue;
                    previous[next] = key;
                    arrived[next] = new Step(side, button, before, after);
                    queue.addLast(next);
                }
            }
        }
        if (previous[goal] < 0) throw new IllegalArgumentException("原格放不回多拿的，分不出这么多");
        List<Step> steps = new ArrayList<>();
        for (int key = goal; key != start; key = previous[key]) steps.add(arrived[key]);
        Collections.reverse(steps);
        return List.copyOf(steps);
    }

    // 点一下之后的样子；这一下点不了（空手点空格、放超了、原格装不下）为 null。
    static State apply(State state, Side side, int button, int amount, int capacity) {
        int s = state.source();
        int c = state.cursor();
        int d = state.deposited();
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

    // 超大的模组堆不展开全部状态：比"直接拿起再退回多的"与"先放半堆再分"两条有界路径，取点得少的。
    private static List<Step> large(State state, int amount, int capacity) {
        List<Step> best = direct(state, amount, capacity, 0, false);
        for (int button : new int[] {0, 1}) {
            List<Step> candidate = direct(state, amount, capacity, button, true);
            if (candidate != null && (best == null || candidate.size() < best.size())) best = candidate;
        }
        int remaining = amount - state.deposited();
        if ((state.source() + 1) / 2 < remaining) {
            List<Step> prefix = new ArrayList<>();
            State cursor = append(prefix, state, Side.SOURCE, 1, amount, capacity);
            cursor = append(prefix, cursor, Side.DESTINATION, 0, amount, capacity);
            try {
                prefix.addAll(large(cursor, amount, capacity));
                if (best == null || prefix.size() < best.size()) best = prefix;
            } catch (IllegalArgumentException impossible) {
                // 这一条要把多的放回装不下的原格：留着另一条路。
            }
        }
        if (best == null) throw new IllegalArgumentException("原格放不回多拿的，分不出这么多");
        return List.copyOf(best);
    }

    private static List<Step> direct(State state, int amount, int capacity, int button, boolean returnExcess) {
        List<Step> result = new ArrayList<>();
        State cursor = append(result, state, Side.SOURCE, button, amount, capacity);
        int remaining = amount - cursor.deposited();
        if (cursor.cursor() < remaining) return null;
        if (returnExcess) {
            while (cursor.cursor() > remaining) {
                cursor = append(result, cursor, Side.SOURCE, 1, amount, capacity);
                if (cursor == null) return null;
            }
            append(result, cursor, Side.DESTINATION, 0, amount, capacity);
        } else if (cursor.cursor() == remaining) {
            append(result, cursor, Side.DESTINATION, 0, amount, capacity);
        } else {
            for (int i = 0; i < remaining; i++) cursor = append(result, cursor, Side.DESTINATION, 1, amount, capacity);
            if (append(result, cursor, Side.SOURCE, 0, amount, capacity) == null) return null;
        }
        return result;
    }

    private static State append(List<Step> steps, State before, Side side, int button, int amount, int capacity) {
        State after = apply(before, side, button, amount, capacity);
        if (after != null) steps.add(new Step(side, button, before, after));
        return after;
    }
}
