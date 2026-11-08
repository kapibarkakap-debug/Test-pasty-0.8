package dev.pastaclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.*;
import java.util.function.Consumer;

/**
 * ClickGUI: one draggable panel per category. Tap a module to toggle it, tap the arrow on its right
 * edge to show sliders, drag a slider to change it, drag a header to move a panel.
 * The text fields along the bottom hold the Name Changer name, the skin file name and the Spotify token.
 */
public class PastaClientScreen extends Screen {
    static final String[] CATS = {"Render", "Finders", "Player", "Misc"};
    static final Map<String, int[]> POS = new HashMap<>();   // remembered between opens
    static final int PW = 104, HH = 16, RH = 15, SH = 13;
    static final int PINK = 0xFFFF4FC3, PANEL = 0xEE1B1822, ROW = 0xE0141219, SLIDER = 0xFFB02F8A;

    record Rect(int x, int y, int w, int h, Module m, Module.IntSetting s, int kind, String cat) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    // kinds: 0 toggle module, 1 expand settings, 2 slider, 3 panel header, 4 close
    final List<Rect> rects = new ArrayList<>();
    final String[] fieldLabels = {"Fake name (Name Changer)", "Skin file (config/pasta-client/)", "Spotify token"};
    final int[] fieldX = new int[3];
    int fieldY;
    String dragPanel; int dragDx, dragDy;
    Rect activeSlider;

    public PastaClientScreen() { super(Text.literal("Pasta Client")); }

    @Override
    protected void init() {
        // first-open layout: wrap panels onto new rows if the screen is narrow
        int x = 6, y = 24;
        for (String cat : CATS) {
            if (x + PW > width) { x = 6; y += 110; }
            POS.putIfAbsent(cat, new int[]{x, y});
            x += PW + 6;
        }

        int fw = Math.min(150, (width - 24) / 3);
        fieldY = height - 48;
        field(0, fw, PastaClient.fakeName, 32, s -> PastaClient.fakeName = s, false);
        field(1, fw, SkinChanger.file, 64, s -> SkinChanger.file = s, false);
        field(2, fw, SpotifyHud.token, 600, s -> SpotifyHud.token = s, true);
    }

    private void field(int i, int w, String value, int max, Consumer<String> setter, boolean masked) {
        fieldX[i] = 8 + i * (w + 4);
        TextFieldWidget f = new TextFieldWidget(textRenderer, fieldX[i], fieldY, w, 18, Text.literal(fieldLabels[i]));
        f.setMaxLength(max);
        f.setText(value == null ? "" : value);
        f.setChangedListener(setter);
        if (masked) f.setRenderTextProvider((s, off) -> OrderedText.styledForwardsVisitedString("*".repeat(s.length()), Style.EMPTY));
        addDrawableChild(f);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);   // background + text fields
        rects.clear();

        for (int i = 0; i < 3; i++)
            ctx.drawTextWithShadow(textRenderer, fieldLabels[i], fieldX[i], fieldY - 10, 0xFFC8C8D0);

        for (String cat : CATS) {
            int[] p = POS.get(cat);
            int x = p[0], y = p[1];
            ctx.fill(x, y, x + PW, y + HH, PANEL);
            ctx.drawTextWithShadow(textRenderer, cat, x + 5, y + 4, 0xFFFFFFFF);
            rects.add(new Rect(x, y, PW, HH, null, null, 3, cat));

            int cy = y + HH;
            for (Module m : PastaClient.MODULES) {
                if (!m.category.equals(cat)) continue;
                boolean hasSet = !m.settings.isEmpty();
                ctx.fill(x, cy, x + PW, cy + RH, m.on ? PINK : ROW);
                ctx.drawTextWithShadow(textRenderer, m.name, x + 5, cy + 4, m.on ? 0xFFFFFFFF : 0xFFC8C8D0);
                if (hasSet) ctx.drawTextWithShadow(textRenderer, m.expanded ? "v" : ">", x + PW - 10, cy + 4, 0xFFFFFFFF);
                rects.add(new Rect(x, cy, PW - (hasSet ? 16 : 0), RH, m, null, 0, cat));
                if (hasSet) rects.add(new Rect(x + PW - 16, cy, 16, RH, m, null, 1, cat));
                cy += RH;

                if (m.expanded) {
                    for (Module.IntSetting s : m.settings) {
                        ctx.fill(x, cy, x + PW, cy + SH, 0xF00C0B10);
                        int fill = (int) (PW * (s.value - s.min) / (double) (s.max - s.min));
                        ctx.fill(x, cy, x + fill, cy + SH, SLIDER);
                        ctx.drawTextWithShadow(textRenderer, s.name + ": " + s.value, x + 5, cy + 3, 0xFFFFFFFF);
                        rects.add(new Rect(x, cy, PW, SH, m, s, 2, cat));
                        cy += SH;
                    }
                }
            }
        }
        // close button (top-right) so touch users can leave without Esc
        ctx.fill(width - 24, 4, width - 4, 20, PINK);
        ctx.drawCenteredTextWithShadow(textRenderer, "X", width - 14, 8, 0xFFFFFFFF);
        rects.add(new Rect(width - 24, 4, 20, 16, null, null, 4, null));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            Rect r = rects.get(i);
            if (!r.has(mx, my)) continue;
            setFocused(null);   // drop text-field focus when using the panels
            switch (r.kind()) {
                case 0 -> r.m().on = !r.m().on;
                case 1 -> r.m().expanded = !r.m().expanded;
                case 2 -> { activeSlider = r; setSlider(r, mx); }
                case 3 -> { dragPanel = r.cat(); dragDx = (int) mx - r.x(); dragDy = (int) my - r.y(); }
                case 4 -> close();
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (activeSlider != null) { setSlider(activeSlider, mx); return true; }
        if (dragPanel != null) {
            int[] p = POS.get(dragPanel);
            p[0] = MathHelper.clamp((int) mx - dragDx, 0, Math.max(0, width - PW));
            p[1] = MathHelper.clamp((int) my - dragDy, 0, Math.max(0, height - HH));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        activeSlider = null; dragPanel = null;
        return super.mouseReleased(mx, my, button);
    }

    static void setSlider(Rect r, double mx) {
        double t = MathHelper.clamp((mx - r.x()) / (double) r.w(), 0, 1);
        r.s().value = r.s().min + (int) Math.round(t * (r.s().max - r.s().min));
    }

    @Override public boolean shouldPause() { return false; }
}
