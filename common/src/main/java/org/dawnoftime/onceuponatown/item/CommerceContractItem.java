package org.dawnoftime.onceuponatown.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.dawnoftime.onceuponatown.client.gui.tooltip.ContractTooltip;
import org.dawnoftime.onceuponatown.town.ContractEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class CommerceContractItem extends Item {

    private static final String TAG_ENTRIES = "ContractEntries";

    public CommerceContractItem(Properties props) {
        super(props);
    }

    public static void writeEntries(ItemStack stack, List<ContractEntry> entries) {
        ListTag list = new ListTag();
        for (ContractEntry e : entries) list.add(e.toNbt());
        stack.getOrCreateTag().put(TAG_ENTRIES, list);
    }

    public static List<ContractEntry> readEntries(ItemStack stack) {
        if (!stack.hasTag()) return Collections.emptyList();
        ListTag list = stack.getOrCreateTag().getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        List<ContractEntry> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++)
            result.add(ContractEntry.fromNbt(list.getCompound(i)));
        return result;
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        List<ContractEntry> entries = readEntries(stack);
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new ContractTooltip(entries));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        List<ContractEntry> entries = readEntries(stack);
        if (entries.isEmpty()) {
            tooltip.add(Component.literal("Empty contract").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("The receiving village will get these resources every day.")
                .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Deposit in a village stock to activate.")
                .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Shift-click in the stock tab to remove this contract.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
