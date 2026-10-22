package pluginjson.scanner;

import com.sun.jdi.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads already-instantiated Spring singleton beans directly through JDI.
 * No application methods, bean factories, getters or lazy targets are invoked.
 * Each instance is used on IntelliJ's debugger manager thread for one export.
 */
public final class SpringBeanLocator {
  private static final String ABSTRACT_CONTEXT =
      "org.springframework.context.support.AbstractApplicationContext";
  private static final int MAX_CONTEXT_INSTANCES_PER_CLASS = 64;
  private static final int MAX_MAP_ENTRIES = 100_000;
  private static final int MAX_MAP_BUCKETS = 1_048_576;
  private static final int BATCH_SIZE = 256;

  public enum Reason {
    NOT_FOUND, AMBIGUOUS, UNAVAILABLE, LIMIT_EXCEEDED, UNCACHED_FACTORY_PRODUCT
  }

  public static final class BeanLookupException extends Exception {
    private final Reason reason;
    public BeanLookupException(@NotNull String message) {
      this(Reason.UNAVAILABLE, message);
    }
    public BeanLookupException(@NotNull Reason reason, @NotNull String message) {
      super(message);
      this.reason = reason;
    }
    public @NotNull Reason reason() { return reason; }
  }

  /** Searches all active Spring application contexts, including Boot contexts. */
  public @NotNull Value findBean(@NotNull VirtualMachine vm, @NotNull String requestedName)
      throws BeanLookupException {
    boolean dereferenceFactory = requestedName.startsWith("&");
    String name = requestedName.replaceFirst("^&+", "");
    if (name.isBlank()) {
      throw new BeanLookupException(Reason.NOT_FOUND, "Inserisci un nome di bean Spring valido.");
    }
    Value matchingBean = null;
    boolean uncachedFactoryProduct = false;
    for (ObjectReference factory : beanFactories(vm)) {
      String beanName = resolveAlias(factory, name);
      Value singleton = findMapValue(objectField(factory, "singletonObjects"), beanName);
      boolean factoryBean = singleton instanceof ObjectReference object
          && SpringProxyReader.isAssignableTo(object, "org.springframework.beans.factory.FactoryBean");
      Value value = dereferenceFactory ? (factoryBean ? singleton : null)
          : findMapValue(objectField(factory, "factoryBeanObjectCache"), beanName);
      if (!dereferenceFactory && value == null) {
        if (factoryBean) uncachedFactoryProduct = true;
        else value = singleton;
      }
      if (value != null) matchingBean = uniqueMatch(matchingBean, value, "Il bean '" + requestedName + "'");
    }
    if (matchingBean != null) return matchingBean;
    if (uncachedFactoryProduct) {
      throw new BeanLookupException(Reason.UNCACHED_FACTORY_PRODUCT,
          "Il prodotto del FactoryBean '" + name + "' non è nella cache dei singleton. "
              + "Il plugin non esegue getObject(). Per esportare la factory, seleziona il metodo "
              + "@Bean che dichiara il FactoryBean oppure una variabile o un campo che la contiene.");
    }
    throw new BeanLookupException(Reason.NOT_FOUND,
        "Bean '" + requestedName + "' non trovato tra i singleton già inizializzati. "
            + "Controlla il nome (anche @Bean(name=...)). I bean @Lazy, prototype e di scope "
            + "non ancora creato non vengono istanziati automaticamente dal plugin.");
  }

  /**
   * Type fallback for a component or injection point whose bean name is unknown.
   * Matches actual initialized objects and cached FactoryBean products only;
   * never infers a live instance from a bean definition or calls getBean().
   */
  public @NotNull Value findBeanByType(@NotNull VirtualMachine vm, @NotNull String qualifiedTypeName)
      throws BeanLookupException {
    Value match = null;
    Set<Long> inspected = new HashSet<>();
    for (ObjectReference factory : beanFactories(vm)) {
      for (ObjectReference candidate : cachedObjects(factory)) {
        if (!inspected.add(candidate.uniqueID()) || !matchesType(candidate, qualifiedTypeName)) continue;
        match = uniqueMatch(match, candidate, "Il tipo '" + qualifiedTypeName + "'");
      }
    }
    if (match != null) return match;
    throw new BeanLookupException(Reason.NOT_FOUND,
        "Nessun bean già inizializzato compatibile con il tipo '" + qualifiedTypeName
            + "'. I bean lazy, prototype e scoped non vengono creati per l'esportazione.");
  }

