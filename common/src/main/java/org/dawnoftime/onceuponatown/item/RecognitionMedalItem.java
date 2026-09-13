package org.dawnoftime.onceuponatown.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecognitionMedalItem extends Item {

    private static final String TAG_NAMESPACE = "CultureNamespace";
    private static final String TAG_IDS = "UnlockedIds";

    public RecognitionMedalItem(Properties props) {
        super(props);
    }

    public static void write(ItemStack stack, String namespace, Set<String> ids) {
        stack.getOrCreateTag().putString(TAG_NAMESPACE, namespace);
        ListTag list = new ListTag();
        for (String id : ids) list.add(StringTag.valueOf(id));
        stack.getOrCreateTag().put(TAG_IDS, list);
    }

    public static String readNamespace(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_NAMESPACE);
    }

    public static Set<String> readIds(ItemStack stack) {
        if (!stack.hasTag()) return Collections.emptySet();
        ListTag list = stack.getOrCreateTag().getList(TAG_IDS, Tag.TAG_STRING);
        Set<String> result = new HashSet<>(list.size());
        for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        String namespace = readNamespace(stack);
        Set<String> ids = readIds(stack);
        if (namespace.isEmpty()) {
            tooltip.add(Component.literal("Empty medal").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("Culture: " + namespace).withStyle(ChatFormatting.WHITE));
            for (String id : ids) {
                tooltip.add(Component.literal("- ").withStyle(ChatFormatting.WHITE)
                    .append(Component.translatable("onceuponatown.building." + id).withStyle(ChatFormatting.WHITE)));
            }
            tooltip.add(Component.literal("Deposit in a village of the same culture to unlock.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
