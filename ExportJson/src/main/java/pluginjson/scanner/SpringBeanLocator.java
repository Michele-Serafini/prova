package pluginjson.scanner;

import com.sun.jdi.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pluginjson.settings.SpringLookupLimits;

/**
 * Reads already-instantiated Spring singleton beans directly through JDI.
 * No application methods, bean factories, getters or lazy targets are invoked.
 * Each instance is used on IntelliJ's debugger manager thread for one export.
 */
public final class SpringBeanLocator {
  private static final String ABSTRACT_CONTEXT =
      "org.springframework.context.support.AbstractApplicationContext";
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

  private final SpringLookupLimits limits;

  public SpringBeanLocator() { this(SpringLookupLimits.defaults()); }

  public SpringBeanLocator(@NotNull SpringLookupLimits limits) { this.limits = limits; }

  public @NotNull Value findBean(@NotNull VirtualMachine vm, @NotNull String requestedName)
      throws BeanLookupException {
    return new Lookup().findBean(vm, requestedName);
  }

  public @NotNull Value findBeanByType(@NotNull VirtualMachine vm, @NotNull String qualifiedTypeName)
      throws BeanLookupException {
    return new Lookup().findBeanByType(vm, qualifiedTypeName);
  }

  public boolean isSpringBean(@NotNull VirtualMachine vm, @Nullable Value value)
      throws BeanLookupException {
    return new Lookup().isSpringBean(vm, value);
  }

  public boolean matchesBeanType(@Nullable Value value, @NotNull String desired)
      throws BeanLookupException {
    return new Lookup().matchesBeanType(value, desired);
  }

  public static boolean isSpringProxy(@Nullable Value value) {
    return value instanceof ObjectReference object && SpringProxyReader.isAopProxy(object);
  }

  /** A fresh read cache for every operation, never retained across debugger resumes. */
  Lookup newLookup() { return new Lookup(); }

  final class Lookup {
    private final SpringProxyReader.ReadContext proxies = new SpringProxyReader.ReadContext();
    private final Map<ReferenceType, Map<String, Field>> fields = new HashMap<>();
    private final Map<ReferenceType, List<Field>> allFields = new HashMap<>();
    private final Map<Long, Optional<ArrayReference>> tables = new HashMap<>();
    private final Map<Long, Map<String, Value>> factoryCaches = new HashMap<>();
    private VirtualMachine discoveredVm;
    private List<ObjectReference> discoveredFactories;

    /** Searches all active Spring application contexts, including Boot contexts. */
    public @NotNull Value findBean(@NotNull VirtualMachine vm, @NotNull String requestedName)
        throws BeanLookupException {
      boolean dereferenceFactory = requestedName.startsWith("&");
      int firstNameCharacter = 0;
      while (firstNameCharacter < requestedName.length() && requestedName.charAt(firstNameCharacter) == '&')
        firstNameCharacter++;
      String name = requestedName.substring(firstNameCharacter);
      if (name.isBlank()) {
        throw new BeanLookupException(Reason.NOT_FOUND, "Inserisci un nome di bean Spring valido.");
      }
      Value matchingBean = null;
      boolean uncachedFactoryProduct = false;
      for (ObjectReference factory : beanFactories(vm)) {
        Map<String, Value> caches = factoryCaches(factory);
        String beanName = resolveAlias(asObject(caches.get("aliasMap")), name);
        Value singleton = findMapValue(asObject(caches.get("singletonObjects")), beanName);
        boolean factoryBean = singleton instanceof ObjectReference object
            && proxies.isAssignableTo(object, "org.springframework.beans.factory.FactoryBean");
        Value value = dereferenceFactory ? (factoryBean ? singleton : null)
            : findMapValue(asObject(caches.get("factoryBeanObjectCache")), beanName);
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
        Map<String, Value> caches = factoryCaches(factory);
        for (String field : List.of("singletonObjects", "factoryBeanObjectCache")) {
          if (visitMapValues(asObject(caches.get(field)), candidate ->
              candidate instanceof ObjectReference object && sameTarget(object, selected))) return true;
        }
      }
      return false;
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
        if (i >= limits.maxProxyDepth()) throw limit("Catena di proxy Spring troppo profonda");
        if (proxies.isAssignableTo(object, desired)) return true;
        object = proxies.singletonTarget(object);
      }
      return false;
    }

