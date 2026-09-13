package org.dawnoftime.onceuponatown.client.gui.widgets;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.dawnoftime.onceuponatown.client.renderer.PingRenderer;
import org.dawnoftime.onceuponatown.town.TownLogEntry;

import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TownSummaryWidget extends DraggableWidget {

    public static final int WIDGET_W  = 175;
    public static final int VISIBLE_H = 180;

    private static final int HEADER_H    = 12;
    private static final int ITEM_H      = 16;
    private static final int CELL_SIZE   = 18;
    private static final int MAX_COLS    = 9;
    private static final int PADDING     = 3;
    private static final int SCROLLBAR_W  = 6;
    private static final int TAB_BTN_W   = 26;
    private static final int TAB_BTN_GAP = 2;
    private static final int BELL_BTN_W  = 10;

    private static final int COLOR_HEADER_BG   = 0xF52A2A2A;
    private static final int COLOR_SECTION_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ORIENT_TEXT  = 0xFFFFFFFF;
    private static final int COLOR_CONTENT_BG   = 0xF51A1A1A;

    private static final ResourceLocation ICONS_TEXTURE =
        new ResourceLocation("onceuponatown", "textures/gui/icons.png");
    private static final ResourceLocation TEXTURE_UPGRADE =
        new ResourceLocation("onceuponatown", "textures/gui/town_upgrade.png");

    private static final String[] TAB_LABELS = {"Info", "Log"};

    private static final List<String> TRADE_PRICE_ORDER = loadTradePriceOrder();

    private static List<String> loadTradePriceOrder() {
        List<String> order = new ArrayList<>();
        try (var stream = TownSummaryWidget.class.getResourceAsStream("/data/onceuponatown/config/trade_prices.json")) {
            if (stream == null) return order;
            var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
            for (var elem : json.getAsJsonArray("prices"))
                order.add(elem.getAsJsonObject().get("item").getAsString());
        } catch (Exception ignored) {}
        return order;
    }

    // Active tab (session-only): false = Info, true = Log
    static boolean showLogs = false;
    // Chat broadcast toggle - set from server on hub open, toggled locally on click
    public static boolean chatBroadcastEnabled = false;

    private Runnable onBroadcastToggled = null;

    // Activity log entries: newest first (index 0 = most recent)
    private final ArrayDeque<TownLogEntry> logEntries = new ArrayDeque<>();

    private enum RowType { WORKERS_ROW, ITEM, SECTION_HEADER, PROD_GRID, TRANSFORM_GRID, LOG_LINE }
    private record Row(ItemStack icon, Component text, RowType type, List<Component> tooltip, FormattedCharSequence fcs, ResourceLocation tex, int texU, int texV, int texW, int texH) {
        Row(ItemStack icon, Component text, RowType type) { this(icon, text, type, null, null, null, 0, 0, 16, 16); }
        Row(ItemStack icon, Component text, RowType type, List<Component> tooltip) { this(icon, text, type, tooltip, null, null, 0, 0, 16, 16); }
        Row(FormattedCharSequence fcs) { this(null, null, RowType.LOG_LINE, null, fcs, null, 0, 0, 16, 16); }
        Row(ResourceLocation tex, int texU, int texV, Component text, RowType type, List<Component> tooltip) { this(null, text, type, tooltip, null, tex, texU, texV, 16, 16); }
        Row(ResourceLocation tex, int texU, int texV, int texW, int texH, Component text, RowType type, List<Component> tooltip) { this(null, text, type, tooltip, null, tex, texU, texV, texW, texH); }
    }

    // Grid cells for production and transformation sections
    private record GridCell(ItemStack stack, boolean locked, int perMin) {}
    private record WorkerEntry(String jobId, BlockPos pos) {}

    private final List<Row>         rows            = new ArrayList<>();
    private final List<GridCell>    productionCells = new ArrayList<>();
    private final List<GridCell>    transformCells  = new ArrayList<>();
    private final List<WorkerEntry> workerEntries   = new ArrayList<>();

    private int scrollPx = 0;
    private int totalH   = 0;

    // Scrollbar drag state
    private boolean draggingScrollbar = false;
    private double  dragStartMouseY   = 0;
    private int     dragStartScrollPx = 0;

    // Deferred tooltip (set during scissored render, drawn after disableScissor)
    private List<Component> pendingTooltipLines = null;
    private int             pendingTooltipX     = 0;
    private int             pendingTooltipY     = 0;

    private CompoundTag cachedMapData;
    private CompoundTag cachedSummaryData;

    public TownSummaryWidget(CompoundTag mapData, CompoundTag summaryData,
                              int x, int y, int freeZoneMaxX, int screenH) {
        super(x, y, WIDGET_W, TITLE_BAR_H + VISIBLE_H, freeZoneMaxX, screenH);
        this.cachedMapData = mapData;
        this.cachedSummaryData = summaryData;
        buildRows(mapData, summaryData);
    }

    public void updateCitizenData(int totalResidents, int activeResidents, int totalFoodDemand,
                                   int totalHerd, int activeHerd, ListTag workersTag) {
        cachedSummaryData.putInt("TotalResidents", totalResidents);
        cachedSummaryData.putInt("ActiveResidents", activeResidents);
        cachedSummaryData.putInt("TotalFoodDemand", totalFoodDemand);
        cachedSummaryData.putInt("TotalHerd", totalHerd);
        cachedSummaryData.putInt("ActiveHerd", activeHerd);
        cachedSummaryData.put("Workers", workersTag);
        rows.clear();
        buildRows(cachedMapData, cachedSummaryData);
    }

    // -------------------------------------------------------------------------
    // Title bar tab buttons (Info / Log)
    // -------------------------------------------------------------------------

    public void setOnBroadcastToggled(Runnable r) { onBroadcastToggled = r; }

    private int tabBtnX(int index) {
        return x + 1 + index * (TAB_BTN_W + TAB_BTN_GAP);
    }

    private int bellBtnX() {
        return x + 1 + 2 * (TAB_BTN_W + TAB_BTN_GAP);
    }

    @Override
    protected String getTitle() { return ""; }

    @Override
    protected void renderTitleBarExtras(GuiGraphics g, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        boolean[] active = {!showLogs, showLogs};
        for (int i = 0; i < 2; i++) {
            int bx = tabBtnX(i);
            boolean hover = mouseX >= bx && mouseX < bx + TAB_BTN_W
                         && mouseY >= y  && mouseY < y + TITLE_BAR_H;
            boolean isActive = active[i];
            int bg = isActive ? (hover ? 0xFF4A4A4A : 0xFF3A3A3A)
                              : (hover ? 0xFF444444 : 0xFF333333);
            int fg = isActive ? 0xFFEEEEEE : 0xFF888888;
            g.fill(bx, y + 1, bx + TAB_BTN_W, y + TITLE_BAR_H - 1, bg);
            String label = TAB_LABELS[i];
            int textX = bx + (TAB_BTN_W - font.width(label)) / 2;
            g.drawString(font, label, textX, y + 2, fg, false);
        }

        // Bell button (chat broadcast toggle)
        int bbx = bellBtnX();
        boolean bellHover = mouseX >= bbx && mouseX < bbx + BELL_BTN_W
                         && mouseY >= y   && mouseY < y + TITLE_BAR_H;
        int bellColor = chatBroadcastEnabled ? 0xFFFFAA00 : (bellHover ? 0xFFCCCCCC : 0xFFAAAAAA);
        drawBellIcon(g, bbx, bellColor);
    }

    private void drawBellIcon(GuiGraphics g, int bx, int color) {
        int ix = bx + 2;
        int iy = y + 2;
        // top knob (1x1)
        g.fill(ix + 2, iy,     ix + 3, iy + 1, color);
        // bell cap (3 wide)
        g.fill(ix + 1, iy + 1, ix + 4, iy + 2, color);
        // bell sides (left and right, 3 tall)
        g.fill(ix,     iy + 2, ix + 1, iy + 5, color);
        g.fill(ix + 4, iy + 2, ix + 5, iy + 5, color);
        // bell rim (5 wide)
        g.fill(ix,     iy + 5, ix + 5, iy + 6, color);
        // clapper (1x1)
        g.fill(ix + 2, iy + 7, ix + 3, iy + 8, color);
    }

    @Override
    protected boolean onTitleBarClick(double mouseX, double mouseY) {
        for (int i = 0; i < 2; i++) {
            int bx = tabBtnX(i);
            if (mouseX >= bx && mouseX < bx + TAB_BTN_W
                    && mouseY >= y && mouseY < y + TITLE_BAR_H) {
                showLogs = (i == 1);
                rows.clear();
                buildRows(cachedMapData, cachedSummaryData);
                scrollPx = 0;
                return true;
            }
        }
        int bbx = bellBtnX();
        if (mouseX >= bbx && mouseX < bbx + BELL_BTN_W
                && mouseY >= y && mouseY < y + TITLE_BAR_H) {
            chatBroadcastEnabled = !chatBroadcastEnabled;
            if (onBroadcastToggled != null) onBroadcastToggled.run();
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Log entry management
    // -------------------------------------------------------------------------

    public void appendLogEntry(TownLogEntry entry) {
        logEntries.addFirst(entry);
        if (logEntries.size() > 20) logEntries.removeLast();
        if (showLogs) {
            rows.clear();
            buildRows(cachedMapData, cachedSummaryData);
        }
    }

    // Loads the initial log snapshot (oldest to newest order) received from the hub packet.
    public void loadInitialLog(List<TownLogEntry> entries) {
        logEntries.clear();
        // entries is oldest-first from the server; insert newest-first into the deque
        for (int i = entries.size() - 1; i >= 0; i--) {
            logEntries.addLast(entries.get(i));
        }
        if (showLogs) {
            rows.clear();
            buildRows(cachedMapData, cachedSummaryData);
        }
    }


    // -------------------------------------------------------------------------
    // Row building
    // -------------------------------------------------------------------------

    private void buildRows(CompoundTag mapData, CompoundTag summaryData) {
        productionCells.clear();
        transformCells.clear();
        workerEntries.clear();

        if (showLogs) {
            if (logEntries.isEmpty()) {
                rows.add(new Row(null, Component.literal("No activity yet.").withStyle(s -> s.withColor(0xFF888888)), RowType.ITEM));
            } else {
                var font = Minecraft.getInstance().font;
                int wrapWidth = WIDGET_W - SCROLLBAR_W - 8;
                for (TownLogEntry e : logEntries) {
                    for (FormattedCharSequence line : font.split(e.toComponent(), wrapWidth)) {
                        rows.add(new Row(line));
                    }
                }
            }
            totalH = PADDING;
            for (Row r : rows) totalH += rowHeight(r);
            totalH += PADDING;
            return;
        }

        String orientation  = summaryData.getString("Orientation");
        int totalResidents  = summaryData.getInt("TotalResidents");
        int activeResidents = summaryData.getInt("ActiveResidents");
        int totalFoodDemand = summaryData.getInt("TotalFoodDemand");
        int totalHerd       = summaryData.getInt("TotalHerd");
        int activeHerd      = summaryData.getInt("ActiveHerd");

        rows.add(new Row(null,
            Component.literal("Orientation: " + capitalize(orientation))
                .withStyle(s -> s.withColor(COLOR_ORIENT_TEXT)),
            RowType.ITEM));

        if (totalResidents > 0) {
            MutableComponent txt = Component.literal("Residents: " + activeResidents + "/" + totalResidents)
                .withStyle(s -> s.withColor(0xFFCCCCCC));
            List<Component> resTooltip = List.of(
                Component.literal("Buildings increase the total number of").withStyle(s -> s.withColor(0xFFFFFFFF)),
                Component.literal("residents. The active count updates").withStyle(s -> s.withColor(0xFFFFFFFF)),
                Component.literal("each time the village is fed.").withStyle(s -> s.withColor(0xFFFFFFFF))
            );
            rows.add(new Row(ICONS_TEXTURE, 16, 64, txt, RowType.ITEM, resTooltip));
        }

        if (totalHerd > 0) {
            MutableComponent txt = Component.literal("Herds: " + activeHerd + "/" + totalHerd)
                .withStyle(s -> s.withColor(0xFFCCCCCC));
            List<Component> herdTooltip = List.of(
                Component.literal("Buildings increase the total size of the").withStyle(s -> s.withColor(0xFFFFFFFF)),
                Component.literal("herd. The active count updates each time").withStyle(s -> s.withColor(0xFFFFFFFF)),
                Component.literal("the village has food they can consume.").withStyle(s -> s.withColor(0xFFFFFFFF))
            );
            rows.add(new Row(ICONS_TEXTURE, 0, 64, txt, RowType.ITEM, herdTooltip));
        }

        if (totalFoodDemand > 0) {
            rows.add(new Row(ICONS_TEXTURE, 36, 68, 8, 8,
                Component.literal("Food units: " + totalFoodDemand + "/day").withStyle(s -> s.withColor(0xFFDDDDDD)),
                RowType.ITEM, null));
        }

        ListTag workersTag = summaryData.getList("Workers", Tag.TAG_COMPOUND);
        for (Tag rawW : workersTag) {
            CompoundTag w = (CompoundTag) rawW;
            workerEntries.add(new WorkerEntry(
                w.getString("JobId"),
                new BlockPos(w.getInt("PosX"), w.getInt("PosY"), w.getInt("PosZ"))
            ));
        }
        if (!workerEntries.isEmpty()) {
            rows.add(new Row(null,
                Component.translatable("onceuponatown.summary.workers")
                    .withStyle(s -> s.withColor(COLOR_SECTION_TEXT)),
                RowType.SECTION_HEADER));
            rows.add(new Row(null, null, RowType.WORKERS_ROW));
        }

        Map<String, int[]> prodData         = new LinkedHashMap<>();
        Map<String, int[]> lockedProdData   = new LinkedHashMap<>();
        Map<String, int[]> transforms       = new LinkedHashMap<>();
        Map<String, int[]> lockedTransforms = new LinkedHashMap<>();

        ListTag elements = mapData.getList("Elements", Tag.TAG_COMPOUND);
        for (Tag rawEl : elements) {
            CompoundTag el = (CompoundTag) rawEl;
            if (el.getByte("Category") != 2) continue;

            for (Tag rawP : el.getList("Production", Tag.TAG_COMPOUND)) {
                CompoundTag pt = (CompoundTag) rawP;
                String itemId  = pt.getString("Item");
                int amount     = pt.getInt("Amount");
                int ticks      = pt.getInt("EveryTicks");
                boolean locked = pt.getBoolean("Locked");
                if (locked) {
                    lockedProdData.merge(itemId, new int[]{amount, ticks},
                        (a, b) -> new int[]{a[0] + b[0], a[1]});
                } else {
                    prodData.merge(itemId, new int[]{amount, ticks},
                        (a, b) -> new int[]{a[0] + b[0], a[1]});
                }
            }

            for (Tag rawT : el.getList("Transforms", Tag.TAG_COMPOUND)) {
                CompoundTag tt = (CompoundTag) rawT;
                String outId   = tt.getString("OutputItem");
                boolean locked = tt.getBoolean("Locked");
                if (locked) {
                    if (!lockedTransforms.containsKey(outId)) {
                        lockedTransforms.put(outId, new int[]{tt.getInt("OutputAmount"), tt.getInt("EveryTicks")});
                    }
                } else {
                    if (!transforms.containsKey(outId)) {
                        transforms.put(outId, new int[]{tt.getInt("OutputAmount"), tt.getInt("EveryTicks")});
                    }
                }
            }
        }

        if (!prodData.isEmpty() || !lockedProdData.isEmpty()) {
            rows.add(new Row(null, Component.literal("Produces/min").withStyle(s -> s.withColor(COLOR_SECTION_TEXT)), RowType.SECTION_HEADER));
            rows.add(new Row(null, null, RowType.PROD_GRID));
            Set<String> sortedProd = new LinkedHashSet<>();
            for (String id : TRADE_PRICE_ORDER) { if (prodData.containsKey(id)) sortedProd.add(id); }
            sortedProd.addAll(prodData.keySet());
            for (String itemId : sortedProd) {
                int[] val = prodData.get(itemId);
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                int perMin = perMinCount(val[0], val[1]);
                productionCells.add(new GridCell(new ItemStack(item, perMin), false, perMin));
            }
            Set<String> sortedLockedProd = new LinkedHashSet<>();
            for (String id : TRADE_PRICE_ORDER) { if (lockedProdData.containsKey(id) && !prodData.containsKey(id)) sortedLockedProd.add(id); }
            for (String id : lockedProdData.keySet()) { if (!prodData.containsKey(id)) sortedLockedProd.add(id); }
            for (String itemId : sortedLockedProd) {
                int[] val = lockedProdData.get(itemId);
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                int perMin = perMinCount(val[0], val[1]);
                productionCells.add(new GridCell(new ItemStack(item, perMin), true, perMin));
            }
        }

        if (!transforms.isEmpty() || !lockedTransforms.isEmpty()) {
            rows.add(new Row(null, Component.literal("Transforms/min").withStyle(s -> s.withColor(COLOR_SECTION_TEXT)), RowType.SECTION_HEADER));
            rows.add(new Row(null, null, RowType.TRANSFORM_GRID));
            Set<String> sortedTransforms = new LinkedHashSet<>();
            for (String id : TRADE_PRICE_ORDER) { if (transforms.containsKey(id)) sortedTransforms.add(id); }
            sortedTransforms.addAll(transforms.keySet());
            for (String itemId : sortedTransforms) {
                int[] val = transforms.get(itemId);
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                int perMin = perMinCount(val[0], val[1]);
                transformCells.add(new GridCell(new ItemStack(item, perMin), false, perMin));
            }
            Set<String> sortedLockedTransforms = new LinkedHashSet<>();
            for (String id : TRADE_PRICE_ORDER) { if (lockedTransforms.containsKey(id) && !transforms.containsKey(id)) sortedLockedTransforms.add(id); }
            for (String id : lockedTransforms.keySet()) { if (!transforms.containsKey(id)) sortedLockedTransforms.add(id); }
            for (String itemId : sortedLockedTransforms) {
                int[] val = lockedTransforms.get(itemId);
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                int perMin = perMinCount(val[0], val[1]);
                transformCells.add(new GridCell(new ItemStack(item, perMin), true, perMin));
            }
        }

        totalH = PADDING;
        for (Row r : rows) totalH += rowHeight(r);
        totalH += PADDING;
    }

    // Converts amount+ticks to per-minute integer, minimum 1.
    private static int perMinCount(int amount, int ticks) {
        if (ticks <= 0) return amount;
        float perMin = amount * (1200.0f / ticks);
        return Math.max(1, Math.round(perMin));
    }

    private int rowHeight(Row r) {
        return switch (r.type()) {
            case WORKERS_ROW    -> gridHeight(workerEntries.size());
            case SECTION_HEADER -> HEADER_H;
            case PROD_GRID      -> gridHeight(productionCells.size());
            case TRANSFORM_GRID -> gridHeight(transformCells.size());
            case ITEM, LOG_LINE -> ITEM_H;
        };
    }

    private static int gridHeight(int cellCount) {
        if (cellCount == 0) return 0;
        return (int) Math.ceil(cellCount / (double) MAX_COLS) * CELL_SIZE;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    protected void renderContent(GuiGraphics g, int cx, int cy, int cw, int ch,
                                  int mx, int my, float delta) {
        g.fill(cx, cy, cx + cw, cy + ch, COLOR_CONTENT_BG);
        pendingTooltipLines = null;

        boolean hasScroll = totalH > ch;
        int contentW = hasScroll ? cw - SCROLLBAR_W : cw;

        g.enableScissor(cx, cy, cx + contentW, cy + ch);

        var font = Minecraft.getInstance().font;
        int rowY = cy + PADDING - scrollPx;

        for (Row row : rows) {
            int rh = rowHeight(row);
            if (rowY + rh > cy && rowY < cy + ch) {
                switch (row.type()) {
                    case WORKERS_ROW    -> renderWorkersRow(g, cx, rowY, mx, my);
                    case SECTION_HEADER -> {
                        g.fill(cx, rowY, cx + contentW, rowY + rh, COLOR_HEADER_BG);
                        g.drawString(font, row.text(), cx + 4, rowY + 2, 0xFFFFFFFF, false);
                    }
                    case PROD_GRID      -> renderGrid(g, productionCells, cx, rowY, contentW, mx, my);
                    case TRANSFORM_GRID -> renderGrid(g, transformCells,  cx, rowY, contentW, mx, my);
                    case ITEM           -> {
                        if (row.tex() != null) {
                            int dw = row.texW(), dh = row.texH();
                            int offX = (16 - dw) / 2, offY = (16 - dh) / 2;
                            g.blit(row.tex(), cx + 2 + offX, rowY + offY, dw, dh, (float) row.texU(), (float) row.texV(), dw, dh, 128, 128);
                            g.drawString(font, row.text(), cx + 20, rowY + 4, 0xFFFFFFFF, false);
                        } else if (row.icon() != null && !row.icon().isEmpty()) {
                            g.renderFakeItem(row.icon(), cx + 2, rowY);
                            g.drawString(font, row.text(), cx + 20, rowY + 4, 0xFFFFFFFF, false);
                        } else {
                            g.drawString(font, row.text(), cx + 4, rowY + 2, 0xFFFFFFFF, false);
                        }
                        if (row.tooltip() != null
                                && mx >= cx && mx < cx + contentW
                                && my >= rowY && my < rowY + ITEM_H) {
                            pendingTooltipLines = row.tooltip();
                            pendingTooltipX = mx;
                            pendingTooltipY = my;
                        }
                    }
                    case LOG_LINE -> {
                        if (row.fcs() != null) {
                            g.drawString(font, row.fcs(), cx + 4, rowY + 2, 0xFFFFFFFF, false);
                        }
                    }
                }
            }
            rowY += rh;
        }

        g.disableScissor();

        if (pendingTooltipLines != null) {
            g.renderComponentTooltip(Minecraft.getInstance().font,
                pendingTooltipLines, pendingTooltipX, pendingTooltipY);
        }

        if (hasScroll) {
            int maxScroll = totalH - ch;
            int thumbH    = Math.max(12, ch * ch / totalH);
            int thumbY    = cy + (int)((long) scrollPx * (ch - thumbH) / maxScroll);
            int trackX    = cx + cw - SCROLLBAR_W;

            g.fill(trackX, cy, cx + cw, cy + ch, 0x33FFFFFF);

            boolean thumbHover = !draggingScrollbar
                && mx >= trackX && mx < cx + cw
                && my >= thumbY && my < thumbY + thumbH;
            int thumbColor = draggingScrollbar ? 0xFFFFFFFF
                           : thumbHover        ? 0xCCCCCCCC
                           :                    0x99AAAAAA;
            g.fill(trackX, thumbY, cx + cw, thumbY + thumbH, thumbColor);
        }
    }

    private void renderGrid(GuiGraphics g, List<GridCell> cells, int cx, int rowY, int contentW, int mx, int my) {
        for (int i = 0; i < cells.size(); i++) {
            int col   = i % MAX_COLS;
            int row   = i / MAX_COLS;
            int cellX = cx + col * CELL_SIZE;
            int cellY = rowY + row * CELL_SIZE;
            GridCell cell = cells.get(i);
            boolean hover = mx >= cellX + 1 && mx < cellX + 17
                         && my >= cellY + 1 && my < cellY + 17;
            if (hover) {
                g.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0x40FFFFFF);
                pendingTooltipLines = List.of(
                    cell.stack().getHoverName().copy().withStyle(s -> s.withColor(0xFFFFFFFF)),
                    Component.literal(cell.perMin() + " / min").withStyle(ChatFormatting.DARK_GRAY)
                );
                pendingTooltipX = mx;
                pendingTooltipY = my;
            }
            g.renderFakeItem(cell.stack(), cellX + 1, cellY + 1);
            g.renderItemDecorations(Minecraft.getInstance().font, cell.stack(), cellX + 1, cellY + 1);
            if (cell.locked()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                NbtPreviewWidget.drawPadlockIcon(g, cellX + 1, cellY + 1);
                g.pose().popPose();
            }
        }
    }

    private static float[] jobIconUV(String jobId) {
        return switch (jobId) {
            case "swineherd", "cowherd" -> new float[]{48f, 48f};
            case "beekeeper"            -> new float[]{0f,  48f};
            case "lumberjack"           -> new float[]{16f, 48f};
            case "shepherd"             -> new float[]{32f, 48f};
            case "builder"              -> new float[]{48f, 32f};
            case "merchant"             -> new float[]{64f, 16f};
            case "miner"                -> new float[]{64f, 0f};
            default                     -> new float[]{48f, 48f};
        };
    }

    private void renderWorkersRow(GuiGraphics g, int cx, int rowY, int mx, int my) {
        for (int i = 0; i < workerEntries.size(); i++) {
            int col   = i % MAX_COLS;
            int row   = i / MAX_COLS;
            int iconX = cx + col * CELL_SIZE + 1;
            int iconY = rowY + row * CELL_SIZE + 1;
            boolean hover = mx >= iconX && mx < iconX + 16 && my >= iconY && my < iconY + 16;
            if (hover) {
                g.fill(iconX, iconY, iconX + 16, iconY + 16, 0x40FFFFFF);
                String jobId = workerEntries.get(i).jobId();
                pendingTooltipLines = List.of(
                    Component.translatable("onceuponatown.job." + jobId)
                        .withStyle(s -> s.withColor(0xFFFFFFFF)),
                    Component.literal("Shift+click: locate")
                        .withStyle(ChatFormatting.DARK_GRAY)
                );
                pendingTooltipX = mx;
                pendingTooltipY = my;
            }
            float[] uv = jobIconUV(workerEntries.get(i).jobId());
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blit(ICONS_TEXTURE, iconX, iconY, 16, 16, uv[0], uv[1], 16, 16, 128, 128);
            RenderSystem.disableBlend();
        }
    }

    // -------------------------------------------------------------------------
    // Scrollbar interaction
    // -------------------------------------------------------------------------

    @Override
    protected boolean contentMouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && Screen.hasShiftDown()) {
            int testY = y + TITLE_BAR_H + PADDING - scrollPx;
            for (Row row : rows) {
                if (row.type() == RowType.WORKERS_ROW) {
                    for (int i = 0; i < workerEntries.size(); i++) {
                        int col   = i % MAX_COLS;
                        int rowI  = i / MAX_COLS;
                        int iconX = x + col * CELL_SIZE + 1;
                        int iconY = testY + rowI * CELL_SIZE + 1;
                        if (mouseX >= iconX && mouseX < iconX + 16
                                && mouseY >= iconY && mouseY < iconY + 16) {
                            PingRenderer.addPing(workerEntries.get(i).pos());
                            return true;
                        }
                    }
                    break;
                }
                testY += rowHeight(row);
            }
        }
        if (button == 0 && totalH > VISIBLE_H) {
            int cx = x;
            int cy = y + TITLE_BAR_H;
            int cw = width;
            int ch = VISIBLE_H;
            int maxScroll = totalH - ch;
            int thumbH    = Math.max(12, ch * ch / totalH);
            int thumbY    = cy + (int)((long) scrollPx * (ch - thumbH) / maxScroll);
            int trackX    = cx + cw - SCROLLBAR_W;

            if (mouseX >= trackX && mouseX < cx + cw
                    && mouseY >= thumbY && mouseY < thumbY + thumbH) {
                draggingScrollbar = true;
                dragStartMouseY   = mouseY;
                dragStartScrollPx = scrollPx;
                return true;
            }
        }
        return isMouseOver(mouseX, mouseY);
    }

    @Override
    protected boolean contentMouseDragged(double mX, double mY, int button, double dX, double dY) {
        if (draggingScrollbar && button == 0) {
            int ch        = VISIBLE_H;
            int maxScroll = Math.max(0, totalH - ch);
            int thumbH    = Math.max(12, ch * ch / totalH);
            double trackRange = ch - thumbH;
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
        int maxScroll = Math.max(0, totalH - VISIBLE_H);
        scrollPx = Math.max(0, Math.min(maxScroll, scrollPx - (int)(delta * 10)));
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Unknown";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

}
