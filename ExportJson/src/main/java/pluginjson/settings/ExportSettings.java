package pluginjson.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Application-level settings; debugger operations receive immutable copies. */
@Service(Service.Level.APP)
@State(name = "RuntimeObjectToJsonSettings", storages = @Storage("runtime-object-to-json.xml"))
public final class ExportSettings implements PersistentStateComponent<ExportSettings.SettingsState> {
  private ScanLimits ordinary = ScanLimits.defaults(false);
  private ScanLimits spring = ScanLimits.defaults(true);
  private SpringLookupLimits lookup = SpringLookupLimits.defaults();

  public static ExportSettings getInstance() {
    return ApplicationManager.getApplication().getService(ExportSettings.class);
  }

  public synchronized ScanLimits getLimits(boolean springMode) {
    return springMode ? spring : ordinary;
  }

  public synchronized SpringLookupLimits getLookupLimits() {
    return lookup;
  }

  public synchronized void update(ScanLimits ordinary, ScanLimits spring, SpringLookupLimits lookup) {
    // Validate all inputs before replacing any profile.
    java.util.Objects.requireNonNull(ordinary);
    java.util.Objects.requireNonNull(spring);
    java.util.Objects.requireNonNull(lookup);
    this.ordinary = ordinary;
    this.spring = spring;
    this.lookup = lookup;
  }

  @Override
  public synchronized @NotNull SettingsState getState() {
    SettingsState state = new SettingsState();
    state.ordinary = new ProfileState(ordinary);
    state.spring = new ProfileState(spring);
    state.lookup = new LookupState(lookup);
    return state;
  }

  @Override
  public synchronized void loadState(@NotNull SettingsState state) {
    // Damaged/older configuration files must never prevent plugin startup.
    ordinary = state.ordinary == null ? ScanLimits.defaults(false) : state.ordinary.toLimits(false);
    spring = state.spring == null ? ScanLimits.defaults(true) : state.spring.toLimits(true);
    lookup = state.lookup == null ? SpringLookupLimits.defaults() : state.lookup.toLimits();
  }

  public static final class SettingsState {
    public ProfileState ordinary = new ProfileState(ScanLimits.defaults(false));
    public ProfileState spring = new ProfileState(ScanLimits.defaults(true));
    public LookupState lookup = new LookupState(SpringLookupLimits.defaults());
  }

  /** Mutable XML serialization DTO, never shared with a running export. */
  public static final class ProfileState {
    public int maxDepth;
    public int maxItems;
    public int maxObjects;
    public int maxValues;
    public int maxTextUnits;
    public int maxStringLength;
    public int maxLayoutSlots;

    public ProfileState() { }

    ProfileState(ScanLimits limits) {
      maxDepth = limits.maxDepth();
      maxItems = limits.maxItems();
      maxObjects = limits.maxObjects();
      maxValues = limits.maxValues();
      maxTextUnits = limits.maxTextUnits();
      maxStringLength = limits.maxStringLength();
      maxLayoutSlots = limits.maxLayoutSlots();
    }

    ScanLimits toLimits(boolean springMode) {
      ScanLimits defaults = ScanLimits.defaults(springMode);
      return new ScanLimits(
          ScanLimits.validOrDefault(maxDepth, 1, 128, defaults.maxDepth()),
          ScanLimits.validOrDefault(maxItems, 1, 1_000_000, defaults.maxItems()),
          ScanLimits.validOrDefault(maxObjects, 1, 1_000_000, defaults.maxObjects()),
          ScanLimits.validOrDefault(maxValues, 1, 5_000_000, defaults.maxValues()),
          ScanLimits.validOrDefault(maxTextUnits, 1, 100_000_000, defaults.maxTextUnits()),
          ScanLimits.validOrDefault(maxStringLength, 1, 10_000_000, defaults.maxStringLength()),
          ScanLimits.validOrDefault(maxLayoutSlots, 1, 10_000_000, defaults.maxLayoutSlots()));
    }
  }

  public static final class LookupState {
    public int maxContextInstancesPerClass;
    public int maxMapEntries;
    public int maxMapBuckets;
    public int maxProxyDepth;
    public int maxAliasDepth;

    public LookupState() { }

    LookupState(SpringLookupLimits limits) {
      maxContextInstancesPerClass = limits.maxContextInstancesPerClass();
      maxMapEntries = limits.maxMapEntries();
      maxMapBuckets = limits.maxMapBuckets();
      maxProxyDepth = limits.maxProxyDepth();
      maxAliasDepth = limits.maxAliasDepth();
    }

    SpringLookupLimits toLimits() {
      SpringLookupLimits defaults = SpringLookupLimits.defaults();
      return new SpringLookupLimits(
          ScanLimits.validOrDefault(maxContextInstancesPerClass, 1, 4_096, defaults.maxContextInstancesPerClass()),
          ScanLimits.validOrDefault(maxMapEntries, 1, 1_000_000, defaults.maxMapEntries()),
          ScanLimits.validOrDefault(maxMapBuckets, 1, 16_777_216, defaults.maxMapBuckets()),
          ScanLimits.validOrDefault(maxProxyDepth, 1, 128, defaults.maxProxyDepth()),
          ScanLimits.validOrDefault(maxAliasDepth, 1, 100_000, defaults.maxAliasDepth()));
    }
  }
}
