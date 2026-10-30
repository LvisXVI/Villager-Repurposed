package de.villagerplus.village;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Focused deterministic checks for road/rail geometry and station-side route selection. */
public final class RoadsChecks {
    private RoadsChecks() { }

    public static int run() {
        int checks = 0;
        BlockPos a = new BlockPos(120, 70, 80), b = new BlockPos(178, 70, 131);
        int length = Roads.routeLength(a, b);
        require(length == 109, "route length counts each Manhattan block step"); checks++;

        Set<BlockPos> firstHalf = new HashSet<>(), secondHalf = new HashSet<>();
        BlockPos previous = Roads.routePoint(a, b, 0, length);
        for (int i = 0; i <= length; i++) {
            BlockPos forward = Roads.routePoint(a, b, i, length);
            BlockPos reverse = Roads.routePoint(b, a, length - i, length);
            require(forward.equals(reverse), "both endpoints generate identical route points in reverse order at " + i); checks++;
            if (i > 0) {
                int dx = Math.abs(forward.getX() - previous.getX());
                int dz = Math.abs(forward.getZ() - previous.getZ());
                require(dx + dz == 1, "successive rail positions are cardinal neighbors at " + i); checks++;
            }
            if (i <= length / 2 + 1) firstHalf.add(forward);
            int fromB = length - i;
            if (fromB <= length / 2 + 1) secondHalf.add(forward);
            previous = forward;
        }
        Set<BlockPos> shared = new HashSet<>(firstHalf);
        shared.retainAll(secondHalf);
        require(!shared.isEmpty(), "the two built route halves overlap at a common midpoint"); checks++;

        int elbow = Math.abs(b.getX() - a.getX());
        require(Roads.alongXAt(a, b, elbow, length) == Roads.alongXAt(b, a, length - elbow, length),
            "lane orientation agrees at the shared elbow from both halves"); checks++;
        require(Roads.alongXAt(a, b, elbow + 1, length) == Roads.alongXAt(b, a, length - elbow - 1, length),
            "lane orientation agrees after the elbow from both halves"); checks++;

        BlockPos stationA = Roads.routePoint(a, b, 5, length).offset(0, 1, 2);
        BlockPos stationB = Roads.routePoint(b, a, 6, length).offset(2, 1, 0);
        int startA = Roads.stationStartCursor(a, b, stationA, length / 2 + 1, length);
        int startB = Roads.stationStartCursor(b, a, stationB, length / 2 + 1, length);
        require(startA == 5 && startB == 6, "each endpoint selects the nearest local route point to its station"); checks++;
        require(stationA.distSqr(Roads.routePoint(a, b, startA, length)) <= 24 * 24
            && stationB.distSqr(Roads.routePoint(b, a, startB, length)) <= 24 * 24,
            "both station boarding paths stay inside the transport walker's 24-block search radius"); checks++;
        return checks;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Roads: " + message);
    }
}
