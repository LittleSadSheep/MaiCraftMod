// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Opaque, Mod-owned protection learned from an observed semantic area.
 *
 * <p>The public intent names an area; a child task discovers its real block footprint.  Concrete
 * cells stay in this receipt and are inherited by later children of the same semantic task.  They
 * are never copied into a public {@link TaskResult}.</p>
 */
public interface InternalAreaProtectionReceipt {

    /**
     * @param semanticLabel optional human label used only as internal provenance
     * @param dimension dimension containing every packed {@code BlockPos}
     * @param protectedMutationCells cells that later work must neither break nor replace
     * @param forbiddenBodyCells feet cells that later navigation/precision stances must not occupy
     */
    record Footprint(
            String semanticLabel,
            String dimension,
            List<Long> protectedMutationCells,
            List<Long> forbiddenBodyCells) {
        public Footprint {
            semanticLabel = semanticLabel == null || semanticLabel.isBlank()
                    ? null : semanticLabel.strip();
            dimension = dimension == null || dimension.isBlank() ? null : dimension.strip();
            protectedMutationCells = immutableDistinct(protectedMutationCells);
            forbiddenBodyCells = immutableDistinct(forbiddenBodyCells);
        }

        private static List<Long> immutableDistinct(List<Long> values) {
            if (values == null || values.isEmpty()) return List.of();
            LinkedHashSet<Long> clean = new LinkedHashSet<>();
            for (Long value : values) if (value != null) clean.add(value);
            return List.copyOf(clean);
        }
    }

    List<Footprint> internalAreaProtections();
}
