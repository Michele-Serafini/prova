package pluginjson.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.xmlb.XmlSerializer;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExportSettingsTest {
  @Test
  void preservesIndependentProfilesAcrossStateReloads() {
    ExportSettings original = new ExportSettings();
    ScanLimits normal = new ScanLimits(8, 50, 500, 2_000, 50_000, 2_000, 5_000);
    ScanLimits spring = new ScanLimits(4, 20, 200, 800, 20_000, 1_000, 2_000);
    SpringLookupLimits lookup = new SpringLookupLimits(20, 5_000, 65_536, 12, 40);
    original.update(normal, spring, lookup);
    ExportSettings reloaded = new ExportSettings();
    reloaded.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(original.getState()),
        ExportSettings.SettingsState.class));
    assertEquals(normal, reloaded.getLimits(false));
    assertEquals(spring, reloaded.getLimits(true));
    assertEquals(lookup, reloaded.getLookupLimits());

    ExportSettings.SettingsState copy = original.getState();
    copy.ordinary.maxDepth = 1;
    assertEquals(normal, original.getLimits(false), "Persistence DTOs must not mutate a running export's limits");
  }

  @Test
  void repairsOnlyInvalidPersistedFieldsAndAcceptsMissingProfiles() {
    ExportSettings settings = new ExportSettings();
    ExportSettings.SettingsState state = settings.getState();
    state.ordinary.maxDepth = Integer.MAX_VALUE;
    state.ordinary.maxItems = 17;
    state.spring.maxTextUnits = -1;
    state.lookup.maxMapBuckets = 0;
    state.lookup.maxAliasDepth = 9;
    settings.loadState(state);
    assertEquals(ScanLimits.defaults(false).maxDepth(), settings.getLimits(false).maxDepth());
    assertEquals(17, settings.getLimits(false).maxItems());
    assertEquals(ScanLimits.defaults(true).maxTextUnits(), settings.getLimits(true).maxTextUnits());
    assertEquals(SpringLookupLimits.defaults().maxMapBuckets(), settings.getLookupLimits().maxMapBuckets());
    assertEquals(9, settings.getLookupLimits().maxAliasDepth());
    state.ordinary = null;
    state.spring = null;
    state.lookup = null;
    settings.loadState(state);
    assertEquals(ScanLimits.defaults(false), settings.getLimits(false));
    assertEquals(ScanLimits.defaults(true), settings.getLimits(true));
    assertEquals(SpringLookupLimits.defaults(), settings.getLookupLimits());
  }

  @Test
  void rejectsUnsafeLimitsBeforeTheyCanReachTheScanner() {
    assertThrows(IllegalArgumentException.class, () -> new ScanLimits(0, 1, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ScanLimits(129, 1, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ScanLimits(1, Integer.MAX_VALUE, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpringLookupLimits(1, 1, 1, 129, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpringLookupLimits(1, 1, 1, 1, 0));
    assertDoesNotThrow(() -> new ScanLimits(128, 1_000_000, 1_000_000, 5_000_000,
        100_000_000, 10_000_000, 10_000_000));
  }

  @Test
  void settingsFormAppliesResetsAndRejectsInvalidTypedInputAtomically() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      ExportSettings settings = new ExportSettings();
      ExportSettingsConfigurable configurable = new ExportSettingsConfigurable(settings);
      JComponent panel = configurable.createComponent();
      List<JSpinner> fields = new ArrayList<>();
      collectSpinners(panel, fields);
      assertEquals(19, fields.size());
      assertFalse(configurable.isModified());
      fields.get(0).setValue(7);
      assertTrue(configurable.isModified());
      assertDoesNotThrow(configurable::apply);
      assertEquals(7, settings.getLimits(false).maxDepth());
      assertEquals(ScanLimits.defaults(true), settings.getLimits(true));
      assertFalse(configurable.isModified());

      fields.get(0).setValue(9);
      JSpinner.NumberEditor editor = (JSpinner.NumberEditor) fields.get(1).getEditor();
      editor.getTextField().setText("invalid");
      assertTrue(configurable.isModified());
      assertThrows(ConfigurationException.class, configurable::apply);
      assertEquals(7, settings.getLimits(false).maxDepth(), "Rejected input must not partially apply profiles");
      configurable.reset();
      assertFalse(configurable.isModified());
      assertEquals(7, fields.get(0).getValue());
      editor.getTextField().setText("1.5");
      assertThrows(ConfigurationException.class, configurable::apply);
      assertEquals(ScanLimits.defaults(false).maxItems(), settings.getLimits(false).maxItems());
      configurable.reset();
      assertFalse(configurable.isModified());
      configurable.disposeUIResources();
      assertFalse(configurable.isModified());
      assertNotNull(configurable.createComponent());
      configurable.disposeUIResources();
    });
  }

  private static void collectSpinners(Component component, List<JSpinner> fields) {
    if (component instanceof JSpinner spinner) fields.add(spinner);
    else if (component instanceof Container container) {
      for (Component child : container.getComponents()) collectSpinners(child, fields);
    }
  }
}
