package org.dawnoftime.onceuponatown.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.dawnoftime.onceuponatown.client.ClientSessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EraProgressDraggableWidget extends DraggableWidget {

    private static final int COLOR_MET         = 0xFF55CC55;
    private static final int COLOR_UNMET       = 0xFF555555;
    private static final int COLOR_END_DOT     = 0xFFAA3333;
    private static final int COLOR_TEXT        = 0xFFCCCCCC;
    private static final int COLOR_DIM         = 0xFF888888;
    private static final int COLOR_CARD_BG     = 0xFF1A1A1A;
    private static final int COLOR_CARD_SEL    = 0xFF223322;
    private static final int COLOR_CARD_BORDER = 0xFF55AA55;

    private static final String EMPTY_CARD_ICON  = "minecraft:barrier";
    private static final String EMPTY_CARD_LABEL = "Max Era";
    private static final String EMPTY_CARD_COND  = "No transition available";

    // GAP: between blocks and card borders; INNER_GAP: between items within a block
    private static final int GAP               = 6;
    private static final int INNER_GAP         = 1;
    private static final int BTN_H             = 12;
    private static final int GAP_BETWEEN_CARDS = 6;
    private static final int FONT_H            = 9;
    private static final int ICON_SIZE         = 16;
    private static final int DOT_W             = 4;
    private static final int DOT_TEXT_GAP      = 3;

    public record CostRow(String itemId, int amount, int have) {}
    public record ReqBuildRow(String defId, int count, int have) {}
    public record SimpleCost(String itemId, int amount) {}
    public record SimpleReq(String defId, int count) {}

    public record UnlockEntry(
        String defId, String iconItem, String category,
        List<SimpleCost> cost, int requiredResidents,
        List<SimpleReq> requiredBuildings, boolean hasProduction
    ) {
        public String buildingName() { return formatBuildingId(defId); }
    }

    public record EraPathOption(
        String id,
        String orientationLabel,
        String iconItem,
        boolean prereqsMet,
        List<CostRow> resourceCost,
        int requiredResidents,
        int activeResidents,
        boolean residentsMet,
        List<ReqBuildRow> requiredBuildings,
        List<UnlockEntry> unlocked
    ) {}

    private List<EraPathOption> pathOptions = new ArrayList<>();
    private String selectedPathId = null;
    private final Consumer<String> onSelectPath;

    private int contentHeight = 0;
    private final List<CardBounds> cardBounds = new ArrayList<>();
    private final List<int[]> selectBtnBounds = new ArrayList<>();

    private record CardBounds(int x, int y, int w, int h, int cardIndex) {}

    public EraProgressDraggableWidget(int x, int y, int freeZoneMaxX, int screenH,
                                       int currentEra, List<EraPathOption> pathOptions,
                                       Consumer<String> onSelectPath) {
        super(x, y, computeWidgetW(pathOptions), TITLE_BAR_H + computeContentH(pathOptions), freeZoneMaxX, screenH);
        this.pathOptions = new ArrayList<>(pathOptions);
        this.onSelectPath = onSelectPath;
        this.contentHeight = computeContentH(pathOptions);
        this.selectedPathId = ClientSessionState.selectedEraPathId;
    }

    public void updateData(int currentEra, List<EraPathOption> pathOptions) {
        this.pathOptions = new ArrayList<>(pathOptions);
        this.contentHeight = computeContentH(pathOptions);
        this.width = computeWidgetW(pathOptions);
        setHeight(TITLE_BAR_H + this.contentHeight);
        if (selectedPathId != null && pathOptions.stream().noneMatch(p -> p.id().equals(selectedPathId))) {
            selectedPathId = null;
        }
    }

    public void applyServerPreselection(String autonomyChosenId) {
        if (autonomyChosenId == null || autonomyChosenId.isEmpty()) return;
        if (ClientSessionState.selectedEraPathId != null) return;
        if (pathOptions.stream().noneMatch(p -> p.id().equals(autonomyChosenId))) return;
        selectedPathId = autonomyChosenId;
        ClientSessionState.selectedEraPathId = selectedPathId;
    }

    public void updateResidents(int activeResidents) {
        List<EraPathOption> updated = new ArrayList<>();
        for (EraPathOption opt : pathOptions) {
            boolean newResidentsMet = opt.requiredResidents() <= 0 || activeResidents >= opt.requiredResidents();
            boolean resourcesMet = opt.resourceCost().stream().allMatch(cr -> cr.have() >= cr.amount());
            boolean buildingsMet = opt.requiredBuildings().stream().allMatch(rb -> rb.have() >= rb.count());
            boolean newPrereqsMet = resourcesMet && newResidentsMet && buildingsMet;
            updated.add(new EraPathOption(
                opt.id(), opt.orientationLabel(), opt.iconItem(),
                newPrereqsMet,
                opt.resourceCost(), opt.requiredResidents(), activeResidents,
                newResidentsMet, opt.requiredBuildings(), opt.unlocked()
            ));
        }
        this.pathOptions = updated;
    }

    // -------------------------------------------------------------------------
    // Layout computation
    // -------------------------------------------------------------------------

    private static int maxCondTextW(EraPathOption opt) {
        var font = Minecraft.getInstance().font;
        int max = 0;
        for (CostRow cr : opt.resourceCost())
            max = Math.max(max, font.width(cr.have() + "/" + cr.amount() + " " + formatItemId(cr.itemId())));
        if (opt.requiredResidents() > 0)
            max = Math.max(max, font.width(opt.activeResidents() + "/" + opt.requiredResidents() + " residents"));
        for (ReqBuildRow rb : opt.requiredBuildings())
            max = Math.max(max, font.width(rb.have() + "/" + rb.count() + " " + formatBuildingId(rb.defId())));
        return max;
    }

    private static int computeCardW(EraPathOption opt) {
        var font = Minecraft.getInstance().font;
        int headerW = Math.max(ICON_SIZE, font.width(opt.orientationLabel()));
        int condW   = DOT_W + DOT_TEXT_GAP + maxCondTextW(opt);
        return Math.max(headerW, condW) + GAP * 2;
    }

    private static int computeEmptyCardH() {
        return GAP + ICON_SIZE + GAP + FONT_H + GAP + FONT_H + GAP;
    }

    public static int computeWidgetW(List<EraPathOption> options) {
        if (options.isEmpty()) {
            var font = Minecraft.getInstance().font;
            int condW   = DOT_W + DOT_TEXT_GAP + font.width(EMPTY_CARD_COND);
            int headerW = Math.max(ICON_SIZE, font.width(EMPTY_CARD_LABEL));
            int cardW   = Math.max(condW, headerW) + GAP * 2;
            int minForTitle = font.width("Era Progress") + 40;
            return Math.max(cardW + GAP * 2, minForTitle);
        }
        int total = GAP * 2;
        for (int i = 0; i < options.size(); i++) {
            total += computeCardW(options.get(i));
            if (i < options.size() - 1) total += GAP_BETWEEN_CARDS;
        }
        var font = Minecraft.getInstance().font;
        int minForTitle = font.width("Era Progress") + 40;
        return Math.max(total, minForTitle);
    }

    private static int computeCardH(EraPathOption opt) {
        int numConds = opt.resourceCost().size()
            + (opt.requiredResidents() > 0 ? 1 : 0)
            + opt.requiredBuildings().size();
        // GAP + icon + GAP + title + GAP (header block)
        int h = GAP + ICON_SIZE + GAP + FONT_H + GAP;
        // condition rows with gaps between them
        if (numConds > 0)
            h += numConds * FONT_H + (numConds - 1) * INNER_GAP;
        // GAP before button + button + GAP at bottom
        h += GAP + BTN_H + GAP;
        return h;
    }

    private static int computeContentH(List<EraPathOption> options) {
        if (options.isEmpty()) return GAP + computeEmptyCardH() + GAP;
        int maxCardH = options.stream().mapToInt(EraProgressDraggableWidget::computeCardH).max().orElse(60);
        return GAP + maxCardH + GAP;
    }

    // -------------------------------------------------------------------------
    // Titlebar
    // -------------------------------------------------------------------------

    @Override
    protected void renderTitleBarExtras(GuiGraphics g, int mouseX, int mouseY) {}

    @Override
    protected boolean onTitleBarClick(double mouseX, double mouseY) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Content rendering
    // -------------------------------------------------------------------------

    @Override
    protected String getTitle() { return "Era Progress"; }

    @Override
    protected void renderContent(GuiGraphics g, int cx, int cy, int cw, int ch,
                                  int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        g.fill(cx, cy, cx + cw, cy + ch, 0xF5111111);

        cardBounds.clear();
        selectBtnBounds.clear();

        if (pathOptions.isEmpty()) {
            int emptyCardH = computeEmptyCardH();
            int condW   = DOT_W + DOT_TEXT_GAP + font.width(EMPTY_CARD_COND);
            int headerW = Math.max(ICON_SIZE, font.width(EMPTY_CARD_LABEL));
            int emptyCardW = Math.max(condW, headerW) + GAP * 2;
            int cardX = cx + (cw - emptyCardW) / 2;
            renderEmptyCard(g, font, cardX, cy + GAP, emptyCardW, emptyCardH, mx, my);
            return;
        }

        int maxCardH = pathOptions.stream().mapToInt(EraProgressDraggableWidget::computeCardH).max().orElse(60);
        boolean multiPath = pathOptions.size() > 1;

        int n = pathOptions.size();
        int totalGaps = GAP_BETWEEN_CARDS * (n - 1);
        int cardW = (cw - GAP * 2 - totalGaps) / n;

        int xCursor = cx + GAP;
        for (int ci = 0; ci < n; ci++) {
            EraPathOption opt = pathOptions.get(ci);
            boolean selected = opt.id().equals(selectedPathId);
            renderCard(g, font, xCursor, cy + GAP, cardW, maxCardH, mx, my, opt, ci, selected, multiPath);
            xCursor += cardW + GAP_BETWEEN_CARDS;
        }
    }

    private void renderCard(GuiGraphics g, net.minecraft.client.gui.Font font,
                             int cx, int cy, int cw, int fixedH,
                             int mx, int my, EraPathOption opt, int cardIdx,
                             boolean selected, boolean selectable) {
        boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + fixedH;
        g.fill(cx, cy, cx + cw, cy + fixedH, selected ? COLOR_CARD_SEL : COLOR_CARD_BG);
        if (selected || hover) {
            g.fill(cx,          cy,              cx + cw,   cy + 1,          COLOR_CARD_BORDER);
            g.fill(cx,          cy + fixedH - 1, cx + cw,   cy + fixedH,     COLOR_CARD_BORDER);
            g.fill(cx,          cy,              cx + 1,    cy + fixedH,     COLOR_CARD_BORDER);
            g.fill(cx + cw - 1, cy,              cx + cw,   cy + fixedH,     COLOR_CARD_BORDER);
        }

        cardBounds.add(new CardBounds(cx, cy, cw, fixedH, cardIdx));

        int rowY = cy + GAP;

        // Block 1: icon centered, title centered below
        int iconX = cx + (cw - ICON_SIZE) / 2;
        renderItemIcon(g, opt.iconItem(), iconX, rowY);
        rowY += ICON_SIZE + GAP;
        int labelX = cx + (cw - font.width(opt.orientationLabel())) / 2;
        g.drawString(font, opt.orientationLabel(), labelX, rowY, 0xFFEEEEEE, false);
        rowY += FONT_H + GAP;

        // Block 2: conditions, centered as a block
        int maxTW = maxCondTextW(opt);
        int blockW = DOT_W + DOT_TEXT_GAP + maxTW;
        int blockX = cx + (cw - blockW) / 2;

        boolean firstCond = true;
        for (CostRow cr : opt.resourceCost()) {
            if (!firstCond) rowY += INNER_GAP;
            renderCondRow(g, font, blockX, rowY, cr.have() + "/" + cr.amount() + " " + formatItemId(cr.itemId()), cr.have() >= cr.amount());
            rowY += FONT_H;
            firstCond = false;
        }
        if (opt.requiredResidents() > 0) {
            if (!firstCond) rowY += INNER_GAP;
            renderCondRow(g, font, blockX, rowY, opt.activeResidents() + "/" + opt.requiredResidents() + " residents", opt.residentsMet());
            rowY += FONT_H;
            firstCond = false;
        }
        for (ReqBuildRow rb : opt.requiredBuildings()) {
            if (!firstCond) rowY += INNER_GAP;
            renderCondRow(g, font, blockX, rowY, rb.have() + "/" + rb.count() + " " + formatBuildingId(rb.defId()), rb.have() >= rb.count());
            rowY += FONT_H;
            firstCond = false;
        }

        // Button anchored to bottom so all cards align
        int btnY = cy + fixedH - GAP - BTN_H;
        int btnW = cw - GAP * 2;
        int btnX = cx + GAP;
        boolean isSelected = opt.id().equals(selectedPathId);
        boolean btnHover = selectable && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + BTN_H;

        int btnColor;
        if (!selectable)       btnColor = 0xFF222222;
        else if (isSelected)   btnColor = btnHover ? 0xFF55AA55 : 0xFF336633;
        else                   btnColor = btnHover ? 0xFF555555 : 0xFF333333;

        g.fill(btnX, btnY, btnX + btnW, btnY + BTN_H, btnColor);
        String btnText      = (!selectable || isSelected) ? "Selected" : "Select";
        int    btnTextColor = !selectable ? 0xFF444444 : (isSelected ? 0xFF88FF88 : 0xFFCCCCCC);
        g.drawString(font, btnText, btnX + (btnW - font.width(btnText)) / 2, btnY + 2, btnTextColor, false);
        selectBtnBounds.add(new int[]{ btnX, btnY, btnW, BTN_H, cardIdx });
    }

    private void renderEmptyCard(GuiGraphics g, net.minecraft.client.gui.Font font,
                                  int cx, int cy, int cw, int fixedH,
                                  int mx, int my) {
        boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + fixedH;
        g.fill(cx, cy, cx + cw, cy + fixedH, COLOR_CARD_BG);
        if (hover) {
            g.fill(cx,          cy,              cx + cw,   cy + 1,          COLOR_CARD_BORDER);
            g.fill(cx,          cy + fixedH - 1, cx + cw,   cy + fixedH,     COLOR_CARD_BORDER);
            g.fill(cx,          cy,              cx + 1,    cy + fixedH,     COLOR_CARD_BORDER);
            g.fill(cx + cw - 1, cy,              cx + cw,   cy + fixedH,     COLOR_CARD_BORDER);
        }

        int rowY = cy + GAP;

        int iconX = cx + (cw - ICON_SIZE) / 2;
        renderItemIcon(g, EMPTY_CARD_ICON, iconX, rowY);
        rowY += ICON_SIZE + GAP;

        int labelX = cx + (cw - font.width(EMPTY_CARD_LABEL)) / 2;
        g.drawString(font, EMPTY_CARD_LABEL, labelX, rowY, 0xFFEEEEEE, false);
        rowY += FONT_H + GAP;

        int condW = DOT_W + DOT_TEXT_GAP + font.width(EMPTY_CARD_COND);
        int condX = cx + (cw - condW) / 2;
        g.fill(condX, rowY + 2, condX + DOT_W, rowY + 6, COLOR_END_DOT);
        g.drawString(font, EMPTY_CARD_COND, condX + DOT_W + DOT_TEXT_GAP, rowY, COLOR_DIM, false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void renderCondRow(GuiGraphics g, net.minecraft.client.gui.Font font,
                                       int x, int y, String text, boolean met) {
        g.fill(x, y + 2, x + DOT_W, y + 6, met ? COLOR_MET : COLOR_UNMET);
        g.drawString(font, text, x + DOT_W + DOT_TEXT_GAP, y, met ? COLOR_TEXT : COLOR_DIM, false);
    }

    private static void renderItemIcon(GuiGraphics g, String iconItemId, int x, int y) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(iconItemId));
            if (item != net.minecraft.world.item.Items.AIR) {
                g.renderFakeItem(new ItemStack(item), x, y);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected boolean contentMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return isMouseOver(mouseX, mouseY);

        if (pathOptions.size() > 1) {
            for (int[] bb : selectBtnBounds) {
                if (mouseX >= bb[0] && mouseX < bb[0] + bb[2]
                        && mouseY >= bb[1] && mouseY < bb[1] + bb[3]) {
                    int cardIdx = bb[4];
                    if (cardIdx < pathOptions.size()) {
                        selectedPathId = pathOptions.get(cardIdx).id();
                        ClientSessionState.selectedEraPathId = selectedPathId;
                        if (onSelectPath != null) onSelectPath.accept(selectedPathId);
                    }
                    return true;
                }
            }
        }

        return isMouseOver(mouseX, mouseY);
    }

    private static String formatItemId(String itemId) {
        String name = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String formatBuildingId(String id) { return formatItemId(id); }
}
