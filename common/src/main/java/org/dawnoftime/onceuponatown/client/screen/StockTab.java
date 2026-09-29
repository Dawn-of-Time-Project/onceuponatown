package org.dawnoftime.onceuponatown.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.item.RecognitionMedalItem;
import org.dawnoftime.onceuponatown.network.C2SBuyPacket;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Scroll zone: x=leftPos+151, y=topPos+17, 18x72 px. Slider travels 57 px (72-15).
// Slider UV: enabled=41, disabled=58 at U=177 in town_hub.png.

class StockTab {

    // Order from trade_prices.json — loaded once from the jar classpath.
    // Items in the stock not present in this list fall back to the end.
    private static final List<String> TRADE_PRICE_ORDER = TownHubTypes.loadTradePriceOrder();

    private static final ResourceLocation TEXTURE =
        new ResourceLocation("onceuponatown", "textures/gui/town_hub.png");

    private static final String GHOST_TAG = "OuatGhost";

    // Persists across screen open/close within the same game session; resets on game restart.
    private static boolean showGhostSlots = true;

    private boolean buyMode = false;
    private final Map<String, int[]> tradePrices = new HashMap<>();
    private final LinkedHashMap<Item, Integer> buyRequest = new LinkedHashMap<>();

    private final List<ItemStack> allStockItems = new ArrayList<>();
    private final Map<String, Integer> stockCapacity = new HashMap<>();
    private CompoundTag lastStockTag = null;
    private int scrollOffset = 0;
    private boolean isDraggingSlider = false;
    private float dragAnchorScreenY = 0;
    private float dragAnchorSliderY = 0;

    void parseTradePrices(CompoundTag hub) {
        tradePrices.clear();
        if (!hub.contains("TradePrices")) return;
        CompoundTag pricesTag = hub.getCompound("TradePrices");
        for (String itemId : pricesTag.getAllKeys()) {
            CompoundTag priceEntry = pricesTag.getCompound(itemId);
            tradePrices.put(itemId, new int[]{ priceEntry.getInt("buy"), priceEntry.getInt("sell"), Math.max(1, priceEntry.getInt("quantity")) });
        }
    }

    void updateCapacity(CompoundTag capTag) {
        stockCapacity.clear();
        for (String key : capTag.getAllKeys())
            stockCapacity.put(key, capTag.getInt(key));
    }

    void applyStockData(CompoundTag stockTag, TownHubMenu menu) {
        lastStockTag = stockTag.copy();
        buildAllStockItems(stockTag);
        loadMenuWindow(menu);
    }

    // Loads the visible window into menu slots, replacing ghost stacks with EMPTY
    // so they don't appear as real items in the container.
    private void loadMenuWindow(TownHubMenu menu) {
        List<ItemStack> forMenu = new ArrayList<>(allStockItems.size());
        for (ItemStack s : allStockItems)
            forMenu.add(isGhost(s) ? ItemStack.EMPTY : s);
        menu.loadVisibleWindow(forMenu, scrollOffset);
    }

    static boolean isGhost(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getOrCreateTag().getBoolean(GHOST_TAG);
    }

    private void buildAllStockItems(CompoundTag stockTag) {
        allStockItems.clear();

        Map<String, Integer> counts = new HashMap<>();
        for (String itemId : stockTag.getAllKeys()) {
            int count = stockTag.getInt(itemId);
            if (count > 0) counts.put(itemId, count);
        }

        ListTag contractEntriesTag = stockTag.contains("onceuponatown:commerce_contract_data", Tag.TAG_LIST)
            ? stockTag.getList("onceuponatown:commerce_contract_data", Tag.TAG_COMPOUND)
            : null;

        CompoundTag medalDataTag = stockTag.contains("onceuponatown:recognition_medal_data", Tag.TAG_COMPOUND)
            ? stockTag.getCompound("onceuponatown:recognition_medal_data")
            : null;

        // Build ordered set: trade_prices.json order first, unknowns appended at the end.
        Set<String> sorted = new LinkedHashSet<>();
        for (String id : TRADE_PRICE_ORDER) { if (counts.containsKey(id)) sorted.add(id); }
        sorted.addAll(counts.keySet());

        for (String itemId : sorted) {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) continue;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || item == Items.AIR) continue;

