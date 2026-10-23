package pluginjson.scanner;

import com.sun.jdi.*;
import java.util.List;
import pluginjson.settings.SpringLookupLimits;

/** Reads the selected instance before considering a Spring context lookup. */
public final class RuntimeValueResolver {
  private final SpringBeanLocator spring;

  public RuntimeValueResolver() { this(SpringLookupLimits.defaults()); }

  public RuntimeValueResolver(SpringLookupLimits limits) { spring = new SpringBeanLocator(limits); }
  public record ResolvedValue(Value value, boolean springMode) {}

  public ResolvedValue resolve(StackFrame frame, ExportTarget target) throws Exception {
    VirtualMachine vm = frame.virtualMachine();
    SpringBeanLocator.Lookup spring = this.spring.newLookup();
    if (target.kind() == ExportTarget.Kind.BEAN) {
      return new ResolvedValue(spring.findBean(vm, target.beanName()), true);
    }
    if (target.kind() == ExportTarget.Kind.SPRING_TYPE) {
      return new ResolvedValue(spring.findBeanByType(vm, target.typeName()), true);
    }
    ReadResult read;
    if (target.kind() == ExportTarget.Kind.LOCAL) {
      read = local(frame, target.name());
    } else if (target.kind() == ExportTarget.Kind.THIS) {
      ObjectReference current = frame.thisObject();
      read = new ReadResult(current != null, current);
    } else {
      read = field(frame, target.fieldPath());
    }
    if (read.found()) {
      // Null is a real runtime value. Never replace it with another bean.
      boolean springMode = target.springHint() || SpringBeanLocator.isSpringProxy(read.value());
      if (!springMode && read.value() instanceof ObjectReference) {
        try { springMode = spring.isSpringBean(vm, read.value()); }
        catch (SpringBeanLocator.BeanLookupException | RuntimeException ignored) {
          // Failure of optional mode detection must not hide an available value.
          // Infrastructure is still excluded by the scanner in ordinary mode.
        }
      }
      return new ResolvedValue(read.value(), springMode);
    }
    boolean canUseContext = target.springHint() && (target.kind() != ExportTarget.Kind.FIELD
        || (target.fieldPath() != null && target.fieldPath().root() == ExportTarget.Root.THIS
            && target.fieldPath().fields().size() == 1));
    if (!canUseContext) {
      throw new IllegalArgumentException("'" + target.name() + "' non è accessibile nel frame "
          + "selezionato. Seleziona il frame dell'oggetto oppure una variabile visibile. "
          + "Per le variabili locali servono le informazioni di debug della compilazione.");
    }
    if (target.beanName() != null) {
      try {
        Value bean = spring.findBean(vm, target.beanName());
        if (target.typeName() == null || spring.matchesBeanType(bean, target.typeName())) {
          return new ResolvedValue(bean, true);
        }
        if (target.explicitBeanName()) {
          throw new IllegalArgumentException("Il bean '" + target.beanName()
              + "' non è compatibile con il tipo selezionato " + target.typeName() + ".");
        }
      } catch (SpringBeanLocator.BeanLookupException error) {
        // Explicit qualifiers are never silently replaced by a different bean.
        if (error.reason() != SpringBeanLocator.Reason.NOT_FOUND
            || target.explicitBeanName()) throw error;
      }
    }
    if (target.typeName() == null) {
      throw new IllegalArgumentException("Impossibile determinare il tipo del bean selezionato.");
    }
    return new ResolvedValue(spring.findBeanByType(vm, target.typeName()), true);
  }

  private record ReadResult(boolean found, Value value) {}

  private ReadResult local(StackFrame frame, String name) throws AbsentInformationException {
    try {
      LocalVariable variable = frame.visibleVariableByName(name);
      return new ReadResult(variable != null, variable == null ? null : frame.getValue(variable));
    } catch (AbsentInformationException missing) {
      return new ReadResult(false, null);
    }
  }

  private ReadResult field(StackFrame frame, ExportTarget.FieldPath path) throws Exception {
    if (path == null || path.fields().isEmpty()) return new ReadResult(false, null);
    Value owner = null;
    ReferenceType staticOwner = null;
    if (path.root() == ExportTarget.Root.STATIC) {
      List<ReferenceType> types = frame.virtualMachine().classesByName(path.rootName());
      ClassLoaderReference frameLoader = frame.location().declaringType().classLoader();
      List<ReferenceType> sameLoader = types.stream()
          .filter(type -> java.util.Objects.equals(type.classLoader(), frameLoader)).toList();
      if (sameLoader.size() == 1) staticOwner = sameLoader.getFirst();
      else if (types.size() == 1) staticOwner = types.getFirst();
      else if (types.size() > 1) throw new IllegalArgumentException(
          "Più classloader contengono " + path.rootName() + ": impossibile scegliere l'istanza statica.");
      else return new ReadResult(false, null);
    } else if (path.root() == ExportTarget.Root.THIS) {
      owner = frame.thisObject();
      if (owner == null) return new ReadResult(false, null);
    } else {
      ReadResult root = local(frame, path.rootName());
      if (!root.found()) return root;
      owner = root.value();
    }
    for (int index = 0; index < path.fields().size(); index++) {
      ExportTarget.FieldKey key = path.fields().get(index);
      if (staticOwner == null && owner == null) {
        throw new IllegalArgumentException("Il riferimento prima di '" + key.name()
            + "' vale null; il campo non può essere letto.");
      }
      ReferenceType type = staticOwner != null ? staticOwner
          : owner instanceof ObjectReference object ? object.referenceType() : null;
      if (type == null) return new ReadResult(false, null);
      Field field = null;
      for (Field candidate : type.allFields()) {
        if (candidate.name().equals(key.name())
            && candidate.declaringType().name().equals(key.declaringType())) {
          field = candidate;
          break;
        }
      }
      if (field == null) return new ReadResult(false, null);
      if (field.isStatic()) owner = field.declaringType().getValue(field);
      else if (owner instanceof ObjectReference object) owner = object.getValue(field);
      else return new ReadResult(false, null);
      staticOwner = null;
    }
    return new ReadResult(true, owner);
  }

}
