// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 机械动力应力网的读法：一格动力方块在哪张网上，看它方块实体上的网络编号；
 * 那张网怎样（应力容量、当前应力、成员数、过载没有）读网上任一成员——Create 把整张网的这几项同步到每个成员上，
 * 全在客户端读得到，不用问服务端。
 *
 * <p>汇总读的是最近一次问到在这张网上的那一格；那一格换了网（拆了、断开了）就如实说读不到，让调用方重新看一遍。
 */
final class KineticNetworkReader implements NetworkReader {

    static final NetworkKind KIND = new NetworkKind("kinetic",
            "机械动力的应力网络：轴、齿轮、传送带连起来的一组，共用网上动力源给的应力容量");
    private static final String PREFIX = "kinetic:";

    private final CreateCompat compat;
    private final PlayerServices services;
    /** 每张网最近见到的一个成员：读汇总时读它；网络编号 → 那一格。 */
    private final Map<String, BlockPos> lastMember = new ConcurrentHashMap<>();

    KineticNetworkReader(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public NetworkKind kind() {
        return KIND;
    }

    @Override public Optional<String> membership(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        Optional<String> id = compat.kineticReading(level, at).flatMap(CreateKinetics.Reading::networkId)
                .map(network -> PREFIX + network);
        id.ifPresent(network -> lastMember.put(network, at.immutable()));
        return id;
    }

    @Override public NetworkSummary summary(String networkId) {
        ClientLevel level = level();
        if (level == null) return NetworkSummary.unreadable(networkId, "角色现在不在世界里，读不到应力网");
        BlockPos member = lastMember.get(networkId);
        if (member == null) return NetworkSummary.unreadable(networkId, "还没看到过这张网上的方块，先看一眼机器");
        Optional<CreateKinetics.Reading> reading = compat.kineticReading(level, member);
        if (reading.isEmpty() || !reading.get().networkId().map(id -> (PREFIX + id).equals(networkId)).orElse(false)) {
            lastMember.remove(networkId);
            return NetworkSummary.unreadable(networkId, "上次看到的那一格已经不在这张网上了，网变了样，重新看一遍");
        }
        return readable(networkId, reading.get());
    }

    /** 一张网的汇总读数，纯函数：容量、当前应力、剩多少、成员数、过载没有。 */
    static NetworkSummary readable(String networkId, CreateKinetics.Reading reading) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("stress_capacity", CreateReadings.round(reading.capacity()));
        readings.put("stress", CreateReadings.round(reading.stress()));
        readings.put("stress_left", CreateReadings.round(reading.capacity() - reading.stress()));
        readings.put("members", (long) reading.networkSize());
        readings.put("overstressed", reading.overStressed());
        String note = reading.overStressed() ? "过载：整张网停转，减掉一些机器或加动力源" : "";
        return new NetworkSummary(networkId, true, readings, note);
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
