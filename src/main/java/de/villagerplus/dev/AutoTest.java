package de.villagerplus.dev;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Development-only: {@code VP_AUTOTEST=roles} runs one headless check mode and exits. */
public final class AutoTest {
	private static final String[] HEADLESS = {
		"roles", "equipment", "mining", "roads", "woodcutting", "caravansites", "minelifecycle", "houses", "civic"
	};

	private AutoTest() { }

	private static String readModeFile() {
		for (String path : new String[] {"vp-autotest.txt", "run/vp-autotest.txt"}) {
			try {
				var file = java.nio.file.Path.of(path);
				if (java.nio.file.Files.isRegularFile(file)) return java.nio.file.Files.readString(file).trim();
			} catch (java.io.IOException ignored) {
				return null;
			}
		}
		return null;
	}

	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		String configured = System.getenv("VP_AUTOTEST");
		if (configured == null || configured.isBlank()) configured = System.getProperty("vp.autotest");
		if (configured == null || configured.isBlank()) configured = readModeFile();
		if (configured == null || configured.isBlank()) return;
		String mode = configured.trim();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> {
			int code = 0;
			try {
				var source = server.createCommandSourceStack();
				String[] modes = mode.equals("headless") ? HEADLESS : new String[] {mode};
				int passed = 0;
				for (String part : modes) passed += Checks090.run(source, part);
				System.out.println("VP_AUTOTEST PASS " + passed);
			} catch (Throwable failure) {
				code = 1;
				failure.printStackTrace();
			}
			int exit = code;
			Thread halt = new Thread(() -> {
				try {
					Thread.sleep(400);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				Runtime.getRuntime().halt(exit);
			}, "vp-autotest-halt");
			halt.setDaemon(true);
			halt.start();
		}));
	}
}
