package org.dawnoftime.onceuponatown.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecognitionMedalItem extends Item {

    private static final String TAG_NAMESPACE         = "CultureNamespace";
    private static final String TAG_MAIN_ORI          = "MainOrientation";
    private static final String TAG_MAIN_ORI_LABEL    = "MainOrientationLabel";
    private static final String TAG_CURRENT_ORI       = "CurrentOrientation";
    private static final String TAG_CURRENT_ORI_LABEL = "CurrentOrientationLabel";
    private static final String TAG_STARTER           = "StarterBuildingId";
    private static final String TAG_ERA_UNLOCKED      = "EraUnlockedIds";
    private static final String TAG_SIGNATURE         = "SignatureIds";

    public RecognitionMedalItem(Properties props) {
        super(props);
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return Rarity.UNCOMMON;
    }

    public static void write(ItemStack stack, Town.MedalSnapshot snapshot) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_NAMESPACE,         snapshot.namespace());
        tag.putString(TAG_MAIN_ORI,          snapshot.mainOrientation());
        tag.putString(TAG_MAIN_ORI_LABEL,    snapshot.mainOrientationLabel());
        tag.putString(TAG_CURRENT_ORI,       snapshot.currentOrientation());
        tag.putString(TAG_CURRENT_ORI_LABEL, snapshot.currentOrientationLabel());
        tag.putString(TAG_STARTER,           snapshot.starterBuildingId());
        tag.put(TAG_ERA_UNLOCKED,            toStringList(snapshot.eraUnlockedIds()));
        tag.put(TAG_SIGNATURE,               toStringList(snapshot.signatureIds()));
    }

    private static ListTag toStringList(Set<String> ids) {
        ListTag list = new ListTag();
        for (String id : ids) list.add(StringTag.valueOf(id));
        return list;
    }

    private static Set<String> readStringSet(ItemStack stack, String key) {
        if (!stack.hasTag()) return Collections.emptySet();
        ListTag list = stack.getOrCreateTag().getList(key, Tag.TAG_STRING);
        Set<String> result = new HashSet<>(list.size());
        for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        return result;
    }

    public static String readNamespace(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_NAMESPACE);
    }

    public static String readMainOrientation(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_MAIN_ORI);
    }

    public static String readMainOrientationLabel(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_MAIN_ORI_LABEL);
    }

    public static String readCurrentOrientation(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_CURRENT_ORI);
    }

    public static String readCurrentOrientationLabel(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_CURRENT_ORI_LABEL);
    }

    public static String readStarterBuildingId(ItemStack stack) {
        if (!stack.hasTag()) return "";
        return stack.getOrCreateTag().getString(TAG_STARTER);
    }

    public static Set<String> readEraUnlockedIds(ItemStack stack) {
        return readStringSet(stack, TAG_ERA_UNLOCKED);
    }

    public static Set<String> readSignatureIds(ItemStack stack) {
        return readStringSet(stack, TAG_SIGNATURE);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        String namespace = readNamespace(stack);
        if (namespace.isEmpty()) {
            tooltip.add(Component.translatable("onceuponatown.tooltip.medal.empty")
                .withStyle(ChatFormatting.WHITE));
            return;
        }

        String mainLabel    = readMainOrientationLabel(stack);
        String currentLabel = readCurrentOrientationLabel(stack);
        String starterBId   = readStarterBuildingId(stack);
        Set<String> sigIds  = readSignatureIds(stack);
        Set<String> eraIds  = readEraUnlockedIds(stack);

        String oriValue = mainLabel.equals(currentLabel)
            ? mainLabel
            : currentLabel + " (" + mainLabel + ")";
        tooltip.add(Component.translatable("onceuponatown.tooltip.medal.orientation")
            .append(Component.literal(": " + oriValue))
            .withStyle(ChatFormatting.WHITE));

        tooltip.add(Component.translatable("onceuponatown.tooltip.medal.culture")
            .append(Component.literal(": " + namespace))
            .withStyle(ChatFormatting.WHITE));

        if (!sigIds.isEmpty() || !eraIds.isEmpty()) {
            tooltip.add(Component.translatable("onceuponatown.tooltip.medal.unlocked_buildings")
                .append(Component.literal(":"))
                .withStyle(ChatFormatting.WHITE));
            for (String id : sigIds) {
                tooltip.add(Component.literal("  - ")
                    .append(Component.translatable("onceuponatown.building." + id))
                    .withStyle(ChatFormatting.GOLD));
            }
            for (String id : eraIds) {
                if (sigIds.contains(id)) continue;
                tooltip.add(Component.literal("  - ")
                    .append(Component.translatable("onceuponatown.building." + id))
                    .withStyle(ChatFormatting.WHITE));
            }
        }

        tooltip.add(Component.translatable("onceuponatown.tooltip.medal.deposit_hint")
            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
