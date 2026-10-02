package org.maiwithu.maicraft.core.task.explore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 写盘期间继续走到新地点、取消任务或暂时写盘失败时，都不得丢弃最后观察到的事实。 */
public final class ExplorationJournalTest {
    public static void main(String[] args) {
        var queue = new ArrayDeque<Runnable>();
        var saved = new ArrayList<ExplorationFinding>();
        var journal = new ExplorationJournal(saved::addAll, queue::add, ignored -> {});
        var seen = at(0, false); journal.observe(seen); journal.flush();
        journal.observe(at(1, true)); journal.observe(at(128, true)); journal.close();
        while (!queue.isEmpty()) queue.remove().run();
        check(journal.pendingCount() == 0 && saved.stream().anyMatch(ExplorationFinding::visited), "close drains changes made during write");
        boolean[] fail = {true};
        var retry = new ExplorationJournal(batch -> { if (fail[0]) throw new IOException("disk busy"); saved.addAll(batch); }, Runnable::run, ignored -> {});
        retry.observe(at(256, true)); retry.close();
        check(retry.pendingCount() == 1 && retry.receipt().get("save_state").equals("failed_retained_for_retry"), "failed writes remain inspectable");
        fail[0] = false; retry.flush();
        check(retry.pendingCount() == 0 && retry.receipt().get("save_state").equals("saved"), "retry preserves observations");
        System.out.println("ExplorationJournalTest: passed");
    }
    private static ExplorationFinding at(int x, boolean visited) {
        return ExplorationFinding.observed("biome", "minecraft:beach", "海岸", "minecraft:overworld", x, 64, 0, visited, 100, "{}");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
