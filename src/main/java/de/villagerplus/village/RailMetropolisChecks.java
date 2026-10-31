package de.villagerplus.village;

/** @deprecated Use {@link RailEndToEndChecks}; kept for the {@code railmetro} autotest mode name. */
public final class RailMetropolisChecks {
	private RailMetropolisChecks() { }

	public static int run(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos at) {
		return RailEndToEndChecks.run(level, at);
	}
}
