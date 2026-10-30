package de.villagerplus.job;

import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

/**
 * Ein Beruf. {@code primary} ist die Hauptaufgabe (Vormittag), {@code secondary} die Nebenaufgabe (Nachmittag).
 * Beide geben true zurueck, wenn sie gerade beschaeftigt sind.
 */
public interface Job {
	boolean primary(Ctx c);

	default boolean secondary(Ctx c) {
		return false;
	}

	/** Welche Items bringt dieser Beruf bei den Treffen zur Dorfkiste? */
	default Predicate<ItemStack> produce() {
		return s -> false;
	}

	/** Soviele passende Items behaelt der Dorfbewohner fuer sich. */
	default int keep() {
		return 0;
	}

	/** Gewitter: Innenberufe arbeiten weiter, Außenberufe suchen Unterstand. */
	default boolean indoorsDuringStorm() {
		return false;
	}
}
