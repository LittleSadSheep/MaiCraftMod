package org.maiwithu.maicraft.core.pathing.baritone;

public final class NavigationProgressTest {
    public static void main(String[] args) {
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0);
        if (progress.recent(0, 20)) throw new AssertionError("initial sampling is not progress");
        for (int tick = 1; tick <= 200; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick);
        }
        if (progress.recent(200, 20) || progress.stalledTicks(200) != 200) {
            throw new AssertionError("doorway block-boundary jitter must not renew a lease");
        }
        progress.observe(1.30, 64, 0, 201);
        if (!progress.recent(201, 0)) throw new AssertionError("physical displacement must count");
        if (progress.recent(222, 20)) throw new AssertionError("progress must expire without new evidence");
        progress.confirm(230);
        if (!progress.recent(231, 2)) throw new AssertionError("confirmed native changes must count");
        if (progress.recent(229, 20)) throw new AssertionError("clock rollback cannot invent recent evidence");
        System.out.println("NavigationProgressTest: passed");
    }
}
