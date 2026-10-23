package pluginjson.settings;

/** Bounds for inspecting existing Spring contexts and singleton caches. */
public record SpringLookupLimits(
    int maxContextInstancesPerClass,
    int maxMapEntries,
    int maxMapBuckets,
    int maxProxyDepth,
    int maxAliasDepth
) {
  public SpringLookupLimits {
    ScanLimits.requireRange("Context instances per class", maxContextInstancesPerClass, 1, 4_096);
    ScanLimits.requireRange("Entries per singleton cache", maxMapEntries, 1, 1_000_000);
    ScanLimits.requireRange("Buckets per singleton cache", maxMapBuckets, 1, 16_777_216);
    ScanLimits.requireRange("Proxy chain depth", maxProxyDepth, 1, 128);
    ScanLimits.requireRange("Alias chain depth", maxAliasDepth, 1, 100_000);
  }

  public static SpringLookupLimits defaults() {
    return new SpringLookupLimits(64, 100_000, 1_048_576, 32, 1_024);
  }
}
