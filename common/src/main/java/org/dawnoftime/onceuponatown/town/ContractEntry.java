package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public record ContractEntry(Item item, int amount, int everyTicks) {

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
        tag.putInt("Amount", amount);
        tag.putInt("EveryTicks", everyTicks);
        return tag;
    }

    public static ContractEntry fromNbt(CompoundTag tag) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(tag.getString("Item")));
        return new ContractEntry(item, tag.getInt("Amount"), tag.getInt("EveryTicks"));
    }
}
