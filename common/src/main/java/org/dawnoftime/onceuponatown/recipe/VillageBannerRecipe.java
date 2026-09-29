package org.dawnoftime.onceuponatown.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import org.dawnoftime.onceuponatown.item.RecognitionMedalItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;

public class VillageBannerRecipe extends CustomRecipe {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageBannerRecipe.class);

    public static final SimpleCraftingRecipeSerializer<VillageBannerRecipe> SERIALIZER =
        new SimpleCraftingRecipeSerializer<>(VillageBannerRecipe::new);

    public VillageBannerRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        boolean hasMedal = false;
        boolean hasBanner = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(ItemRegistry.RECOGNITION_MEDAL)) {
                if (RecognitionMedalItem.readNamespace(stack).isEmpty()) return false;
                hasMedal = true;
            } else if (stack.getItem().builtInRegistryHolder().is(ItemTags.BANNERS)) {
                hasBanner = true;
            } else {
                return false;
            }
        }
        return hasMedal && hasBanner;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        ItemStack medal = ItemStack.EMPTY;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemRegistry.RECOGNITION_MEDAL)) {
                medal = stack;
                break;
            }
        }
        if (medal.isEmpty()) return ItemStack.EMPTY;

        LOGGER.info("[OUAT-RECIPE] Medal hasTag={} tag={}", medal.hasTag(), medal.getTag());
        ItemStack result = new ItemStack(ItemRegistry.VILLAGE_BANNER);
        if (medal.hasTag()) result.setTag(medal.getTag().copy());
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