    private boolean sameTarget(ObjectReference candidate, ObjectReference selected) throws BeanLookupException {
      Set<Long> seen = new HashSet<>();
      for (int i = 0; candidate != null && seen.add(candidate.uniqueID()); i++) {
        if (i >= limits.maxProxyDepth()) throw limit("Catena di proxy Spring troppo profonda");
        if (candidate.equals(selected)) return true;
        candidate = proxies.singletonTarget(candidate);
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
      Map<String, Value> caches = factoryCaches(factory);
      for (String field : List.of("singletonObjects", "factoryBeanObjectCache")) {
        for (Value value : mapValues(asObject(caches.get(field)))) {
          if (value instanceof ObjectReference object) result.add(object);
        }
      }
      return result;
    }

    private List<ObjectReference> beanFactories(VirtualMachine vm) throws BeanLookupException {
      if (vm.equals(discoveredVm) && discoveredFactories != null) return discoveredFactories;
      List<ObjectReference> factories = discoverBeanFactories(vm);
      discoveredVm = vm;
      discoveredFactories = factories;
      return factories;
    }

    private List<ObjectReference> discoverBeanFactories(VirtualMachine vm) throws BeanLookupException {
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
        List<ObjectReference> contexts = type.instances(limits.maxContextInstancesPerClass() + 1);
        if (contexts.size() > limits.maxContextInstancesPerClass()) {
          throw limit("Troppi contesti Spring della classe " + type.name());
        }
        for (ObjectReference context : contexts) {
          try {
            if (!contextsSeen.add(context.uniqueID())) continue;
            Map<String, Value> state = fieldValues(context, "active", "beanFactory");
            if (!isActive(context, state.get("active"))) continue;
            activeContext = true;
            ObjectReference factory = asObject(state.get("beanFactory"));
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

    private boolean isActive(ObjectReference context, Value flag) throws BeanLookupException {
      if (flag instanceof BooleanValue bool) return bool.booleanValue();
      if (flag instanceof ObjectReference atomic) {
        Value value = fieldValue(atomic, "value");
        if (value instanceof BooleanValue bool) return bool.booleanValue();
        if (value instanceof IntegerValue integer) return integer.intValue() != 0;
      }
      throw new BeanLookupException(Reason.UNAVAILABLE,
          "Impossibile leggere lo stato del contesto Spring " + context.referenceType().name() + ".");
    }

    private String resolveAlias(ObjectReference aliases, String requested) throws BeanLookupException {
      String name = requested;
      Set<String> seen = new HashSet<>();
      int aliasesFollowed = 0;
      while (seen.add(name)) {
        Value target = findMapValue(aliases, name);
        if (!(target instanceof StringReference text)) return name;
        if (++aliasesFollowed > limits.maxAliasDepth()) throw limit("Catena di alias Spring troppo profonda");
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
          if (nodes.size() > limits.maxMapEntries()) throw limit("Troppi elementi nella cache Spring");
          Map<String, Value> values = fieldValues(node, "key", "val", "value", "next");
          Value nodeKey = values.get("key");
          if (nodeKey instanceof StringReference text && key.equals(text.value())) {
            Value content = values.get("val");
            return content != null ? content : values.get("value");
          }
          node = asObject(values.get("next"));
        }
        return null;
      }
      return null;
    }

    /** Full bounded scan used only for identity/type discovery; limits are explicit. */
    private List<Value> mapValues(@Nullable ObjectReference map) throws BeanLookupException {
      List<Value> result = new ArrayList<>();
      visitMapValues(map, value -> { result.add(value); return false; });
      return result;
    }

    @FunctionalInterface
    private interface ValueVisitor {
      boolean visit(Value value) throws BeanLookupException;
    }

    private boolean visitMapValues(@Nullable ObjectReference map, ValueVisitor visitor)
        throws BeanLookupException {
      ArrayReference root = mapTable(map);
      if (root == null) return false;
      ArrayDeque<ArrayReference> pending = new ArrayDeque<>();
      pending.add(root);
      Set<Long> tables = new HashSet<>();
      Set<Long> nodes = new HashSet<>();
      int bucketsExamined = 0;
      while (!pending.isEmpty()) {
        ArrayReference table = pending.removeFirst();
        if (!tables.add(table.uniqueID())) continue;
        if (table.length() > limits.maxMapBuckets() - bucketsExamined) throw limit("Cache Spring troppo grande per una ricerca completa");
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
              if (nodes.size() > limits.maxMapEntries()) throw limit("Troppi elementi nella cache Spring");
              Map<String, Value> values = fieldValues(node, "val", "value", "next");
              Value content = values.get("val");
              if (content == null) content = values.get("value");
              if (visitor.visit(content)) return true;
              node = asObject(values.get("next"));
            }
          }
        }
      }
      return false;
    }

