package dev.pastaclient.mixin;

import com.mojang.authlib.GameProfile;
import dev.pastaclient.SkinChanger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow public abstract GameProfile getProfile();

    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void pasta$skin(CallbackInfoReturnable<SkinTextures> cir) {
        SkinTextures custom = SkinChanger.textures;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (custom != null && mc.player != null && getProfile().getId().equals(mc.player.getUuid()))
            cir.setReturnValue(custom);
    }
}
