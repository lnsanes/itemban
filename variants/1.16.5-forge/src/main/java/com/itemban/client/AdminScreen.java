package com.itemban.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.itemban.AdminNetwork;
import com.itemban.ItemNames;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.util.text.StringTextComponent;

import java.util.ArrayList;
import java.util.List;

public class AdminScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final int ROW_H = 18;
    private static final int BTN_W = 36;
    private static final int BAR_W = 6;
    private static int savedScroll;

    private JsonObject state;
    private TextFieldWidget idBox;
    private TextFieldWidget nbtBox;
    private int scroll;
    private boolean draggingBar;
    private int panelX;
    private final List<Row> rows = new ArrayList<Row>();

    public AdminScreen(String json) {
        super(new StringTextComponent("ItemBan 管理"));
        this.state = parse(json);
        this.scroll = savedScroll;
    }

    private static JsonObject parse(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            return obj == null ? new JsonObject() : obj;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    @Override
    protected void init() {
        super.init();
        if (this.minecraft != null) {
            this.minecraft.keyboardHandler.setSendRepeatsToGui(true);
        }
        ItemNames.ensureLoaded();
        int left = 12;
        this.panelX = Math.max(220, this.width - 198);
        int fieldW = Math.max(100, this.panelX - left - 8);
        this.idBox = new TextFieldWidget(this.font, left, 22, fieldW, 18, new StringTextComponent("ID"));
        this.idBox.setMaxLength(256);
        this.idBox.setResponder(v -> refreshHint());
        this.addButton(this.idBox);

        this.nbtBox = new TextFieldWidget(this.font, left, 44, fieldW, 18, new StringTextComponent("NBT"));
        this.nbtBox.setMaxLength(8000);
        this.nbtBox.setResponder(v -> refreshHint());
        this.addButton(this.nbtBox);
        this.refreshHint();

        int x = this.panelX;
        int y = 20;
        int bw = 88;
        this.addButton(new Button(x, y, bw, 20, new StringTextComponent("添加物品"), b -> add("add_item", false)));
        this.addButton(new Button(x + 90, y, bw, 20, new StringTextComponent("添加方块"), b -> add("add_block", true)));
        this.addButton(new Button(x, y + 22, bw, 20, new StringTextComponent("重载配置"), b -> AdminNetwork.sendAction("reload", "")));
        this.addButton(new Button(x + 90, y + 22, bw, 20, new StringTextComponent("关闭"), b -> onClose()));

        int ty = y + 46;
        this.addButton(toggle("公示", "announce", "publicAnnounce", x, ty));
        this.addButton(toggle("踢出", "autoban", "autoBanOnViolation", x + 90, ty));
        this.addButton(toggle("掉落检测", "dropdetect", "detectDroppedItems", x, ty + 22));
        this.addButton(toggle("方块扫描", "blockscan", "detectWorldBlocks", x + 90, ty + 22));
        this.setInitialFocus(this.idBox);
        rebuildRows();
        clampScroll();
    }

    private void refreshHint() {
        if (this.idBox != null) {
            this.idBox.setSuggestion(this.idBox.getValue().isEmpty() ? "物品/方块 ID 或中文名" : null);
        }
        if (this.nbtBox != null) {
            this.nbtBox.setSuggestion(this.nbtBox.getValue().isEmpty() ? "NBT，可空  例如 {Damage:1}" : null);
        }
    }

    private Button toggle(String label, String op, String key, int x, int y) {
        boolean on = bool(key);
        return new Button(x, y, 88, 20, new StringTextComponent(label + (on ? " §a开" : " §c关")), b -> {
            AdminNetwork.sendAction(op, bool(key) ? "0" : "1");
        });
    }

    private void add(String op, boolean preferBlock) {
        String id = this.idBox.getValue().trim();
        String nbt = this.nbtBox.getValue().trim();
        if (id.isEmpty()) {
            error("请输入物品/方块 ID");
            return;
        }
        String resolved = ItemNames.resolveId(id, preferBlock);
        if (resolved == null || resolved.isEmpty()) {
            error("找不到该物品/方块 ID");
            return;
        }
        if (!nbt.isEmpty()) {
            if (!nbt.startsWith("{")) {
                nbt = "{" + nbt + "}";
            }
            try {
                JsonToNBT.parseTag(nbt);
            } catch (Exception e) {
                error("NBT 格式无效");
                return;
            }
        }
        AdminNetwork.sendAction(op, nbt.isEmpty() ? resolved : resolved + nbt);
    }

    private void error(String msg) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(new StringTextComponent("§c" + msg), false);
        }
    }

    private boolean bool(String key) {
        return this.state.has(key) && this.state.get(key).getAsBoolean();
    }

    private int listTop() {
        return 68;
    }

    private int listBottom() {
        return this.height - 8;
    }

    private int listLeft() {
        return 12;
    }

    private int listRight() {
        return Math.max(listLeft() + 90, this.panelX - 8);
    }

    private int viewH() {
        return Math.max(1, listBottom() - listTop());
    }

    private int contentH() {
        return this.rows.size() * ROW_H;
    }

    private int maxScroll() {
        return Math.max(0, contentH() - viewH());
    }

    private void clampScroll() {
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
        savedScroll = this.scroll;
    }

    private void rebuildRows() {
        this.rows.clear();
        this.rows.add(Row.header("物品黑名单"));
        addSection("items", "remove_item");
        this.rows.add(Row.spacer());
        this.rows.add(Row.header("方块黑名单"));
        addSection("blocks", "remove_block");
        this.rows.add(Row.spacer());
        this.rows.add(Row.header("审计排除"));
        addSection("excludes", "remove_exclude");
    }

    private void addSection(String key, String removeOp) {
        if (!this.state.has(key) || !this.state.get(key).isJsonArray()) {
            this.rows.add(Row.empty());
            return;
        }
        JsonArray arr = this.state.getAsJsonArray(key);
        if (arr.size() == 0) {
            this.rows.add(Row.empty());
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            String id = str(row, "id");
            String name = str(row, "name");
            String nbt = str(row, "nbt");
            String label = id;
            if (!name.isEmpty() && !name.equals(id)) {
                label += "  " + name;
            }
            if (!nbt.isEmpty()) {
                label += "  " + nbt;
            }
            this.rows.add(Row.entry(removeOp, id, nbt, label));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.idBox != null) {
            this.idBox.tick();
        }
        if (this.nbtBox != null) {
            this.nbtBox.tick();
        }
        this.refreshHint();
    }

    @Override
    public void render(MatrixStack matrix, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(matrix);
        super.render(matrix, mouseX, mouseY, partialTick);
        this.font.draw(matrix, "ItemBan 游戏内管理  keyId=" + str("keyId"), 12, 8, 0xFFE082);

        int left = listLeft();
        int top = listTop();
        int right = listRight();
        int bottom = listBottom();
        fill(matrix, left - 2, top - 2, right + 2, bottom, 0xC0000000);

        boolean bar = contentH() > viewH();
        int textRight = bar ? right - BAR_W - 4 : right - 4;
        clampScroll();

        for (int i = 0; i < this.rows.size(); i++) {
            int y = top + i * ROW_H - this.scroll;
            if (y + ROW_H <= top || y >= bottom) {
                continue;
            }
            Row row = this.rows.get(i);
            int btnX = textRight - BTN_W;
            int labelW = row.removable() ? btnX - left - 4 : textRight - left;
            String shown = ellipsize(row.label, Math.max(8, labelW));
            this.font.draw(matrix, shown, left, y + 4, row.color);
            if (row.removable()) {
                int by = y + 2;
                boolean hover = mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= by && mouseY < by + 14
                        && mouseY >= top && mouseY < bottom;
                fill(matrix, btnX, by, btnX + BTN_W, by + 14, hover ? 0xFFC0392B : 0xFF8B1E1E);
                this.font.draw(matrix, "移除", btnX + 4, by + 3, 0xFFFFFF);
            }
        }

        if (bar) {
            int barX = right - BAR_W;
            fill(matrix, barX, top, right, bottom, 0x33000000);
            int thumbH = Math.max(12, viewH() * viewH() / contentH());
            int travel = Math.max(1, viewH() - thumbH);
            int thumbY = top + (int) ((long) travel * this.scroll / Math.max(1, maxScroll()));
            fill(matrix, barX, thumbY, right, thumbY + thumbH, 0xFFBBBBBB);
        }
    }

    private String ellipsize(String s, int maxW) {
        if (this.font.width(s) <= maxW) {
            return s;
        }
        while (s.length() > 0 && this.font.width(s + "...") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    private boolean hitRemove(double mouseX, double mouseY, Row row, int y) {
        if (!row.removable()) {
            return false;
        }
        int right = listRight();
        boolean bar = contentH() > viewH();
        int textRight = bar ? right - BAR_W - 4 : right - 4;
        int btnX = textRight - BTN_W;
        int by = y + 2;
        return mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= by && mouseY < by + 14;
    }

    private boolean clickList(double mouseX, double mouseY) {
        int top = listTop();
        int bottom = listBottom();
        int left = listLeft();
        int right = listRight();
        if (mouseX < left || mouseX >= right || mouseY < top || mouseY >= bottom) {
            return false;
        }
        if (contentH() > viewH() && mouseX >= right - BAR_W) {
            this.draggingBar = true;
            scrollToBar(mouseY);
            return true;
        }
        for (int i = 0; i < this.rows.size(); i++) {
            int y = top + i * ROW_H - this.scroll;
            if (y + ROW_H <= top || y >= bottom) {
                continue;
            }
            Row row = this.rows.get(i);
            if (hitRemove(mouseX, mouseY, row, y)) {
                String payload = row.nbt.isEmpty() ? row.id : row.id + row.nbt;
                AdminNetwork.sendAction(row.removeOp, payload);
                return true;
            }
        }
        return false;
    }

    private void scrollToBar(double mouseY) {
        int top = listTop();
        int thumbH = Math.max(12, viewH() * viewH() / Math.max(1, contentH()));
        int travel = Math.max(1, viewH() - thumbH);
        double rel = (mouseY - top - thumbH / 2.0) / travel;
        this.scroll = (int) Math.round(rel * maxScroll());
        clampScroll();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (clickList(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingBar) {
            scrollToBar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.scroll = (int) (this.scroll - delta * ROW_H);
        clampScroll();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.keyboardHandler.setSendRepeatsToGui(false);
        }
        super.onClose();
    }

    private String str(String key) {
        return this.state.has(key) ? this.state.get(key).getAsString() : "";
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    private static final class Row {
        final String removeOp;
        final String id;
        final String nbt;
        final String label;
        final int color;

        private Row(String removeOp, String id, String nbt, String label, int color) {
            this.removeOp = removeOp;
            this.id = id == null ? "" : id;
            this.nbt = nbt == null ? "" : nbt;
            this.label = label == null ? "" : label;
            this.color = color;
        }

        static Row header(String title) {
            return new Row(null, "", "", title, 0xFFD54F);
        }

        static Row spacer() {
            return new Row(null, "", "", "", 0xFFFFFF);
        }

        static Row empty() {
            return new Row(null, "", "", "  （空）", 0xAAAAAA);
        }

        static Row entry(String op, String id, String nbt, String label) {
            return new Row(op, id, nbt, "  " + label, 0xFFFFFF);
        }

        boolean removable() {
            return this.removeOp != null && !this.id.isEmpty();
        }
    }
}
