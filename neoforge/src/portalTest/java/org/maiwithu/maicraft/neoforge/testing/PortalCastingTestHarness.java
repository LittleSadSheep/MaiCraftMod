// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.maiwithu.maicraft.core.Constants;

/** 专用副本的一次性起始夹具：只准备浅池和普通物资，绝不注入黑曜石、门框或施工结果。 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class PortalCastingTestHarness {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static CompletableFuture<Map<String, Object>> pending;
    private static boolean ready;
    private static long nextObservation;
    private PortalCastingTestHarness() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        String expected = System.getProperty("maicraft.portalTest.world", "");
        var server = game.getSingleplayerServer();
        if (FMLLoader.isProduction() || !expected.startsWith("TEST-Portal-Cast-") || game.player == null
                || game.level == null || server == null
                || !server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString().equals(expected)) return;
        Path directory = game.gameDirectory.toPath().resolve("portal-cast-test").resolve(expected);
        try {
            Files.createDirectories(directory);
            if (Files.exists(directory.resolve("stop"))) { game.stop(); return; }
            if (pending != null) {
                if (!pending.isDone()) return;
                Files.writeString(directory.resolve(ready ? "observation.json" : "ready.json"), JSON.toJson(pending.join()));
                // 保留逐次身体和现场变化，死亡后也能核对角色是怎样离开安全站位的；静态石台已在起始凭证里。
                var sample = new LinkedHashMap<>(pending.join());
                var changed = ((List<?>) sample.get("blocks")).stream().filter(row ->
                        !"minecraft:stone".equals(((Map<?, ?>) row).get("block"))).toList();
                sample.put("blocks", changed);
                Files.writeString(directory.resolve("timeline.jsonl"), new Gson().toJson(sample) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                pending = null; ready = true; nextObservation = game.level.getGameTime() + 40; return;
            }
            // 重启只继续观察旧现场；已经写过起始凭证的副本不能再次补桶、恢复岩浆或擦掉失败产物。
            if (!ready && Files.exists(directory.resolve("ready.json"))) ready = true;
            var id = game.player.getUUID();
            if (!ready) {
                pending = server.submit(() -> prepare(server.getPlayerList().getPlayer(id)));
            } else if (game.level.getGameTime() >= nextObservation) {
                pending = server.submit(() -> observe(server.getPlayerList().getPlayer(id)));
            }
        } catch (Exception failure) {
            try { Files.writeString(directory.resolve("error.txt"), failure.toString()); }
            catch (Exception ignored) { /* 观察写盘失败不能改变角色或世界来伪造通过。 */ }
        }
    }

    private static Map<String, Object> prepare(ServerPlayer player) {
        if (player == null) throw new IllegalStateException("test player not ready");
        var world = player.serverLevel();
        // 在独立高台生成一格深池，迫使执行器真实挖池底；世界里不预置黑曜石和导流模具。
        for (BlockPos at : BlockPos.betweenClosed(-10, 96, -12, 12, 107, 10)) {
            var block = at.getY() <= 99 ? Blocks.STONE : Blocks.AIR;
            world.setBlockAndUpdate(at, block.defaultBlockState());
        }
        for (BlockPos at : BlockPos.betweenClosed(-3, 99, -7, 5, 99, -1))
            world.setBlockAndUpdate(at, Blocks.LAVA.defaultBlockState());
        world.setBlockAndUpdate(new BlockPos(-6, 99, 3), Blocks.WATER.defaultBlockState());
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(.5, 100, 3.5);
        player.setHealth(player.getMaxHealth()); player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(10);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.BUCKET));
        player.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
        player.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 32));
        player.getInventory().setItem(3, new ItemStack(Items.FLINT_AND_STEEL));
        player.getInventory().setItem(4, new ItemStack(Items.COOKED_BEEF, 32));
        player.inventoryMenu.broadcastChanges();
        world.setDayTime(6000);
        world.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, world.getServer());
        var result = new LinkedHashMap<>(observe(player));
        result.put("fixture", "shallow_lava_pool_empty_bucket_no_obsidian_no_diamonds");
        result.put("fixture_only", true); return result;
    }

    private static Map<String, Object> observe(ServerPlayer player) {
        if (player == null) throw new IllegalStateException("test player unavailable");
        var blocks = new ArrayList<Map<String, Object>>();
        var world = player.serverLevel();
        // 服务端只读记录真实流体、门框和门面，不根据客户端任务的成功字样推断验收通过。
        for (BlockPos at : BlockPos.betweenClosed(-8, 98, -9, 10, 105, 8)) {
            var state = world.getBlockState(at);
            if (state.isAir()) continue;
            blocks.add(Map.of("position", List.of(at.getX(), at.getY(), at.getZ()),
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), "state", state.toString()));
        }
        var inventory = new LinkedHashMap<String, Integer>();
        player.getInventory().items.stream().filter(stack -> !stack.isEmpty()).forEach(stack ->
                inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum));
        return Map.of("game_time", world.getGameTime(), "dimension", world.dimension().location().toString(),
                "feet", List.of(player.getX(), player.getY(), player.getZ()), "health", player.getHealth(),
                "deaths", player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)),
                "last_damage", player.getLastDamageSource() == null ? "none" : player.getLastDamageSource().getMsgId(),
                "inventory", inventory, "blocks", blocks);
    }
}
