package de.villagerplus.client;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Explicit development-hook check of the scaled map's real pointer event handlers. */
final class ClientChecks {
	private ClientChecks() { }
	private static java.lang.reflect.Field field(String name) throws ReflectiveOperationException {
		var field = BoardScreen.class.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static void check(boolean value, String label) {
		if (!value) throw new IllegalStateException("VPCLIENT FAILED: " + label);
	}
	static void map(BoardScreen screen) throws ReflectiveOperationException {
		int tab = field("tab").getInt(screen), cell = field("mapCell").getInt(screen);
		int panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		try {
			field("tab").setInt(screen, 0); field("mapCell").setInt(screen, 4);
			field("mapPanX").setInt(screen, 0); field("mapPanY").setInt(screen, 0);
			float scale = field("uiScale").getFloat(screen);
			int left = field("left").getInt(screen), top = field("top").getInt(screen);
			check(left * scale >= 0 && top * scale >= 0 && (left + 460) * scale <= screen.width && (top + 280) * scale <= screen.height, "panel fits GUI");
			double x = (left + 14 + 96) * scale, y = (top + 54 + 96) * scale;
			screen.mouseScrolled(x, y, 0, 1);
			check(field("mapCell").getInt(screen) == 6 && field("mapPanX").getInt(screen) == -48 && field("mapPanY").getInt(screen) == -48, "zoom preserves pointer anchor");
			var right = new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
			check(screen.mouseClicked(right, false), "map accepts drag");
			screen.mouseDragged(right, 12 * scale, 8 * scale); screen.mouseReleased(right);
			check(field("mapPanX").getInt(screen) == -36 && field("mapPanY").getInt(screen) == -40, "drag uses scaled pointer delta");
			screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), true);
			check(field("mapCell").getInt(screen) == 4 && field("mapPanX").getInt(screen) == 0 && field("mapPanY").getInt(screen) == 0, "double click resets map");
		} finally {
			field("tab").setInt(screen, tab); field("mapCell").setInt(screen, cell);
			field("mapPanX").setInt(screen, panX); field("mapPanY").setInt(screen, panY);
			field("draggingMap").setBoolean(screen, false);
		}
	}
}
