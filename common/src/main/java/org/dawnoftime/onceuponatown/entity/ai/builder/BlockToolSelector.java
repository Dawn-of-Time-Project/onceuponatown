package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Maps a BlockState to the most appropriate held tool for an NPC breaking that block. */
public final class BlockToolSelector {

    private BlockToolSelector() {}

    /**
     * Returns the tool an NPC should visually hold when breaking {@code state}.
     * Returns {@link ItemStack#EMPTY} if no specific tool applies.
     */
    public static ItemStack getDestructionTool(BlockState state) {
        if (state.isAir()) return ItemStack.EMPTY;
        if (state.is(BlockTags.MINEABLE_WITH_AXE))     return new ItemStack(Items.WOODEN_AXE);
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL))  return new ItemStack(Items.WOODEN_SHOVEL);
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return new ItemStack(Items.WOODEN_PICKAXE);
        return ItemStack.EMPTY;
    }

    /**
     * Returns the item an NPC should hold when placing {@code target}.
     * Falls back to the block's own item for normal placements.
     */
    public static ItemStack getPlacementItem(BlockState target) {
        if (target.is(Blocks.WATER)) return new ItemStack(Items.WATER_BUCKET);
        ItemStack blockItem = new ItemStack(target.getBlock().asItem());
        return blockItem.isEmpty() ? ItemStack.EMPTY : blockItem;
    }
}