  /** Identity-based recognition for direct local/field values; no name guessing. */
  public boolean isSpringBean(@NotNull VirtualMachine vm, @Nullable Value value)
      throws BeanLookupException {
    if (!(value instanceof ObjectReference selected) || vm.classesByName(ABSTRACT_CONTEXT).isEmpty()) {
      return false;
    }
    for (ObjectReference factory : beanFactories(vm)) {
      for (ObjectReference candidate : cachedObjects(factory)) {
        if (sameTarget(candidate, selected)) return true;
      }
    }
    return false;
  }

  public static boolean isSpringProxy(@Nullable Value value) {
    return value instanceof ObjectReference object && SpringProxyReader.isAopProxy(object);
  }

  public boolean matchesBeanType(@Nullable Value value, @NotNull String desired)
      throws BeanLookupException {
    if (value == null || (value instanceof ObjectReference object
        && object.referenceType().name().equals("org.springframework.beans.factory.support.NullBean"))) {
      return !Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void")
          .contains(desired);
    }
    return value instanceof ObjectReference object && matchesType(object, desired);
  }

  private boolean matchesType(ObjectReference object, String desired) throws BeanLookupException {
    desired = switch (desired) {
      case "boolean" -> "java.lang.Boolean";
      case "byte" -> "java.lang.Byte";
      case "short" -> "java.lang.Short";
      case "char" -> "java.lang.Character";
      case "int" -> "java.lang.Integer";
      case "long" -> "java.lang.Long";
      case "float" -> "java.lang.Float";
      case "double" -> "java.lang.Double";
      default -> desired;
    };
    Set<Long> seen = new HashSet<>();
    for (int i = 0; object != null && seen.add(object.uniqueID()); i++) {
      if (i >= 32) throw limit("Catena di proxy Spring troppo profonda");
      if (SpringProxyReader.isAssignableTo(object, desired)) return true;
      object = SpringProxyReader.singletonTarget(object);
    }
    return false;
  }

  private boolean sameTarget(ObjectReference candidate, ObjectReference selected) throws BeanLookupException {
    Set<Long> seen = new HashSet<>();
    for (int i = 0; candidate != null && seen.add(candidate.uniqueID()); i++) {
      if (i >= 32) throw limit("Catena di proxy Spring troppo profonda");
      if (candidate.equals(selected)) return true;
      candidate = SpringProxyReader.singletonTarget(candidate);
    }
    return false;
  }

  private Value uniqueMatch(Value previous, Value value, String description) throws BeanLookupException {
    // Keep distinct proxy registrations distinct, even if they share a target.
    if (previous != null && !previous.equals(value)) {
      throw new BeanLookupException(Reason.AMBIGUOUS,
          description + " corrisponde a più istanze in uno o più contesti Spring (più contesti "
              + "possono contenere lo stesso nome). Impossibile scegliere automaticamente il bean corretto.");
    }
    return value;
  }

  private List<ObjectReference> cachedObjects(ObjectReference factory) throws BeanLookupException {
    List<ObjectReference> result = new ArrayList<>();
    for (String field : List.of("singletonObjects", "factoryBeanObjectCache")) {
      for (Value value : mapValues(objectField(factory, field))) {
        if (value instanceof ObjectReference object) result.add(object);
      }
    }
    return result;
  }

