// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 模组根据实际观察到的语义区域生成的内部保护回执。
 * 子任务将真实方块范围保留在回执中，供同一语义任务的后续步骤继承；
 * 这些坐标不写入对外的 {@link TaskResult}。
 */
public interface InternalAreaProtectionReceipt {

    /**
     * @param semanticLabel 可选的人类可读标签，仅用于内部追溯保护来源
     * @param dimension 所有打包 {@code BlockPos} 坐标所属的维度
     * @param protectedMutationCells 后续工作不能破坏或替换的方块位置
     * @param forbiddenBodyCells 后续寻路和精确站位不能占据的脚部位置
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
