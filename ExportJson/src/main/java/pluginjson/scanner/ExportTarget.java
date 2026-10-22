package pluginjson.scanner;

import java.util.List;

/** Resolved source intent; contains no PSI objects and is safe on the JDI thread. */
public record ExportTarget(String name, Kind kind, String typeName,
                           String beanName, boolean explicitBeanName,
                           boolean springHint, FieldPath fieldPath) {
  public enum Kind { LOCAL, FIELD, BEAN, SPRING_TYPE, THIS }
  public enum Root { THIS, LOCAL, STATIC }
  public record FieldKey(String declaringType, String name) {}
  public record FieldPath(Root root, String rootName, List<FieldKey> fields) {
    public FieldPath { fields = List.copyOf(fields); }
  }
}
