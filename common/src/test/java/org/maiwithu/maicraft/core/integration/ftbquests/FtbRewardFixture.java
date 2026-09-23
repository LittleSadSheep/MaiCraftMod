// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Node;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Type;

/** 奖励夹具把领取、抽奖和奖励表序列化设为禁用，验证只读查看不调用这些带状态的路径。 */
public final class FtbRewardFixture {
    public static final class Reward extends Node {
        public final String type;
        public boolean teamReward, blocked, sharedClaimed;
        public String auto = "disabled";
        public final Set<UUID> claimedPlayers = new HashSet<>();
        public final CompoundTag data = new CompoundTag();
        public Table table;
        public int reads;
        public Reward(long id, String type) {
            super(id, "奖励 " + id); this.type = "ftbquests:" + type;
            data.putInt("count", 2); data.putInt("random_bonus", 3);
        }
        public Type getType() { return new Type(type); }
        public String getAutoClaimType() { return auto; }
        public boolean isTeamReward() { return teamReward; }
        public boolean getExcludeFromClaimAll() { return type.equals("ftbquests:choice"); }
        public Table getTable() { return table; }
        public int getCount() { return 2; }
        public ItemStack getItem() { return new ItemStack(Items.APPLE); }
        public HolderLookup.Provider holderLookup() { return RegistryAccess.EMPTY; }
        public void writeData(CompoundTag result, HolderLookup.Provider provider) {
            if (table != null) throw new AssertionError("不能通过序列化绕过奖池隐藏或展开整个图");
            reads++; result.merge(data);
        }
        public void claim() { throw new AssertionError("不能领取奖励"); }
    }
    public record Claim(boolean claimed, boolean available) {
        public boolean isClaimed() { return claimed; }
        public boolean canClaim() { return available && !claimed; }
    }
    public record Weighted(Reward reward, float weight) {
        public Reward getReward() { return reward; }
        public float getWeight() { return weight; }
    }
    public static final class Table extends Node {
        public final List<Weighted> entries = new ArrayList<>();
        public boolean show = true;
        private int lootSize = 2;
        private float emptyWeight = 1;
        public Table(long id) { super(id, "奖励表"); }
        public boolean shouldShowTooltip() { return show; }
        public List<Weighted> getWeightedRewards() { return entries; }
        public float getTotalWeight(boolean includeEmpty) {
            return entries.stream().map(Weighted::weight).reduce(includeEmpty ? emptyWeight : 0f, Float::sum);
        }
        public void generateWeightedRandomRewards() { throw new AssertionError("不能提前抽奖"); }
        public void writeData(CompoundTag data, HolderLookup.Provider provider) { throw new AssertionError("不能序列化完整奖池"); }
    }
}
