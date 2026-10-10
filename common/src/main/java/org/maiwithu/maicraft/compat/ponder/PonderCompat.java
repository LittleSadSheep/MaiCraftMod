// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ponder;

import java.util.Objects;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 思索（Ponder）的联动入口：把注册的思索场景交给查资料。Ponder 是独立的模组 ID（ponder），
 * Create 把它打包在自己的 jar 里一起装，Create 的附属模组也往里注册场景。
 */
public final class PonderCompat extends CompatModule {

    public static final String MOD_ID = "ponder";

    private final PonderReads reads;

    public PonderCompat(PonderReads reads) {
        super(MOD_ID, "思索（Ponder）");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 思索场景当资料来源：目录里一行索引，搜索与物品资料页里每个场景一条，读的时候在演示世界里放一遍。
        registry.knowledgeSource(this, new PonderScenes(this, reads, System::nanoTime));
    }
}
