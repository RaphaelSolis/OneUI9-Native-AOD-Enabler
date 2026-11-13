import org.raphaelsolis.aodbattery.BatteryPolicy;

public final class BatteryPolicyTest {
    private static void expect(boolean expected, boolean main, boolean attached, boolean active,
                               boolean plugged, boolean restricted, int level, String scenario) {
        boolean actual = BatteryPolicy.shouldShow(main, attached, active, plugged, restricted, level);
        if (actual != expected) throw new AssertionError(scenario);
    }

    public static void main(String[] args) {
        expect(true, true, true, true, false, false, 57, "unplugged main AOD");
        expect(true, true, true, true, false, false, 0, "empty battery");
        expect(true, true, true, true, false, false, 100, "full unplugged battery");
        expect(false, true, true, true, true, false, 57, "charging uses stock behavior");
        expect(false, true, true, false, false, false, 57, "unlocked phone");
        expect(false, false, true, true, false, false, 57, "other charging or cover view");
        expect(false, true, false, true, false, false, 57, "detached AOD view");
        expect(false, true, true, true, false, true, 57, "USB restriction remains effective");
        expect(false, true, true, true, false, false, -1, "unknown battery level");
        expect(false, true, true, true, false, false, 101, "invalid battery level");
        System.out.println("Passed 10 AOD battery activation scenarios.");
    }
}
