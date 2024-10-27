package com.denorite.mixin;

import com.denorite.Denorite;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public class ScreenHandlerMixin {

    @Inject(method = "onClosed", at = @At("HEAD"))
    private void onClose(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity) {
            Denorite.onContainerClose((ServerPlayerEntity) player, (ScreenHandler) (Object) this);
        }
    }
}
