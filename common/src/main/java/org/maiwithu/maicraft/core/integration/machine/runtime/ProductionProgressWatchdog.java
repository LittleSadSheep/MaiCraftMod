// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

/** Bounds complete native observation rounds while allowing fresh input and longer physical delivery paths. */
final class ProductionProgressWatchdog {
    private final long initialLimit, processingLimit, deliveryLimit;
    private long mutationTick = -1;

    ProductionProgressWatchdog(long windowTicks, long processingLimit, int longestPath) {
        if (windowTicks < 1 || processingLimit < 1 || longestPath < 0) throw new IllegalArgumentException("Invalid production waiting limits");
        this.processingLimit = processingLimit; initialLimit = Math.max(windowTicks, processingLimit);
        deliveryLimit = Math.max(1200, Math.addExact(processingLimit, Math.multiplyExact((long) longestPath, 40)));
    }

    void noteInput(long tick) {
        if (tick < 0) throw new IllegalArgumentException("Negative native input tick");
        mutationTick = Math.max(mutationTick, tick);
    }

    String failure(long coverageTick, long processingIdle, long lastProcessingTick, boolean productionVerified,
                   long lastDeliveryTick, boolean exhausted, String pacingFailure) {
        if (processingIdle < 0) return null;
        long idle = mutationTick < 0 ? processingIdle : Math.min(processingIdle, Math.max(0, coverageTick - mutationTick));
        if (productionVerified) return deliveryFailure(coverageTick, idle, lastDeliveryTick);
        if (idle <= (lastProcessingTick < 0 ? initialLimit : processingLimit)) return null;
        if (pacingFailure != null) return pacingFailure;
        return exhausted ? "production_input_budget_exhausted_before_required_window" : "production_native_processing_stalled";
    }

    String deliveryFailure(long now, long processingIdle, long lastDeliveryTick) {
        if (processingIdle < 0) return null; // An incomplete round cannot support a no-progress conclusion.
        long idle = processingIdle;
        if (lastDeliveryTick >= 0) idle = Math.min(idle, Math.max(0, now - lastDeliveryTick));
        return idle > deliveryLimit ? "production_native_delivery_stalled_after_processing" : null;
    }
}
