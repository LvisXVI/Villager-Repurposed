package de.villagerplus.client;

import de.villagerplus.net.AssignJob;
import de.villagerplus.net.NetworkAction;
import de.villagerplus.net.BoardData;
import de.villagerplus.net.BoardRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Das Dorfverwaltungsmenue der Dorftafel. Reiter: Uebersicht (Karte, Legende, Kennzahlen), Einwohner (Tabelle; Klick auf eine Zeile
 * und dann auf einen Beruf teilt diesen Beruf zu), Details, Expeditionen. Pergament-Look, Text ohne Schatten (gut lesbar).
 */
public class BoardScreen extends Screen {
	private static final int W = 460;
	private static final int H = 280;
	private static final String[] TABS = {"Übersicht", "Einwohner", "Details", "Reisen", "Nachbarn", "Journal", "Arbeit", "Lager"};
	private static final int[] TERRAIN = {0xFF5E8A3E, 0xFF2F5E2B, 0xFFC9AC72, 0xFF3A74C8, 0xFFB5C24A, 0xFFB5523A, 0xFFE0CC8A, 0xFF8A8A84, 0xFFE8EEF2, 0xFFB86A44, 0xFFB8B4A8, 0xFF16120C};
	private static final String[] TERRAIN_NAMES = {"Wiese", "Wald", "Weg", "Wasser", "Feld", "Gebäude", "Sand", "Stein", "Schnee", "Tonland", "Platz", "unbekannt"};
	private static final String[] MARK_NAMES = {"", "Glocke", "Dorfkiste", "Dorftafel", "Schule", "Lager", "Gehege", "Mine", "alte Mine", "Bewohner", "Kornspeicher", "Werkzeugkammer", "Küche", "Baustelle", "Laden", "Bahnhof"};
	private static final int[] MARK_COLORS = {0, 0xFFFFFFFF, 0xFFFFE04A, 0xFF4AE0FF, 0xFF5A7BFF, 0xFFB0B0B0, 0xFFFF5A5A, 0xFFC02020, 0xFF703030, 0xFF50FF50, 0xFFE0A030, 0xFF9A9A9A, 0xFFF08040, 0xFFFFFF40, 0xFFFF9AD0, 0xFF60C0C0};
	private static final String[][] JOBS = {{"minecraft:farmer", "Bauer"}, {"minecraft:shepherd", "Schäfer"}, {"minecraft:butcher", "Metzger"},
		{"minecraft:fisherman", "Fischer"}, {"minecraft:armorer", "Rüstungsschmied"}, {"minecraft:toolsmith", "Werkzeugschmied"},
		{"minecraft:weaponsmith", "Waffenschmied"}, {"minecraft:cleric", "Kleriker"}, {"minecraft:mason", "Maurer"}, {"minecraft:fletcher", "Pfeilmacher"},
		{"minecraft:leatherworker", "Gerber"}, {"minecraft:cartographer", "Kartograph"}, {"minecraft:librarian", "Bibliothekar"}, {"minecraft:none", "ohne Beruf"}};
	private static final int INK = 0xFF2B1D0E;
	private static final int SOFT = 0xFF6A5236;

	private BoardData data;
	private int tab;
	private int scroll;
	private int left;
	private int top;
	private float uiScale = 1.0F;
	private int selected = -1;
	private int neighbor = -1;
	private int category;
	public static List<String> stockRows = List.of();
	private String stockItem = "", stockType = "material";
	private int stockCategory;
	private StockSlider stockSlider;
	private Button stockFilter, stockSave, stockReset;
	private static final String[] STOCK_TYPES = {"material", "food", "tools"};
	private static final String[] STOCK_NAMES = {"Baustoffe", "Lebensmittel", "Werkzeuge"};
	private int person = -1;
	private Button filterCategory, filterPerson, cancelBuild, cancelVillager, renameButton, renameOk, demolishButton, zoneButton, moveButton;
	/** Auf der Karte angeklicktes Gebaeude (Zeile aus den {@code @b}-Eintraegen), -1 = keins. */
	private int pickedBuilding = -1;
	private boolean movePending;
	private int mapCell = 4, mapPanX, mapPanY;
	private boolean draggingMap;
	private boolean drawingZone;
	private boolean zoneTool;
	private int zoneKind;
	private int zoneStartX, zoneStartZ, zoneEndX, zoneEndZ;
	private double dragCarryX, dragCarryY;
	private net.minecraft.client.gui.components.EditBox renameBox;
	private final List<Button> networkButtons = new ArrayList<>();
	private Button approveProjectButton, mapCardButton;
	private final Map<String, QuotaSlider> quotaSliders = new LinkedHashMap<>();
	private ResidentJobSlider residentJobSlider;
	private Button applyResidentJob;
	private void networkVisibility() {
		if (stockFilter != null) stockFilter.visible = tab == 7;
		if (stockSlider != null) { stockSlider.visible = tab == 7; stockSlider.active = !stockItem.isEmpty(); }
		if (stockSave != null) { stockSave.visible = tab == 7; stockSave.active = !stockItem.isEmpty(); }
		if (stockReset != null) { stockReset.visible = tab == 7; stockReset.active = !stockItem.isEmpty(); }
		if (cancelBuild != null) cancelBuild.visible = tab == 2;
		if (demolishButton != null) { demolishButton.visible = tab == 0; demolishButton.active = pickedBuilding >= 0 && pickedBuilding < buildingRows().size() && !buildingRows().get(pickedBuilding)[6].equals("1"); }
		if (zoneButton != null) zoneButton.visible = tab == 0;
		if (moveButton != null) { moveButton.visible = tab == 0; moveButton.active = pickedBuilding >= 0 && pickedBuilding < buildingRows().size(); }
		if (cancelVillager != null) { cancelVillager.visible = tab == 1; cancelVillager.active = selected >= 0 && selected < data.residents().size(); }
		if (filterCategory != null) filterCategory.visible = tab == 5;
		if (filterPerson != null) filterPerson.visible = tab == 5;
		for (int i = 0; i < networkButtons.size(); i++) {
			Button b = networkButtons.get(i);
			b.visible = tab == 4;
			b.active = neighbor >= 0 && neighbor < data.neighbors().size();
		}
		if (approveProjectButton != null) { approveProjectButton.visible = tab == 4; approveProjectButton.active = neighbor >= 0 && neighbor < data.neighbors().size(); }
		if (mapCardButton != null) mapCardButton.visible = tab == 4;
		if (residentJobSlider != null) {
			residentJobSlider.visible = tab == 1 && selected >= 0 && selected < data.residents().size();
			residentJobSlider.active = residentJobSlider.visible;
		}
		if (applyResidentJob != null) {
			applyResidentJob.visible = tab == 1 && selected >= 0 && selected < data.residents().size();
			applyResidentJob.active = applyResidentJob.visible;
		}
		for (Map.Entry<String, QuotaSlider> entry : quotaSliders.entrySet()) {
			entry.getValue().visible = tab == 6;
			entry.getValue().active = tab == 6 && !entry.getKey().equals("none");
		}
	}

