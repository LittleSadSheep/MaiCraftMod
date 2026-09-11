// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Objects;
import java.util.function.Supplier;

/** Candidate-only read projection; never writes player input, entity flags, pose or network state. */
public final class PlacementSneakProjection {
    private record Candidate(Object player, boolean sneak) {}
    private static final ThreadLocal<Candidate> CURRENT = new ThreadLocal<>();

    private PlacementSneakProjection() {}

    /** The current prediction masks an outer one until it returns, including on exceptional exits. */
    public static <T> T withCandidate(Object player, boolean sneak, Supplier<T> prediction) {
        Objects.requireNonNull(player, "placement prediction player");
        Objects.requireNonNull(prediction, "placement prediction");
        Candidate previous = CURRENT.get();
        CURRENT.set(new Candidate(player, sneak));
        try { return prediction.get(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Used only by the client LocalPlayer getter bridge; all other reads retain their actual value. */
    public static boolean project(Object player, boolean actual) {
        Candidate candidate = CURRENT.get();
        return candidate != null && candidate.player() == player ? candidate.sneak() : actual;
    }
}
