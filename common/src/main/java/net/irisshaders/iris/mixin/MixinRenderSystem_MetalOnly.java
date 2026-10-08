package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.irisshaders.iris.metal.MetalGbuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * mcopt's Metal backend: remembers which RenderPipeline each backend pipeline was built for, so the Metal gbuffer
 * delegate (which only sees backend pipelines) can pick the pack program the way IrisPipelines does on GL.
 */
@Mixin(RenderSystem.class)
public class MixinRenderSystem_MetalOnly {
	@Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
	private static void iris$rememberPipeline(RenderPipeline renderPipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		if (cir.getReturnValue() instanceof FrontendRenderPipeline frontend) {
			MetalGbuffers.remember(frontend.backendRenderPipeline(), renderPipeline);
		}
	}
}