    private @Nullable ArrayReference mapTable(@Nullable ObjectReference map) throws BeanLookupException {
      if (map == null) return null;
      Optional<ArrayReference> cached = tables.get(map.uniqueID());
      if (cached != null) return cached.orElse(null);
      ArrayReference table = readMapTable(map);
      tables.put(map.uniqueID(), Optional.ofNullable(table));
      return table;
    }

    private @Nullable ArrayReference readMapTable(ObjectReference map) throws BeanLookupException {
      String declaration = proxies.isAssignableTo(map, "java.util.concurrent.ConcurrentHashMap")
          ? "java.util.concurrent.ConcurrentHashMap"
          : proxies.isAssignableTo(map, "java.util.HashMap") ? "java.util.HashMap" : null;
      if (declaration == null) {
        throw new BeanLookupException(Reason.UNAVAILABLE,
            "Struttura della cache Spring non supportata: " + map.referenceType().name() + ".");
      }
      for (Field field : allFields.computeIfAbsent(map.referenceType(), ReferenceType::allFields)) {
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
      Field field = fieldMap(object.referenceType()).get(name);
      return field == null ? null : object.getValue(field);
    }

    private Map<String, Value> factoryCaches(ObjectReference factory) {
      return factoryCaches.computeIfAbsent(factory.uniqueID(), ignored ->
          fieldValues(factory, "aliasMap", "singletonObjects", "factoryBeanObjectCache"));
    }

    private Map<String, Value> fieldValues(ObjectReference object, String... names) {
      Map<String, Field> available = fieldMap(object.referenceType());
      List<Field> selected = new ArrayList<>(names.length);
      for (String name : names) {
        Field field = available.get(name);
        if (field != null) selected.add(field);
      }
      Map<Field, Value> read = object.getValues(selected);
      Map<String, Value> values = new HashMap<>();
      for (Field field : selected) values.put(field.name(), read.get(field));
      return values;
    }

    private Map<String, Field> fieldMap(ReferenceType type) {
      return fields.computeIfAbsent(type, ignored -> {
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field field : allFields.computeIfAbsent(type, ReferenceType::allFields)) {
          if (field.isStatic()) continue;
          // Prefer actual framework state over custom subclass shadow fields.
          Field existing = byName.get(field.name());
          if (existing == null || (field.declaringType().name().startsWith("org.springframework.")
              && !existing.declaringType().name().startsWith("org.springframework.")))
            byName.put(field.name(), field);
        }
        return byName;
      });
    }
  }
}
