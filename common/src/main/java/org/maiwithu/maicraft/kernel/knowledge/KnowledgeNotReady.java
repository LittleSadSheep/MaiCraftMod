// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.knowledge;

/**
 * 这一篇资料还在准备，下一刻再来读：例如思索场景的结构要在演示世界里放完一遍，一刻做不完，分几刻做。
 * 读资料的一方等一会儿再读同一篇；等不到就如实说"还在准备，过一会儿再读"，不拿半截结果冒充完整的。
 */
public final class KnowledgeNotReady extends RuntimeException {

    /** @param progress 准备到哪了，写给 LLM 看的一句话，例如"思索场景还在回放，已到第 300 刻，共 1200 刻" */
    public KnowledgeNotReady(String progress) {
        super(progress);
    }
}
