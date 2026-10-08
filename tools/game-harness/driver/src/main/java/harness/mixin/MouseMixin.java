package harness.mixin;
import harness.Driver;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public class MouseMixin {
 @Inject(method="turnPlayer", at=@At("HEAD"), cancellable=true)
 private void lockCamera(double elapsed, CallbackInfo ci) { if(Driver.poseLocked()) ci.cancel(); }
}
