// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.OwnershipQueries;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.acquire.LiveCarryReads;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.survival.CombatMemory;
import org.maiwithu.maicraft.behavior.survival.LiveCombatSenses;
import org.maiwithu.maicraft.behavior.travel.ClientTravelWorldView;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 离线拼一份能力清单的依赖：全部用离线可构造的实例，不碰任何真实游戏对象。
 * 总装测试拿它验证装得上、推得动；接口快照拿它列出生产清单里的全部能力。
 */
final class OfflineCatalog {

    /** 测试专用的世界身份编号：任意一个合法的 SHA-256 形状。 */
    static final String WORLD_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 一条空的发送通道：离线不连服务器，会话构造需要它存在而已。 */
    private static final LinkTransport NO_TRANSPORT = new LinkTransport() {
        @Override public boolean available() {
            return false;
        }

        @Override public void send(JsonObject envelope) {
            throw new IllegalStateException("离线总装不发信封");
        }
    };

    /** 按住使用键投影的替身：进食的续期一律不续，任务按"按住到期"如实收场。 */
    private static final UseKeyProjection NO_HOLD = new UseKeyProjection() {
        @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                InteractionHand hand, ItemStack before) {
            return false;
        }

        @Override public void release(Object owner) {
        }
    };

    /** 离线角色的玩家编号。 */
    private static final String SELF_ID = "00000000-0000-0000-0000-000000000000";

    private OfflineCatalog() {}

    /** 一份依赖；世界记忆的库文件放在给定的临时目录里。 */
    static AbilityCatalog.Deps deps(Path tempDir) {
        Supplier<PlayerContext> nobody = () -> null;
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), WORLD_KEY);
        ClientHooks.registerChatLog(new ChatLog());
        ServerLinkSession session = new ServerLinkSession(NO_TRANSPORT);
        // 交互动作入口允许没有按住使用键投影；生存需求共用的挖掘走原生交互，离线给空壳。
        return new AbilityCatalog.Deps(
                nobody,
                new Interactions(null),
                new BaritoneInternals(),
                new BaritoneInternals(),
                new BlockScanService(),
                new Scene(memory), memory,
                session,
                SELF_ID,
                (ReadsCreatureSituation) entityId -> Optional.empty(),
                new LiveCombatSenses(new CombatMemory(), itemId -> Optional.empty()),
                PlayerViews.backpack(nobody),
                LiveCarryReads.offhand(nobody),
                LiveCarryReads.itemTags(),
                LiveCarryReads.characterPosition(nobody),
                LiveCarryReads.itemRegistry(),
                LiveCarryReads.toolRequirements(nobody),
                PlayerViews.hunger(nobody),
                PlayerViews.foods(nobody),
                PlayerViews.equipment(nobody),
                PlayerViews.effects(nobody),
                PlayerViews.gearFit(nobody),
                NO_HOLD,
                () -> null,
                new ClientTravelWorldView(nobody),
                progress -> { },
                new ChatChannel(nobody),
                () -> 0,
                new InputDriver(new PlayerControlBoundary()),
                false,
                new Protection(new OwnershipQueries(session), memory, memory, GuessesPlayerMade.NOTHING, SELF_ID),
                stack -> 0);
    }
}
