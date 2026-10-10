// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import java.util.Objects;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * FTB 任务的联动入口：把任务书交给查资料。读任务书的那一层（读写接缝 QuestBook 与它的读写端）只有一份，
 * quest 能力的提交、勾选、领奖也经它读"这一项现在能不能交、奖励领没领"。
 */
public final class FtbQuestsCompat extends CompatModule {

    public static final String MOD_ID = "ftbquests";

    private final QuestBook book;

    public FtbQuestsCompat(QuestBook book) {
        super(MOD_ID, "FTB 任务");
        this.book = Objects.requireNonNull(book, "book");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 任务书当资料来源：索引、章、条目各一篇，只给玩家在书里看得见的。
        registry.knowledgeSource(this, new QuestBookPages(this, book));
    }

    /** 读任务书的那一层，quest 能力的联动实现也用它。 */
    public QuestBook book() {
        return book;
    }
}
