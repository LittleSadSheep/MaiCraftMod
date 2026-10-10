// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.behavior.acquire.LiveCarryReads;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Access;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Chapter;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Quest;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Requirement;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Reward;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.RewardTable;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Snapshot;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FTB 任务书操作的联动实现与它的接线：看得见的才给、候选按界面顺序从 1 编号、发包前占本刻的交互机会。 */
class FtbQuestBookOperationsTest {

    private static final String QUEST = "1234567890ABCDEF";
    private static final String ITEM_REQUIREMENT = "0102030405060708";
    private static final String CHECKMARK = "0F0E0D0C0B0A0908";
    private static final String KILL = "1111111122222222";
    private static final String CUSTOM = "3333333344444444";
    private static final String PLAIN_REWARD = "1122334455667788";
    private static final String CHOICE_REWARD = "9988776655443322";
    private static final String HIDDEN_REWARD = "1212121234343434";
    private static final String MEMORY_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 任务书读取替身：一次快照由测试摆。 */
    private static final class StubBook implements QuestBook {
        Snapshot snapshot = Snapshot.unreadable(Access.NOT_SYNCED);

        @Override public Snapshot read() {
            return snapshot;
        }
    }

    /** 发包替身：记下收到的编号。 */
    private static final class StubActions implements QuestBookActions {
        final List<String> sent = new ArrayList<>();
        final List<int[]> choiceIndexes = new ArrayList<>();
        boolean connected = true;

        @Override public boolean submitTask(long requirementId) {
            return record("submit " + requirementId);
        }

        @Override public boolean claimReward(long rewardId) {
            return record("claim " + rewardId);
        }

        @Override public boolean claimChoiceReward(long rewardId, int choiceIndex) {
            choiceIndexes.add(new int[] {(int) rewardId, choiceIndex});
            return record("claimChoice " + rewardId);
        }

        private boolean record(String what) {
            if (!connected) return false;
            sent.add(what);
            return true;
        }
    }

    /** 角色替身：本刻只占得到一次交互机会，和真实的每刻名额一个规矩。 */
    private static final class StubPlayer implements PlayerContext {
        boolean canInteract = true;
        int claims;
        private long claimedTick = Long.MIN_VALUE;
        private long tick = 5000;

        /** 测试推进一刻：占过的名额恢复。 */
        void nextTick() {
            tick++;
        }

        @Override public boolean canInteractThisTick() {
            return canInteract && claimedTick != tick;
        }

        @Override public boolean tryClaimInteraction() {
            if (!canInteractThisTick()) return false;
            claimedTick = tick;
            claims++;
            return true;
        }

        @Override public LocalPlayer localPlayer() {
            return null;
        }

        @Override public ClientLevel level() {
            return null;
        }

