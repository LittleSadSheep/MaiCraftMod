// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Optional;

/**
 * 能力私有步骤状态的存取接缝：只有能力自己知道这一步做到哪了（例如扫描扫到哪一格），
 * 这些状态要跨重启保存时，由能力自己编码，存储实现按（能力，键）保存与读回。
 *
 * <p>编码格式由能力与存储实现约定（例如 JSON 文本），内核不解释内容；存储移植会给出真正的实现。
 */
public interface StepStateStore {

    /** 保存一条能力私有的步骤状态；同（能力，键）再次保存时覆盖。 */
    void save(String abilityId, String key, String encoded);

    /** 读回一条能力私有的步骤状态；没存过时为空。 */
    Optional<String> load(String abilityId, String key);
}