            int currentCount = counts.get(itemId);
            int stackMax = item.getMaxStackSize();

            // Real stacks
            int remaining = currentCount;
            while (remaining > 0) {
                int stackSize = Math.min(remaining, stackMax);
                ItemStack stack = new ItemStack(item, stackSize);
                if (contractEntriesTag != null && "onceuponatown:commerce_contract".equals(itemId)) {
                    stack.getOrCreateTag().put("ContractEntries", contractEntriesTag);
                }
                if (medalDataTag != null && "onceuponatown:recognition_medal".equals(itemId)) {
                    net.minecraft.nbt.CompoundTag medalTag = stack.getOrCreateTag();
                    medalTag.putString("CultureNamespace", medalDataTag.getString("CultureNamespace"));
                    medalTag.putString("MainOrientation", medalDataTag.getString("MainOrientation"));
                    medalTag.putString("MainOrientationLabel", medalDataTag.getString("MainOrientationLabel"));
                    medalTag.putString("CurrentOrientation", medalDataTag.getString("CurrentOrientation"));
                    medalTag.putString("CurrentOrientationLabel", medalDataTag.getString("CurrentOrientationLabel"));
                    medalTag.putString("StarterBuildingId", medalDataTag.getString("StarterBuildingId"));
                    medalTag.put("EraUnlockedIds", medalDataTag.getList("EraUnlockedIds", Tag.TAG_STRING).copy());
                    medalTag.put("SignatureIds", medalDataTag.getList("SignatureIds", Tag.TAG_STRING).copy());
                }
                allStockItems.add(stack);
                remaining -= stackSize;
            }