        @Override public ClientPacketListener connection() {
            return null;
        }

        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("联动测试不碰输入");
        }

        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("联动测试不碰交互提交");
        }

        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("联动测试不碰界面操作");
        }

        @Override public long clientTick() {
            return 0;
        }

        @Override public boolean isCurrent() {
            return true;
        }
    }

    /** 一本可读的书：一条交物品（差 5 件）、一条勾选、一条击杀、一条脚本要求；普通、选择、隐藏奖励各一份。 */
    private static Snapshot readableBook() {
        Requirement item = new Requirement(ITEM_REQUIREMENT, "ftbquests:item", "8x Iron Ingot", 3, 8,
                false, true, QuestBook.SubmitButton.YES, List.of("minecraft:iron_ingot"));
        Requirement checkmark = new Requirement(CHECKMARK, "ftbquests:checkmark", "点这里", 0, 1,
                false, false, QuestBook.SubmitButton.YES, List.of());
        Requirement kill = new Requirement(KILL, "ftbquests:kill", "杀 5 只僵尸", 1, 5,
                false, false, QuestBook.SubmitButton.NO, List.of());
        Requirement custom = new Requirement(CUSTOM, "ftbquests:custom", "脚本要求", 0, 1,
                false, false, QuestBook.SubmitButton.UNKNOWN, List.of());
        Reward plain = new Reward(PLAIN_REWARD, "ftbquests:item", "铁锭 x4", false, false, true, false, null);
        Reward choice = new Reward(CHOICE_REWARD, "ftbquests:choice", "挑一样", false, false, true, false,
                new RewardTable(true, true, List.of(
                        new QuestBook.RewardOption("钻石 x3", 1),
                        new QuestBook.RewardOption("铁锭 x16", 1))));
        Reward hidden = new Reward(HIDDEN_REWARD, "ftbquests:item", "自动领的", true, false, true, false, null);
        Quest quest = new Quest(QUEST, "铁的基础", "", true, false, false, List.of(), false, true,
                true, true, false, false, false, List.of(item, checkmark, kill, custom),
                List.of(plain, choice, hidden), List.of());
        Chapter visible = new Chapter("AAAAAAAAAAAAAAAA", "第一章", List.of(), true, List.of(quest));
        Chapter invisible = new Chapter("BBBBBBBBBBBBBBBB", "隐藏章", List.of(), false,
                List.of(new Quest("9999999999999999", "看不见的", "", true, false, false, List.of(),
                        false, false, false, false, false, false, false, List.of(), List.of(), List.of())));
        return new Snapshot(Access.READABLE, "直播队", List.of(visible, invisible));
    }

    private final StubBook book = new StubBook();
    private final StubActions actions = new StubActions();
    private final StubPlayer player = new StubPlayer();
    private final QuestBookOperations operations =
            new FtbQuestBookOperations(new FtbQuestsCompat(book, actions), book, actions, () -> player);

    @Test
    void statusSaysWhyWhenUnreadable() {
        book.snapshot = Snapshot.unreadable(Access.NOT_SYNCED);
        assertFalse(operations.status().usable());
        assertTrue(operations.status().reason().contains("同步"), operations.status().reason());
        assertTrue(operations.quest(QUEST).isEmpty(), "读不了时条目一律给空");

        book.snapshot = readableBook();
        assertTrue(operations.status().usable());
    }

    @Test
    void onlyVisibleQuestsAreFoundAndIdsMatchCaseInsensitively() {
        book.snapshot = readableBook();
        assertTrue(operations.quest(QUEST.toLowerCase()).isPresent(), "编号大小写不敏感");
        assertTrue(operations.quest("9999999999999999").isEmpty(), "不可见章里的条目不给");
        assertTrue(operations.quest("0000000000000001").isEmpty(), "对不上的编号给空");
    }

    @Test
    void viewCarriesRequirementsRewardsAndChoices() {
        book.snapshot = readableBook();
        QuestView view = operations.quest(QUEST).orElseThrow();

        assertEquals(QUEST, view.questId());
        assertTrue(view.canStart());
        assertEquals(4, view.requirements().size());

        QuestView.Requirement item = view.requirements().get(0);
        assertEquals(5, item.remaining());
        assertTrue(item.submittable());
        assertFalse(item.manualCheck());
        assertEquals(List.of("minecraft:iron_ingot"), item.acceptedItems());

        QuestView.Requirement checkmark = view.requirements().get(1);
        assertTrue(checkmark.manualCheck());
        assertTrue(checkmark.submittable());

        // 击杀这类自动完成的：不能交、说明怎么完成。
        QuestView.Requirement kill = view.requirements().get(2);
        assertFalse(kill.submittable());
        assertTrue(kill.howCompleted().contains("击杀"), kill.howCompleted());

        // 脚本要求的按钮开没开客户端读不到：照样让服务器结算，不替游戏拒绝。
        QuestView.Requirement custom = view.requirements().get(3);
        assertTrue(custom.submittable());

        assertEquals(2, view.rewards().size(), "隐藏的（自动领取）奖励不列");
        QuestView.Reward plain = view.rewards().get(0);
        assertEquals(PLAIN_REWARD, plain.id());
        assertTrue(plain.claimable());
        assertFalse(plain.needsChoice());

        QuestView.Reward choice = view.rewards().get(1);
        assertTrue(choice.needsChoice());
        assertEquals("1", choice.choices().get(0).id());
        assertEquals("钻石 x3", choice.choices().get(0).description());
        assertEquals("2", choice.choices().get(1).id());
    }

    @Test
    void lockedQuestCarriesTheReason() {
        book.snapshot = readableBook();
        Quest locked = new Quest("8888888888888888", "被挡的", "", true, false, false, List.of(), false,
                false, false, false, false, false, false, List.of(), List.of(), List.of());
        book.snapshot = new Snapshot(Access.READABLE, "直播队",
                List.of(new Chapter("AAAAAAAAAAAAAAAA", "第一章", List.of(), true, List.of(locked))));
        QuestView view = operations.quest("8888888888888888").orElseThrow();
        assertFalse(view.canStart());
        assertTrue(view.lockedReason().contains("前置"), view.lockedReason());
    }

    @Test
    void sendingClaimsThisTicksInteractionFirst() {
        book.snapshot = readableBook();
        assertTrue(operations.submit(QUEST, ITEM_REQUIREMENT));
        assertEquals(1, player.claims);
        assertEquals(List.of("submit " + Long.parseUnsignedLong(ITEM_REQUIREMENT, 16)), actions.sent);

        // 同一刻机会已被占：不再发，如实答没发出去。
        assertFalse(operations.submit(QUEST, ITEM_REQUIREMENT));
        assertEquals(1, actions.sent.size());
    }

    @Test
    void noOpportunityOrGarbageIdSendsNothing() {
        book.snapshot = readableBook();
        player.canInteract = false;
        assertFalse(operations.confirm(QUEST, CHECKMARK));
        assertEquals(0, player.claims);
        assertTrue(actions.sent.isEmpty());

        player.canInteract = true;
        assertFalse(operations.submit(QUEST, "不是十六进制"), "编号换不成 long 就不发，也不占交互机会");
        assertEquals(0, player.claims);
        assertTrue(actions.sent.isEmpty());
    }

    @Test
    void claimWithoutChoiceSendsPlainClaimAndWithChoiceSendsTheIndex() {
        book.snapshot = readableBook();
        assertTrue(operations.claim(QUEST, PLAIN_REWARD, null));
        assertEquals(List.of("claim " + Long.parseUnsignedLong(PLAIN_REWARD, 16)), actions.sent);

        // 下一刻才轮得到下一次交互机会。
        player.nextTick();
        assertTrue(operations.claim(QUEST, CHOICE_REWARD, "2"));
        assertEquals(1, actions.choiceIndexes.size());
        assertEquals((int) Long.parseUnsignedLong(CHOICE_REWARD, 16), actions.choiceIndexes.get(0)[0]);
        assertEquals(1, actions.choiceIndexes.get(0)[1], "候选编号从 1 起，发出去换成 FTB 要的 0 起序号");

        player.nextTick();
        assertFalse(operations.claim(QUEST, PLAIN_REWARD, "不是编号"), "候选编号不是数字就发不出去");
    }

    @TempDir
    Path tempDir;

    @Test
    void theCompatModuleHandsTheQuestBookToTheRegistry() {
        // 联动入口把任务书操作交上登记表：进世界带着玩家行为建出来，建出的操作走同一份读与发。
        CompatRegistry registry = CompatRegistry.empty();
        new FtbQuestsCompat(book, actions).contribute(registry);

        List<QuestBookOperations> built = registry.questBooks(services());
        assertEquals(1, built.size(), "任务书操作要登记上，不然 quest 能力只能说联动没接上");
        book.snapshot = readableBook();
        assertTrue(built.getFirst().quest(QUEST).isPresent());
        assertTrue(built.getFirst().submit(QUEST, ITEM_REQUIREMENT));
        assertEquals(1, player.claims, "发请求前占本刻的交互机会");
    }

    /** 一份只够建任务书操作用的玩家行为：只有角色上下文会被真正用到。 */
    private PlayerServices services() {
        return new PlayerServices(() -> player,
                (target, permissions) -> {
                    throw new UnsupportedOperationException("接线测试不走近");
                },
                new Interactions(null),
                itemId -> {
                    throw new UnsupportedOperationException("接线测试不换手");
                },
                (request, permissions) -> {
                    throw new UnsupportedOperationException("接线测试不拿东西");
                },
                protection(),
                LiveCarryReads.itemTags(),
                new MenuLayouts(List.of()),
                new BlockScanService());
    }

    /** 这个世界的保护判断：归属问不到、没有记住的区域，保护判断按"谁的东西都不碰"收窄。 */
    private Protection protection() {
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), MEMORY_KEY);
        return new Protection((dimension, x, y, z) -> Optional.empty(), memory, memory,
                GuessesPlayerMade.NOTHING, "00000000-0000-0000-0000-000000000000");
    }
}
