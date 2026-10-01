// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.testing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.MachineChainConveyorLimit;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;

/** 仅开发测试源码集可用：模型交付图纸文件，独立执行器直接生成，正常游戏物理继续结算。 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class BlueprintTestHarness {
    private static CompletableFuture<JsonObject> pending;
    private static JsonObject request, result;
    private static MachineConstructionPlan plan;
    private static Path inbox, receipt;
    private static long nextPoll, settleTick;
    private static int phase;
    private BlueprintTestHarness() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        String expected = System.getProperty("maicraft.blueprintTest.world", "");
        // 发布包没有这个类；开发环境也必须同时满足命名世界、集成服与创造玩家，普通存档不读取测试队列。
        if (FMLLoader.isProduction() || !expected.startsWith("TEST-Blueprint-Flat-") || game.player == null
                || game.level == null || !game.player.isCreative() || game.getSingleplayerServer() == null
                || !game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize().getFileName().toString().equals(expected)
                || !ClientMachineCatalog.ready(game.player)) return;
        try {
            if (pending != null) { advance(game); return; }
            if (game.level.getGameTime() < nextPoll) return;
            nextPoll = game.level.getGameTime() + 20;
            Path directory = game.gameDirectory.toPath().resolve("blueprint-test").resolve(expected);
            Files.createDirectories(directory.resolve("requests")); Files.createDirectories(directory.resolve("receipts"));
            BlueprintTestArchive.replay(game, directory);
            try (var files = Files.list(directory.resolve("requests"))) {
                inbox = files.filter(path -> path.getFileName().toString().matches("[a-zA-Z0-9_-]+\\.json"))
                        .filter(path -> !Files.exists(directory.resolve("receipts").resolve(path.getFileName())))
                        .sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
            }
            if (inbox == null) return;
            receipt = directory.resolve("receipts").resolve(inbox.getFileName());
            request = JsonParser.parseString(Files.readString(inbox)).getAsJsonObject();
            String label = request.get("label").getAsString();
            BlockPos anchor = BlueprintTestNative.position(request.getAsJsonArray("anchor"));
            var authored = request.getAsJsonObject("blueprint").deepCopy();
            if (authored.has("assembly")) {
                // 测试直接生成物理图纸，加工关系只是作者说明；稀疏改料路不必重写未变化的机械手才能落地。
                var relations = authored.getAsJsonObject("assembly").remove("processing");
                if (relations != null) {
                    var metadata = authored.has("metadata") ? authored.getAsJsonObject("metadata") : new JsonObject();
                    metadata.add("test_authored_processing", relations); authored.add("metadata", metadata);
                }
            }
            var layout = MachineBlueprintDocument.compile(authored, MachineConstructionPlan.registry());
            if (!layout.buildable()) {
                // 首稿可能写了不存在的状态值；直接保留字段诊断，不用整页未施工端口几何和转义 JSON 淹没错误。
                result = new JsonObject(); result.add("validation", layout.report().get("validation").deepCopy());
                result.addProperty("effects_started", false);
                throw new IllegalArgumentException("blueprint_schema_or_block_state_invalid");
            }
            plan = MachineConstructionPlan.compile(anchor, layout, true, true);
            if (ClientMachineCatalog.blueprint(game.player, label, anchor).isPresent()) plan.markModification();
            var scope = MachineChainConveyorLimit.bind(game.player, "machine-test-" + inbox.getFileName(), label, anchor, true);
            MachineChainConveyorLimit.check(game.player, scope, MachineChainConveyorLimit.changes(plan)).requireAllowed();
            // 图纸校验仅解析可生成内容；不调用施工任务、MCP、材料获取或角色导航。
            var captured = plan; var server = game.getSingleplayerServer(); phase = 0;
            pending = CompletableFuture.supplyAsync(() -> BlueprintTestNative.apply(server.overworld(), captured), server);
        } catch (Exception failure) { finish(game, failure); }
    }

    private static void advance(Minecraft game) {
        if (!pending.isDone()) return;
        if (phase == 0) {
            result = pending.join(); settleTick = game.level.getGameTime() + 20; phase = 1;
        }
        if (game.level.getGameTime() < settleTick) return;
        if (phase == 1) {
            // 方块实体先完成原生初始化，再应用测试输入和配置，避免写入尚不存在的机械行为。
            var server = game.getSingleplayerServer(); var captured = request; var anchor = plan.anchor();
            pending = CompletableFuture.supplyAsync(() -> BlueprintTestNative.fixtures(server.overworld(), anchor, captured), server);
            phase = 2; return;
        }
        if (phase == 2) {
            result.add("fixtures", pending.join()); settleTick = game.level.getGameTime() + 20; phase = 3; return;
        }
        // 只把直接生成事实留档，方向、结构差异与生产仍由 Luna 通过正式观察自行判断。
        result.add("machine", ClientMachineCatalog.installationBuilt(game.player, plan, request.get("label").getAsString()));
        finish(game, null);
    }

    private static void finish(Minecraft game, Exception failure) {
        try {
            if (receipt == null) { Constants.LOG.warn("Blueprint test queue unavailable", failure); return; }
            if (result == null) result = new JsonObject();
            result.addProperty("test_only_direct_generation", true);
            result.addProperty("request_file", inbox.getFileName().toString());
            result.addProperty("status", failure == null ? "applied" : "error");
            result.addProperty("production_verified", false);
            if (failure != null) result.addProperty("error", failure.toString());
            Path temporary = receipt.resolveSibling(receipt.getFileName() + ".tmp");
            Files.writeString(temporary, result.toString()); Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE);
            Constants.LOG.info("Blueprint test request {}: {}", inbox.getFileName(), result.get("status"));
        } catch (Exception writeFailure) { Constants.LOG.error("Blueprint test receipt write failed", writeFailure); }
        finally { pending = null; request = null; result = null; plan = null; inbox = null; receipt = null; phase = 0; }
    }
}
