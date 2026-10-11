// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 照明样本：要验收亮度的格，采样时定下、复测时冻结重测。种类记它当初为什么算样本
 * （能站住的格、庄稼、耕地上方），农田档只收后两种。
 *
 * @param pos  样本格
 * @param kind 采样时的种类
 */
public record LightSample(BlockPos pos, SampleRules.SampleKind kind) {

    public LightSample {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        kind = Objects.requireNonNull(kind, "kind");
    }
}
