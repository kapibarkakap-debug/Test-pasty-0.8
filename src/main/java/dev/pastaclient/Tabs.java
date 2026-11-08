package dev.pastaclient;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/** Bottom tab bar (Modules | Client | HUD | Friends | Theme | Config) and the screens behind it. */
final class Tabs {
    static final String[] NAMES = {"Modules", "Client", "HUD", "Friends", "Theme", "Config"};

    static Screen make(int i) {
        switch (i) {
            case 0: return new PastaClientScreen();
            case 1: return new ClientScreen();
            case 2: return new HudScreen();
            case 3: return new FriendsScreen();
            case 4: return new ThemeScreen(new PastaClientScreen());
            default: return new ConfigScreen();
        }
    }

    /** Builds the tab buttons; the caller decides how to add them to its screen. */
    static void build(int active, int w, int h, Consumer<ClickableWidget> adder) {
        int n = NAMES.length;
        int tabW = Math.max(40, Math.min(64, (w - 8) / n - 2));
        int total = n * (tabW + 2) - 2;
        int x0 = (w - total) / 2;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(NAMES[i]),
                    btn -> MinecraftClient.getInstance().setScreen(make(idx)))
                    .dimensions(x0 + i * (tabW + 2), h - 22, tabW, 18).build();
            b.active = (i != active);
            adder.accept(b);
        }
    }

    static Text label(Module m) {
        return Text.literal(m.name + ": ").append(Text.literal(m.on ? "ON" : "OFF")
                .styled(s -> s.withColor(m.on ? 0x55FF55 : 0xFF5555)));
    }

    static ButtonWidget toggle(Module m, int x, int y, int w) {
        return ButtonWidget.builder(label(m), b -> { m.on = !m.on; b.setMessage(label(m)); })
                .dimensions(x, y, w, 20).build();
    }

    static TextFieldWidget field(int x, int y, int w, String label, String value, int max,
                                 Consumer<String> setter, boolean masked) {
        TextFieldWidget f = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y, w, 18, Text.literal(label));
        f.setMaxLength(max);
        f.setText(value == null ? "" : value);
        f.setChangedListener(setter);
        if (masked) f.setRenderTextProvider((s, off) -> OrderedText.styledForwardsVisitedString("*".repeat(s.length()), Style.EMPTY));
        return f;
    }
}

/** Slider bound to a Module.IntSetting. */
class SettingSlider extends SliderWidget {
    final Module.IntSetting s;

    SettingSlider(int x, int y, int w, Module.IntSetting s) {
        super(x, y, w, 20, Text.empty(), (s.value - s.min) / (double) (s.max - s.min));
        this.s = s;
        updateMessage();
    }

    @Override protected void updateMessage() { if (s != null) setMessage(Text.literal(s.name + ": " + s.value)); }

    @Override protected void applyValue() { s.value = s.min + (int) Math.round(value * (s.max - s.min)); }
}

/** Client tab: global sliders and the text boxes (fake name, skin file, Spotify token). */
final class ClientScreen extends Screen {
    ClientScreen() { super(Text.literal("Client")); }

    @Override
    protected void init() {
        int fw = Math.min(220, width - 24), x = width / 2 - fw / 2, y = 40;
        addDrawableChild(new SettingSlider(x, y, fw, PastaClient.RADIUS));
        addDrawableChild(new SettingSlider(x, y + 24, fw, PastaClient.THRESHOLD));
        addDrawableChild(Tabs.field(x, y + 72, fw, "Fake name", PastaClient.fakeName, 32, s -> PastaClient.fakeName = s, false));
        addDrawableChild(Tabs.field(x, y + 108, fw, "Skin file", SkinChanger.file, 64, s -> SkinChanger.file = s, false));
        addDrawableChild(Tabs.field(x, y + 144, fw, "Spotify token", SpotifyHud.token, 600, s -> SpotifyHud.token = s, true));
        Tabs.build(1, width, height, w -> addDrawableChild(w));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float d) {
        super.render(ctx, mx, my, d);
        int fw = Math.min(220, width - 24), x = width / 2 - fw / 2, y = 40;
        ctx.drawCenteredTextWithShadow(textRenderer, "Client", width / 2, 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Pasta Client 0.4  |  GUI key: Options > Controls", width / 2, 24, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Fake name (Name Changer)", x, y + 62, 0xFFC8C8D0);
        ctx.drawTextWithShadow(textRenderer, "Skin file (config/pasta-client/)", x, y + 98, 0xFFC8C8D0);
        ctx.drawTextWithShadow(textRenderer, "Spotify token", x, y + 134, 0xFFC8C8D0);
    }

