package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 脚边火把和草必须能提交一次原生清障；零硬度不是不可挖，提交也不能冒充世界已经改变。 */
public final class InstantClearanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var backendField = field(EmbeddedBaritoneRuntime.class, "backend");
        var ownerField = field(EmbeddedBaritoneRuntime.class, "owner");
        var previousBackend = backendField.get(null); var previousOwner = ownerField.get(null);
        var previousPolicy = EmbeddedBaritonePolicy.snapshot(); Boolean previousBreak = null;
        try (var w = new InteractionWorldTestHarness()) {
            field(Minecraft.class, "gameDirectory").set(Minecraft.getInstance(), Path.of("instant-clearance-fixture").toAbsolutePath().toFile());
            // 生存角色无药水、未浸水，直接使用原版工具速度和方块硬度计算，不手造无限大进度。
            field(LivingEntity.class, "activeEffects").set(w.player, Map.of());
            field(Entity.class, "fluidOnEyes").set(w.player, Set.of());
            previousBreak = BaritoneAPI.getSettings().allowBreak.value;
            BaritoneAPI.getSettings().allowBreak.value = true; EmbeddedBaritonePolicy.clear();
            BlockPos target = new BlockPos(4, 1, 4);
            var hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
            var playerContext = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                    new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> switch (method.getName()) {
                        case "player" -> w.player;
                        case "objectMouseOver" -> hit;
                        default -> throw new AssertionError(method.getName());
                    });
            backendField.set(null, Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class},
                    (proxy, method, values) -> method.getName().equals("getPlayerContext") ? playerContext : null));
            var navigation = new EmbeddedBaritoneNavigator(w.player, () -> null, () -> false, PlayerNav.ContextProvider.TERRAFORM, false);
            ownerField.set(null, navigation);
            int[] submissions = {0};
            var actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                    new Class<?>[]{NativeActionPort.class}, (proxy, method, values) -> {
                        if (!method.getName().equals("startBreaking")) throw new AssertionError(method.getName());
                        submissions[0]++;
                        // 模拟原生拒绝：确认进入客户端操作入口后仍不得凭这次尝试伪造已挖掉的账本。
                        throw new IllegalStateException("native rejection fixture");
                    });
            var base = ClientRuntime.requireContext(w.player);
            var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, values) ->
                            method.getName().equals("actions") ? actions : method.invoke(base, values));
            var bridge = new EmbeddedBaritoneActionBridge();
            var start = EmbeddedBaritoneActionBridge.class.getDeclaredMethod("startBreak", LocalPlayerContext.class, EmbeddedBaritoneNavigator.class);
            start.setAccessible(true);
            for (var block : List.of(Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SHORT_GRASS)) {
                w.set(target, block.defaultBlockState());
                check(block.defaultBlockState().getDestroyProgress(w.player, w.level, target) == Float.POSITIVE_INFINITY,
                        "native zero-hardness progress is instant: " + block);
                int before = submissions[0]; start.invoke(bridge, context, navigation);
                check(submissions[0] == before + 1, "instant terrain must reach native breaking: " + block);
                check(navigation.ledger().isEmpty(), "a refused native attempt is not a confirmed removal");
            }
            // 基岩仍不能挖；真实保护格也不能借瞬间清除绕过授权边界。
            int before = submissions[0]; w.set(target, Blocks.BEDROCK.defaultBlockState()); start.invoke(bridge, context, navigation);
            w.set(target, Blocks.TORCH.defaultBlockState());
            EmbeddedBaritonePolicy.install(new LongOpenHashSet(new long[]{target.asLong()}), null, null);
            start.invoke(bridge, context, navigation);
            check(submissions[0] == before, "unbreakable and protected cells submit nothing");
        } finally {
            backendField.set(null, previousBackend); ownerField.set(null, previousOwner);
            EmbeddedBaritonePolicy.installSnapshot(previousPolicy);
            if (previousBreak != null) BaritoneAPI.getSettings().allowBreak.value = previousBreak;
        }
        System.out.println("InstantClearanceTest: passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