	public BoardScreen(BoardData data) {
		super(Component.literal("Dorftafel"));
		this.data = data;
	}

	/** Zahl der automatischen Abfragen, deren Antwort noch aussteht (eine Antwort nach dem Schliessen darf die Tafel nicht wieder oeffnen). */
	public static int autoPending;
	/** Arbeitsmarkt-Zeilen {@code schluessel|name|anzahl|soll|standard} (kommen als eigenes Paket zusammen mit den Tafeldaten). */
	public static java.util.List<String> quota = java.util.List.of();
	private int liveTicks;
	private int decay;
	private long updatedAt = System.currentTimeMillis();

	/** Live-Tracker: solange die Tafel offen ist, fragt sie alle 2 Sekunden die aktuellen Daten ab (kein manuelles Aktualisieren noetig). */
	@Override
	public void tick() {
		super.tick();
		ensureQuotaSliders();
		if (++liveTicks >= 40) {
			liveTicks = 0;
			if (++decay >= 5 && autoPending > 0) { autoPending--; decay = 0; } // verlorene Antworten (Spieler zu weit weg) verstopfen den Zaehler nicht
			if (autoPending < 3) {
				autoPending++;
				ClientPlayNetworking.send(new BoardRequest(data.board()));
				if (tab == 7) requestStock();
			}
		}
	}

	/** Frische Daten vom Server (Aktualisieren, nach einer Berufszuteilung): Reiter und Auswahl bleiben. */
	public void update(BoardData fresh) {
		this.data = fresh;
		this.updatedAt = System.currentTimeMillis();
		if (selected >= fresh.residents().size()) selected = -1;
		if (person >= fresh.residents().size()) person = -1;
		if (neighbor >= fresh.neighbors().size()) neighbor = -1;
		networkVisibility();
	}

