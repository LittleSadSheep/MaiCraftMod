// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.concurrent.atomic.AtomicReference;

/** Pure read-scope boundaries, independent of Minecraft startup or any mutable player input. */
public final class PlacementSneakProjectionTest {
    private PlacementSneakProjectionTest() {}

    public static void main(String[] args) throws Exception {
        Object player = new Object(), other = new Object();
        check(!PlacementSneakProjection.project(player, false) && PlacementSneakProjection.project(player, true), "Inactive getter changed actual input");
        for (boolean actual : new boolean[]{false, true}) {
            boolean candidate = !actual;
            PlacementSneakProjection.withCandidate(player, candidate, () -> {
                check(PlacementSneakProjection.project(player, actual) == candidate, "Candidate posture leaked the actual shift state");
                check(PlacementSneakProjection.project(other, actual) == actual, "Prediction changed another player's getter");
                return null;
            });
            check(PlacementSneakProjection.project(player, actual) == actual, "Projection survived the completed prediction");
        }
        PlacementSneakProjection.withCandidate(player, true, () -> {
            try {
                PlacementSneakProjection.withCandidate(player, false, () -> {
                    check(!PlacementSneakProjection.project(player, true), "Inner prediction did not mask the outer posture");
                    throw new IllegalStateException("native prediction failed");
                });
                throw new AssertionError("Native exception was swallowed");
            } catch (IllegalStateException expected) {
                check(PlacementSneakProjection.project(player, false), "Exceptional inner prediction erased the outer scope");
            }
            PlacementSneakProjection.withCandidate(other, false, () -> {
                check(!PlacementSneakProjection.project(other, true), "Another candidate did not receive its own posture");
                check(!PlacementSneakProjection.project(player, false), "An inactive outer candidate affected an unrelated prediction");
                return null;
            });
            check(PlacementSneakProjection.project(player, false), "Nested player scope was not restored");
            return null;
        });
        try { PlacementSneakProjection.withCandidate(player, true, () -> { throw new AssertionError("native error"); }); }
        catch (AssertionError expected) { check(expected.getMessage().equals("native error"), "Error identity changed"); }
        check(!PlacementSneakProjection.project(player, false), "Error path leaked candidate posture");
        AtomicReference<Boolean> crossThread = new AtomicReference<>();
        Thread worker = new Thread(() -> crossThread.set(PlacementSneakProjection.project(player, false)), "placement-read-projection-test");
        PlacementSneakProjection.withCandidate(player, true, () -> {
            worker.start();
            try { worker.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            check(!worker.isAlive() && Boolean.FALSE.equals(crossThread.get()), "Candidate posture escaped to another thread");
            return null;
        });
        check(!PlacementSneakProjection.project(player, false), "Thread isolation check leaked scope");
        System.out.println("PlacementSneakProjectionTest: opposite postures, identity/thread isolation and exceptional nesting passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
