// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 应用能源2（AE2）的服务端联动入口：登记只读操作 ae2.network——客户端问某一格所在那张 ME 网怎样，
 * 回答控制器、频道、能量、存储总量、合成 CPU 的汇总。只答角色所在维度、角色附近已加载的格子：
 * 看得见那台设备的玩家才问得着，远处别人家的网不回答。不替角色动手，也不列存了哪些东西。
 */
public final class Ae2ServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "ae2";
    /** 只读操作的名字：客户端的 ME 网络读取器经它问。 */
    public static final String OPERATION = "ae2.network";
    /** 只答角色这么远以内的格子（格）：和角色能看见的范围同一量级，不让它变成远程探查。 */
    static final int MAX_DISTANCE = 128;

    private final Ae2GridReads reads;

    public Ae2ServerCompat(Ae2GridReads reads) {
        super(MOD_ID, "应用能源2");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, OPERATION, 1, this::answer);
    }

    // 问哪一格就答哪一格所在的网：先核对这一格在角色附近、已加载，再读网格服务的汇总。
    private JsonObject answer(ServerPlayer player, JsonObject body) {
        BlockPos at = new BlockPos(intOf(body, "x"), intOf(body, "y"), intOf(body, "z"));
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(at)) {
            throw ServerOperationException.notApplied("not_loaded", "那一格没加载");
        }
        if (player.blockPosition().distSqr(at) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            throw ServerOperationException.notApplied("too_far", "那一格离角色太远，只答附近的网");
        }
        Optional<Ae2GridReads.GridSummary> summary = call("读 ME 网络摘要", () -> reads.summarize(level, at));
        return summary.map(Ae2ServerCompat::json).orElseGet(() -> {
            JsonObject none = new JsonObject();
            none.addProperty("found", false);
            return none;
        });
    }

    /** 汇总写成回答：字段名就是客户端读的名字。 */
    static JsonObject json(Ae2GridReads.GridSummary summary) {
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("grid", summary.gridKey());
        out.addProperty("nodes", summary.nodes());
        out.addProperty("controller", summary.controller());
        out.addProperty("booting", summary.booting());
        out.addProperty("channel_mode", summary.channelMode());
        out.addProperty("channels_used", summary.channelsUsed());
        out.addProperty("ad_hoc_limit", summary.adHocLimit());
        out.addProperty("ad_hoc_problem", summary.adHocProblem());
        out.addProperty("powered", summary.powered());
        out.addProperty("energy_stored", summary.energyStored());
        out.addProperty("energy_max", summary.energyMax());
        out.addProperty("stored_types", summary.storedTypes());
        out.addProperty("stored_amount", summary.storedAmount());
        out.addProperty("crafting_cpus", summary.craftingCpus());
        out.addProperty("crafting_busy", summary.craftingBusy());
        return out;
    }

    private static int intOf(JsonObject body, String key) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "请求缺坐标 " + key);
        }
        return body.get(key).getAsInt();
    }
}