	@Override
	protected void init() {
		uiScale = Math.min(1.0F, Math.min((width - 12.0F) / (W + 8.0F), (height - 12.0F) / (H + 8.0F)));
		uiScale = Math.max(0.1F, uiScale);
		left = (int)(width / uiScale - W) / 2;
		top = (int)(height / uiScale - H) / 2;
		int bw = (W - 16 - (TABS.length - 1) * 4) / TABS.length;
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> {
				if (idx == 5 && selected >= 0) { person = selected; if (filterPerson != null) filterPerson.setMessage(Component.literal("Bewohner: "+data.residents().get(person).split("\\|")[0])); }
				tab = idx;
				if (tab == 7) requestStock();
				scroll = 0;
				selected = -1;
				networkVisibility();
			}).bounds(left + 8 + i * (bw + 4), top + 24, bw, 18).build());
		}
		filterCategory = addRenderableWidget(Button.builder(Component.literal("Kategorie: Alle"),b->{category=(category+1)%4;scroll=0;b.setMessage(Component.literal("Kategorie: "+new String[]{"Alle","Handel","Befinden","Bauen"}[category]));}).bounds(left+10,top+50,150,18).build());
        filterPerson = addRenderableWidget(Button.builder(Component.literal("Bewohner: Alle"),b->{person++;if(person>=data.residents().size())person=-1;scroll=0;b.setMessage(Component.literal("Bewohner: "+(person<0?"Alle":data.residents().get(person).split("\\|")[0])));}).bounds(left+166,top+50,W-176,18).build());
        networkButtons.clear();
		String[] actions = {"swap", "route", "invite", "greet"};
		String[] captions = {"Waren tauschen", "Route an/aus", "Gäste einladen", "Gruß senden"};
		for (int i = 0; i < actions.length; i++) {
			final String action = actions[i];
			Button b = Button.builder(Component.literal(captions[i]), click -> ClientPlayNetworking.send(new NetworkAction(data.board(), action, neighbor)))
				.bounds(left + 10 + i * 100, top + H - 49, 97, 18).build();
			networkButtons.add(addRenderableWidget(b));
		}
		approveProjectButton = addRenderableWidget(Button.builder(Component.literal("Großprojekt zustimmen"), click -> ClientPlayNetworking.send(new NetworkAction(data.board(), "approve", neighbor)))
			.bounds(left + 84, top + H - 24, 144, 18).build());
		mapCardButton = addRenderableWidget(Button.builder(Component.literal("Dorfkarte mitnehmen"), click -> ClientPlayNetworking.send(new NetworkAction(data.board(), "card", neighbor)))
			.bounds(left + 230, top + H - 24, 126, 18).build());
		renameBox = new net.minecraft.client.gui.components.EditBox(font, left + 6, top + 2, 230, 16, Component.literal("Dorfname"));
		renameBox.setMaxLength(24);
		renameBox.visible = false;
		addRenderableWidget(renameBox);
		renameOk = addRenderableWidget(Button.builder(Component.literal("OK"), b -> {
			ClientPlayNetworking.send(new de.villagerplus.net.RenameVillage(data.board(), renameBox.getValue()));
			renameBox.visible = false;
			renameOk.visible = false;
			renameButton.visible = true;
		}).bounds(left + 240, top + 1, 40, 18).build());
		renameOk.visible = false;
		renameButton = addRenderableWidget(Button.builder(Component.literal("Umbenennen ✎"), b -> {
			renameBox.setValue(data.village());
			renameBox.visible = true;
			renameOk.visible = true;
			renameButton.visible = false;
			setFocused(renameBox);
		}).bounds(left + W - 104, top + 1, 100, 18).build());
		demolishButton = addRenderableWidget(Button.builder(Component.literal("Abriss"), b -> {
			if (pickedBuilding >= 0 && pickedBuilding < buildingRows().size()) {
				String[] r = buildingRows().get(pickedBuilding);
				ClientPlayNetworking.send(new de.villagerplus.net.BuildingAction(data.board(), new net.minecraft.core.BlockPos(Integer.parseInt(r[3]), Integer.parseInt(r[4]), Integer.parseInt(r[5])), "demolish"));
			}
		}).bounds(left + 12, top + H - 48, 82, 18).build());
		zoneButton = addRenderableWidget(Button.builder(Component.literal("Zone zeichnen"), b -> {
			if (!zoneTool) { zoneTool = true; b.setMessage(Component.literal(zoneKind == 4 ? "Zone löschen" : "Zone: " + new String[]{"Feld", "Wohnen", "Arbeit", "Weide"}[zoneKind])); }
			else { zoneKind = (zoneKind + 1) % 5; b.setMessage(Component.literal(zoneKind == 4 ? "Zone löschen" : "Zone: " + new String[]{"Feld", "Wohnen", "Arbeit", "Weide"}[zoneKind])); }
		}).bounds(left + 100, top + H - 48, 94, 18).build());
		moveButton = addRenderableWidget(Button.builder(Component.literal("Versetzen"), b -> {
			if (pickedBuilding >= 0 && pickedBuilding < buildingRows().size()) { movePending = true; b.setMessage(Component.literal("Ziel wählen…")); }
		}).bounds(left + 200, top + H - 48, 100, 18).build());
		cancelBuild = addRenderableWidget(Button.builder(Component.literal("Bau abbrechen"), b -> ClientPlayNetworking.send(new de.villagerplus.net.CancelTask(data.board(), "build", new UUID(0L, 0L))))
			.bounds(left + 84, top + H - 24, 110, 18).build());
		cancelVillager = addRenderableWidget(Button.builder(Component.literal("Aufgabe abbrechen"), b -> {
			if (selected >= 0 && selected < data.residents().size()) {
				String[] p = data.residents().get(selected).split("\\|");
				if (p.length > 5) ClientPlayNetworking.send(new de.villagerplus.net.CancelTask(data.board(), "villager", UUID.fromString(p[5])));
			}
		}).bounds(left + 84, top + H - 24, 120, 18).build());
		residentJobSlider = addRenderableWidget(new ResidentJobSlider(left + 12, top + H - 30 - 52 + 25, 288));
		applyResidentJob = addRenderableWidget(Button.builder(Component.literal("Zuteilen"), b -> assignSelectedResidentJob())
			.bounds(left + 308, top + H - 30 - 52 + 25, 138, 20).build());
		stockFilter = addRenderableWidget(Button.builder(Component.literal("Lager: " + STOCK_NAMES[stockCategory]), b -> {
			stockCategory = (stockCategory + 1) % STOCK_TYPES.length; stockItem = ""; scroll = 0;
			b.setMessage(Component.literal("Lager: " + STOCK_NAMES[stockCategory])); networkVisibility(); requestStock();
		}).bounds(left + 12, top + 50, 150, 18).build());
		stockReset = addRenderableWidget(Button.builder(Component.literal("Unbegrenzt"), b -> {
			if (!stockItem.isEmpty()) ClientPlayNetworking.send(new de.villagerplus.net.SetStockPolicy(data.board(), stockItem, stockType, 0, true));
		}).bounds(left + W - 132, top + 50, 120, 18).build());
		stockSlider = addRenderableWidget(new StockSlider(left + 12, top + 225, W - 162));
		stockSave = addRenderableWidget(Button.builder(Component.literal("Soll übernehmen"), b -> {
			if (!stockItem.isEmpty()) ClientPlayNetworking.send(new de.villagerplus.net.SetStockPolicy(data.board(), stockItem, stockType, stockSlider.target(), false));
		}).bounds(left + W - 142, top + 225, 130, 20).build());
		requestStock();
		ensureQuotaSliders();

		networkVisibility();
		addRenderableWidget(Button.builder(Component.literal("● Live"), b -> ClientPlayNetworking.send(new BoardRequest(data.board())))
			.bounds(left + W - 98, top + H - 24, 90, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose()).bounds(left + 8, top + H - 24, 70, 18).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		x /= uiScale; y /= uiScale;
		if (insideMap(x, y)) {
			int next = Math.max(4, Math.min(12, mapCell + (int)Math.signum(dy) * 2));
			double cx = x - left - 14, cy = y - top - 54;
			mapPanX = (int)Math.round(cx - (cx - mapPanX) * next / mapCell);
			mapPanY = (int)Math.round(cy - (cy - mapPanY) * next / mapCell);
			mapCell = next; clampMap(); return true;
		}
		scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
		return true;
	}



	private MouseButtonEvent scaledMouse(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo());
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(x / uiScale, y / uiScale);
	}

	private boolean insideMap(double x, double y) {
		return tab == 0 && x >= left + 14 && y >= top + 54
			&& x < left + 14 + data.size() * 4 && y < top + 54 + data.size() * 4;
	}

	private void clampMap() {
		int min = data.size() * (4 - mapCell);
		mapPanX = Math.max(min, Math.min(0, mapPanX));
		mapPanY = Math.max(min, Math.min(0, mapPanY));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		event = scaledMouse(event); dx /= uiScale; dy /= uiScale;
		if (draggingMap && tab == 0) {
			dragCarryX += dx; dragCarryY += dy;
			int moveX = (int)dragCarryX, moveY = (int)dragCarryY;
			dragCarryX -= moveX; dragCarryY -= moveY;
			mapPanX += moveX; mapPanY += moveY; clampMap(); return true;
		}
		if (drawingZone && tab == 0) { int[] p = mapBlock(event.x(), event.y()); if (p != null) { zoneEndX = p[0]; zoneEndZ = p[1]; } return true; }
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		event = scaledMouse(event);
		if (draggingMap) { draggingMap = false; return true; }
		if (drawingZone) {
			drawingZone = false;
			ClientPlayNetworking.send(new de.villagerplus.net.SetZone(data.board(), zoneStartX, zoneStartZ, zoneEndX, zoneEndZ,
				new String[]{"farm", "housing", "work", "pasture"}[zoneKind], false));
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.pose().pushMatrix(); g.pose().scale(uiScale, uiScale);
		// Holzrahmen, Pergament, Kopfbanner.
		g.fill(left - 4, top - 4, left + W + 4, top + H + 4, 0xFF3A2414);
		g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF6B4423);
		g.fill(left, top, left + W, top + H, 0xFFE8D9B0);
		g.fill(left, top, left + W, top + 20, 0xFF8C2A22);
		g.fill(left, top + 20, left + W, top + 21, 0xFF5C1712);
		g.fill(left + 6, top + 46, left + W - 6, top + H - 30, 0xFFF3E8C8);
		g.outline(left + 6, top + 46, W - 12, H - 76, 0xFFB79C6B);
		g.pose().popMatrix();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.pose().pushMatrix(); g.pose().scale(uiScale, uiScale);
		mouseX = (int)(mouseX / uiScale); mouseY = (int)(mouseY / uiScale);
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, (renameBox != null && renameBox.visible ? "" : "Dorftafel  ·  " + data.village() + "  (" + data.board().getX() + " " + data.board().getY() + " " + data.board().getZ() + ")"), left + 8, top + 6, 0xFFFFE9A8, false);
		int bw = (W - 16 - (TABS.length - 1) * 4) / TABS.length;
		g.fill(left + 8 + tab * (bw + 4), top + 42, left + 8 + tab * (bw + 4) + bw, top + 44, 0xFFB5381C);
		switch (tab) {
			case 0 -> overview(g, mouseX, mouseY);
			case 1 -> residents(g, mouseX, mouseY);
			case 2 -> lines(g, data.details());
			case 3 -> lines(g, data.expeditions());
			case 4 -> neighbors(g);
			case 5 -> journal(g);
			case 6 -> work(g);
			case 7 -> stock(g);
		}
		g.pose().popMatrix();
	}

	/** Abreissbare Gebaeude aus den Zusatzzeilen {@code @b|typ|name|x|y|z|laeuft}. */
	private java.util.List<String[]> buildingRows() {
		java.util.List<String[]> out = new java.util.ArrayList<>();
		for (String row : quota) {
			if (row.startsWith("@b|")) {
				String[] p = row.split("\\|");
				if (p.length >= 7) out.add(p);
			}
		}
		return out;
	}

	private int[] mapOrigin() {
		for (String row : quota) {
			if (row.startsWith("@o|")) {
				String[] p = row.split("\\|");
				return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
			}
		}
		return null;
	}

	/** Arbeitsmarkt: je Beruf Ist-Zahl, Sollanteil (Prozent) und -/+ zum Einstellen. Das Dorf besetzt sich selbst nach diesen Quoten um. */
	private void work(GuiGraphicsExtractor g) {
		label(g, "Berufsquoten – das Dorf verteilt Arbeit automatisch nach diesen Anteilen (% der Einwohner)", left + 12, top + 52, INK);
		int rowIndex = 0;
		for (String entry : quota) {
			if (entry.startsWith("@")) continue;
			String[] p = entry.split("\\|");
			if (p.length < 5) continue;
			int lineY = top + 62 + rowIndex * 13;
			label(g, p[1], left + 14, lineY + 2, INK);
			label(g, "Ist: " + p[2], left + 190, lineY + 2, SOFT);
			boolean changed = !p[3].equals(p[4]);
			label(g, "Soll" + (changed ? " *" : ""), left + 250, lineY + 2, changed ? 0xFFB5381C : INK);
			rowIndex++;
		}
		label(g, "* = vom Standard abgewichen · 0 % = wird nicht nachbesetzt", left + 14, top + H - 30, SOFT);
	}


	private void requestStock() { ClientPlayNetworking.send(new de.villagerplus.net.RequestStockPolicy(data.board())); }

	private List<String[]> filteredStock() {
		return stockRows.stream().map(row -> row.split("\\|", -1))
			.filter(row -> row.length == 4 && row[1].equals(STOCK_TYPES[stockCategory])).toList();
	}

	private void stock(GuiGraphicsExtractor g) {
		label(g, "Gegenstand", left + 14, top + 72, INK);
		label(g, "Gemeinsam / Soll", left + W - 145, top + 72, INK);
		List<String[]> rows = filteredStock();
		int visible = 12; scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			String[] row = rows.get(i); int y = top + 85 + (i - scroll) * 11;
			if (row[0].equals(stockItem)) g.fill(left + 10, y - 1, left + W - 10, y + 10, 0xFFE2C98A);
			var id = net.minecraft.resources.Identifier.tryParse(row[0]);
			var item = id == null ? java.util.Optional.<net.minecraft.world.item.Item>empty() : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
			String name = item.map(it -> new net.minecraft.world.item.ItemStack(it).getHoverName().getString()).orElse(row[0]);
			label(g, font.plainSubstrByWidth(name, W - 175), left + 14, y, INK);
			label(g, row[2] + " / " + (row[3].equals("-1") ? "unbegrenzt" : row[3]), left + W - 145, y, SOFT);
		}
		if (rows.isEmpty()) label(g, "Noch keine Waren in dieser Lagergruppe.", left + 14, top + 90, SOFT);
	}

	private final class StockSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		StockSlider(int x, int y, int width) { super(x, y, width, 20, Component.literal("Soll: 0"), 0); }
		int target() { return (int)Math.round(value * 4096); }
		void target(int count) { value = Math.max(0, Math.min(4096, count)) / 4096.0; updateMessage(); }
		@Override protected void updateMessage() { setMessage(Component.literal("Soll: " + target())); }
		@Override protected void applyValue() { }
	}

	/** Accessible quota control; keyboard arrows adjust one percentage point, while clicks and drags retain mouse input. */
	private final class QuotaSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		private final String job;
		QuotaSlider(String job, int x, int y, int width, int target) {
			super(x, y, width, 13, Component.literal(target + "%"), Math.max(0, Math.min(40, target)) / 40.0);
			this.job = job;
			updateMessage();
		}
		int target() { return Math.max(0, Math.min(40, (int)Math.round(value * 40))); }
		void setTargetSilently(int target) {
			value = Math.max(0, Math.min(40, target)) / 40.0;
			updateMessage();
		}
		@Override protected void updateMessage() { setMessage(Component.literal(target() + "%")); }
		@Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
			return Component.translatable("gui.narrate.slider", Component.literal(quotaLabel(job) + ": " + target() + "%"));
		}
		@Override protected void applyValue() { setQuotaTarget(job, target()); }
		@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			if (event.isSelection()) return super.keyPressed(event);
			if (canChangeValue && (event.isLeft() || event.isRight())) {
				int next = target() + (event.isLeft() ? -1 : 1);
				setValue(Math.max(0, Math.min(40, next)) / 40.0);
				return true;
			}
			return super.keyPressed(event);
		}
	}

	/** Discrete profession picker used for both adult job assignment and children's apprenticeship plans. */
	private final class ResidentJobSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		ResidentJobSlider(int x, int y, int width) {
			super(x, y, width, 20, Component.literal("Beruf"), (JOBS.length - 1) / (double)(JOBS.length - 1));
			updateMessage();
		}
		int selectedJob() { return Math.max(0, Math.min(JOBS.length - 1, (int)Math.round(value * (JOBS.length - 1)))); }
		void select(int job) {
			value = Math.max(0, Math.min(JOBS.length - 1, job)) / (double)(JOBS.length - 1);
			updateMessage();
		}
		@Override protected void updateMessage() { setMessage(Component.literal(JOBS[selectedJob()][1])); }
		@Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
			String role = selectedResidentIsChild() ? "Lehrlingsziel" : "Beruf";
			return Component.translatable("gui.narrate.slider", Component.literal(role + ": " + JOBS[selectedJob()][1]));
		}
		@Override protected void applyValue() { updateMessage(); }
		@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			if (event.isSelection()) return super.keyPressed(event);
			if (canChangeValue && (event.isLeft() || event.isRight())) {
				int next = selectedJob() + (event.isLeft() ? -1 : 1);
				select(Math.max(0, Math.min(JOBS.length - 1, next)));
				return true;
			}
			return super.keyPressed(event);
		}
	}

	private void label(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, false);
	}

	private static int shade(int argb, int level) {
		float f = 1.0f + 0.07f * (level - 3);
		int r = Math.min(255, (int) (((argb >> 16) & 255) * f)), gg = Math.min(255, (int) (((argb >> 8) & 255) * f)), bb = Math.min(255, (int) ((argb & 255) * f));
		return 0xFF000000 | (r << 16) | (gg << 8) | bb;
	}

	private static int noise(int col, int row, int salt) {
		int h = col * 73856093 ^ row * 19349663 ^ salt * 83492791;
		return (h >>> 5) & 7;
	}

	/** Schmuckpixel je Feldart (Gras-Sprenkel, Baumkronen, Wellen, Ackerfurchen, Dachziegel ...), damit die Karte nicht wie Rasterpapier wirkt. */
	private void drawCell(GuiGraphicsExtractor g, int kind, int shadeLevel, int col, int row, int x, int y, int cell) {
		int base = shade(TERRAIN[Math.max(0, Math.min(TERRAIN.length - 1, kind))], shadeLevel);
		g.fill(x, y, x + cell, y + cell, base);
		int n = noise(col, row, kind);
		switch (kind) {
			case 0, 6, 8, 9 -> {
				if (n < 3) g.fill(x + (n & 1) * 2, y + (n >> 1) % 2 * 2, x + (n & 1) * 2 + 1, y + (n >> 1) % 2 * 2 + 1, shade(TERRAIN[kind], shadeLevel + 1));
			}
			case 1 -> {
				g.fill(x, y + 2, x + cell, y + cell, shade(TERRAIN[1], shadeLevel - 1));
				g.fill(x + 1, y, x + 3, y + 2, shade(TERRAIN[1], shadeLevel + 1));
			}
			case 2 -> g.fill(x, y + 1, x + cell, y + 3, shade(0xFFD8C08A, shadeLevel));
			case 3 -> {
				if (n < 4) g.fill(x + 1, y + 1 + (n & 1), x + 3, y + 2 + (n & 1), 0xFF7FB0F0);
			}
			case 4 -> {
				g.fill(x, y + 1, x + cell, y + 2, shade(0xFF8E9A38, shadeLevel));
				g.fill(x, y + 3, x + cell, y + 4, shade(0xFF8E9A38, shadeLevel));
			}
			case 5 -> {
				g.fill(x, y + cell - 1, x + cell, y + cell, 0xFF5A281C);
				g.fill(x, y, x + cell, y + 1, 0xFFD87A58);
			}
			case 10 -> {
				if (((col + row) & 1) == 0) g.fill(x, y, x + cell, y + cell, shade(0xFFC9C5B8, shadeLevel));
			}
			default -> { }
		}
	}

	private void icon(GuiGraphicsExtractor g, int m, int cx, int cy) {
		int c = MARK_COLORS[m];
		g.fill(cx - 4, cy - 4, cx + 4, cy + 4, 0xFF151008);
		g.fill(cx - 3, cy - 3, cx + 3, cy + 3, c);
		g.fill(cx - 3, cy - 3, cx + 3, cy - 2, 0x66FFFFFF);
		g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF151008);
		if (m == 1) { // Glocke: goldene Raute
			g.fill(cx, cy - 5, cx + 1, cy + 5, 0xFFFFE04A);
			g.fill(cx - 5, cy, cx + 5, cy + 1, 0xFFFFE04A);
		}
	}

	private void overview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int n = data.size();
		int cell = mapCell;
		int view = n * 4;
		clampMap();
		int mx = left + 14;
		int my = top + 54;
		// Rahmen aus Holz mit Pergamentkante
		g.fill(mx - 5, my - 5, mx + view + 5, my + view + 5, 0xFF2E1D0E);
		g.fill(mx - 4, my - 4, mx + view + 4, my + view + 4, 0xFF7A5230);
		g.fill(mx - 2, my - 2, mx + view + 2, my + view + 2, 0xFFD9C48F);
		g.enableScissor(mx, my, mx + view, my + view);
		byte[] cells = data.cells();
		byte[] marks = data.marks();
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int i = row * n + col;
				int raw = cells[i] & 0xFF;
				drawCell(g, raw & 15, (raw >> 4) & 7, col, row, mx + mapPanX + col * cell, my + mapPanY + row * cell, cell);
			}
		}
		int hoverMark = 0;
		int hoverCol = Math.floorDiv(mouseX - mx - mapPanX, cell), hoverRow = Math.floorDiv(mouseY - my - mapPanY, cell);
		boolean inside = insideMap(mouseX, mouseY) && hoverCol >= 0 && hoverRow >= 0 && hoverCol < n && hoverRow < n;
		// Bewohner als kleine Punkte, danach Gebaeudemarker obenauf
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int m = marks[row * n + col];
				if (m == 9) {
					int x = mx + mapPanX + col * cell, y = my + mapPanY + row * cell;
					g.fill(x, y, x + 3, y + 3, 0xFF0C2A0C);
					g.fill(x, y, x + 2, y + 2, MARK_COLORS[9]);
				}
			}
		}
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int m = marks[row * n + col];
				if (m > 0 && m != 9 && m < MARK_COLORS.length) {
					icon(g, m, mx + mapPanX + col * cell + cell / 2, my + mapPanY + row * cell + cell / 2);
				}
			}
		}
		int[] zoneOrigin = mapOrigin();
		if (zoneOrigin != null) {
			g.enableScissor(mx, my, mx + view, my + view);
			for (String row : quota) if (row.startsWith("@z|")) {
				String[] z = row.split("\\|"); if (z.length < 7) continue;
				try {
					int x1 = mx + mapPanX + Math.floorDiv((Integer.parseInt(z[3]) - zoneOrigin[0]) * cell, data.cellSize());
					int y1 = my + mapPanY + Math.floorDiv((Integer.parseInt(z[4]) - zoneOrigin[1]) * cell, data.cellSize());
					int x2 = mx + mapPanX + Math.floorDiv((Integer.parseInt(z[5]) - zoneOrigin[0] + 1) * cell, data.cellSize());
					int y2 = my + mapPanY + Math.floorDiv((Integer.parseInt(z[6]) - zoneOrigin[1] + 1) * cell, data.cellSize());
					int color = switch (z[2]) { case "farm" -> 0xAAE0D050; case "housing" -> 0xAA5090E0; case "pasture" -> 0xAA70B860; default -> 0xAAD07050; };
					g.fill(x1, y1, x2, y1 + 1, color); g.fill(x1, y2 - 1, x2, y2, color);
					g.fill(x1, y1, x1 + 1, y2, color); g.fill(x2 - 1, y1, x2, y2, color);
				} catch (NumberFormatException ignored) { }
			}
			if (drawingZone) {
				int x1 = mx + mapPanX + Math.floorDiv((zoneStartX - zoneOrigin[0]) * cell, data.cellSize());
				int y1 = my + mapPanY + Math.floorDiv((zoneStartZ - zoneOrigin[1]) * cell, data.cellSize());
				int x2 = mx + mapPanX + Math.floorDiv((zoneEndX - zoneOrigin[0] + 1) * cell, data.cellSize());
				int y2 = my + mapPanY + Math.floorDiv((zoneEndZ - zoneOrigin[1] + 1) * cell, data.cellSize());
				g.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), 0xFFFFFFFF);
			}
			g.disableScissor();
		}
		if (inside) {
			// Treffer auch auf Nachbarfeldern, damit die 8-Pixel-Icons leicht zu treffen sind
			for (int dr = -1; dr <= 1 && hoverMark == 0; dr++) {
				for (int dc = -1; dc <= 1 && hoverMark == 0; dc++) {
					int cc = hoverCol + dc, rr = hoverRow + dr;
					if (cc >= 0 && rr >= 0 && cc < n && rr < n) {
						int m = marks[rr * n + cc];
						if (m > 0 && m != 9 && m < MARK_NAMES.length) hoverMark = m;
					}
				}
			}
		}
		g.disableScissor();
		// Kompass und Massstab
		label(g, "N", mx + view - 9, my + 3, 0xFFFFFFFF);
		g.fill(mx + view - 7, my + 12, mx + view - 6, my + 22, 0xFFFFFFFF);
		g.fill(mx + view - 9, my + 15, mx + view - 4, my + 16, 0xFFFFFFFF);
		g.fill(mx + 4, my + view - 7, mx + 4 + 12, my + view - 6, 0xFFFFFFFF);
		label(g, (12 * data.cellSize() / cell) + " m", mx + 18, my + view - 11, 0xFFFFFFFF);
		label(g, font.plainSubstrByWidth("Mausrad: Zoom · Rechts ziehen: Verschieben", view), mx, my + view + 1, SOFT);

		int rx = mx + view + 16;
		int ry = top + 52;
		java.util.List<String[]> buildings = buildingRows();
		String mapTitle = pickedBuilding >= 0 && pickedBuilding < buildings.size() ? "Auswahl: " + buildings.get(pickedBuilding)[2] : "Legende · Gebäude anklicken";
		label(g, font.plainSubstrByWidth(mapTitle, W - (rx - left) - 12), rx, ry, INK);
		ry += 12;
		for (int m = 1; m < MARK_NAMES.length; m++) {
			int x = rx + ((m - 1) % 2) * 112;
			int y = ry + ((m - 1) / 2) * 11;
			g.fill(x, y, x + 9, y + 9, 0xFF151008);
			g.fill(x + 1, y + 1, x + 8, y + 8, MARK_COLORS[m]);
			label(g, MARK_NAMES[m], x + 12, y + 1, INK);
		}
		int[] org = mapOrigin();
		if (org != null) {
			java.util.List<String[]> rows = buildingRows();
			g.enableScissor(mx, my, mx + view, my + view);
			for (int i = 0; i < rows.size(); i++) {
				int cx = mx + mapPanX + (Integer.parseInt(rows.get(i)[3]) - org[0]) * cell / data.cellSize(), cy = my + mapPanY + (Integer.parseInt(rows.get(i)[5]) - org[1]) * cell / data.cellSize();
				if (cx < mx || cy < my || cx >= mx + view || cy >= my + view) continue;
				boolean picked = i == pickedBuilding;
				g.fill(cx - 1, cy - 1, cx + 1, cy + 1, picked ? 0xFFFFFFFF : 0xFFE8D08A);
				if (picked) {
					g.outline(cx - 5, cy - 5, 10, 10, 0xFFFFFFFF);
				}
			}
			g.disableScissor();
		}
		if (hoverMark > 0) {
			String tip = MARK_NAMES[hoverMark];
			int tw = font.width(tip) + 8;
			int tx = Math.min(mouseX + 10, left + W - tw - 4), ty = mouseY - 4;
			g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 13, 0xFF151008);
			g.fill(tx, ty, tx + tw, ty + 12, 0xFFF3E8C8);
			label(g, tip, tx + 4, ty + 2, INK);
		}
		ry += 11 * ((MARK_NAMES.length - 2) / 2 + 1) + 6;
		label(g, "Kennzahlen", rx, ry, INK);
		ry += 12;
		int avail = left + W - 12 - rx;
		for (String bar : data.bars()) {
			String[] p = bar.split("\\|", 4);
			if (p.length < 4) {
				continue;
			}
			label(g, p[0], rx, ry + 1, INK);
			int barX = rx + 62;
			int barW = Math.max(40, avail - 62);
			int pct = Math.max(0, Math.min(100, parse(p[2])));
			g.fill(barX, ry, barX + barW, ry + 10, 0xFF5A4A30);
			g.fill(barX + 1, ry + 1, barX + barW - 1, ry + 9, 0xFFD8C89E);
			g.fill(barX + 1, ry + 1, barX + 1 + (barW - 2) * pct / 100, ry + 9, 0xFF000000 | parseHex(p[3]));
			label(g, font.plainSubstrByWidth(p[1], barW - 6), barX + 4, ry + 1, INK);
			ry += 12;
		}
	}

	private int rowTop() {
		return top + 64;
	}

	private int visibleRows() {
		return (H - 30 - 64 - (selected >= 0 ? 52 : 0)) / 11;
	}

	private void residents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = top + 50;
		label(g, "Name", left + 12, y, INK);
		label(g, "Beruf", left + 110, y, INK);
		label(g, "Tätigkeit", left + 220, y, INK);
		label(g, "Stimmung", left + 348, y, INK);
		g.fill(left + 10, y + 10, left + W - 10, y + 11, 0xFFB79C6B);
		List<String> rows = data.residents();
		int visible = visibleRows();
		scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		y = rowTop();
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			String[] p = rows.get(i).split("\\|", 9);
			if (p.length < 5) {
				continue;
			}
			boolean hover = mouseX >= left + 8 && mouseX < left + W - 8 && mouseY >= y - 1 && mouseY < y + 10;
			if (i == selected) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFE2C98A);
			} else if (hover) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFEFE0B8);
			} else if (i % 2 == 0) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFEDE1C0);
			}
			int mood = parse(p[3]);
			label(g, font.plainSubstrByWidth(p[0] + ("ja".equals(p[4]) ? "" : "  (ohne Haus)"), 96), left + 12, y, INK);
			label(g, font.plainSubstrByWidth(p[1], 106), left + 110, y, INK);
			label(g, font.plainSubstrByWidth(p[2], 124), left + 220, y, SOFT);
			g.fill(left + 348, y + 1, left + 348 + 52, y + 8, 0xFF5A4A30);
			g.fill(left + 349, y + 2, left + 349 + 50 * Math.max(0, Math.min(100, mood)) / 100, y + 7,
				mood >= 60 ? 0xFF4FB84F : mood >= 35 ? 0xFFD8B33A : 0xFFC84A4A);
			y += 11;
		}
		if (selected >= 0 && selected < rows.size()) {
			String[] p = rows.get(selected).split("\\|", 9);
			int py = top + H - 30 - 52;
			g.fill(left + 8, py, left + W - 8, py + 50, 0xFFE2C98A);
			boolean child = p.length >= 8 && p[7].equalsIgnoreCase("true");
			label(g, (child ? "Lehrlingsziel für " : "Beruf zuteilen für ") + p[0] + ":", left + 12, py + 3, INK);
		}
		label(g, rows.size() + " Einwohner · Auswahl mit Pfeiltasten oder Maus ändern, dann zuteilen", left + 20, top + H - 19, SOFT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		event = scaledMouse(event);
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (tab == 0) {
			if (movePending && event.button() == 0 && insideMap(event.x(), event.y())) {
				int[] p = mapBlock(event.x(), event.y());
				if (p != null && pickedBuilding >= 0 && pickedBuilding < buildingRows().size()) {
					String[] row = buildingRows().get(pickedBuilding);
					ClientPlayNetworking.send(new de.villagerplus.net.BuildingAction(data.board(),
						new net.minecraft.core.BlockPos(Integer.parseInt(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5])),
						new net.minecraft.core.BlockPos(p[0], Integer.parseInt(row[4]), p[1]), "move"));
				}
				movePending = false; if (moveButton != null) moveButton.setMessage(Component.literal("Versetzen")); return true;
			}
			if (zoneTool && event.button() == 0 && insideMap(event.x(), event.y())) {
				int[] p = mapBlock(event.x(), event.y()); if (p == null) return true;
				if (zoneKind == 4) {
					for (String row : quota) if (row.startsWith("@z|")) {
						String[] z = row.split("\\|"); if (z.length >= 7 && p[0] >= parse(z[3]) && p[0] <= parse(z[5]) && p[1] >= parse(z[4]) && p[1] <= parse(z[6])) {
							ClientPlayNetworking.send(new de.villagerplus.net.SetZone(data.board(), 0, 0, 0, 0, z[1], true)); break;
						}
					}
					zoneTool = false; zoneKind = 0; if (zoneButton != null) zoneButton.setMessage(Component.literal("Zone zeichnen")); return true;
				}
				zoneStartX = zoneEndX = p[0]; zoneStartZ = zoneEndZ = p[1]; drawingZone = true; zoneTool = false;
				if (zoneButton != null) zoneButton.setMessage(Component.literal("Zone zeichnen")); return true;
			}
			if (insideMap(event.x(), event.y()) && (event.button() == 1 || event.button() == 2)) {
				draggingMap = true; dragCarryX = dragCarryY = 0; return true;
			}
			if (event.button() != 0) return false;
			if (insideMap(event.x(), event.y()) && doubleClick) {
				mapCell = 4; mapPanX = mapPanY = 0; return true;
			}
			int[] org = mapOrigin();
			int mx0 = left + 14, my0 = top + 54;
			if (org != null && event.x() >= mx0 && event.y() >= my0 && event.x() < mx0 + data.size() * 4 && event.y() < my0 + data.size() * 4) {
				int wx = org[0] + Math.floorDiv(((int)event.x() - mx0 - mapPanX) * data.cellSize(), mapCell), wz = org[1] + Math.floorDiv(((int)event.y() - my0 - mapPanY) * data.cellSize(), mapCell);
				int best = -1, bestD = 49;
				java.util.List<String[]> rows = buildingRows();
				for (int i = 0; i < rows.size(); i++) {
					int dx = Integer.parseInt(rows.get(i)[3]) - wx, dz = Integer.parseInt(rows.get(i)[5]) - wz;
					int dd = dx * dx + dz * dz;
					if (dd < bestD) { bestD = dd; best = i; }
				}
				pickedBuilding = best;
				networkVisibility();
				return true;
			}
			return false;
		}

		if (tab == 7 && event.button() == 0 && event.x() >= left + 10 && event.x() < left + W - 10
			&& event.y() >= top + 85 && event.y() < top + 217) {
			int index = ((int)event.y() - top - 85) / 11 + scroll;
			List<String[]> rows = filteredStock();
			if (index >= 0 && index < rows.size()) {
				String[] row = rows.get(index); stockItem = row[0]; stockType = row[1];
				stockSlider.target(row[3].equals("-1") ? Integer.parseInt(row[2]) : Integer.parseInt(row[3])); networkVisibility();
			}
			return true;
		}

		if (tab == 6) {
			return false;
		}
		if (tab == 4) {
			int row = ((int) event.y() - (top + 111)) / 27 + scroll;
			if (event.x() >= left + 10 && event.x() < left + W - 10 && event.y() >= top + 111 && event.y() < top + 219 && row < data.neighbors().size()) {
				neighbor = row; networkVisibility(); return true;
			}
			return false;
		}
		if (tab != 1) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		List<String> rows = data.residents();
		int y = rowTop();
		for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
			if (mx >= left + 8 && mx < left + W - 8 && my >= y - 1 && my < y + 10) {
				selected = selected == i ? -1 : i;
				if (selected >= 0 && residentJobSlider != null) residentJobSlider.select(jobIndexForResident(rows.get(selected)));
				networkVisibility();
				return true;
			}
			y += 11;
		}
		return false;
	}

	private void ensureQuotaSliders() {
		if (left == 0 && top == 0) return;
		int row = 0;
		for (String entry : quota) {
			if (entry.startsWith("@")) continue;
			String[] fields = entry.split("\\|");
			if (fields.length < 5) continue;
			String job = fields[0];
			QuotaSlider slider = quotaSliders.get(job);
			int y = top + 60 + row * 13;
			if (slider == null) {
				slider = addRenderableWidget(new QuotaSlider(job, left + 330, y, 88, parse(fields[3])));
				quotaSliders.put(job, slider);
			} else {
				slider.setTargetSilently(parse(fields[3]));
			}
			slider.setY(y);
			slider.visible = tab == 6;
			slider.active = tab == 6 && !job.equals("none");
			row++;
		}
	}

	private void setQuotaTarget(String job, int target) {
		String row = quota.stream().filter(q -> !q.startsWith("@") && q.startsWith(job + "|")).findFirst().orElse(null);
		if (row == null) return;
		String[] p = row.split("\\|"); if (p.length < 4) return;
		target = Math.max(0, Math.min(40, target));
		int old; try { old = Integer.parseInt(p[3]); } catch (NumberFormatException ignored) { return; }
		if (target != old) {
			ClientPlayNetworking.send(new de.villagerplus.net.SetQuota(data.board(), job, target - old));
			List<String> updated = new ArrayList<>(quota);
			for (int i = 0; i < updated.size(); i++) if (updated.get(i).startsWith(job + "|")) {
				String[] fields = updated.get(i).split("\\|"); fields[3] = Integer.toString(target); updated.set(i, String.join("|", fields)); break;
			}
			quota = List.copyOf(updated);
		}
	}

	private String quotaLabel(String job) {
		String row = quota.stream().filter(q -> !q.startsWith("@") && q.startsWith(job + "|")).findFirst().orElse(null);
		if (row == null) return job;
		String[] fields = row.split("\\|");
		return fields.length >= 2 ? fields[1] : job;
	}

	private int jobIndexForResident(String row) {
		String[] fields = row.split("\\|", 9);
		String current = fields.length >= 7 ? fields[6] : "minecraft:none";
		if (fields.length >= 9 && fields[7].equalsIgnoreCase("true") && !fields[8].isBlank()) {
			current = fields[8];
		}
		for (int i = 0; i < JOBS.length; i++) if (JOBS[i][0].equals(current)) return i;
		// Backward compatibility while an older server sends only the human-readable apprenticeship label.
		if (fields.length >= 2 && fields[1].startsWith("Lehrling → ")) {
			String target = fields[1].substring("Lehrling → ".length());
			int xp = target.indexOf(" · ");
			if (xp >= 0) target = target.substring(0, xp);
			for (int i = 0; i < JOBS.length; i++) if (JOBS[i][1].equals(target)) return i;
		}
		return JOBS.length - 1;
	}

	private void assignSelectedResidentJob() {
		if (selected < 0 || selected >= data.residents().size() || residentJobSlider == null) return;
		String[] fields = data.residents().get(selected).split("\\|", 7);
		if (fields.length < 6) return;
		try {
			ClientPlayNetworking.send(new AssignJob(data.board(), UUID.fromString(fields[5]), JOBS[residentJobSlider.selectedJob()][0]));
		} catch (IllegalArgumentException ignored) {
			// Invalid resident UUID: leave the selection unchanged.
		}
	}

	private boolean selectedResidentIsChild() {
		if (selected < 0 || selected >= data.residents().size()) return false;
		String[] fields = data.residents().get(selected).split("\\|", 9);
		return fields.length >= 8 && fields[7].equalsIgnoreCase("true");
	}

	private int[] mapBlock(double x, double y) {
		int[] org = mapOrigin(); if (org == null) return null;
		int mx = left + 14, my = top + 54;
		int col = Math.floorDiv((int)x - mx - mapPanX, mapCell), row = Math.floorDiv((int)y - my - mapPanY, mapCell);
		if (col < 0 || row < 0 || col >= data.size() || row >= data.size()) return null;
		return new int[]{org[0] + Math.floorDiv(col * data.cellSize(), mapCell), org[1] + Math.floorDiv(row * data.cellSize(), mapCell)};
	}

	private void neighbors(GuiGraphicsExtractor g) {
		label(g, "Partnerorte · Beziehung · letzter Besuch", left + 12, top + 51, INK);
		int y = top + 63;
		for (String row : data.partners()) {
			String[] p = row.split("\\|", -1);
			if (p.length >= 4) label(g, font.plainSubstrByWidth(p[0] + " · " + p[1] + " · " + p[2] + " · " + p[3], W - 24), left + 12, y, SOFT);
			y += 11;
		}
		label(g, "Nachbardörfer (anklicken)", left + 12, top + 99, INK);
		scroll = Math.min(scroll, Math.max(0, data.neighbors().size() - 4));
		y = top + 111;
		for (int i = scroll; i < Math.min(data.neighbors().size(), scroll + 4); i++) {
			String[] p = data.neighbors().get(i).split("\\|", -1);
			if (p.length < 7) continue;
			if (i == neighbor) g.fill(left + 9, y - 1, left + W - 9, y + 17, 0xFFE2C98A);
			label(g, font.plainSubstrByWidth(p[0] + " · " + p[1] + " · Beziehung " + p[4], W - 24), left + 12, y, INK);
			label(g, "Einwohner: " + p[3] + " · Route: " + p[5] + " · " + (p[6].equals("1") ? "erreichbar" : "nicht geladen"), left + 12, y + 9, SOFT);
			if (p.length >= 9) {
				label(g, font.plainSubstrByWidth("Bilanz: " + p[7] + " · Großprojekt: " + p[8], W - 24), left + 12, y + 18, SOFT);
				y += 27;
			} else y += 18;
		}
		if (data.neighbors().isEmpty()) label(g, "Dorfkarte mitnehmen und an einer anderen Tafel benutzen.", left + 12, y, SOFT);
	}

 private void journal(GuiGraphicsExtractor g) {
  String wanted = new String[]{"Alle","Handel","Befinden","Bauen"}[category];
  String resident=person>=0 && person<data.residents().size()?data.residents().get(person).split("\\|")[0]:"";
  List<String> rows=new ArrayList<>();
  for(String row:data.journal()) {
   String[] p=row.split("\\|",4);if(p.length<4 || (category!=0&&!p[0].equals(wanted)) || (!resident.isEmpty()&&!p[2].equals(resident)&&!p[3].contains(resident)))continue;
   rows.add(p[1]+" · "+p[0]+" · "+p[2]);String rest=p[3];
   while(!rest.isEmpty()) {String line=font.plainSubstrByWidth(rest,W-30);if(line.isEmpty())break;rows.add("  "+line);rest=rest.substring(line.length());} rows.add("");
  }
  int visible=(H-110)/11;scroll=Math.min(scroll,Math.max(0,rows.size()-visible));int y=top+74;
  for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++){label(g,rows.get(i),left+12,y,rows.get(i).startsWith("  ")?SOFT:INK);y+=11;}
  if(rows.isEmpty())label(g,"Noch keine passenden Ereignisse aufgezeichnet.",left+12,y,SOFT);
 }

	private void lines(GuiGraphicsExtractor g, List<String> src) {
		List<String> wrapped = new ArrayList<>();
		int maxW = W - 28;
		for (String line : src) {
			String rest = line.replace("===", "").trim();
			boolean first = true;
			if (rest.isEmpty()) {
				wrapped.add("");
			}
			while (!rest.isEmpty()) {
				String part = font.plainSubstrByWidth(rest, maxW - (first ? 0 : 12));
				if (part.length() < rest.length()) {
					int sp = part.lastIndexOf(' ');
					if (sp > 8) {
						part = part.substring(0, sp);
					}
				}
				wrapped.add((first ? "" : "   ") + part);
				rest = rest.substring(part.length()).stripLeading();
				first = false;
			}
		}
		int visible = (H - 30 - 52) / 11;
		scroll = Math.min(scroll, Math.max(0, wrapped.size() - visible));
		int y = top + 52;
		for (int i = scroll; i < Math.min(wrapped.size(), scroll + visible); i++) {
			String s = wrapped.get(i);
			int colon = s.indexOf(':');
			if (!s.startsWith(" ") && colon > 0 && colon < 28) {
				// "Titel: Wert" - Titel kraeftig, Wert normal
				label(g, s.substring(0, colon + 1), left + 12, y, 0xFF8C2A22);
				label(g, s.substring(colon + 1), left + 12 + font.width(s.substring(0, colon + 1)), y, INK);
			} else {
				label(g, s, left + 12, y, INK);
			}
			y += 11;
		}
		if (wrapped.size() > visible) {
			label(g, "Mausrad: blättern", left + 84, top + H - 19, SOFT);
		}
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int parseHex(String s) {
		try {
			return Integer.parseInt(s.trim(), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return 0x888888;
		}
	}
}
