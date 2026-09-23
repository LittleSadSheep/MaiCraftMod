// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.UUID;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Chapter;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Link;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Quest;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Task;

/** 验证同步时机、玩家身份和原生可见性，确保读取任务书不会改变任务或通过旁路揭示隐藏内容。 */
public final class FtbQuestAccessTest {
    public static void main(String[] args) {
        // 切服时即使旧文件仍有效，也必须等待新连接的原生任务书同步。
        Object file = new Object(), connection = new Object();
        FtbQuestSync.received(file, connection);
        check(FtbQuestSync.matches(file, connection) && !FtbQuestSync.matches(file, new Object())
                && !FtbQuestSync.matches(new Object(), connection), "同步回执同时绑定文件与连接");
        FtbQuestSync.received(null, null);
        check(!FtbQuestSync.matches(file, connection), "失效的同步回执不能沿用");
        var fixture = new FtbQuestFixture();
        var hidden = new Quest(4, "隐藏目标", fixture.chapter); hidden.visible = false;
        fixture.chapter.quests.add(hidden); fixture.quest.dependencies.add(hidden);
        var hiddenChapter = new Chapter(5, "秘密章节"); hiddenChapter.visible = false;
        var linked = new Quest(6, "可见链接", hiddenChapter); hiddenChapter.quests.add(linked);
        fixture.file.chapters.add(hiddenChapter); fixture.chapter.links.add(new Link(linked));
        var first = fixture.access.snapshot();
        check(first.context().get("session_id").equals(fixture.access.snapshot().context().get("session_id")), "同一连接的连续读取保留分页身份");
        check(first.status().equals("available") && first.chapters().size() == 1, "遵守章节可见性");
        check(first.chapters().getFirst().quests().size() == 2, "隐藏任务不列出，可见任务链接保留");
        var quest = first.chapters().getFirst().quests().getFirst();
        check(quest.id().equals("FEDCBA9876543210") && fixture.quest.bodyReads == 0, "编号无精度损失且发现不读正文");
        check(quest.summary().get("can_start_tasks").getAsBoolean() && !quest.summary().get("dependencies_satisfied").getAsBoolean(),
                "灵活进度允许提前开始，不把可开始等同前置完成");
        var detail = quest.details().get();
        check(detail.get("dependency_requirement").getAsString().equals("one_started"), "保留任一前置开始的规则，不冒充全部完成");
        check(detail.getAsJsonArray("dependencies").isEmpty() && detail.get("hidden_dependency_count").getAsInt() == 1,
                "前置详情不泄露隐藏目标");
        var task = detail.getAsJsonArray("tasks").get(0).getAsJsonObject();
        check(task.get("progress").getAsString().equals("2") && task.get("required").getAsString().equals("8")
                && task.get("consumes_resources").getAsBoolean() && task.get("only_from_crafting").getAsBoolean()
                && task.get("task_screen_only").getAsBoolean() && task.get("match_components").getAsString().equals("exact"),
                "保留消耗、合成来源、任务屏幕和组件匹配条件");
        // 隐藏详情时连任务条件都不展开，隐藏正文时保留允许玩家查看的任务目标。
        fixture.quest.hideDetails = true; fixture.quest.startable = false;
        check(!fixture.access.snapshot().chapters().getFirst().quests().getFirst().details().get().has("tasks"), "锁定详情不读条件");
        fixture.quest.startable = true; fixture.quest.hideText = true; fixture.quest.bodyReads = 0;
        var textHidden = fixture.access.snapshot().chapters().getFirst().quests().getFirst().details().get();
        check(!textHidden.has("description") && !textHidden.has("description_raw") && fixture.quest.bodyReads == 0, "隐藏剧情正文不读取");
        fixture.task.progress = 8;
        check(!FtbQuestTasks.read(fixture.task, fixture.file.selfTeamData).get("completed").getAsBoolean(), "达到数量不推断任务已经完成");
        Task custom = new Task(7, "扩展任务", "addon:custom");
        check(FtbQuestTasks.read(custom, fixture.file.selfTeamData).get("conditions_status").getAsString().equals("unsupported")
                && custom.definitionReads == 0, "未知类型保留进度并说明条件未解析");

        String session = first.context().get("session_id").getAsString();
        fixture.file.selfTeamData.id = UUID.randomUUID();
        check(!fixture.access.snapshot().context().get("session_id").getAsString().equals(session), "换队伍产生新的快照身份");
        session = fixture.access.snapshot().context().get("session_id").getAsString(); fixture.connection = new Object();
        check(!fixture.access.snapshot().context().get("session_id").getAsString().equals(session), "即使队伍相同，重新连接也撤销旧分页身份");
        fixture.file.selfTeamData.id = new UUID(0, 0); status(fixture, "sync_pending");
        fixture.file.selfTeamData.id = UUID.randomUUID(); fixture.file.selfTeamData.locked = true; status(fixture, "book_locked");
        fixture.file.selfTeamData.locked = false; fixture.file.disabled = true; status(fixture, "book_disabled");
        fixture.file.disabled = false; fixture.file.valid = false; status(fixture, "sync_pending");
        fixture.file.valid = true; fixture.file.chapters.clear();
        check(fixture.access.snapshot().status().equals("available") && fixture.access.snapshot().chapters().isEmpty(), "同步后的空书仍可正常读取");
        fixture.file = null; status(fixture, "sync_pending");
        fixture.online = false; status(fixture, "no_world");
        check(new ReflectiveFtbQuestsAccess().snapshot().status().equals("not_installed"), "FTB 缺席不强制加载游戏或崩溃");
        System.out.println("FtbQuestAccessTest: passed");
    }
    private static void status(FtbQuestFixture fixture, String expected) {
        var snapshot = fixture.access.snapshot();
        check(snapshot.status().equals(expected) && snapshot.chapters().isEmpty(), "不可用状态必须明确且不返回旧目录：" + expected);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