  private List<ObjectReference> beanFactories(VirtualMachine vm) throws BeanLookupException {
    List<ReferenceType> bases = vm.classesByName(ABSTRACT_CONTEXT);
    if (bases.isEmpty()) {
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "Nessun ApplicationContext Spring caricato nella JVM in debug. "
              + "Avvia Spring Boot e fermati su un breakpoint dopo l'avvio.");
    }
    if (!vm.canGetInstanceInfo()) {
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "La JVM in debug non supporta la ricerca delle istanze (JDI instances).");
    }
    Set<Long> contextsSeen = new HashSet<>();
    Set<Long> factoriesSeen = new HashSet<>();
    List<ObjectReference> factories = new ArrayList<>();
    boolean activeContext = false;
    for (ClassType type : contextTypes(bases)) {
      List<ObjectReference> contexts = type.instances(MAX_CONTEXT_INSTANCES_PER_CLASS + 1);
      if (contexts.size() > MAX_CONTEXT_INSTANCES_PER_CLASS) {
        throw limit("Troppi contesti Spring della classe " + type.name());
      }
      for (ObjectReference context : contexts) {
        try {
          if (!contextsSeen.add(context.uniqueID()) || !isActive(context)) continue;
          activeContext = true;
          ObjectReference factory = objectField(context, "beanFactory");
          if (factory != null && factoriesSeen.add(factory.uniqueID())) factories.add(factory);
        } catch (ObjectCollectedException ignored) {
          // A context collected between instances() and its field read is no longer live.
        }
      }
    }
    if (!activeContext) {
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "Nessun ApplicationContext Spring attivo trovato. Attendi che Spring Boot termini l'inizializzazione.");
    }
    if (factories.isEmpty()) {
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "Il contesto Spring è presente, ma il suo BeanFactory non è accessibile tramite i campi JDI.");
    }
    return factories;
  }

  private List<ClassType> contextTypes(List<ReferenceType> bases) {
    ArrayDeque<ClassType> pending = new ArrayDeque<>();
    for (ReferenceType base : bases) if (base instanceof ClassType type) pending.add(type);
    Set<ClassType> seen = new HashSet<>();
    List<ClassType> result = new ArrayList<>();
    while (!pending.isEmpty()) {
      ClassType type = pending.removeFirst();
      if (!seen.add(type)) continue;
      result.add(type);
      pending.addAll(type.subclasses());
    }
    return result;
  }

  private boolean isActive(ObjectReference context) throws BeanLookupException {
    Value flag = fieldValue(context, "active");
    if (flag instanceof BooleanValue bool) return bool.booleanValue();
    if (flag instanceof ObjectReference atomic) {
      Value value = fieldValue(atomic, "value");
      if (value instanceof BooleanValue bool) return bool.booleanValue();
      if (value instanceof IntegerValue integer) return integer.intValue() != 0;
    }
    throw new BeanLookupException(Reason.UNAVAILABLE,
        "Impossibile leggere lo stato del contesto Spring " + context.referenceType().name() + ".");
  }

  private String resolveAlias(ObjectReference factory, String requested) throws BeanLookupException {
    ObjectReference aliases = objectField(factory, "aliasMap");
    String name = requested;
    Set<String> seen = new HashSet<>();
    while (seen.add(name)) {
      if (seen.size() > 1_024) throw limit("Catena di alias Spring troppo profonda");
      Value target = findMapValue(aliases, name);
      if (!(target instanceof StringReference text)) return name;
      name = text.value();
    }
    throw new BeanLookupException(Reason.UNAVAILABLE, "Ciclo nella registrazione degli alias Spring per '" + requested + "'.");
  }

  /** Reads the actual hash bucket, including a ConcurrentHashMap resize forwarding table. */
  private @Nullable Value findMapValue(@Nullable ObjectReference map, String key)
      throws BeanLookupException {
    ArrayReference table = mapTable(map);
    if (table == null) return null;
    int hash = key.hashCode() ^ (key.hashCode() >>> 16);
    Set<Long> tables = new HashSet<>();
    Set<Long> nodes = new HashSet<>();
    while (table.length() > 0 && tables.add(table.uniqueID())) {
      ObjectReference node = asObject(table.getValue((table.length() - 1) & hash));
      if (node != null && node.referenceType().name().endsWith("$ForwardingNode")) {
        Value next = fieldValue(node, "nextTable");
        if (!(next instanceof ArrayReference forwarded)) return null;
        table = forwarded;
        if (tables.size() > 32) throw limit("Troppe tabelle di ridimensionamento Spring");
        continue;
      }
      if (node != null && node.referenceType().name().endsWith("$TreeBin")) node = objectField(node, "first");
      while (node != null && nodes.add(node.uniqueID())) {
        if (nodes.size() > MAX_MAP_ENTRIES) throw limit("Troppi elementi nella cache Spring");
        Value nodeKey = fieldValue(node, "key");
        if (nodeKey instanceof StringReference text && key.equals(text.value())) return mapNodeValue(node);
        node = objectField(node, "next");
      }
      return null;
    }
    return null;
  }

  /** Full bounded scan used only for identity/type discovery; limits are explicit. */
  private List<Value> mapValues(@Nullable ObjectReference map) throws BeanLookupException {
    List<Value> result = new ArrayList<>();
    ArrayReference root = mapTable(map);
    if (root == null) return result;
    ArrayDeque<ArrayReference> pending = new ArrayDeque<>();
    pending.add(root);
    Set<Long> tables = new HashSet<>();
    Set<Long> nodes = new HashSet<>();
    int bucketsExamined = 0;
    while (!pending.isEmpty()) {
      ArrayReference table = pending.removeFirst();
      if (!tables.add(table.uniqueID())) continue;
      if (table.length() > MAX_MAP_BUCKETS - bucketsExamined) throw limit("Cache Spring troppo grande per una ricerca completa");
      bucketsExamined += table.length();
      for (int start = 0; start < table.length(); start += BATCH_SIZE) {
        for (Value value : table.getValues(start, Math.min(BATCH_SIZE, table.length() - start))) {
          ObjectReference node = asObject(value);
          if (node != null && node.referenceType().name().endsWith("$ForwardingNode")) {
            if (fieldValue(node, "nextTable") instanceof ArrayReference next) pending.add(next);
            continue;
          }
          if (node != null && node.referenceType().name().endsWith("$TreeBin")) node = objectField(node, "first");
          while (node != null && nodes.add(node.uniqueID())) {
            if (nodes.size() > MAX_MAP_ENTRIES) throw limit("Troppi elementi nella cache Spring");
            result.add(mapNodeValue(node));
            node = objectField(node, "next");
          }
        }
      }
    }
    return result;
  }

  private @Nullable Value mapNodeValue(ObjectReference node) {
    Value value = fieldValue(node, "val");
    return value != null ? value : fieldValue(node, "value");
  }

  private @Nullable ArrayReference mapTable(@Nullable ObjectReference map) throws BeanLookupException {
    if (map == null) return null;
    String declaration = SpringProxyReader.isAssignableTo(map, "java.util.concurrent.ConcurrentHashMap")
        ? "java.util.concurrent.ConcurrentHashMap"
        : SpringProxyReader.isAssignableTo(map, "java.util.HashMap") ? "java.util.HashMap" : null;
    if (declaration == null) {
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "Struttura della cache Spring non supportata: " + map.referenceType().name() + ".");
    }
    for (Field field : map.referenceType().allFields()) {
      if (field.name().equals("table") && field.declaringType().name().equals(declaration)) {
        Value table = map.getValue(field);
        return table instanceof ArrayReference array ? array : null;
      }
    }
    throw new BeanLookupException(Reason.UNAVAILABLE,
        "La struttura della cache Spring è cambiata: campo table non disponibile in " + declaration + ".");
  }

  private BeanLookupException limit(String message) {
    return new BeanLookupException(Reason.LIMIT_EXCEEDED, message + ": ricerca interrotta per evitare risultati incompleti.");
  }

  private @Nullable ObjectReference objectField(@Nullable ObjectReference object, String name) {
    return asObject(fieldValue(object, name));
  }
  private @Nullable ObjectReference asObject(@Nullable Value value) {
    return value instanceof ObjectReference object ? object : null;
  }
  private @Nullable Value fieldValue(@Nullable ObjectReference object, String name) {
    if (object == null) return null;
    Field fallback = null;
    // Prefer actual framework state when a custom subclass shadows a private
    // field such as beanFactory or active with an unrelated application field.
    for (Field field : object.referenceType().allFields()) {
      if (field.isStatic() || !name.equals(field.name())) continue;
      if (field.declaringType().name().startsWith("org.springframework.")) return object.getValue(field);
      if (fallback == null) fallback = field;
    }
    return fallback == null ? null : object.getValue(fallback);
  }
}
