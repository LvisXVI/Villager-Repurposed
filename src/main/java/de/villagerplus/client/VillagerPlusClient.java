package de.villagerplus.client;

import de.villagerplus.net.InfoRequest;
import de.villagerplus.net.InfoResponse;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;

/**
 * Client-Teil: ein "Info"-Knopf im Handelsmenue, der neben dem Menue den Steckbrief des Villagers einblendet
 * (Name, Beruf, Taetigkeit, Befinden, Haus, Besitz, Ruf ...). Die Daten kommen live vom Server.
 */
public class VillagerPlusClient implements ClientModInitializer {
	private static final int GUI_WIDTH = 276;
	private static final int GUI_HEIGHT = 166;
	private static final int PANEL_WIDTH = 176;
	private static final int REFRESH_TICKS = 20;

	private record Row(String label, String value, int percent) { }

	/** Bereits zerlegte Zeilen: das Zerlegen passiert einmal beim Empfang, nicht in jedem Frame. */
	private static volatile List<Row> lines = List.of();
	private static boolean visible;
	private static int ticks;

	@Override
	public void onInitializeClient() {
		VillageMessageToast.register();
		net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry.registerModelLayer(
			CaravanTraderModel.LAYER, CaravanTraderModel::createBodyLayer);
		net.minecraft.client.renderer.entity.EntityRenderers.register(
			de.villagerplus.entity.ModEntities.CARAVAN_TRADER, CaravanTraderRenderer::new);
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Nur Entwicklung: Datei "vpshot.txt" im Spielordner loest einen Screenshot aus (fuer automatische Sichtpruefung).
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (mc.player != null && mc.player.tickCount % 10 == 0) {
					java.io.File f = new java.io.File(mc.gameDirectory, "vpshot.txt");
					if (f.exists()) {
						String cmd = "";
						try { cmd = java.nio.file.Files.readString(f.toPath()).trim(); } catch (java.io.IOException ignored) { }
						if (f.delete()) {
							if (cmd.equals("mapcheck") && mc.gui.screen() instanceof BoardScreen board) {
								try {
									ClientChecks.map(board);
									java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vp-client-checks.txt"), "PASS: scaled panel, zoom anchor, drag and reset");
								} catch (Exception error) {
									try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vp-client-checks.txt"), "FAILED: " + error); } catch (java.io.IOException ignored) { }
								}
							} else if (cmd.equals("close")) {
								mc.gui.setScreen(null);
							} else {
								net.minecraft.client.Screenshot.grab(mc, false);
							}
						}
					}
				}
			});
		}
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.StockPolicyData.TYPE, (payload, context) -> context.client().execute(() -> BoardScreen.stockRows = payload.rows()));
		ClientPlayNetworking.registerGlobalReceiver(InfoResponse.TYPE, (payload, context) -> lines = parse(payload.lines()));
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.QuotaData.TYPE, (payload, context) -> context.client().execute(() -> BoardScreen.quota = payload.rows()));
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.BoardData.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().gui.screen() instanceof BoardScreen open) {
				if (BoardScreen.autoPending > 0) BoardScreen.autoPending--;
				open.update(payload);
			} else if (BoardScreen.autoPending > 0) {
				BoardScreen.autoPending--; // Antwort auf eine Live-Abfrage, der Spieler hat die Tafel schon geschlossen
			} else {
				context.client().gui.setScreen(new BoardScreen(payload));
			}
		}));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof MerchantScreen)) {
				return;
			}
			visible = false;
			lines = List.of();
			ticks = 0;
			int left = (width - GUI_WIDTH) / 2;
			int top = (height - GUI_HEIGHT) / 2;
			int buttonY = Math.max(2, top - 22);
			Button info = Button.builder(Component.literal("Info"), b -> {
				visible = !visible;
				if (visible) {
					ClientPlayNetworking.send(new InfoRequest());
				}
			}).bounds(left, buttonY, 48, 20).build();
			Screens.getWidgets(screen).add(info);

			ScreenEvents.remove(screen).register(s -> visible = false);
			ScreenEvents.afterTick(screen).register(s -> {
				if (visible && ++ticks % REFRESH_TICKS == 0) {
					ClientPlayNetworking.send(new InfoRequest());
				}
			});
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (visible) {
					drawPanel(graphics, Screens.getFont(s), left, top);
				}
			});
		});
	}

	private static void drawPanel(GuiGraphicsExtractor g, Font font, int guiLeft, int guiTop) {
		List<Row> rows = lines;
		// Spaltenbreiten aus dem Inhalt: nichts ueberlappt, das Feld waechst bei langen Texten (soweit links Platz ist).
		int labelW = font.width("Dorfbewohner-Steckbrief") / 2;
		int valueW = 0;
		final int barW = 44;
		for (Row row : rows) {
			labelW = Math.max(labelW, font.width(row.label()));
			valueW = Math.max(valueW, row.percent() >= 0 ? barW + 4 + font.width(row.value()) : font.width(row.value()));
		}
		int panelW = Math.max(PANEL_WIDTH, 6 + labelW + 6 + valueW + 6);
		int x = Math.max(4, guiLeft - panelW - 6);
		panelW = Math.min(panelW, Math.max(60, guiLeft - 6 - x));
		int y = Math.max(4, guiTop);
		int height = 18 + Math.max(1, rows.size()) * 11 + 4;
		g.fill(x, y, x + panelW, y + height, 0xE0101018);
		g.outline(x, y, panelW, height, 0xFF6B6B8A);
		g.text(font, "Dorfbewohner-Steckbrief", x + 6, y + 5, 0xFFFFD27F);

		int rowY = y + 18;
		if (rows.isEmpty()) {
			g.text(font, "Lade ...", x + 6, rowY, 0xFFAAAAAA);
			return;
		}
		int valueX = x + 6 + labelW + 6;
		for (Row row : rows) {
			g.text(font, row.label(), x + 6, rowY, 0xFFAAAAAA);
			if (row.percent() >= 0) {
				g.fill(valueX, rowY + 1, valueX + barW, rowY + 8, 0xFF303040);
				g.fill(valueX, rowY + 1, valueX + barW * row.percent() / 100, rowY + 8, barColor(row.percent()));
				g.text(font, font.plainSubstrByWidth(row.value(), x + panelW - 4 - (valueX + barW + 4)), valueX + barW + 4, rowY, 0xFFFFFFFF);
			} else {
				g.text(font, font.plainSubstrByWidth(row.value(), x + panelW - 4 - valueX), valueX, rowY, 0xFFFFFFFF);
			}
			rowY += 11;
		}
	}

	private static int barColor(int percent) {
		if (percent >= 60) {
			return 0xFF4FB84F;
		} else if (percent >= 30) {
			return 0xFFD8B33A;
		}
		return 0xFFC84A4A;
	}

	private static List<Row> parse(List<String> raw) {
		java.util.ArrayList<Row> out = new java.util.ArrayList<>(raw.size());
		for (String row : raw) {
			String[] parts = row.split("\\|", 3);
			if (parts.length >= 3) {
				out.add(new Row(parts[0], parts[1], parseInt(parts[2])));
			}
		}
		return List.copyOf(out);
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