    @Override public void removed() { Config.save(); }
    @Override public boolean shouldPause() { return false; }
}

/** HUD tab: quick on/off switches for everything drawn on screen. */
final class HudScreen extends Screen {
    HudScreen() { super(Text.literal("HUD")); }

    @Override
    protected void init() {
        Module[] mods = {PastaClient.SPOTIFY, PastaClient.PLAYER_LIST, Visuals.WATERMARK,
                Visuals.CROSSHAIR, Visuals.HAND_GLOW, PastaClient.NAMETAGS};
        int bw = Math.min(150, (width - 30) / 2), left = width / 2 - bw - 3, right = width / 2 + 3, y = 40;
        for (int i = 0; i < mods.length; i++)
            addDrawableChild(Tabs.toggle(mods[i], i % 2 == 0 ? left : right, y + (i / 2) * 26, bw));
        Tabs.build(2, width, height, w -> addDrawableChild(w));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float d) {
        super.render(ctx, mx, my, d);
        ctx.drawCenteredTextWithShadow(textRenderer, "HUD", width / 2, 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Move the Spotify HUD in the Theme tab", width / 2, 24, 0xFFAAAAAA);
    }

    @Override public void removed() { Config.save(); }
    @Override public boolean shouldPause() { return false; }
}

/** Friend list. Friends get a green ESP box (drawn by Visuals) instead of the red one. */
final class Friends {
    static final List<String> LIST = new ArrayList<>();
    static boolean is(String name) {
        for (String f : LIST) if (f.equalsIgnoreCase(name)) return true;
        return false;
    }
    static void add(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.isEmpty() && !is(n)) LIST.add(n);
    }
}

final class FriendsScreen extends Screen {
    static String pending = "";

    FriendsScreen() { super(Text.literal("Friends")); }

    @Override
    protected void init() {
        int fw = Math.min(220, width - 24), x = width / 2 - fw / 2;
        addDrawableChild(Tabs.field(x, 34, fw - 54, "Name", pending, 16, s -> pending = s, false));
        addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> { Friends.add(pending); pending = ""; clearAndInit(); })
                .dimensions(x + fw - 50, 33, 50, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Add nearest player"), b -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.player == null) return;
            PlayerEntity best = null;
            double bd = Double.MAX_VALUE;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                double dist = mc.player.squaredDistanceTo(p);
                if (dist < bd) { bd = dist; best = p; }
            }
            if (best != null) { Friends.add(best.getName().getString()); clearAndInit(); }
        }).dimensions(x, 58, fw, 20).build());

        int rows = Math.max(1, (height - 90 - 28) / 22);
        for (int i = 0; i < Math.min(rows, Friends.LIST.size()); i++) {
            final String name = Friends.LIST.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> { Friends.LIST.remove(name); clearAndInit(); })
                    .dimensions(x + fw - 24, 86 + i * 22, 24, 20).build());
        }
        Tabs.build(3, width, height, w -> addDrawableChild(w));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float d) {
        super.render(ctx, mx, my, d);
        int fw = Math.min(220, width - 24), x = width / 2 - fw / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, "Friends (green ESP box)", width / 2, 10, 0xFFFFFFFF);
        int rows = Math.max(1, (height - 90 - 28) / 22);
        for (int i = 0; i < Math.min(rows, Friends.LIST.size()); i++)
            ctx.drawTextWithShadow(textRenderer, Friends.LIST.get(i), x + 4, 92 + i * 22, 0xFF55FF55);
        if (Friends.LIST.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, "No friends yet", width / 2, 96, 0xFFAAAAAA);
    }

    @Override public void removed() { Config.save(); }
    @Override public boolean shouldPause() { return false; }
}

/** Config tab: save / load / reset. Settings also auto-save when a screen closes and on exit. */
final class ConfigScreen extends Screen {
    String status = "";

