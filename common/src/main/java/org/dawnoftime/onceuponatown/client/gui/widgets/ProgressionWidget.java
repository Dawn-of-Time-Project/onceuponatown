package org.dawnoftime.onceuponatown.client.gui.widgets;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ProgressionWidget extends DraggableWidget {

    public static final int WIDGET_W  = 196;
    public static final int VISIBLE_H = 180;

    private static final ResourceLocation TEXTURE_CONSTRUCTION =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/town_construction.png");

    private static final int ROW_H       = 22;
    private static final int CLAIM_H     = 36;
    private static final int PAD         = 4;
    private static final int FONT_H      = 9;
    private static final int BAR_H       = 5;
    private static final int SCROLLBAR_W = 6;

    // Color palette
    private static final int COL_BG       = 0xF5111111;
    private static final int COL_ROW_ALT  = 0x08FFFFFF;
    private static final int COL_ROW_HOVER= 0x18FFFFFF;
    private static final int COL_TEXT     = 0xFFCCCCCC;
    private static final int COL_DIM      = 0xFF888888;
    private static final int COL_DIS      = 0xFF555555;
    private static final int COL_LOCKED   = 0xFF444444;
    private static final int COL_LEVEL    = 0xFFAAAAAA;
    private static final int COL_SLOT_BG  = 0xFF1A1A1A;
    private static final int COL_SLOT_BORDER = 0xFF444444;
    private static final int COL_SLOT_CLAIMED= 0xFF444444;
    private static final int COL_SLOT_GLOW   = 0x40FFFFFF;
    private static final int COL_BTN_CL_BG   = 0xFF222222;
    private static final int COL_BTN_CL_HV   = 0xFF333333;
    private static final int COL_BTN_CL_BD   = 0xFF555555;
    private static final int COL_BTN_CL_TX   = 0xFFCCCCCC;
    private static final int COL_BTN_NO_BG   = 0xFF222222;
    private static final int COL_BTN_NO_TX   = 0xFF555555;
    private static final int COL_BTN_DN_BG   = 0xFF1E1E1E;
    private static final int COL_BTN_DN_BD   = 0xFF444444;
    private static final int COL_BTN_DN_TX   = 0xFF888888;

    public record BuildingRow(
        String defId, String iconItem,
        boolean accessible, boolean built,
        int currentLevel, int maxLevel, boolean maxed
    ) {}

    private List<BuildingRow> rows = new ArrayList<>();
    private boolean medalClaimed = false;
    private final BlockPos anchorPos;
    private final Consumer<BlockPos> onClaimMedal;

    private int scrollPx = 0;
    private int totalH   = 0;

    private boolean draggingScrollbar = false;
    private double  dragStartMouseY   = 0;
    private int     dragStartScrollPx = 0;

    // Hit boxes recomputed each render frame
    private int[] claimBtnBounds = null;  // {x1, y1, x2, y2}

    // Tooltip collected during renderContent, flushed at the end
    private String pendingTooltip = null;
    private int pendingTooltipX, pendingTooltipY;

    public ProgressionWidget(int x, int y, int freeZoneMaxX, int screenH,
                             CompoundTag progressionData, BlockPos anchorPos,
                             Consumer<BlockPos> onClaimMedal) {
        super(x, y, WIDGET_W, computeHeight(countRows(progressionData)), freeZoneMaxX, screenH);
        this.anchorPos    = anchorPos;
        this.onClaimMedal = onClaimMedal;
        parseData(progressionData);
    }

    public void updateData(CompoundTag progressionData) {
        parseData(progressionData);
        totalH = Math.max(ROW_H, rows.size() * ROW_H);
        setHeight(computeHeight(rows.size()));
        int maxScroll = Math.max(0, totalH - VISIBLE_H);
        scrollPx = Math.min(scrollPx, maxScroll);
    }

    private void parseData(CompoundTag data) {
        if (data == null) return;
        medalClaimed = data.getBoolean("MedalClaimed");
        rows.clear();
        ListTag list = data.getList("SignatureBuildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            rows.add(new BuildingRow(
                t.getString("DefId"),
                t.getString("IconItem"),
                t.getBoolean("Accessible"),
                t.getBoolean("Built"),
                t.getInt("CurrentLevel"),
                t.getInt("MaxLevel"),
                t.getBoolean("Maxed")
            ));
        }
        totalH = Math.max(ROW_H, rows.size() * ROW_H);
    }

    private static int countRows(CompoundTag data) {
        if (data == null) return 0;
        return data.getList("SignatureBuildings", Tag.TAG_COMPOUND).size();
    }

    public static int computeHeight(int rowCount) {
        int rowsH = Math.min(Math.max(1, rowCount) * ROW_H, VISIBLE_H);
        return TITLE_BAR_H + rowsH + CLAIM_H;
    }

    @Override
    protected String getTitle() { return "Progression"; }

    @Override
    protected void renderContent(GuiGraphics g, int cx, int cy, int cw, int ch,
                                  int mouseX, int mouseY, float delta) {
        g.fill(cx, cy, cx + cw, cy + ch, COL_BG);
        pendingTooltip = null;

        int scrollH  = ch - CLAIM_H;
        boolean hasScroll = totalH > scrollH;
        int contentW = hasScroll ? cw - SCROLLBAR_W : cw;

        g.enableScissor(cx, cy, cx + contentW, cy + scrollH);

        if (rows.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, "No signature buildings",
                cx + PAD, cy + 6, COL_DIM, false);
        } else {
            int ry = cy - scrollPx;
            for (int i = 0; i < rows.size(); i++) {
                if (ry + ROW_H > cy && ry < cy + scrollH) {
                    renderRow(g, cx, ry, contentW, i, mouseX, mouseY);
                }
                ry += ROW_H;
            }
        }

        g.disableScissor();

        if (hasScroll) {
            int maxScroll = totalH - scrollH;
            int thumbH    = Math.max(12, scrollH * scrollH / totalH);
            int thumbY    = cy + (int)((long) scrollPx * (scrollH - thumbH) / maxScroll);
            int trackX    = cx + cw - SCROLLBAR_W;

            g.fill(trackX, cy, cx + cw, cy + scrollH, 0x33FFFFFF);

            boolean thumbHover = !draggingScrollbar
                && mouseX >= trackX && mouseX < cx + cw
                && mouseY >= thumbY && mouseY < thumbY + thumbH;
            int thumbColor = draggingScrollbar ? 0xFFFFFFFF
                           : thumbHover        ? 0xCCCCCCCC
                           :                    0x99AAAAAA;
            g.fill(trackX, thumbY, cx + cw, thumbY + thumbH, thumbColor);
        }

        renderClaimSection(g, cx, cy + scrollH, cw, mouseX, mouseY);

        if (pendingTooltip != null) {
            g.renderTooltip(Minecraft.getInstance().font,
                Component.literal(pendingTooltip),
                pendingTooltipX, pendingTooltipY);
        }
    }

    private void renderRow(GuiGraphics g, int cx, int cy, int cw, int index, int mx, int my) {
        BuildingRow row = rows.get(index);

        if (index % 2 == 0) g.fill(cx, cy, cx + cw, cy + ROW_H, COL_ROW_ALT);
        boolean hovered = row.accessible()
            && mx >= cx && mx < cx + cw && my >= cy && my < cy + ROW_H;
        if (hovered) g.fill(cx, cy, cx + cw, cy + ROW_H, COL_ROW_HOVER);

        int iconX = cx + PAD;
        int iconY = cy + 3;

        if (row.accessible() && !row.iconItem().isEmpty()) {
            try {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(row.iconItem()));
                if (item != Items.AIR) g.renderFakeItem(new ItemStack(item), iconX, iconY);
            } catch (Exception ignored) {}
        } else if (!row.accessible()) {
            NbtPreviewWidget.drawPadlockIcon(g, iconX, iconY);
        }

        // Level label
        String levelStr;
        if (!row.accessible()) {
            levelStr = "";
        } else if (!row.built() || row.currentLevel() < 0) {
            levelStr = "Not built";
        } else if (row.maxLevel() <= 0) {
            levelStr = "Built";
        } else if (row.maxed()) {
            levelStr = "Level " + row.maxLevel() + "/" + row.maxLevel();
        } else {
            levelStr = "Level " + (row.currentLevel() + 1) + "/" + row.maxLevel();
        }

        int textY    = cy + 3;
        int textX    = iconX + 16 + PAD;
        int barRight = textX + 162;  // right edge of the progress bar
        int levelW   = Minecraft.getInstance().font.width(levelStr);
        int levelX   = barRight - PAD - levelW;

        // Building name (translatable, trimmed to available width)
        String rawName = buildingName(row);
        int maxNameW   = levelStr.isEmpty() ? (barRight - PAD - textX - 2) : (levelX - textX - 2);
        int nameColor  = !row.accessible() ? COL_LOCKED : (!row.built() ? COL_DIM : COL_TEXT);
        String dispName = trim(rawName, maxNameW);

        g.drawString(Minecraft.getInstance().font, dispName, textX, textY, nameColor, false);
        if (!levelStr.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, levelStr, levelX, textY, COL_LEVEL, false);
        }

        if (hovered && !dispName.equals(rawName)) {
            pendingTooltip  = rawName;
            pendingTooltipX = mx;
            pendingTooltipY = my;
        }

        // Progress bar aligned with text column, fixed 162px to match texture
        int barY = cy + ROW_H - BAR_H - 2;
        int barX = textX;
        int barW = 162;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEXTURE_CONSTRUCTION, barX, barY, barW, BAR_H, 1f, 223f, barW, BAR_H, 256, 256);

        int fillW = 0;
        if (row.built() && row.currentLevel() >= 0) {
            if (row.maxed() || row.maxLevel() <= 0) {
                fillW = barW;
            } else {
                fillW = Math.max(0, Math.min(barW,
                    (int)((float)(row.currentLevel() + 1) / row.maxLevel() * barW)));
            }
        }
        if (fillW > 0) {
            g.blit(TEXTURE_CONSTRUCTION, barX, barY, fillW, BAR_H, 1f, 228f, fillW, BAR_H, 256, 256);
        }
    }

    private void renderClaimSection(GuiGraphics g, int cx, int cy, int cw, int mx, int my) {
        boolean anyMaxed = rows.stream().anyMatch(BuildingRow::maxed);

        int slotX = cx + PAD;
        int slotY = cy + (CLAIM_H - 16) / 2;
        g.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17,
            medalClaimed ? COL_SLOT_CLAIMED : COL_SLOT_BORDER);
        g.fill(slotX, slotY, slotX + 16, slotY + 16, COL_SLOT_BG);

        boolean slotHover = mx >= slotX && mx < slotX + 16 && my >= slotY && my < slotY + 16;
        if (anyMaxed && !medalClaimed && slotHover) {
            g.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, COL_SLOT_GLOW);
        }

        if (!medalClaimed && anyMaxed && ItemRegistry.RECOGNITION_MEDAL != null) {
            g.renderFakeItem(new ItemStack(ItemRegistry.RECOGNITION_MEDAL), slotX, slotY);
        }

        // Claim button
        int btnX = slotX + 16 + PAD + PAD;
        int btnW = cw - PAD - (btnX - cx);
        int btnH = 14;
        int btnY = cy + (CLAIM_H - btnH) / 2;
        claimBtnBounds = new int[]{btnX, btnY, btnX + btnW, btnY + btnH};
        boolean btnHover = mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH;

        if (medalClaimed) {
            g.fill(btnX, btnY, btnX + btnW, btnY + btnH, COL_BTN_DN_BG);
            drawBorder(g, btnX, btnY, btnW, btnH, COL_BTN_DN_BD);
            drawCenteredStr(g, "Medal Claimed", btnX, btnY, btnW, btnH, COL_BTN_DN_TX);
        } else if (anyMaxed) {
            g.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnHover ? COL_BTN_CL_HV : COL_BTN_CL_BG);
            drawBorder(g, btnX, btnY, btnW, btnH, COL_BTN_CL_BD);
            drawCenteredStr(g, "Claim Medal", btnX, btnY, btnW, btnH, COL_BTN_CL_TX);
        } else {
            g.fill(btnX, btnY, btnX + btnW, btnY + btnH, COL_BTN_NO_BG);
            drawCenteredStr(g, "No medal ready", btnX, btnY, btnW, btnH, COL_BTN_NO_TX);
        }
    }

    @Override
    protected boolean contentMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;

        int scrollH = height - TITLE_BAR_H - CLAIM_H;
        if (totalH > scrollH) {
            int cx = x;
            int cy = y + TITLE_BAR_H;
            int cw = width;
            int maxScroll = totalH - scrollH;
            int thumbH    = Math.max(12, scrollH * scrollH / totalH);
            int thumbY    = cy + (int)((long) scrollPx * (scrollH - thumbH) / maxScroll);
            int trackX    = cx + cw - SCROLLBAR_W;

            if (mouseX >= trackX && mouseX < cx + cw
                    && mouseY >= thumbY && mouseY < thumbY + thumbH) {
                draggingScrollbar = true;
                dragStartMouseY   = mouseY;
                dragStartScrollPx = scrollPx;
                return true;
            }
        }

        boolean anyMaxed = rows.stream().anyMatch(BuildingRow::maxed);
        if (!medalClaimed && anyMaxed && claimBtnBounds != null) {
            if (mouseX >= claimBtnBounds[0] && mouseX < claimBtnBounds[2]
             && mouseY >= claimBtnBounds[1] && mouseY < claimBtnBounds[3]) {
                onClaimMedal.accept(anchorPos);
                return true;
            }
        }

        return true;
    }

    @Override
    protected boolean contentMouseDragged(double mX, double mY, int button, double dX, double dY) {
        if (draggingScrollbar && button == 0) {
            int scrollH   = height - TITLE_BAR_H - CLAIM_H;
            int maxScroll = Math.max(0, totalH - scrollH);
            int thumbH    = Math.max(12, scrollH * scrollH / totalH);
            double trackRange = scrollH - thumbH;
            if (trackRange > 0) {
                double delta = mY - dragStartMouseY;
                scrollPx = (int) Math.max(0, Math.min(maxScroll,
                    dragStartScrollPx + delta * maxScroll / trackRange));
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean contentMouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean contentMouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        int scrollH   = height - TITLE_BAR_H - CLAIM_H;
        int maxScroll = Math.max(0, totalH - scrollH);
        scrollPx = Math.max(0, Math.min(maxScroll, scrollPx - (int)(delta * 10)));
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x,         y,         x + w,     y + 1,     col);
        g.fill(x,         y + h - 1, x + w,     y + h,     col);
        g.fill(x,         y,         x + 1,     y + h,     col);
        g.fill(x + w - 1, y,         x + w,     y + h,     col);
    }

    private static void drawCenteredStr(GuiGraphics g, String text, int bx, int by, int bw, int bh, int col) {
        int tw = Minecraft.getInstance().font.width(text);
        g.drawString(Minecraft.getInstance().font, text,
            bx + (bw - tw) / 2, by + (bh - FONT_H) / 2, col, false);
    }

    private static String buildingName(BuildingRow row) {
        String fallback = formatBuildingId(row.defId());
        return Component.translatableWithFallback("onceuponatown.building." + row.defId(), fallback)
            .getString();
    }

    private static String formatBuildingId(String id) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String trim(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) return text;
        String cut = font.plainSubstrByWidth(text, maxWidth - font.width("..."));
        return cut + "...";
    }
}
