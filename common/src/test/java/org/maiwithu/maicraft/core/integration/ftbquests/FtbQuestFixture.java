// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 模拟已同步的任务书公开接口；用隐藏任务和大编号验证读取范围，而不启动游戏或伪造 FTB 包名。 */
public final class FtbQuestFixture {
    public File file = new File();
    public UUID player = UUID.randomUUID();
    public Object connection = new Object();
    public boolean online = true;
    public final Chapter chapter = new Chapter(1, "起步");
    public final Quest quest = new Quest(0xFEDCBA9876543210L, "收集铁锭", chapter);
    public final Task task = new Task(3, "铁锭", "ftbquests:item");
    public final ReflectiveFtbQuestsAccess access = new ReflectiveFtbQuestsAccess(() -> online
            ? new ReflectiveFtbQuestsAccess.Environment(file, player, "minecraft:overworld", connection) : null);
    public FtbQuestFixture() {
        file.chapters.add(chapter); chapter.quests.add(quest); quest.tasks.add(task);
    }
    public static class Node {
        final long id;
        final String title;
        public boolean visible = true, completed;
        public Node(long id, String title) { this.id = id; this.title = title; }
        public long getId() { return id; }
        public Component getTitle() { return Component.literal(title); }
        public boolean isVisible(Team team) { return visible; }
        public Kind getObjectType() { return new Kind(this instanceof Chapter ? "chapter" : "quest"); }
    }
    public record Kind(String id) { public String getId() { return id; } }
    public static final class File {
        public Team selfTeamData = new Team(this);
        public final List<Chapter> chapters = new ArrayList<>();
        public boolean valid = true, disabled;
        public boolean isValid() { return valid; }
        public boolean isDisableGui() { return disabled; }
        public String getLocale() { return "zh_cn"; }
        public void forAllChapters(Consumer<Chapter> consumer) { chapters.forEach(consumer); }
    }
    public static final class Team {
        public UUID id = UUID.randomUUID();
        public File file;
        public boolean locked;
        public Team(File file) { this.file = file; }
        public UUID getTeamId() { return id; }
        public File getFile() { return file; }
        public String getName() { return "测试队伍"; }
        public boolean isLocked() { return locked; }
        public boolean isCompleted(Node node) { return node.completed; }
        public boolean isStarted(Node node) { return false; }
        public boolean canStartTasks(Quest quest) { return quest.startable; }
        public boolean areDependenciesComplete(Quest quest) { return quest.dependenciesComplete; }
        public long getMilliSecondsUntilRepeatable(Quest quest) { return 0; }
        public long getProgress(Task task) { return task.progress; }
        // 所有写入口都立即报错，避免测试把“读取时偷偷完成任务”误认为正常的进度更新。
        public void setProgress(Task task, long value) { throw new AssertionError("不能改任务进度"); }
        public Team getOrCreateTeamData(UUID id) { throw new AssertionError("不能创建队伍"); }
    }
    public static final class Chapter extends Node {
        public final List<Quest> quests = new ArrayList<>();
        public final List<Link> links = new ArrayList<>();
        public boolean hideText;
        public Chapter(long id, String title) { super(id, title); }
        public List<Quest> getQuests() { return quests; }
        public List<Link> getQuestLinks() { return links; }
        public List<String> getRawSubtitle() { return List.of("第一章说明"); }
        public boolean isHideTextUntilComplete() { return hideText; }
    }
    public record Link(Quest quest) {
        public boolean isVisible(Team team) { return quest.visible; }
        public Optional<Quest> getQuest() { return Optional.of(quest); }
    }
    public record Tri(boolean hidden) { public boolean get(boolean fallback) { return hidden || fallback; } }
    public static final class Quest extends Node {
        private final Kind dependencyRequirement = new Kind("one_started");
        public final Chapter chapter;
        public final List<Task> tasks = new ArrayList<>();
        public final List<Node> dependencies = new ArrayList<>();
        public boolean startable = true, dependenciesComplete, hideDetails, hideText;
        public int bodyReads;
        public Quest(long id, String title, Chapter chapter) { super(id, title); this.chapter = chapter; }
        public boolean hideDetailsUntilStartable() { return hideDetails; }
        public Chapter getChapter() { return chapter; }
        public Tri getHideTextUntilComplete() { return new Tri(hideText); }
        public Component getSubtitle() { return Component.literal("准备材料"); }
        public List<Component> getDescription() { bodyReads++; return List.of(Component.literal("收集八个铁锭")); }
        public List<String> getRawDescription() { return List.of("收集八个铁锭"); }
        public String getGuidePage() { return ""; }
        public String getProgressionMode() { return "FLEXIBLE"; }
        public int getMinRequiredDependencies() { return 1; }
        public boolean getRequireSequentialTasks() { return true; }
        public boolean isOptional() { return false; }
        public boolean canBeRepeated() { return false; }
        public Stream<Node> streamDependencies() { return dependencies.stream(); }
        public List<Task> getTasks() { return tasks; }
    }
    public record Type(String id) { public String getTypeId() { return id; } }
    public static final class Task extends Node {
        public final String type;
        public long progress = 2;
        public int definitionReads;
        public Task(long id, String title, String type) { super(id, title); this.type = type; }
        public Type getType() { return new Type(type); }
        public long getMaxProgress() { return 8; }
        public String formatMaxProgress() { return "8"; }
        public String formatProgress(Team team, long progress) { return Long.toString(progress); }
        public boolean isOptionalForProgression(Team team) { return false; }
        public boolean consumesResources() { return true; }
        public HolderLookup.Provider holderLookup() { return RegistryAccess.EMPTY; }
        public void writeData(CompoundTag data, HolderLookup.Provider provider) {
            definitionReads++; data.putString("item", "minecraft:iron_ingot"); data.putLong("count", 8);
            data.putString("match_components", "exact");
        }
        public ItemStack getItemStack() { return new ItemStack(Items.IRON_INGOT); }
        public boolean isOnlyFromCrafting() { return true; }
        public boolean isTaskScreenOnly() { return true; }
        public List<ItemStack> getValidDisplayItems() { return List.of(getItemStack()); }
    }
}
