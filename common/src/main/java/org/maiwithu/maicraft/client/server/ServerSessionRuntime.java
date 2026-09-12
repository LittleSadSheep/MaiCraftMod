// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.network.ClientProtocolBridge;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/** Loader-installed transport listener, advanced only inside the common client's actor lifecycle. */
public final class ServerSessionRuntime {
    private static final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private static final ServerRequestOwners owners = new ServerRequestOwners();
    private static ClientRequestRouter router;
    private static MutationJournal journal;
    private static boolean installed;
    private static ClientPacketListener connection;
    private static LocalPlayer player;
    private static ClientLevel level;
    private static volatile long connectionRevision;
    private static long bindingRevision;
    private static long tick;

    private ServerSessionRuntime() {}
    public static boolean installed() { return installed; }

    public static void install() {
        requireThread();
        if (installed) return;
        installed = true;
        router();
        ClientProtocolBridge.installListener(new ClientProtocolBridge.Listener() {
            @Override public void connected() { /* binding waits for the actual player and world */ }
            @Override public void received(com.google.gson.JsonObject envelope) {
                long receivedConnection = connectionRevision;
                var frozen = envelope.deepCopy();
                enqueue(() -> router.receive(frozen, receivedConnection));
            }
            @Override public void disconnected() {
                long disconnectedConnection = connectionRevision;
                enqueue(() -> {
                    if (disconnectedConnection == connectionRevision) {
                        router.disconnect();
                        connection = null;
                        player = null;
                        level = null;
                    }
                });
            }
        });
    }

    static ClientRequestRouter router() {
        requireThread();
        if (router == null) {
            journal = new MutationJournal(Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config/maicraft/server-assistance-unresolved.json"));
            router = new ClientRequestRouter(ClientProtocolBridge::available, ClientProtocolBridge::send,
                    ServerSessionRuntime::requireThread, ServerSessionRuntime::enqueue,
                    ServerSessionRuntime::submitMutation, journal);
            router.register(new ClientOperation("machine.snapshot", 1, false, new ClientMachineSnapshot()));
            router.register(new ClientOperation("machine.configure", 1, true, null));
            router.register(new ClientOperation("inventory.transfer", 1, true, null));
            router.register(new ClientOperation("inventory.ae2_supply", 1, true, null));
            for (String operation : new String[]{"machine.recipe", "machine.connections", "machine.configuration",
                    "machine.production_events", "machine.watch", "inventory.quote", "inventory.ae2_network", "inventory.ae2_craft_status"})
                router.register(new ClientOperation(operation, 1, false, null));
            for (String operation : new String[]{"inventory.ae2_craft_plan", "inventory.ae2_craft_start", "inventory.ae2_craft_cancel"})
                router.register(new ClientOperation(operation, 1, true, null));
        }
        return router;
    }

    public static void observe(Minecraft minecraft, LocalPlayerContext context) {
        requireThread();
        if (!installed) return;
        tick++;
        if (context == null || minecraft.getConnection() == null
                || !minecraft.getConnection().getConnection().isConnected()) {
            if (connection != null) router.disconnect();
            connection = null;
            player = null;
            level = null;
        } else {
            if (connection != minecraft.getConnection()) connectionRevision++;
            if (connection != minecraft.getConnection() || player != minecraft.player || level != minecraft.level)
                bindingRevision++;
            connection = minecraft.getConnection();
            player = minecraft.player;
            level = minecraft.level;
            var server = minecraft.getCurrentServer();
            var local = minecraft.getSingleplayerServer();
            String serverIdentity = server != null ? "remote:" + server.ip : "local:" + (local == null
                    ? minecraft.gameDirectory.toPath() : local.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            journal.bind(serverIdentity, player.getUUID().toString(), level.dimension().location().toString());
            boolean allowed = context.body().automationOwnsControls() && !PreviewController.waitingReview()
                    && !GameplayAttentionMonitor.blocksAutomation(context.player());
            router.bind(connectionRevision, bindingRevision, level.dimension().location().toString(),
                    context.controlRevision(), allowed, tick);
        }
        // Bind the current body/control owner before callbacks can settle or enqueue more work.
        for (int i = 0; i < 256; i++) {
            Runnable callback = callbacks.poll();
            if (callback == null) break;
            try { callback.run(); }
            catch (RuntimeException failure) { Constants.LOG.warn("MaiCraft server-assist callback failed", failure); }
        }
        retireOwners();
        router.observe(tick);
        if (context != null) {
            router.dispatch(false);
        }
    }

    public static void dispatch(LocalPlayerContext context) {
        requireThread();
        if (!installed) return;
        retireOwners();
        String winner = CompanionTickDispatcher.controllingTask();
        String owner = selectedOwner();
        router.dispatch(context.permitsNativeActions() && context.mutationAvailable()
                && ServerRequestOwners.ordinaryWinner(winner), receipt -> owners.selected(receipt.id(), owner));
    }

    /** Passive job status never acquires the body or admits a native mutation. */
    public static void dispatchBackgroundReads() {
        requireThread();
        if (installed) router.dispatch(false, receipt -> false,
                receipt -> receipt.operation.id().equals("machine.watch"));
    }

    static String selectedOwner() {
        var current = CompanionTickDispatcher.current();
        String winner = CompanionTickDispatcher.controllingTask();
        if (winner.equals("synchronous_task"))
            return CompanionTickDispatcher.list().stream().filter(record -> record != current)
                    .map(org.maiwithu.maicraft.task.TaskRecord::publicId).findFirst().orElse(null);
        return winner.equals("current_task") && current != null ? current.publicId() : null;
    }

    static String submissionOwner() {
        String selected = selectedOwner();
        var current = CompanionTickDispatcher.current();
        return selected != null ? selected : current == null ? null : current.publicId();
    }

    static void rememberOwner(ClientRequestReceipt receipt, String owner) {
        requireThread();
        owners.remember(receipt.id(), owner);
    }

    private static void retireOwners() {
        owners.retire(router, CompanionTickDispatcher::find, player == null ? Long.MAX_VALUE : player.level().getGameTime());
    }

    private static boolean submitMutation(ClientRequestReceipt receipt, Runnable send) {
        var context = ClientRuntime.actor().activeContext().orElse(null);
        if (context == null || !context.permitsNativeActions() || !context.mutationAvailable()) return false;
        try {
            boolean menu = receipt.arguments.has("container_id");
            if (menu && (context.player().containerMenu == context.player().inventoryMenu
                    || context.player().containerMenu.containerId != receipt.arguments.get("container_id").getAsInt()))
                throw new IllegalStateException("The request's actual menu changed");
            var nativeReceipt = menu ? context.actions().submitProtocol(context,
                    "server " + receipt.operation.id(), send, NativeConfirmation.pending(), 1)
                    : context.actions().submitControlProtocol(context,
                    "server " + receipt.operation.id(), send, NativeConfirmation.pending(), 1);
            // The actor enforces this tick's native boundary. The router/journal own the remote
            // receipt, so a delayed reply must not lock next tick's emergency native input slot.
            context.actions().retireOneShotForTaskBoundary(context, nativeReceipt,
                    "remote request outcome is retained by the independent server receipt ledger");
            return true;
        } catch (IllegalStateException unavailable) {
            // Native screen, control and active-action gates run before the sender is invoked.
            return false;
        }
    }

    public static void shutdown() {
        requireThread();
        if (router != null) router.close();
        owners.clear();
        connection = null;
        player = null;
        level = null;
    }

    private static void enqueue(Runnable callback) { callbacks.add(callback); }
    private static void requireThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("server assist is client-thread only");
    }
}
