// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近功能方块的读端：在已登记的方块扫描上查角色附近看得见的设施方块。
 *
 * <p>复用游戏接口层的 {@code BlockScanService}：设施的种类（容器、工作设施、床）首次用到时
 * 向扫描服务登记一次，之后每次查询走索引，一刻只花小预算；扫完之前返回已找到的部分，
 * "没扫完"不冒充"没有"。查到的每一格再用与观察同一套遮挡判断筛一遍——
 * 墙后的箱子不报成看见。索引登记的方块种类以设施分类为限，感知只认设施。
 */
public final class ClientNearbyBlocksSight implements NearbyBlocksSight {

    // 观察要的是"附近有什么"，不是最近的一个：给够数量，超出由场景按远近取舍。
    private static final int WANT = 32;
    private static final int MAX_CHUNK_RADIUS = 2;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private boolean registered;

    public ClientNearbyBlocksSight(BlockScanService scans, Supplier<PlayerContext> context) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<BlockSighting> nearby(Set<String> blockTypes) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null
                || blockTypes.isEmpty()) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        ensureRegistered(level);
        Collection<Block> targets = blocksOf(blockTypes);
        if (targets.isEmpty()) {
            return List.of();
        }
        var result = scans.query(level, player.blockPosition(), targets,
                WANT, MAX_CHUNK_RADIUS, BUILD_BUDGET);
        List<BlockSighting> sightings = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            // 视线被挡住的功能方块不是看得见的设施；等角色走近、看得见了再报。
            if (!ObservationVisibility.block(player, hit)) {
                continue;
            }
            String typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit).getBlock()).toString();
            sightings.add(new BlockSighting(
                    WorldPosition.here(hit.getX(), hit.getY(), hit.getZ()), typeId));
        }
        return sightings;
    }

    // 首次用到时登记感知关心的设施种类；登记计数在扫描服务里，重复登记不叠加负担。
    private void ensureRegistered(ClientLevel level) {
        if (registered) {
            return;
        }
        Set<Block> facilities = new LinkedHashSet<>();
        for (String typeId : FacilityKinds.CONTAINERS) {
            blockOf(typeId).ifPresent(facilities::add);
        }
        for (String typeId : FacilityKinds.WORKSTATIONS) {
            blockOf(typeId).ifPresent(facilities::add);
        }
        // 床有十六种染色，按注册表按名找齐；名字以外的新床出现时也会在下次登记前进来。
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_bed")) {
                facilities.add(block);
            }
        }
        scans.register(level, facilities);
        registered = true;
    }

    // 调用方给的是方块类型注册 ID 字符串；认得的换成方块对象，认不得的忽略。
    private static Collection<Block> blocksOf(Set<String> blockTypes) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String typeId : blockTypes) {
            blockOf(typeId).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static Optional<Block> blockOf(String typeId) {
        return Optional.ofNullable(
                BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(typeId.toLowerCase(Locale.ROOT))));
    }
}
