package net.irisshaders.iris.gl.blending;

public class BlendModeOverride {
	public static final BlendModeOverride OFF = new BlendModeOverride(null);

	private final BlendMode blendMode;

	public BlendModeOverride(BlendMode blendMode) {
		this.blendMode = blendMode;
	}

	public static void restore() {
		BlendModeStorage.restoreBlend();
	}

	/** The blend mode, or null for "off" (the Metal pipeline bakes it into its pipeline state). */
	public BlendMode getBlendMode() {
		return this.blendMode;
	}

	public void apply() {
		BlendModeStorage.overrideBlend(this.blendMode);
	}
}
