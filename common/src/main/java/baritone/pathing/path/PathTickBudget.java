// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.path;

/** Bounds same-tick movement handoffs; revisiting a cursor cannot represent physical progress. */
final class PathTickBudget {
    enum Decision { RUN, YIELD, CYCLE }

    private final int[] visited = new int[16];
    private int count;

    void reset() { count = 0; }

    Decision enter(int position) {
        for (int i = 0; i < count; i++) {
            if (visited[i] == position) return Decision.CYCLE;
        }
        if (count == visited.length) return Decision.YIELD;
        visited[count++] = position;
        return Decision.RUN;
    }
}
