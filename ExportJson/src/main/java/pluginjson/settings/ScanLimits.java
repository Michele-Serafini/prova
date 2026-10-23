package pluginjson.settings;

/** Immutable limits captured once at the start of an export. */
public record ScanLimits(
    int maxDepth,
    int maxItems,
    int maxObjects,
    int maxValues,
    int maxTextUnits,
    int maxStringLength,
    int maxLayoutSlots
) {
  public ScanLimits {
    requireRange("Maximum depth", maxDepth, 1, 128);
    requireRange("Items per collection/map", maxItems, 1, 1_000_000);
    requireRange("Objects per snapshot", maxObjects, 1, 1_000_000);
    requireRange("Values per snapshot", maxValues, 1, 5_000_000);
    requireRange("Text characters per snapshot", maxTextUnits, 1, 100_000_000);
    requireRange("Characters per string", maxStringLength, 1, 10_000_000);
    requireRange("Layout slots per container", maxLayoutSlots, 1, 10_000_000);
  }

  public static ScanLimits defaults(boolean springMode) {
    return springMode
        ? new ScanLimits(16, 2_000, 20_000, 80_000, 2_000_000, 100_000, 100_000)
        : new ScanLimits(40, 10_000, 50_000, 200_000, 5_000_000, 100_000, 100_000);
  }

  static void requireRange(String name, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + ".");
    }
  }

  static int validOrDefault(int value, int minimum, int maximum, int defaultValue) {
    return value >= minimum && value <= maximum ? value : defaultValue;
  }
}
