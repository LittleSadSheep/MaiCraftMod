// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 建门的结果细节：这扇门是怎么建的。跨维度行程把它写进自己的结果，LLM 才知道角色是摆的黑曜石还是浇的。
 *
 * @param portalBuiltHow {@code obsidian} 摆黑曜石直建 / {@code lava_casting} 在岩浆池岸浇筑
 */
record PortalBuildDetails(String portalBuiltHow) implements ResultDetails {

    /** 摆黑曜石直建。 */
    static final String OBSIDIAN = "obsidian";
    /** 在岩浆池岸用水浇岩浆成黑曜石。 */
    static final String LAVA_CASTING = "lava_casting";
}
