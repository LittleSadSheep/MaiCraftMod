package org.maiwithu.maicraft.core.task.build;

/** Wait for genuine convergence, not forever for an unchanged or externally overridden camera. */
final class BuildAimProgress {
    private long lastTick = Long.MIN_VALUE;
    private double bestError = Double.POSITIVE_INFINITY;
    private int idleTicks;

    boolean stalled(long tick, double errorDegrees) {
        if (lastTick == Long.MIN_VALUE || tick < lastTick || tick - lastTick > 1) {
            bestError = errorDegrees; idleTicks = 0;
        } else if (tick != lastTick) {
            if (errorDegrees < bestError - .1) { bestError = errorDegrees; idleTicks = 0; }
            else idleTicks++;
        }
        lastTick = tick;
        return idleTicks >= 40;
    }

    void reset() { lastTick = Long.MIN_VALUE; bestError = Double.POSITIVE_INFINITY; idleTicks = 0; }
}
