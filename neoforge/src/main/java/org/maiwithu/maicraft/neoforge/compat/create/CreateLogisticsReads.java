// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderRequestPacket;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.maiwithu.maicraft.compat.create.CreateLogistics;

/**
 * 读写机械动力物流网络的读写端：按区块翻方块实体找请求器与包裹端口；库存管理员照 Create 的
 * getStockTickerPosition 认（坐在座位上、旁边恰好一台请求器）；上次用的地址读同步数据 PreviousAddress；
 * 现货读 getLastClientsideStockSnapshotAsSummary（去掉合成预览）；下单发 PackageOrderRequestPacket。只翻译，不判断。
 */
public final class CreateLogisticsReads implements CreateLogistics {

    @Override public List<Ticker> tickers(ClientLevel level, BlockPos center, int radius) {
        List<Ticker> found = new ArrayList<>();
        for (BlockEntity entity : blockEntities(level, center, radius)) {
            if (!(entity instanceof StockTickerBlockEntity ticker)) continue;
            BlockPos pos = ticker.getBlockPos();
            OptionalInt keeper = level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(2.0),
                            candidate -> pos.equals(StockTickerInteractionHandler.getStockTickerPosition(candidate)))
                    .stream().mapToInt(LivingEntity::getId).findFirst();
            String address = ticker.getUpdateTag(level.registryAccess()).getString("PreviousAddress");
            Map<String, Integer> stock = new LinkedHashMap<>();
            for (BigItemStack big : ticker.getLastClientsideStockSnapshotAsSummary().getStacks()) {
                if (big instanceof CraftableBigItemStack || big.stack.isEmpty()) continue;
                stock.merge(BuiltInRegistries.ITEM.getKey(big.stack.getItem()).toString(), big.count, Integer::sum);
            }
            found.add(new Ticker(pos, keeper, address, stock, ticker.getTicksSinceLastUpdate()));
        }
        return found;
    }

    @Override public List<Pickup> pickups(ClientLevel level, BlockPos center, int radius) {
        List<Pickup> found = new ArrayList<>();
        for (BlockEntity entity : blockEntities(level, center, radius)) {
            if (!(entity instanceof PackagePortBlockEntity pickup)) continue;
            List<ItemStack> packages = new ArrayList<>();
            for (int slot = 0; slot < pickup.inventory.getSlots(); slot++) packages.add(pickup.inventory.getStackInSlot(slot).copy());
            found.add(new Pickup(pickup.getBlockPos(), pickup.addressFilter == null ? "" : pickup.addressFilter, pickup.acceptsPackages,
                    packages));
        }
        return found;
    }

    @Override public Optional<Parcel> parcel(ItemStack stack) {
        if (!PackageItem.isPackage(stack)) return Optional.empty();
        ItemStackHandler contents = PackageItem.getContents(stack);
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack inside = contents.getStackInSlot(slot);
            if (!inside.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(inside.getItem()).toString(), inside.getCount(), Integer::sum);
        }
        String address = PackageItem.getAddress(stack);
        return Optional.of(new Parcel(address == null ? "" : address, items));
    }

    @Override public boolean requestMenuOpen(LocalPlayer player) {
        return player.containerMenu instanceof StockKeeperRequestMenu;
    }

    @Override public void order(BlockPos ticker, Map<String, Integer> items, String address) {
        List<BigItemStack> stacks = new ArrayList<>();
        items.forEach((itemId, count) -> BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                .ifPresent(item -> stacks.add(new BigItemStack(new ItemStack(item), count))));
        CatnipServices.NETWORK.sendToServer(new PackageOrderRequestPacket(ticker, PackageOrderWithCrafts.simple(stacks),
                address, false));
    }

    // 按区块翻方块实体表：只看客户端已经有的区块，没加载的不去加载。
    private static List<BlockEntity> blockEntities(ClientLevel level, BlockPos center, int radius) {
        List<BlockEntity> found = new ArrayList<>();
        for (int chunkX = (center.getX() - radius) >> 4; chunkX <= (center.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - radius) >> 4; chunkZ <= (center.getZ() + radius) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos at = entity.getBlockPos();
                    if (Math.abs(at.getX() - center.getX()) <= radius && Math.abs(at.getY() - center.getY()) <= radius
                            && Math.abs(at.getZ() - center.getZ()) <= radius) {
                        found.add(entity);
                    }
                }
            }
        }
        return found;
    }
}