            if (showGhostSlots) {
                int maxCount = stockCapacity.getOrDefault(itemId, 0);
                if (maxCount > currentCount) {
                    int usedStacks = (int) Math.ceil((double) currentCount / stackMax);
                    int maxStacks  = (int) Math.ceil((double) maxCount    / stackMax);
                    for (int i = usedStacks; i < maxStacks; i++) {
                        ItemStack ghost = new ItemStack(item, 1);
                        ghost.getOrCreateTag().putBoolean(GHOST_TAG, true);
                        allStockItems.add(ghost);
                    }
                }
            }
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, computeMaxScroll()));
    }

    private int computeMaxScroll() {
        int totalRows = (int) Math.ceil((double) allStockItems.size() / TownHubMenu.COLS);
        return Math.max(0, totalRows - TownHubMenu.ROWS);
    }

    private int computeSliderY() {
        int max = computeMaxScroll();
        if (max <= 0) return 0;
        return Math.round((float) scrollOffset / max * 55);
    }

    boolean handleDrag(double mY, TownHubMenu menu) {
        if (!isDraggingSlider) return false;
        int max = computeMaxScroll();
        if (max <= 0) { isDraggingSlider = false; return false; }
        float delta = (float) (mY - dragAnchorScreenY);
        float newSliderY = dragAnchorSliderY + delta;
        scrollOffset = Math.round(newSliderY / 55f * max);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
        loadMenuWindow(menu);
        return true;
    }

    boolean handleRelease() {
        if (isDraggingSlider) { isDraggingSlider = false; return true; }
        return false;
    }

    boolean handleScroll(double delta, TownHubMenu menu) {
        int max = computeMaxScroll();
        if (max <= 0) return false;
        scrollOffset -= (int) Math.signum(delta);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
        loadMenuWindow(menu);
        return true;
    }

    void render(GuiGraphics g, int leftPos, int topPos, int mx, int my,
                TownHubTypes.TownHubTabContext ctx, TownHubMenu menu) {
        // Ghost slots: drawn over empty container slots, at 25% alpha, no count label.
        // Called after super.render() in TownHubScreen, so they appear on top of the slot bg.
        // setShaderColor must be active when the buffer flushes, so flush() is called after the
        // loop — not inside — to batch all ghost items in one pass before resetting the color.
        // Ghost slots: two passes.
        // Pass 1 — render all ghost items at full opacity.
        // Pass 2 — overlay a 75% opaque slot-colored fill with depth test disabled so it
        //          draws on top of items (GUI items render at z=200, fills default to z=0).
        //          Net result: item appears at ~25% visibility for all render types (2D + 3D).
        int startIndex = scrollOffset * TownHubMenu.COLS;
        if (showGhostSlots) {
            for (int slot = 0; slot < TownHubMenu.CHEST_SIZE; slot++) {
                int srcIndex = startIndex + slot;
                if (srcIndex >= allStockItems.size()) break;
                ItemStack s = allStockItems.get(srcIndex);
                if (!isGhost(s)) continue;
                g.renderFakeItem(new ItemStack(s.getItem()),
                    leftPos + 8 + (slot % TownHubMenu.COLS) * 18,
                    topPos + 18 + (slot / TownHubMenu.COLS) * 18);
            }
            // Pass 2: draw dimming overlay via Tesselator to bypass RenderType GL state override.
            // RenderType.gui() resets depth/blend when it flushes, so g.fill() is unreliable here.
            g.flush();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            Tesselator tes = Tesselator.getInstance();
            BufferBuilder buf = tes.getBuilder();
            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            org.joml.Matrix4f mat = g.pose().last().pose();
            for (int slot = 0; slot < TownHubMenu.CHEST_SIZE; slot++) {
                int srcIndex = startIndex + slot;
                if (srcIndex >= allStockItems.size()) break;
                if (!isGhost(allStockItems.get(srcIndex))) continue;
                float sx = leftPos + 8 + (slot % TownHubMenu.COLS) * 18;
                float sy = topPos + 18 + (slot / TownHubMenu.COLS) * 18;
                buf.vertex(mat, sx,      sy + 16, 300).color(0x8B, 0x8B, 0x8B, 0xBF).endVertex();
                buf.vertex(mat, sx + 16, sy + 16, 300).color(0x8B, 0x8B, 0x8B, 0xBF).endVertex();
                buf.vertex(mat, sx + 16, sy,      300).color(0x8B, 0x8B, 0x8B, 0xBF).endVertex();
                buf.vertex(mat, sx,      sy,      300).color(0x8B, 0x8B, 0x8B, 0xBF).endVertex();
            }
            BufferUploader.drawWithShader(buf.end());
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
        }

        int blueX = leftPos + 8;
        int blueY = topPos + 90;
        int greenY = topPos + 107;
        int toggleX = leftPos + 151;
        int toggleY = topPos + 89;
        int confirmX = leftPos + 151;
        int confirmY = topPos + 107;

        int arrowV = buyMode ? 21 : 1;
        g.blit(TEXTURE, toggleX, toggleY, 177, arrowV, 18, 18);
        boolean arrowHover = mx >= toggleX && mx < toggleX + 18 && my >= toggleY && my < toggleY + 18;
        if (arrowHover) g.fill(toggleX, toggleY, toggleX + 18, toggleY + 18, 0x30FFFFFF);

        // Scroll zone
        int scrollZoneX = leftPos + 151;
        int scrollZoneY = topPos + 17;
        boolean canScroll = computeMaxScroll() > 0;
        int sliderV = canScroll ? 41 : 58;
        int sliderScreenX = scrollZoneX + 1;
        int sliderScreenY = scrollZoneY + 1 + computeSliderY();
        g.blit(TEXTURE, sliderScreenX, sliderScreenY, 177, sliderV, 16, 15);
        if (canScroll) {
            boolean hover = mx >= sliderScreenX && mx < sliderScreenX + 16
                         && my >= sliderScreenY && my < sliderScreenY + 15;
            if (hover || isDraggingSlider) g.fill(sliderScreenX, sliderScreenY,
                sliderScreenX + 16, sliderScreenY + 15, 0x30FFFFFF);
        }

        if (buyMode) {
            List<Map.Entry<Item, Integer>> slots = expandBuySlots();
            for (int col = 0; col < TownHubMenu.DEPOSIT_SLOTS && col < slots.size(); col++) {
                int sx = blueX + col * 18;
                ItemStack stack = new ItemStack(slots.get(col).getKey(), slots.get(col).getValue());
                g.renderFakeItem(stack, sx, blueY);
                g.renderItemDecorations(ctx.font(), stack, sx, blueY);
            }
        }

        int totalEmeralds = 0;
        if (buyMode) {
            for (Map.Entry<Item, Integer> e : buyRequest.entrySet()) {
                String id = BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
                int[] prices = tradePrices.get(id);
                if (prices != null) {
                    int qty = prices[2];
                    totalEmeralds += prices[0] * (e.getValue() / qty);
                }
            }
        } else {
            var deposit = menu.getDepositContainer();
            for (int i = 0; i < deposit.getContainerSize(); i++) {
                ItemStack stack = deposit.getItem(i);
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                int[] prices = tradePrices.get(id);
                if (prices != null) {
                    int qty = prices[2];
                    totalEmeralds += prices[1] * (stack.getCount() / qty);
                }
            }
        }

        if (totalEmeralds > 0) {
            int remaining = totalEmeralds;
            int col = 0;
            while (remaining > 0 && col < TownHubMenu.DEPOSIT_SLOTS) {
                int stackSize = Math.min(remaining, 64);
                ItemStack emeraldStack = new ItemStack(Items.EMERALD, stackSize);
                int sx = blueX + col * 18;
                g.pose().pushPose();
                g.pose().translate(0, 0, 50);
                g.fill(sx, greenY, sx + 16, greenY + 16, 0x4400AA00);
                g.renderFakeItem(emeraldStack, sx, greenY);
                g.renderItemDecorations(ctx.font(), emeraldStack, sx, greenY);
                g.pose().popPose();
                remaining -= stackSize;
                col++;
            }
        }

        boolean toggleBtnHover = mx >= confirmX && mx < confirmX + 18 && my >= confirmY && my < confirmY + 18;
        g.fill(confirmX, confirmY, confirmX + 18, confirmY + 18, toggleBtnHover ? 0xFF555555 : 0xFF333333);
        String modeLabel = buyMode ? "BUY" : "SELL";
        float labelScale = 0.75f;
        g.pose().pushPose();
        g.pose().translate(confirmX + 9, confirmY + 9, 0);
        g.pose().scale(labelScale, labelScale, 1.0f);
        g.drawCenteredString(ctx.font(), modeLabel, 0, -4, 0xFFFFFFFF);
        g.pose().popPose();

        // Ghost slots toggle button
        int ghostBtnX = leftPos + 155;
        int ghostBtnY = topPos + 5;
        g.blit(TEXTURE, ghostBtnX, ghostBtnY, 179, 102, 10, 10);
        if (showGhostSlots) {
            g.fill(ghostBtnX, ghostBtnY, ghostBtnX + 10, ghostBtnY + 10, 0x66FFFFFF);
        }
        boolean ghostHover = mx >= ghostBtnX && mx < ghostBtnX + 10 && my >= ghostBtnY && my < ghostBtnY + 10;
        if (ghostHover) {
            Component stateLabel = showGhostSlots
                ? Component.translatable("onceuponatown.ui.ghost_toggle.tooltip.on").withStyle(ChatFormatting.GREEN)
                : Component.translatable("onceuponatown.ui.ghost_toggle.tooltip.off").withStyle(ChatFormatting.GRAY);
            g.renderComponentTooltip(ctx.font(), List.of(
                Component.translatable("onceuponatown.ui.ghost_toggle.tooltip"),
                stateLabel
            ), mx, my);
        }
    }

    // Returns true if the trade-price tooltip was rendered (caller skips super.renderTooltip).
    boolean renderTradePriceTooltip(GuiGraphics g, int mx, int my,
                                     Slot hoveredSlot,
                                     int leftPos, int topPos,
                                     Font font) {
        if (hoveredSlot == null || !hoveredSlot.hasItem()
                || hoveredSlot.index >= TownHubMenu.CHEST_SIZE) return false;
        if (isGhost(hoveredSlot.getItem())) return false;
        String itemId = BuiltInRegistries.ITEM.getKey(hoveredSlot.getItem().getItem()).toString();
        int[] prices = tradePrices.get(itemId);
        if (prices == null) return false;

        int buy = prices[0], sell = prices[1], qty = prices[2];
        Item item = hoveredSlot.getItem().getItem();

        String itemName  = new ItemStack(item).getHoverName().getString();
        String labelBuy  = net.minecraft.network.chat.Component.translatable("onceuponatown.tooltip.trade_buy").getString();
        String labelSell = net.minecraft.network.chat.Component.translatable("onceuponatown.tooltip.trade_sell").getString();
        int labelW = Math.max(font.width(labelBuy), font.width(labelSell));

        int rowH = 18;
        int nameRowH = 11;
        int priceContentW = 4 + labelW + 3 + 16 + 5 + 5 + 16 + 4;
        int panW = Math.max(priceContentW, 4 + font.width(itemName) + 4);
        int panH = 4 + nameRowH + rowH + rowH + 4;
        int panX = mx + 10;
        int panY = my - panH / 2;
        var window = Minecraft.getInstance().getWindow();
        int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        if (panX + panW > screenW)  panX = mx - panW - 4;
        if (panY < 0)               panY = 0;
        if (panY + panH > screenH)  panY = screenH - panH;

        g.fill(panX,            panY,            panX + panW,     panY + panH,     0xF0100010);
        g.fill(panX + 1,        panY,            panX + panW - 1, panY + 1,        0x505000FF);
        g.fill(panX + 1,        panY + panH - 1, panX + panW - 1, panY + panH,     0x5028007F);
        g.fill(panX,            panY + 1,        panX + 1,        panY + panH - 1, 0x505000FF);
        g.fill(panX + panW - 1, panY + 1,        panX + panW,     panY + panH - 1, 0x5028007F);

        int labelX = panX + 4;
        int iconX1 = labelX + labelW + 3;
        int slashX = iconX1 + 16 + 2;
        int iconX2 = slashX + 5;

        g.drawString(font, itemName, panX + 4, panY + 4, 0xFFFFFFFF, false);

        int row1Y = panY + 4 + nameRowH;
        g.drawString(font, labelBuy, labelX, row1Y + 5, 0xFFAAAAAA, false);
        g.renderFakeItem(new ItemStack(item, qty), iconX1, row1Y);
        g.renderItemDecorations(font, new ItemStack(item, qty), iconX1, row1Y);
        g.drawString(font, "/", slashX, row1Y + 5, 0xFFAAAAAA, false);
        g.renderFakeItem(new ItemStack(Items.EMERALD, buy), iconX2, row1Y);
        g.renderItemDecorations(font, new ItemStack(Items.EMERALD, buy), iconX2, row1Y);

        int row2Y = row1Y + rowH;
        g.drawString(font, labelSell, labelX, row2Y + 5, 0xFFAAAAAA, false);
        g.renderFakeItem(new ItemStack(item, qty), iconX1, row2Y);
        g.renderItemDecorations(font, new ItemStack(item, qty), iconX1, row2Y);
        g.drawString(font, "/", slashX, row2Y + 5, 0xFFAAAAAA, false);
        g.renderFakeItem(new ItemStack(Items.EMERALD, sell), iconX2, row2Y);
        g.renderItemDecorations(font, new ItemStack(Items.EMERALD, sell), iconX2, row2Y);
        return true;
    }

    boolean isArrowHovered(int mx, int my, int leftPos, int topPos) {
        int toggleX = leftPos + 151;
        int toggleY = topPos + 89;
        return mx >= toggleX && mx < toggleX + 18 && my >= toggleY && my < toggleY + 18;
    }

    boolean isModeToggleHovered(int mx, int my, int leftPos, int topPos) {
        int toggleX = leftPos + 151;
        int toggleY = topPos + 107;
        return mx >= toggleX && mx < toggleX + 18 && my >= toggleY && my < toggleY + 18;
    }

    boolean isBuyMode() { return buyMode; }

    // Handles all tab-0 clicks. Returns true if the click was consumed.
    boolean handleClick(double mX, double mY, int button,
                        int leftPos, int topPos,
                        TownHubTypes.TownHubTabContext ctx, TownHubMenu menu) {
        // Shift+left-click on the active contract in the stock grid removes it.
        if (button == 0 && Screen.hasShiftDown()) {
            for (int i = 0; i < TownHubMenu.CHEST_SIZE; i++) {
                Slot slot = menu.slots.get(i);
                if (slot.hasItem() && isHoveringSlot(slot, mX, mY, leftPos, topPos)) {
                    if (slot.getItem().getItem() == ItemRegistry.COMMERCE_CONTRACT && !isGhost(slot.getItem())) {
                        NetworkHelper.sendCancelContractPacket.accept(ctx.anchorPos());
                        return true;
                    }
                }
            }
        }

        if (button == 0) {
            int ghostBtnX = leftPos + 155;
            int ghostBtnY = topPos + 5;
            if (mX >= ghostBtnX && mX < ghostBtnX + 10 && mY >= ghostBtnY && mY < ghostBtnY + 10) {
                showGhostSlots = !showGhostSlots;
                if (lastStockTag != null) {
                    buildAllStockItems(lastStockTag);
                    loadMenuWindow(menu);
                }
                return true;
            }

            int scrollZoneX = leftPos + 151;
            int scrollZoneY = topPos + 17;
            if (computeMaxScroll() > 0) {
                int sliderY  = computeSliderY();
                int sliderSX = scrollZoneX + 1;
                int sliderSY = scrollZoneY + 1 + sliderY;
                if (mX >= sliderSX && mX < sliderSX + 16 && mY >= sliderSY && mY < sliderSY + 15) {
                    isDraggingSlider  = true;
                    dragAnchorScreenY = (float) mY;
                    dragAnchorSliderY = sliderY;
                    return true;
                }
                if (mX >= scrollZoneX && mX < scrollZoneX + 18
                        && mY >= scrollZoneY && mY < scrollZoneY + 72) {
                    float ratio = ((float) mY - scrollZoneY - 1) / 55f;
                    scrollOffset = Math.round(ratio * computeMaxScroll());
                    scrollOffset = Math.max(0, Math.min(scrollOffset, computeMaxScroll()));
                    loadMenuWindow(menu);
                    return true;
                }
            }

            int toggleX = leftPos + 151;
            int toggleY = topPos + 107;
            if (mX >= toggleX && mX < toggleX + 18 && mY >= toggleY && mY < toggleY + 18) {
                if (buyMode) {
                    buyRequest.clear();
                    buyMode = false;
                } else {
                    returnDepositToPlayer(menu);
                    buyMode = true;
                    buyRequest.clear();
                }
                return true;
            }

            int confirmX = leftPos + 151;
            int confirmY = topPos + 89;
            if (mX >= confirmX && mX < confirmX + 18 && mY >= confirmY && mY < confirmY + 18) {
                if (buyMode && !buyRequest.isEmpty()) {
                    List<C2SBuyPacket.Entry> entries = new ArrayList<>();
                    buyRequest.forEach((item, count) -> {
                        String id = BuiltInRegistries.ITEM.getKey(item).toString();
                        entries.add(new C2SBuyPacket.Entry(id, count));
                    });
                    NetworkHelper.sendBuyPacket.accept(ctx.anchorPos(), entries);
                    buyRequest.clear();
                } else if (!buyMode) {
                    NetworkHelper.sendDepositPacket.accept(ctx.anchorPos());
                }
                return true;
            }
        }

        if (buyMode && (button == 0 || button == 1)) {
            for (int i = 0; i < TownHubMenu.CHEST_SIZE; i++) {
                Slot slot = menu.slots.get(i);
                if (slot.hasItem() && isHoveringSlot(slot, mX, mY, leftPos, topPos)) {
                    ItemStack stack = slot.getItem();
                    if (isGhost(stack)) return true;
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (tradePrices.containsKey(itemId)) {
                        int[] prices  = tradePrices.get(itemId);
                        int qty       = prices[2];
                        int inStock   = ctx.stockSnapshot().getOrDefault(itemId, 0);
                        int current   = buyRequest.getOrDefault(stack.getItem(), 0);
                        int add;
                        if (button == 0 && Screen.hasShiftDown()) add = (inStock / qty) * qty;
                        else                                       add = qty;
                        int slotsUsedByOthers = expandBuySlots().size() - (int)Math.ceil((double)current / 64);
                        int maxSlots = Math.max(0, TownHubMenu.DEPOSIT_SLOTS - slotsUsedByOthers);
                        int maxCount = (Math.min(inStock, maxSlots * 64) / qty) * qty;
                        int newCount = Math.min(current + add, maxCount);
                        if (newCount > 0) buyRequest.put(stack.getItem(), newCount);
                    }
                    return true;
                }
            }

            int blueZoneX = leftPos + 8;
            int blueZoneY = topPos + 90;
            if (mX >= blueZoneX && mX < blueZoneX + TownHubMenu.DEPOSIT_SLOTS * 18
                    && mY >= blueZoneY && mY < blueZoneY + 18) {
                int col = (int)(mX - blueZoneX) / 18;
                List<Map.Entry<Item, Integer>> slots = expandBuySlots();
                if (col < slots.size()) {
                    Item item = slots.get(col).getKey();
                    int totalCurrent = buyRequest.get(item);
                    String rmItemId  = BuiltInRegistries.ITEM.getKey(item).toString();
                    int[] rmPrices   = tradePrices.get(rmItemId);
                    int rmQty        = (rmPrices != null) ? rmPrices[2] : 1;
                    int remove;
                    if (button == 0 && Screen.hasShiftDown()) remove = totalCurrent;
                    else                                       remove = rmQty;
                    int newCount = totalCurrent - remove;
                    if (newCount <= 0) buyRequest.remove(item);
                    else buyRequest.put(item, newCount);
                }
                return true;
            }

            int depositStart = TownHubMenu.CHEST_SIZE;
            int depositEnd   = TownHubMenu.CHEST_SIZE + TownHubMenu.DEPOSIT_SLOTS;
            for (int i = depositStart; i < depositEnd; i++) {
                if (isHoveringSlot(menu.slots.get(i), mX, mY, leftPos, topPos)) return true;
            }
        }

        return false;
    }

    void onTabEnter(BlockPos anchorPos) {
        buyMode = false;
        buyRequest.clear();
        NetworkHelper.sendRequestStockPacket.accept(anchorPos);
    }

    void onTabLeave(TownHubMenu menu) {
        returnDepositToPlayer(menu);
    }

    private List<Map.Entry<Item, Integer>> expandBuySlots() {
        List<Map.Entry<Item, Integer>> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : buyRequest.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                int batch = Math.min(remaining, 64);
                result.add(Map.entry(entry.getKey(), batch));
                remaining -= batch;
            }
        }
        return result;
    }

    private void returnDepositToPlayer(TownHubMenu menu) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var deposit = menu.getDepositContainer();
        for (int i = 0; i < deposit.getContainerSize(); i++) {
            ItemStack stack = deposit.getItem(i);
            if (!stack.isEmpty()) {
                mc.player.addItem(stack.copy());
                deposit.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static boolean isHoveringSlot(Slot slot, double mx, double my, int leftPos, int topPos) {
        int sx = leftPos + slot.x;
        int sy = topPos + slot.y;
        return mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16;
    }
}
