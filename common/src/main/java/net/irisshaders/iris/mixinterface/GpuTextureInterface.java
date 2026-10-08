package net.irisshaders.iris.mixinterface;

import com.mojang.renderpearl.api.textures.GpuTexture;

public interface GpuTextureInterface {
	default int iris$getGlId() {
		// mcopt's Metal textures have no GL name: 0, so leftover GL-id lookups (texture tracking, PBR) see "no texture".
		if (net.irisshaders.iris.mixin.IrisMixinPlugin.usingMetal) return 0;
		throw new AssertionError("Not accessible.");
	}

    default void iris$markMipmapNonLinear() {
		if (net.irisshaders.iris.mixin.IrisMixinPlugin.usingMetal) return;
		throw new AssertionError("Not accessible.");
	}
}
