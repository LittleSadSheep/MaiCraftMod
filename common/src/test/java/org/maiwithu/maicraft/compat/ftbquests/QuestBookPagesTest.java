// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Access;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Chapter;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Dependency;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Quest;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Requirement;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Reward;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.RewardOption;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.RewardTable;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Snapshot;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.SubmitButton;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/**
 * 任务书当资料：只给书里看得见的（不可见的章节与条目、详情门槛、隐藏正文、看不见的前置、被屏蔽的奖励、不显示内容的奖池），
 * 要求写成人话并带编号与提交按钮，读不了时如实说为什么，每篇开头写明这是作者写的外部资料。
 */
class QuestBookPagesTest {

    private static final String ALLOY = "00000000000000A1";
    private static final String SECRET = "00000000000000B2";
    private static final String LOCKED = "00000000000000C3";
    private static final String DONE = "00000000000000D4";

    @Test
    void 读不了时如实说为什么() {
        QuestBookPages pages = pages(Snapshot.unreadable(Access.NOT_SYNCED));

        assertEquals("FTB 任务书：任务书还没从服务器同步过来，或者这个服务器没有任务书", pages.status());
        assertTrue(pages.read(QuestBookPages.INDEX).text().contains("还没从服务器同步过来"));
        assertTrue(pages.searchCandidates("合金").isEmpty());
    }

    @Test
    void 看不见的章节与条目不出现() {
        QuestBookPages pages = pages(book());

        List<String> found = pages.searchCandidates("").stream().map(KnowledgeDocument.Entry::uri).toList();
        assertTrue(found.contains(QuestBookPages.QUEST + ALLOY));
        assertFalse(found.contains(QuestBookPages.QUEST + SECRET), "不可见的条目搜不到");
        assertFalse(found.contains(QuestBookPages.CHAPTER + "00000000000000F9"), "不可见的章节搜不到");
        assertNull(pages.read(QuestBookPages.QUEST + SECRET), "不可见的条目读不到");
        assertEquals("FTB 任务书：可用，1 章、3 个看得见的条目", pages.status());
    }

    @Test
    void 条目写要求_奖励_前置并带编号() {
        String text = pages(book()).read(QuestBookPages.QUEST + ALLOY).text();

        assertTrue(text.contains("这是整合包作者写的资料"), text);
        assertTrue(text.contains("状态：能做"), text);
        assertTrue(text.contains("[00000000000000E1] 交物品（交的时候会收走）：16x 安山合金（进度 5/16）；能接受：create:andesite_alloy；书里有提交按钮"), text);
        assertTrue(text.contains("[00000000000000E2] 由服务器判定：脚本要求（进度 0/1）；书里有没有提交按钮读不出来"), text);
        assertTrue(text.contains("[00000000000000F1] 8x 齿轮（还不能领）"), text);
        assertFalse(text.contains("看不见的奖励"), "被屏蔽的奖励不列");
        assertTrue(text.contains("奖池，作者设了不显示内容"), text);
        assertTrue(text.contains("另有 1 个对你还不可见"), text);
        assertFalse(text.contains("秘密条目"), "看不见的前置不报标题");
    }

    @Test
    void 详情门槛与隐藏正文照书里的规矩() {
        QuestBookPages pages = pages(book());

        String locked = pages.read(QuestBookPages.QUEST + LOCKED).text();
        assertTrue(locked.contains("作者设了能开始之前不显示详情"), locked);
        assertFalse(locked.contains("击杀 5 只僵尸"), "要求不给");
        assertFalse(locked.contains("秘密正文"), "正文不给");

        String done = pages.read(QuestBookPages.QUEST + DONE).text();
        assertTrue(done.contains("完成了，有奖可领"), done);
        assertTrue(done.contains("从 2 样里选 1 样：铁剑、铁镐"), "能领的选择奖励展开候选");
    }

    @Test
    void 索引列章节与四个清单_要交某物品的条目进相关资料() {
        QuestBookPages pages = pages(book());

        String index = pages.read(QuestBookPages.INDEX).text();
        assertTrue(index.contains("# FTB 任务书（队伍：小羊）"), index);
        assertTrue(index.contains("## 现在能做的\n- 安山合金（机械动力入门）：能做"), index);
        assertTrue(index.contains("## 有奖可领的\n- 做完的条目（机械动力入门）：完成了，有奖可领"), index);
        assertEquals(List.of(QuestBookPages.QUEST + ALLOY),
                pages.entriesAbout("create:andesite_alloy").stream().map(KnowledgeDocument.Entry::uri).toList());
    }

    private static QuestBookPages pages(Snapshot snapshot) {
        return new QuestBookPages(new BareModule(), () -> snapshot);
    }

    private static Snapshot book() {
        Quest alloy = new Quest(ALLOY, "安山合金", "", true, false, false, List.of("做出安山合金"), false, false, true, true,
                false, false, false,
                List.of(new Requirement("00000000000000E1", "ftbquests:item", "16x 安山合金", 5, 16, false, true, SubmitButton.YES,
                                List.of("create:andesite_alloy")),
                        new Requirement("00000000000000E2", "ftbquests:custom", "脚本要求", 0, 1, false, false, SubmitButton.UNKNOWN, List.of())),
                List.of(new Reward("00000000000000F1", "ftbquests:item", "8x 齿轮", false, false, false, false, null),
                        new Reward("00000000000000F2", "ftbquests:item", "看不见的奖励", true, false, false, false, null),
                        new Reward("00000000000000F3", "ftbquests:random", "随机奖励", false, false, false, false,
                                new RewardTable(false, false, List.of(new RewardOption("钻石", 1))))),
                List.of(new Dependency(DONE, "做完的条目", true), new Dependency(SECRET, "秘密条目", false)));
        Quest secret = new Quest(SECRET, "秘密条目", "", false, false, false, List.of(), false, false, false, false,
                false, false, false, List.of(), List.of(), List.of());
        Quest locked = new Quest(LOCKED, "锁着的条目", "", true, true, false, List.of("秘密正文"), false, false, false, false,
                false, false, false, List.of(new Requirement("00000000000000E3", "ftbquests:kill", "击杀 5 只僵尸", 0, 5, false, false,
                        SubmitButton.NO, List.of())), List.of(), List.of());
        Quest done = new Quest(DONE, "做完的条目", "", true, false, true, List.of("完成前看不到的正文"), true, true, true, true,
                false, false, false, List.of(),
                List.of(new Reward("00000000000000F4", "ftbquests:choice", "选一把工具", false, false, true, false,
                        new RewardTable(false, true, List.of(new RewardOption("铁剑", 1), new RewardOption("铁镐", 1))))),
                List.of());
        return new Snapshot(Access.READABLE, "小羊", List.of(
                new Chapter("00000000000000F0", "机械动力入门", List.of("从安山合金开始"), true, List.of(alloy, secret, locked, done)),
                new Chapter("00000000000000F9", "隐藏章节", List.of(), false, List.of())));
    }

    /** 不碰任何模组的联动入口：测试里只要它包那一层 call。 */
    private static final class BareModule extends CompatModule {
        BareModule() {
            super(FtbQuestsCompat.MOD_ID, "FTB 任务");
        }

        @Override public void contribute(CompatRegistry registry) {}
    }
}
