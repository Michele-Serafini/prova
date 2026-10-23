package pluginjson.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.ParseException;
import java.text.ParsePosition;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.NotNull;

/** Settings → Tools → Runtime Object to JSON. */
public final class ExportSettingsConfigurable implements SearchableConfigurable {
  private final ExportSettings settings;
  private JPanel panel;
  private ProfileForm ordinary;
  private ProfileForm spring;
  private LookupForm lookup;

  public ExportSettingsConfigurable() {
    this(ExportSettings.getInstance());
  }

  ExportSettingsConfigurable(ExportSettings settings) {
    this.settings = settings;
  }

  @Override
  public @NotNull String getId() { return "runtimeobjecttojson.settings"; }

  @Override
  public String getDisplayName() { return "Runtime Object to JSON"; }

  @Override
  public JComponent createComponent() {
    if (panel != null) return panel;
    ordinary = new ProfileForm();
    spring = new ProfileForm();
    lookup = new LookupForm();
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Ordinary values", ordinary.panel);
    tabs.addTab("Spring beans", spring.panel);
    tabs.addTab("Spring lookup", lookup.panel);

    panel = new JPanel(new BorderLayout(0, 12));
    panel.add(new JLabel("<html>Limits apply to the next export.<br>"
        + "Lower limits reduce debugger work and snapshot size.</html>"), BorderLayout.NORTH);
    panel.add(tabs, BorderLayout.CENTER);
    JButton defaults = new JButton("Restore Defaults");
    defaults.addActionListener(event -> {
      ordinary.set(ScanLimits.defaults(false));
      spring.set(ScanLimits.defaults(true));
      lookup.set(SpringLookupLimits.defaults());
    });
    JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
    buttons.add(defaults);
    panel.add(buttons, BorderLayout.SOUTH);
    reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    if (panel == null) return false;
    try {
      return !ordinary.read().equals(settings.getLimits(false))
          || !spring.read().equals(settings.getLimits(true))
          || !lookup.read().equals(settings.getLookupLimits());
    } catch (ParseException | IllegalArgumentException invalid) {
      return true;
    }
  }

  @Override
  public void apply() throws ConfigurationException {
    if (panel == null) return;
    try {
      settings.update(ordinary.read(), spring.read(), lookup.read());
    } catch (ParseException | IllegalArgumentException invalid) {
      throw new ConfigurationException("Enter whole numbers within the allowed ranges. "
          + invalid.getMessage(), "Invalid export limits");
    }
  }

  @Override
  public void reset() {
    if (panel == null) return;
    ordinary.set(settings.getLimits(false));
    spring.set(settings.getLimits(true));
    lookup.set(settings.getLookupLimits());
  }

  @Override
  public void disposeUIResources() {
    panel = null;
    ordinary = null;
    spring = null;
    lookup = null;
  }

  private static class Form {
    final JPanel panel = new JPanel(new GridBagLayout());
    private int row;

    JSpinner number(String title, int maximum, String explanation) {
      JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 1, maximum, 1));
      spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
      ((JSpinner.NumberEditor) spinner.getEditor()).getFormat().setParseIntegerOnly(true);
      ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setColumns(12);
      String tooltip = explanation + " Allowed range: 1–" + maximum + ".";
      JLabel label = new JLabel(title + ":");
      label.setLabelFor(spinner);
      label.setToolTipText(tooltip);
      spinner.setToolTipText(tooltip);
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = 0;
      constraints.gridy = row;
      constraints.anchor = GridBagConstraints.WEST;
      constraints.insets = new Insets(5, 8, 5, 12);
      panel.add(label, constraints);
      constraints.gridx = 1;
      constraints.weightx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      panel.add(spinner, constraints);
      row++;
      return spinner;
    }

    void finish() {
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridy = row;
      constraints.weighty = 1;
      panel.add(new JPanel(), constraints);
    }

    static int value(JSpinner spinner) throws ParseException {
      JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
      String text = editor.getTextField().getText();
      ParsePosition position = new ParsePosition(0);
      if (editor.getFormat().parse(text, position) == null || position.getIndex() != text.length()) {
        throw new ParseException("Enter a whole number.", position.getIndex());
      }
      spinner.commitEdit();
      return ((Number) spinner.getValue()).intValue();
    }

    static void setValue(JSpinner spinner, int value) {
      spinner.setValue(value);
      // An unchanged model value emits no event; explicitly discard invalid
      // editor text when Reset or Restore Defaults reloads that same value.
      ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setValue(value);
    }
  }

  private static final class ProfileForm extends Form {
    private final JSpinner depth = number("Maximum depth", 128, "Maximum nesting depth in the JSON snapshot.");
    private final JSpinner items = number("Items per collection/map", 1_000_000,
        "Maximum exported elements or entries in each container.");
    private final JSpinner objects = number("Objects per snapshot", 1_000_000,
        "Maximum number of distinct non-scalar objects.");
    private final JSpinner values = number("Values per snapshot", 5_000_000,
        "Maximum visited values, including scalar values.");
    private final JSpinner text = number("Text characters per snapshot", 100_000_000,
        "Total text budget in UTF-16 code units, including field names.");
    private final JSpinner string = number("Characters per string", 10_000_000,
        "Maximum exported text per string in UTF-16 code units.");
    private final JSpinner slots = number("Sparse table slots / linked nodes", 10_000_000,
        "Limit for sparse table capacity and linked-node inspection; compact arrays use the items limit.");

    ProfileForm() { finish(); }

    ScanLimits read() throws ParseException {
      return new ScanLimits(value(depth), value(items), value(objects), value(values),
          value(text), value(string), value(slots));
    }

    void set(ScanLimits limits) {
      setValue(depth, limits.maxDepth());
      setValue(items, limits.maxItems());
      setValue(objects, limits.maxObjects());
      setValue(values, limits.maxValues());
      setValue(text, limits.maxTextUnits());
      setValue(string, limits.maxStringLength());
      setValue(slots, limits.maxLayoutSlots());
    }
  }

  private static final class LookupForm extends Form {
    private final JSpinner contexts = number("Context instances per class", 4_096,
        "Maximum Spring application context instances inspected per loaded context class.");
    private final JSpinner entries = number("Entries per singleton cache", 1_000_000,
        "Maximum entries inspected in each Spring singleton or alias cache.");
    private final JSpinner buckets = number("Buckets per singleton cache", 16_777_216,
        "Maximum internal map buckets inspected in each Spring cache.");
    private final JSpinner proxies = number("Proxy chain depth", 128,
        "Maximum nested Spring proxies inspected when resolving a bean.");
    private final JSpinner aliases = number("Alias chain depth", 100_000,
        "Maximum aliases followed when resolving a bean name.");

    LookupForm() { finish(); }

    SpringLookupLimits read() throws ParseException {
      return new SpringLookupLimits(value(contexts), value(entries), value(buckets), value(proxies), value(aliases));
    }

    void set(SpringLookupLimits limits) {
      setValue(contexts, limits.maxContextInstancesPerClass());
      setValue(entries, limits.maxMapEntries());
      setValue(buckets, limits.maxMapBuckets());
      setValue(proxies, limits.maxProxyDepth());
      setValue(aliases, limits.maxAliasDepth());
    }
  }
}
