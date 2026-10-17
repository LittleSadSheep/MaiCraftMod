// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import java.util.Objects;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * FTB 任务的联动入口：任务书当资料交给查资料，任务书操作（提交、勾选、领奖）交给 quest 能力。
 * 读任务书的那一层（读写接缝 QuestBook 与它的读写端）只有一份，quest 能力经它读"这一项现在能不能交、奖励领没领"；
 * 发包走自己的发包接缝，读写端直发 FTB 的网络包。
 */
public final class FtbQuestsCompat extends CompatModule {

    public static final String MOD_ID = "ftbquests";

    private final QuestBook book;
    private final QuestBookActions sends;

    public FtbQuestsCompat(QuestBook book, QuestBookActions sends) {
        super(MOD_ID, "FTB 任务");
        this.book = Objects.requireNonNull(book, "book");
        this.sends = Objects.requireNonNull(sends, "sends");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 任务书当资料来源：索引、章、条目各一篇，只给玩家在书里看得见的。
        registry.knowledgeSource(this, new QuestBookPages(this, book));
        // 任务书操作：读用读取接缝，发包用发包接缝；进世界时带着联动能用的玩家行为建，停用后由登记表包一层如实回答。
        registry.questBook(this, services -> new FtbQuestBookOperations(this, book, sends, services.context()));
    }

    /** 读任务书的那一层，quest 能力的联动实现也用它。 */
    public QuestBook book() {
        return book;
    }
}
