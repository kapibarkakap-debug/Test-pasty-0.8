package dev.pastaclient;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Skin Changer: shows a skin PNG from config/pasta-client/ on YOUR character, locally only. */
public final class SkinChanger {
    public static volatile SkinTextures textures;        // read by PlayerListEntryMixin
    public static String file = "skin.png";
    static final Identifier ID = Identifier.of("pastaclient", "custom_skin");
    static String loadedKey;

    static void update(MinecraftClient mc) {
        if (!PastaClient.SKIN_CHANGER.on) { textures = null; loadedKey = null; return; }
        String key = file + "|" + PastaClient.SLIM.value;
        if (key.equals(loadedKey)) return;
        loadedKey = key;
        textures = null;
        Path p = FabricLoader.getInstance().getConfigDir().resolve("pasta-client").resolve(file);
        try (InputStream in = Files.newInputStream(p)) {
            NativeImage img = NativeImage.read(in);   // needs a 64x64 skin
            mc.getTextureManager().registerTexture(ID, new NativeImageBackedTexture(img));
            textures = new SkinTextures(ID, null, null, null,
                    PastaClient.SLIM.value == 1 ? SkinTextures.Model.SLIM : SkinTextures.Model.WIDE, false);
            PastaClient.say(mc, "§e[Pasta Client] §7skin loaded: " + file);
        } catch (Exception e) {
            PastaClient.say(mc, "§e[Pasta Client] §cskin not found: config/pasta-client/" + file);
        }
    }
}
