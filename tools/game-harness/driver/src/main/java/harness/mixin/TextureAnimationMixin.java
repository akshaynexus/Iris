package harness.mixin;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Keep atlas animations at their initial frame for reproducible caustics on both backends. */
@Mixin(TextureManager.class)
public class TextureAnimationMixin {
 @Inject(method="tick",at=@At("HEAD"),cancellable=true)
 private void animationPhase(CallbackInfo ci){
  if(Boolean.getBoolean("harness.enabled") && Boolean.getBoolean("harness.freezeTextures"))ci.cancel();
 }
}