    ConfigScreen() { super(Text.literal("Config")); }

    @Override
    protected void init() {
        int bw = Math.min(180, width - 30), x = width / 2 - bw / 2, y = 40;
        addDrawableChild(ButtonWidget.builder(Text.literal("Save now"), b -> status = Config.save() ? "Saved" : "Save failed")
                .dimensions(x, y, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Load saved config"), b -> { Config.load(); status = "Loaded"; })
                .dimensions(x, y + 26, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset all to defaults"), b -> { Config.reset(); status = "Reset"; })
                .dimensions(x, y + 52, bw, 20).build());
        Tabs.build(5, width, height, w -> addDrawableChild(w));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float d) {
        super.render(ctx, mx, my, d);
        ctx.drawCenteredTextWithShadow(textRenderer, "Config", width / 2, 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "config/pasta-client/config.properties", width / 2, 24, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, status, width / 2, 126, 0xFF55FF55);
        ctx.drawCenteredTextWithShadow(textRenderer, "The Spotify token is never saved.", width / 2, 140, 0xFFAAAAAA);
    }

    @Override public boolean shouldPause() { return false; }
}

/** Persistent settings: module switches, slider values, names, friends, Spotify HUD position. */
final class Config {
    static final Map<String, Boolean> DEF_ON = new HashMap<>();
    static final Map<String, Integer> DEF_VAL = new HashMap<>();
    static boolean snapped;

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pasta-client").resolve("config.properties");
    }

    static void snapshotDefaults() {
        if (snapped) return;
        snapped = true;
        for (Module m : PastaClient.MODULES) {
            DEF_ON.put(m.name, m.on);
            for (Module.IntSetting s : m.settings) DEF_VAL.put(m.name + "." + s.name, s.value);
        }
    }

    static boolean save() {
        try {
            Properties pr = new Properties();
            for (Module m : PastaClient.MODULES) {
                pr.setProperty("m." + m.name, Boolean.toString(m.on));
                for (Module.IntSetting s : m.settings) pr.setProperty("s." + m.name + "." + s.name, Integer.toString(s.value));
            }
            pr.setProperty("fakeName", PastaClient.fakeName == null ? "" : PastaClient.fakeName);
            pr.setProperty("skinFile", SkinChanger.file == null ? "" : SkinChanger.file);
            pr.setProperty("friends", String.join(",", Friends.LIST));
            pr.setProperty("spotX", Float.toString(Visuals.spotX));
            pr.setProperty("spotY", Float.toString(Visuals.spotY));
            Path p = file();
            Files.createDirectories(p.getParent());
            try (var out = Files.newOutputStream(p)) { pr.store(out, "Pasta Client config"); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            Properties pr = new Properties();
            try (var in = Files.newInputStream(p)) { pr.load(in); }
            for (Module m : PastaClient.MODULES) {
                String on = pr.getProperty("m." + m.name);
                if (on != null) m.on = Boolean.parseBoolean(on);
                for (Module.IntSetting s : m.settings) {
                    String v = pr.getProperty("s." + m.name + "." + s.name);
                    if (v != null) s.value = Math.max(s.min, Math.min(s.max, Integer.parseInt(v.trim())));
                }
            }
            PastaClient.fakeName = pr.getProperty("fakeName", PastaClient.fakeName);
            SkinChanger.file = pr.getProperty("skinFile", SkinChanger.file);
            Friends.LIST.clear();
            for (String f : pr.getProperty("friends", "").split(",")) Friends.add(f);
            Visuals.spotX = Math.max(0f, Math.min(1f, Float.parseFloat(pr.getProperty("spotX", "0.5"))));
            Visuals.spotY = Math.max(0f, Math.min(1f, Float.parseFloat(pr.getProperty("spotY", "0.01"))));
        } catch (Exception ignored) { }
    }

    static void reset() {
        for (Module m : PastaClient.MODULES) {
            Boolean on = DEF_ON.get(m.name);
            if (on != null) m.on = on;
            for (Module.IntSetting s : m.settings) {
                Integer v = DEF_VAL.get(m.name + "." + s.name);
                if (v != null) s.value = v;
            }
        }
        Friends.LIST.clear();
        Visuals.spotX = 0.5f;
        Visuals.spotY = 0.01f;
        save();
    }
}
