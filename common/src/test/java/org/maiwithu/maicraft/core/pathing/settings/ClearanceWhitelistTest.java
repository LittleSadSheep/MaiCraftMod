package org.maiwithu.maicraft.core.pathing.settings;

import baritone.pathing.movement.CalculationContext;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import it.unimi.dsi.fastutil.longs.LongSets;
import sun.misc.Unsafe;

/** 直接检验实际寻路成本：天然土层可挖，砖墙必须绕路，已有保护格和空白配置不能被地形许可覆盖。 */
public final class ClearanceWhitelistTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(ClearanceWhitelist.allows(Blocks.STONE.defaultBlockState()), "stone without server tags");
        check(ClearanceWhitelist.allows(Blocks.OAK_LOG.defaultBlockState()), "natural log type");
        for (var block : List.of(Blocks.BRICKS, Blocks.OAK_PLANKS, Blocks.CHEST, Blocks.GLASS, Blocks.OAK_DOOR))
            check(!ClearanceWhitelist.allows(block.defaultBlockState()), "preserve constructed obstacles");
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var context = (CalculationContext) ((Unsafe) unsafeField.get(null)).allocateInstance(CalculationContext.class);
        set(context, "allowBreak", true);
        set(context, "allowBreakAnyway", List.of(Blocks.BRICKS));
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null, null));
        check(context.breakCostMultiplierAt(0, 0, 0, Blocks.BRICKS.defaultBlockState()) >= 1e6,
                "route search must reject brick demolition even with explicit terraform and allowBreakAnyway");
        check(context.breakCostMultiplierAt(0, 0, 0, Blocks.DIRT.defaultBlockState()) == 1, "dirt route may be dug");
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(LongSets.singleton(BlockPos.ZERO.asLong()), null, null));
        check(context.breakCostMultiplierAt(0, 0, 0, Blocks.DIRT.defaultBlockState()) >= 1e6,
                "whitelisted terrain inside protected blueprint remains protected");
        set(context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null, null));
        set(context, "allowBreak", false);
        check(context.breakCostMultiplierAt(0, 0, 0, Blocks.DIRT.defaultBlockState()) >= 1e6,
                "whitelist does not grant terrain permission");

        // 玩家用配置明确扩展名单后才允许砖墙；空表或写坏配置必须关闭清障，不能退回更宽的默认名单。
        var directory = Files.createTempDirectory("maicraft-clearance-");
        var config = directory.resolve(ClearanceWhitelist.CONFIG);
        try {
            ClearanceWhitelist.initialize(directory);
            check(Files.isRegularFile(config), "first launch creates editable defaults");
            Files.writeString(config, "[\"minecraft:bricks\"]"); ClearanceWhitelist.initialize(directory);
            check(ClearanceWhitelist.allows(Blocks.BRICKS.defaultBlockState()), "custom type whitelist");
            check(!ClearanceWhitelist.allows(Blocks.DIRT.defaultBlockState()), "custom list replaces defaults");
            Files.writeString(config, "[]"); ClearanceWhitelist.initialize(directory);
            check(!ClearanceWhitelist.allows(Blocks.DIRT.defaultBlockState()), "empty whitelist disables clearance");
            Files.writeString(config, "[123]"); ClearanceWhitelist.initialize(directory);
            check(!ClearanceWhitelist.allows(Blocks.DIRT.defaultBlockState()), "invalid whitelist fails closed");
        } finally {
            Files.deleteIfExists(config); ClearanceWhitelist.initialize(directory);
            Files.delete(config); Files.delete(config.getParent()); Files.delete(directory);
        }
        System.out.println("ClearanceWhitelistTest: passed");
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
