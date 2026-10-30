package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Inventory conservation and persisted freight-state regression checks; no world mutation. */
public final class RailTransportChecks {
    private RailTransportChecks() { }

    public static int run() {
        int checks = 0;
        SimpleContainer source = new SimpleContainer(1), target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 48));
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 60));
        require(RailTransport.transfer(source, 0, target, 32) == 4, "partial destination capacity limits freight transfer"); checks++;
        require(source.getItem(0).getCount() == 44 && target.getItem(0).getCount() == 64, "partial transfer conserves exact item count"); checks++;
        require(RailTransport.transfer(source, 0, target, 32) == 0 && source.getItem(0).getCount() == 44, "full depot retains freight at its source"); checks++;
        target.setItem(0, new ItemStack(Items.GOLD_INGOT, 12));
        require(RailTransport.transfer(source, 0, target, 32) == 0 && target.getItem(0).is(Items.GOLD_INGOT), "unrelated cargo cannot be overwritten"); checks++;
        target.setItem(0, ItemStack.EMPTY);
        require(RailTransport.transfer(source, 0, target, 7) == 7 && source.getItem(0).getCount() == 37 && target.getItem(0).getCount() == 7, "per-step freight budget is enforced"); checks++;
        require(RailTransport.transfer(source, 0, source, 64) == 0 && source.getItem(0).getCount() == 37, "self transfer is rejected without mutation"); checks++;
        require(RailTransport.transfer(source, 0, target, 0) == 0, "zero transfer budget is a no-op"); checks++;
        target.setItem(0, ItemStack.EMPTY);
        require(RailTransport.transfer(source, 0, target, 64) == 37 && source.isEmpty() && target.getItem(0).getCount() == 37, "complete unloading empties source exactly once"); checks++;
        require(RailTransport.transfer(source, 0, target, 64) == 0 && target.getItem(0).getCount() == 37, "repeated unloading cannot duplicate cargo"); checks++;
        var trip = new RailTransport.Trip(new BlockPos(1, 64, 1), new BlockPos(90, 65, 1),
            new BlockPos(3, 64, 1), new BlockPos(88, 65, 1), true, true, false, 0, 120);
        var encoded = RailTransport.Trip.CODEC.encodeStart(JsonOps.INSTANCE, trip).getOrThrow();
        require(trip.equals(RailTransport.Trip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()), "trip endpoints, direction, cargo phase and budget survive codec roundtrip"); checks++;
        var arrived = trip.arrive(1000);
        require(!arrived.toOther() && !arrived.travelling() && arrived.unloading() && arrived.loaded() == 0, "physical arrival prepares reciprocal direction and unloading"); checks++;
        require(arrived.destinationRail().equals(trip.homeRail()), "reciprocal trip uses the original home track"); checks++;
        var rider = new RailTransport.Rider(java.util.UUID.randomUUID(), trip.home(), false, 2400, true);
        var passengerJson = RailTransport.Rider.CODEC.encodeStart(JsonOps.INSTANCE, rider).getOrThrow();
        require(rider.equals(RailTransport.Rider.CODEC.parse(JsonOps.INSTANCE, passengerJson).getOrThrow()), "passenger vehicle identity, original AI and visit phase survive reload"); checks++;
        var returning = arrived.depart().arrive(3000);
        require(returning.toOther() && returning.unloading() && returning.home().equals(trip.home()), "round-trip arrival restores the original home endpoint"); checks++;
        return checks;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Rail transport: " + message);
    }
}
