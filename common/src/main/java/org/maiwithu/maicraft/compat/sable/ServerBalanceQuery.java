// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;

/**
 * 问服务端一个物理结构的受力（只读操作 sable.body）：问一次，逐刻看回答；服务端刚打开各处力的记账时这一次没有受力，
 * 隔 5 刻再问，最多问三次。服务端答不了（没装 MaiCraft、所有者关掉了、没有这个结构）给一份读不到的受力，写明原因。
 * 回答换成驾驶接口里的受力：两种情况、升力比重量、配重建议；预测不是实飞写进说明。
 */
final class ServerBalanceQuery implements VehicleReader.BalanceQuery {

    /** 记账刚打开时隔几刻再问、最多问几次。 */
    static final int RETRY_TICKS = 5;
    static final int TRIES = 3;

    private final PlayerServices services;
    private final String structureId;
    private final boolean ballast;
    private UUID asking;
    private int tries;
    private int waiting;
    private VehicleReader.Balance answer;

    ServerBalanceQuery(PlayerServices services, String structureId, boolean ballast) {
        this.services = Objects.requireNonNull(services, "services");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.ballast = ballast;
    }

    @Override public Optional<VehicleReader.Balance> poll() {
        if (answer != null) return Optional.of(answer);
        if (!services.serverReads().available(SableCompat.FORCES_OPERATION)) {
            return done(unknown("服务端答不了受力（没装 MaiCraft，或所有者关掉了这个只读操作）"));
        }
        if (asking == null) {
            if (waiting > 0) {
                waiting--;
                return Optional.empty();
            }
            JsonObject body = new JsonObject();
            body.addProperty("structure", structureId);
            body.addProperty("ballast", ballast);
            asking = services.serverReads().ask(SableCompat.FORCES_OPERATION, body).orElse(null);
            tries++;
            if (asking == null) return done(unknown("受力没问出去（和服务端的连接还没准备好）"));
            return Optional.empty();
        }
        Optional<ClientRequest.Snapshot> snapshot = services.serverReads().answer(asking);
        if (snapshot.isPresent() && !snapshot.get().settled()) return Optional.empty();
        asking = null;
        if (snapshot.isEmpty() || snapshot.get().status() != ClientRequest.Status.SUCCEEDED) {
            return done(unknown("问受力没问到：" + snapshot.map(ClientRequest.Snapshot::message).orElse("请求已经退场")));
        }
        JsonObject result = snapshot.get().result();
        if (!result.has("tracking") || !result.get("tracking").getAsBoolean()) {
            if (tries >= TRIES) return done(unknown("服务端打开了各处力的记账，问了 " + TRIES + " 次还没记到"));
            waiting = RETRY_TICKS;
            return Optional.empty();
        }
        return done(parse(result));
    }

    private Optional<VehicleReader.Balance> done(VehicleReader.Balance balance) {
        answer = balance;
        return Optional.of(balance);
    }

    // 回答换成受力：两种情况、升力比重量、配重建议；动力没开时说开着动力那种要开着再看一次。
    static VehicleReader.Balance parse(JsonObject result) {
        List<VehicleReader.Case> cases = new ArrayList<>();
        for (JsonElement element : result.getAsJsonArray("cases")) {
            JsonObject entry = element.getAsJsonObject();
            cases.add(new VehicleReader.Case(entry.get("name").getAsString(), entry.get("tilt_x").getAsDouble(),
                    entry.get("tilt_z").getAsDouble(), entry.get("steady").getAsBoolean(), entry.get("tendency").getAsString()));
        }
        List<VehicleReader.Ballast> ballast = new ArrayList<>();
        if (result.has("ballast")) {
            for (JsonElement element : result.getAsJsonArray("ballast")) {
                JsonObject entry = element.getAsJsonObject();
                JsonObject at = entry.has("world") ? entry.getAsJsonObject("world") : entry.getAsJsonObject("local");
                ballast.add(new VehicleReader.Ballast(at.get("x").getAsDouble(), at.get("y").getAsDouble(),
                        at.get("z").getAsDouble(), entry.get("block").getAsString(), entry.get("mass").getAsDouble(),
                        entry.get("before").getAsDouble(), entry.get("after").getAsDouble()));
            }
        }
        boolean powered = result.get("powered").getAsBoolean();
        String note = "静态预测，不是实飞：实飞要靠 vehicle_drive 验；阻力跟着速度走，没算"
                + (powered ? "" : "；动力此刻没开，开着动力那种情况要在开着动力时再看一次")
                + (result.has("ballast") && ballast.isEmpty() ? "；试了 " + result.get("ballast_cells_tried").getAsInt()
                        + " 个空格，没有放一块能让两种情况都倒得慢的位置" : "");
        return new VehicleReader.Balance(true, "", result.get("mass").getAsDouble(), result.get("lift_ratio").getAsDouble(),
                powered, cases, ballast, note);
    }

    private static VehicleReader.Balance unknown(String why) {
        return new VehicleReader.Balance(false, why, 0, 0, false, List.of(), List.of(), "");
    }
}
