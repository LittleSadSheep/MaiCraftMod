// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ServerLifecycle;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.neoforge.compat.ServerCompatCatalog;

/**
 * NeoForge 的通用入口，独立服务器和客户端都会加载。
 * 只把服务端事件转给公共代码，不写业务，也不引用任何仅客户端的类。
 */
@Mod(ModIdentity.MOD_ID)
public final class MaiCraftNeoForge {
    private final ServerLifecycle server;

    public MaiCraftNeoForge(IEventBus modBus) {
        // 服务端把确认信封直接发给目标玩家的客户端；通道保持 optional，没装 Mod 的普通玩家照样进服。
        server = Bootstrap.startCommon(new NeoForgeLoaderEnvironment(),
                (player, envelope) -> PacketDistributor.sendToPlayer(player, MaiCraftPayload.of(envelope)),
                ServerCompatCatalog.rows());
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onRespawn);
        NeoForge.EVENT_BUS.addListener(this::onBreakBlock);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // 版本不交给通道协商，由信封内的 JSON 协议自己协商；optional 让未装 Mod 的普通玩家进服。
        event.registrar("1").optional().playBidirectional(MaiCraftPayload.TYPE, MaiCraftPayload.CODEC,
                (payload, context) -> {
                    if (context.flow() == PacketFlow.SERVERBOUND) {
                        if (context.player() instanceof ServerPlayer player)
                            server.clientEnvelope(player, payload,
                                    response -> context.reply(MaiCraftPayload.of(response)));
                    } else NeoForgeClientLink.received(payload.json());
                });
    }

    private void onServerTick(ServerTickEvent.Post event) {
        server.tickEnd(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        server.stopped(event.getServer());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) server.clientDisconnected(player);
    }

    private void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) server.clientWorldChanged(player);
    }

    private void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) server.clientWorldChanged(player);
    }

    private void onBreakBlock(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && !event.getLevel().isClientSide())
            server.playerBrokeBlock(player, event.getPos(), event.getState());
    }
}
