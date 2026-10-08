package net.irisshaders.iris.metal;

import mcopt.metal.PackCompiler;

import mcopt.metal.PackGlsl;

import mcopt.metal.MetalBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline check of PackGlsl + PackCompiler: every program of a patched_shaders dump to a Metal pipeline state. */
public class MetalSpike {
	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		TreeMap<String, String[]> programs = new TreeMap<>();
		try (var files = Files.list(dir)) {
			for (Path f : files.toList()) {
				String n = f.getFileName().toString();
				if (!n.endsWith(".vsh") && !n.endsWith(".fsh")) continue;
				String prog = n.substring(0, n.length() - 4);
				programs.computeIfAbsent(prog, k -> new String[2])[n.endsWith(".vsh") ? 0 : 1] = Files.readString(f);
			}
		}
		long ctx = MetalBridge.createHeadlessContext();
		int ok = 0, fail = 0;
		List<String> failures = new ArrayList<>();
		Pattern outLoc = Pattern.compile("layout\\(location = (\\d+)\\) out");
		for (var e : programs.entrySet()) {
			String[] s = e.getValue();
			if (s[0] == null || s[1] == null) continue;
			try {
				try (var program = new mcopt.metal.PackProgram(ctx, e.getKey(), s[0], s[1], null, java.util.Map.of())) {
				PackCompiler.Program p = program.compiled();
				int colors = 0;
				Matcher m = outLoc.matcher(s[1]);
				while (m.find()) colors = Math.max(colors, Integer.parseInt(m.group(1)) + 1);
				List<Integer> d = new ArrayList<>();
				d.add(1); d.add(MetalBridge.vertexBufferSlot(0)); d.add(64); d.add(0);
				d.add(p.vertexInputs().size());
				for (var in : p.vertexInputs().entrySet()) {
					String t = p.vertexInputTypes().get(in.getKey());
					int fmt = switch (t) { case "float" -> 28; case "vec2" -> 29; case "vec3" -> 30; case "vec4" -> 31; case "int" -> 32; case "ivec2" -> 33; case "ivec3" -> 34; case "ivec4" -> 35; case "uint" -> 36; case "uvec2" -> 37; case "uvec3" -> 38; case "uvec4" -> 39; default -> 31; };
					d.add(in.getValue()); d.add(MetalBridge.vertexBufferSlot(0)); d.add(0); d.add(fmt);
				}
				d.add(colors);
				for (int c = 0; c < colors; c++) { d.add(115); d.add(15); d.add(0); d.add(1); d.add(0); d.add(0); d.add(1); d.add(0); d.add(0); }
				d.add(0); d.add(3);
				program.pipeline(d.stream().mapToInt(Integer::intValue).toArray());
				ok++;
				System.out.printf("ok   %-40s buffers=%s textures=%d compare=%s uniforms=%dB%n", e.getKey(), p.bufferSlots().keySet(), p.textureSlots().size(), p.compareSamplers(), p.uniformSize());
				}
			} catch (Throwable t) {
				fail++;
				String msg = String.valueOf(t.getMessage());
				failures.add("== " + e.getKey() + ": " + (msg.length() > 3000 ? msg.substring(0, 3000) : msg));
			}
		}
		System.out.println("ok=" + ok + " fail=" + fail);
		Files.writeString(Path.of(args[1]), String.join("\n", failures));
		if (ok != 99 || fail != 0) throw new AssertionError("Expected 99 successful programs");
	}
}
