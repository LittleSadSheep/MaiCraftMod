// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.BodyRange;

/** 一次取物调用及递归补料共用固定翻箱范围，走到新箱子旁边不会获得更大的调查半径。 */
public record ContainerSearchScope(String dimension, BlockPos origin, int radius) {
    public static final int MAX_RADIUS = 32;
    private static final ThreadLocal<ContainerSearchScope> CURRENT = new ThreadLocal<>();

    public ContainerSearchScope { origin = origin.immutable(); radius = Math.clamp(radius, 1, MAX_RADIUS); }
    public static ContainerSearchScope capture(LocalPlayer player, int radius) {
        ContainerSearchScope parent = CURRENT.get();
        return parent == null ? new ContainerSearchScope(player.level().dimension().location().toString(), player.blockPosition(), radius)
                : new ContainerSearchScope(parent.dimension(), parent.origin(), Math.min(radius, parent.radius()));
    }
    public boolean contains(LocalPlayer player) {
        return dimension.equals(player.level().dimension().location().toString()) && new BodyRange(origin, radius).contains(player.blockPosition());
    }
    public <T> T inherit(Supplier<T> operation) {
        ContainerSearchScope previous = CURRENT.get(); CURRENT.set(this);
        try { return operation.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public <T> T boundMovement(Supplier<T> operation) {
        // 只有翻箱的接近路线加活动边界；其他合法来源仍使用它们各自的搜索范围。
        return NavigationSafetyContext.withBodyRange(new BodyRange(origin, radius), operation);
    }
    public Map<String, Object> receipt() {
        return Map.of("dimension", dimension, "origin", List.of(origin.getX(), origin.getY(), origin.getZ()),
                "radius", radius, "distance_metric", "euclidean_3d", "visibility_required", true);
    }
}
