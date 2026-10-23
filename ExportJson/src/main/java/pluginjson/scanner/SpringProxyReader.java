package pluginjson.scanner;

import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Field-only access to Spring AOP targets. No dependency on Spring in the IDE. */
final class SpringProxyReader {
  private static final String FRAMEWORK = "org.springframework.aop.framework.";
  private static final String CGLIB = FRAMEWORK + "CglibAopProxy$";
  private static final String ADVISED = FRAMEWORK + "AdvisedSupport";
  private static final String SINGLETON_SOURCE =
      "org.springframework.aop.target.SingletonTargetSource";

  private SpringProxyReader() {}

  static boolean isAopProxy(ObjectReference object) {
    return new ReadContext().isAopProxy(object);
  }

  static @Nullable ObjectReference singletonTarget(ObjectReference proxy) {
    return new ReadContext().singletonTarget(proxy);
  }

  static boolean isAssignableTo(ObjectReference object, String desired) {
    return new ReadContext().isAssignableTo(object, desired);
  }

  /** Metadata and target reads are scoped to one suspended export/lookup. */
  static final class ReadContext {
    private final Map<ReferenceType, List<Field>> fields = new HashMap<>();
    private final Map<ReferenceType, Map<String, Boolean>> assignability = new HashMap<>();
    private final Map<Long, Optional<ObjectReference>> targets = new HashMap<>();

    boolean isAopProxy(ObjectReference object) {
      return isAssignableTo(object, "org.springframework.aop.SpringProxy");
    }

    @Nullable ObjectReference singletonTarget(ObjectReference proxy) {
      if (!isAopProxy(proxy)) return null;
      Optional<ObjectReference> cached = targets.get(proxy.uniqueID());
      if (cached != null) return cached.orElse(null);
      ObjectReference target = readSingletonTarget(proxy);
      targets.put(proxy.uniqueID(), Optional.ofNullable(target));
      return target;
    }

    private @Nullable ObjectReference readSingletonTarget(ObjectReference proxy) {
      if (isAssignableTo(proxy, "java.lang.reflect.Proxy")) {
        ObjectReference handler = objectField(proxy, "h", "java.lang.reflect.Proxy");
        // SpringProxy is a public marker and can also be implemented by arbitrary
        // invocation handlers. Do not interpret their coincidentally named fields.
        if (handler == null || !handler.referenceType().name().equals(
            FRAMEWORK + "JdkDynamicAopProxy")) return null;
        return advisedTarget(objectField(handler, "advised", FRAMEWORK + "JdkDynamicAopProxy"));
      }

      List<Field> fields = new ArrayList<>();
      for (Field field : allFields(proxy.referenceType())) {
        if (!field.isStatic() && field.name().startsWith("CGLIB$CALLBACK_")) fields.add(field);
      }
      ObjectReference target = null;
      // A single JDI field read is enough even for frozen proxies with a callback
      // per method. Callback order and callback 0 being present are irrelevant.
      for (Value value : proxy.getValues(fields).values()) {
        if (!(value instanceof ObjectReference callback)) continue;
        String type = callback.referenceType().name();
        ObjectReference candidate = null;
        if (isAdvisedCallback(type)) {
          candidate = advisedTarget(objectField(callback, "advised", type));
        } else if (isCapturedStaticCallback(type)) {
          // These exact Spring implementations hold the target already acquired
          // while building a static proxy. This is safe even for a custom static
          // TargetSource: no isStatic()/getTarget() call is made by the exporter.
          candidate = objectField(callback, "target", type);
        }
        if (candidate == null) continue;
        if (target != null && !target.equals(candidate)) {
          // An Advised target source may have changed after construction while
          // old static callbacks still reference a different object.
          return null;
        }
        target = candidate;
      }
      return target;
    }

    private static boolean isAdvisedCallback(String type) {
      return type.equals(CGLIB + "DynamicAdvisedInterceptor")
          || type.equals(CGLIB + "AdvisedDispatcher")
          || type.equals(CGLIB + "EqualsInterceptor")
          || type.equals(CGLIB + "HashCodeInterceptor");
    }

    private static boolean isCapturedStaticCallback(String type) {
      return type.equals(CGLIB + "StaticUnadvisedInterceptor")
          || type.equals(CGLIB + "StaticUnadvisedExposedInterceptor")
          || type.equals(CGLIB + "StaticDispatcher")
          || type.equals(CGLIB + "FixedChainStaticTargetInterceptor");
    }

    private @Nullable ObjectReference advisedTarget(@Nullable ObjectReference advice) {
      ObjectReference source = objectField(advice, "targetSource", ADVISED);
      // An arbitrary dynamic TargetSource may have a field called target, whose
      // meaning cannot be established without executing application methods.
      if (source == null || !source.referenceType().name().equals(SINGLETON_SOURCE)) return null;
      return objectField(source, "target", SINGLETON_SOURCE);
    }

    boolean isAssignableTo(ObjectReference object, String desired) {
      Map<String, Boolean> matches = assignability.computeIfAbsent(object.referenceType(), ignored -> new HashMap<>());
      Boolean cached = matches.get(desired);
      if (cached != null) return cached;
      boolean result = isTypeAssignableTo(object.referenceType(), desired, new HashSet<>());
      matches.put(desired, result);
      return result;
    }

    private List<Field> allFields(ReferenceType type) {
      return fields.computeIfAbsent(type, ReferenceType::allFields);
    }

    private static boolean isTypeAssignableTo(ReferenceType actual, String desired,
        Set<ReferenceType> seen) {
      if (sameTypeName(actual.name(), desired)) return true;
      if (desired.equals("java.lang.Object")) return true;
      if (!seen.add(actual)) return false;
      if (actual instanceof ArrayType array) {
        if (desired.equals("java.lang.Cloneable") || desired.equals("java.io.Serializable")) return true;
        if (!desired.endsWith("[]")) return false;
        String component = desired.substring(0, desired.length() - 2);
        if (sameTypeName(array.componentTypeName(), component)) return true;
        try {
          Type actualComponent = array.componentType();
          // Primitive arrays are invariant; reference arrays are covariant, also
          // across dimensions (e.g. int[][] is assignable to Object[]).
          return actualComponent instanceof ReferenceType reference
              && isTypeAssignableTo(reference, component, seen);
        } catch (ClassNotLoadedException ignored) {
          return false;
        }
      }
      if (actual instanceof ClassType type) {
        for (ClassType current = type; current != null; current = current.superclass()) {
          if (sameTypeName(current.name(), desired)) return true;
        }
        for (InterfaceType iface : type.allInterfaces()) {
          if (sameTypeName(iface.name(), desired)) return true;
        }
      } else if (actual instanceof InterfaceType iface) {
        for (InterfaceType parent : iface.superinterfaces()) {
          if (isTypeAssignableTo(parent, desired, seen)) return true;
        }
      }
      return false;
    }

    private static boolean sameTypeName(String actual, String desired) {
      // PSI uses canonical nested names; JDI uses their binary '$' names.
      return actual.equals(desired) || actual.replace('$', '.').equals(desired.replace('$', '.'));
    }

    private @Nullable ObjectReference objectField(
        @Nullable ObjectReference object, String name, String declaringType) {
      if (object == null) return null;
      for (Field field : allFields(object.referenceType())) {
        if (!field.isStatic() && field.name().equals(name)
            && field.declaringType().name().equals(declaringType)) {
          Value value = object.getValue(field);
          return value instanceof ObjectReference reference ? reference : null;
        }
      }
      return null;
    }
  }
}
