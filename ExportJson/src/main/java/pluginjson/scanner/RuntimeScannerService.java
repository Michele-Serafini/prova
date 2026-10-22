package pluginjson.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.jdi.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An intentionally non-invasive snapshot of values in a suspended JVM.
 *
 * Only JDI field/array reads are used. Never invoke toString(), getters,
 * Jackson, application code or Spring methods on the debuggee. JDK container
 * layouts are recognized explicitly; unknown layouts are identified rather
 * than misrepresented as their implementation fields.
 *
 * Each instance is for ONE export on IntelliJ's debugger manager thread.
 */
public final class RuntimeScannerService {
  private static final Pattern SECRET_FIELD = Pattern.compile(
      "(?i).*(password|passwd|pwd|passphrase|secret|token|credential|authorization|"
          + "api[_-]?key|private[_-]?key|access[_-]?key|client[_-]?secret).*"
  );
  private static final int BATCH_SIZE = 256;
  private static final int MAX_STRING_LENGTH = 100_000;
  private static final int MAX_LAYOUT_SLOTS = 100_000;

  private final int maxDepth;
  private final int maxItems;
  private final int maxObjects;
  private final int maxValues;
  private final int maxTextUnits;
  private final Map<Long, String> firstPaths = new HashMap<>();
  private int objectCount;
  private int valueCount;
  private int textUnits;

  public RuntimeScannerService() { this(false); }

  public RuntimeScannerService(boolean springBeanMode) {
    maxDepth = springBeanMode ? 16 : 40;
    maxItems = springBeanMode ? 2_000 : 10_000;
    maxObjects = springBeanMode ? 20_000 : 50_000;
    maxValues = springBeanMode ? 80_000 : 200_000;
    maxTextUnits = springBeanMode ? 2_000_000 : 5_000_000;
  }

  public @NotNull JsonElement scanValue(@Nullable Value value) {
    // A scanner can be reused without accidentally sharing references or limits.
    firstPaths.clear();
    objectCount = 0;
    valueCount = 0;
    textUnits = 0;
    return scan(value, 0, "#");
  }

  private JsonElement scan(@Nullable Value value, int depth, String path) {
    if (++valueCount > maxValues) return marker("$truncated", "Maximum visited value count (" + maxValues + ") reached");
    if (value == null) return JsonNull.INSTANCE;
    if (depth > maxDepth) return marker("$truncated", "Maximum depth (" + maxDepth + ") reached");
    try {
      if (value instanceof StringReference s) return textUnits >= maxTextUnits
          ? marker("$truncated", "Maximum snapshot text budget reached") : stringValue(s.value());
      if (value instanceof BooleanValue v) return new JsonPrimitive(v.booleanValue());
      if (value instanceof ByteValue v) return new JsonPrimitive(v.byteValue());
      if (value instanceof ShortValue v) return new JsonPrimitive(v.shortValue());
      if (value instanceof IntegerValue v) return new JsonPrimitive(v.intValue());
      if (value instanceof LongValue v) return new JsonPrimitive(v.longValue());
      if (value instanceof FloatValue v) return Float.isFinite(v.floatValue())
          ? new JsonPrimitive(v.floatValue()) : new JsonPrimitive(String.valueOf(v.floatValue()));
      if (value instanceof DoubleValue v) return finiteNumber(v.doubleValue());
      if (value instanceof CharValue v) return new JsonPrimitive(String.valueOf(v.charValue()));
      if (value instanceof ObjectReference object) {
        if (isTransparentWrapper(object)) {
          Value content = object;
          int wrapperDepth = depth;
          Set<Long> wrappers = new HashSet<>();
          while (content instanceof ObjectReference wrapper && isTransparentWrapper(wrapper)) {
            if (!wrappers.add(wrapper.uniqueID())) return skipped(wrapper.referenceType().name(),
                "Cycle through transparent Optional/AtomicReference wrappers has no JSON payload");
            if (++wrapperDepth > maxDepth) return marker("$truncated", "Maximum depth (" + maxDepth + ") reached");
            content = fieldValue(wrapper, "value");
          }
          // Track the actual payload rather than wrapper identities. Otherwise
          // aliases may point at a JSON location that contains only another $ref.
          return scan(content, wrapperDepth, path);
        }
        // Boxed numbers/immutable scalar values must remain JSON scalars even
        // when the JDK reuses their instances (e.g. Integer.valueOf(1)).
        // Otherwise an innocent repeated integer would turn into a $ref.
        if (!(object instanceof ArrayReference)) {
          String type = object.referenceType().name();
          if (isScalarType(object, type)) {
            JsonElement scalar = scanScalar(object, type, depth, path);
            if (scalar != null) return scalar;
          }
        }
        String previousPath = firstPaths.get(object.uniqueID());
        if (previousPath != null) return marker("$ref", previousPath);
        if (++objectCount > maxObjects) {
          return marker("$truncated", "Maximum object count (" + maxObjects + ") reached");
        }
        firstPaths.put(object.uniqueID(), path);
        if (object instanceof ArrayReference array) return scanArray(array, depth, path);
        // Unwrap at every level, including injected dependencies and proxies
        // reached through ordinary local variables. Track both proxy and target
        // IDs so cycles and shared targets retain correct JSON pointers.
        if (SpringProxyReader.isAopProxy(object)) {
          ObjectReference target = SpringProxyReader.singletonTarget(object);
          if (target == null) return skipped(object.referenceType().name(),
              "Spring proxy target unavailable without invoking application code");
          return scan(target, depth + 1, path);
        }
        return scanObject(object, depth, path);
      }
      return marker("$unsupported", "Unsupported JDI value: " + value.type().name());
    } catch (RuntimeException error) {
      return marker("$error", error.getClass().getSimpleName() + ": " + safeMessage(error));
    }
  }

  private JsonElement scanObject(ObjectReference object, int depth, String path) {
    String type = object.referenceType().name();
    if (type.equals("org.springframework.beans.factory.support.NullBean")) return JsonNull.INSTANCE;
    if (type.startsWith("org.springframework.context.")
        || type.startsWith("org.springframework.beans.factory.")
        || type.startsWith("org.springframework.core.env.")
        || isAssignableTo(object, "org.springframework.context.ApplicationContext")
        || isAssignableTo(object, "org.springframework.beans.factory.BeanFactory")
        || isAssignableTo(object, "org.springframework.core.env.Environment")) {
      return skipped(type, "Spring framework infrastructure");
    }
    if (type.equals("java.lang.Class") || isAssignableTo(object, "java.lang.Thread")
        || isAssignableTo(object, "java.lang.ClassLoader")) {
      return skipped(type, "JVM infrastructure");
    }

    JsonElement scalar = scanScalar(object, type, depth, path);
    if (scalar != null) return scalar;

    if (isAssignableTo(object, "java.util.Map")) return scanMap(object, depth, path);
    if (isAssignableTo(object, "java.util.Collection")) return scanCollection(object, depth, path);

    // Do not descend into arbitrary Java framework implementations. Their
    // internal fields are not a meaningful JSON representation.
    if (type.startsWith("java.") || type.startsWith("jdk.")
        || type.startsWith("sun.")) {
      return skipped(type, "Unsupported JDK object layout");
    }
    return scanPojo(object, depth, path);
  }

  private @Nullable JsonElement scanScalar(ObjectReference object, String type,
                                            int depth, String path) {
    if (object instanceof ClassObjectReference classObject) {
      return stringValue(classObject.reflectedType().name());
    }
    if (isWrapper(type)) return scan(fieldValue(object, "value"), depth + 1, path);
    if (isAssignableTo(object, "java.lang.Enum")) {
      Value name = fieldValue(object, "name");
      if (name instanceof StringReference s) return stringValue(s.value());
    }
    if (type.equals("java.lang.StringBuilder") || type.equals("java.lang.StringBuffer")) {
      return scanMutableString(object);
    }
    if (type.equals("java.util.Currency")) {
      String code = stringField(object, "currencyCode");
      if (code != null) return stringValue(code);
    }
    if (type.equals("java.net.URI")) {
      String uri = stringField(object, "string");
      if (uri != null) return stringValue(uri);
    }
    if (type.equals("java.io.File")) {
      String filePath = stringField(object, "path");
      if (filePath != null) return stringValue(filePath);
    }
    if (type.equals("java.util.regex.Pattern")) {
      String regex = stringField(object, "pattern");
      if (regex != null) return stringValue(regex);
    }
    if (type.equals("java.util.UUID")) {
      Long msb = longField(object, "mostSigBits");
      Long lsb = longField(object, "leastSigBits");
      if (msb != null && lsb != null) return new JsonPrimitive(new UUID(msb, lsb).toString());
    }
    if (type.equals("java.math.BigInteger")) {
      if (textUnits >= maxTextUnits) return marker("$truncated", "Maximum snapshot text budget reached");
      BigInteger value = readBigInteger(object);
      if (value != null) return numericValue(value);
    }
    if (type.equals("java.math.BigDecimal")) {
      if (textUnits >= maxTextUnits) return marker("$truncated", "Maximum snapshot text budget reached");
      BigDecimal value = readBigDecimal(object);
      if (value != null) return numericValue(value);
    }
    if (type.equals("java.util.OptionalInt") || type.equals("java.util.OptionalLong")
        || type.equals("java.util.OptionalDouble")) {
      if (!Boolean.TRUE.equals(booleanField(object, "isPresent"))) return JsonNull.INSTANCE;
      return scan(fieldValue(object, "value"), depth + 1, path);
    }
    if (type.equals("java.util.concurrent.atomic.AtomicBoolean")) {
      Integer value = intField(object, "value");
      return value == null ? null : new JsonPrimitive(value != 0);
    }
    if (type.equals("java.util.concurrent.atomic.AtomicInteger")
        || type.equals("java.util.concurrent.atomic.AtomicLong")) {
      return scan(fieldValue(object, "value"), depth + 1, path);
    }
    try {
      String formatted = formatDateTime(object, type);
      if (formatted != null) return new JsonPrimitive(formatted);
    } catch (RuntimeException ignored) {
      // A partially initialized temporal object is represented as unsupported.
    }
    return null;
  }

  private JsonElement scanArray(ArrayReference array, int depth, String path) {
    JsonArray result = new JsonArray();
    int limit = Math.min(array.length(), maxItems);
    for (int start = 0; start < limit; start += BATCH_SIZE) {
      int amount = Math.min(BATCH_SIZE, limit - start);
      List<Value> batch = array.getValues(start, amount);
      for (int i = 0; i < batch.size(); i++) {
        int index = start + i;
        result.add(scan(batch.get(i), depth + 1, append(path, String.valueOf(index))));
      }
    }
    if (array.length() > limit) result.add(marker("$truncated", array.length() - limit + " more elements"));
    return result;
  }

  private JsonElement scanCollection(ObjectReference object, int depth, String path) {
    // Delegate wrappers are traversed without running any methods in the JVM.
    ObjectReference actual = unwrapCollection(object, false);
    List<Value> elements = readCollectionElements(actual);
    if (elements == null) return skipped(object.referenceType().name(),
        "Collection layout unsupported, inconsistent, or beyond the inspection budget");
    JsonArray result = new JsonArray();
    int size = Math.min(elements.size(), maxItems);
    for (int i = 0; i < size; i++) {
      result.add(scan(elements.get(i), depth + 1, append(path, String.valueOf(i))));
    }
    if (elements.size() > size) result.add(marker("$truncated", collectionRemainder(actual, size)));
    return result;
  }

  private @Nullable List<Value> readCollectionElements(ObjectReference object) {
    String type = object.referenceType().name();
    if (type.startsWith("java.util.Collections$Empty")) return List.of();
    if (type.equals("java.util.Collections$SingletonList") || type.equals("java.util.Collections$SingletonSet")) {
      return one(fieldValue(object, "element"));
    }
    if (type.equals("java.util.ImmutableCollections$List12") || type.equals("java.util.ImmutableCollections$Set12")) {
      List<Value> out = new ArrayList<>();
      out.add(fieldValue(object, "e0"));
      Value second = fieldValue(object, "e1");
      if (second != null && !isEmptySentinel(second)) out.add(second);
      return out;
    }
    if (type.equals("java.util.ImmutableCollections$ListN")) {
      ArrayReference array = arrayField(object, "elements");
      return array == null ? null : take(array, maxItems + 1);
    }
    if (type.equals("java.util.ImmutableCollections$SetN")) {
      ArrayReference array = arrayField(object, "elements");
      if (array == null || array.length() > MAX_LAYOUT_SLOTS) return null;
      List<Value> out = new ArrayList<>();
      // SetN is an open-addressed sparse table, not a compact element array.
      for (int start = 0; start < array.length() && out.size() <= maxItems; start += BATCH_SIZE) {
        for (Value value : array.getValues(start, Math.min(BATCH_SIZE, array.length() - start))) {
          if (value != null) out.add(value);
          if (out.size() > maxItems) break;
        }
      }
      return out;
    }
    if (type.startsWith("java.util.") && type.endsWith("$SubList")) {
      ObjectReference root = asObject(fieldValue(object, "root"));
      if (root == null) root = asObject(fieldValue(object, "l"));
      Integer offset = intField(object, "offset"), size = intField(object, "size");
      if (root == null || offset == null || size == null || offset < 0 || size < 0) return null;
      ArrayReference storage = listStorage(root);
      if (storage != null) {
        if ((long) offset + size > storage.length()) return null;
        return slice(storage, offset, Math.min(size, maxItems + 1));
      }
      // Do not claim a complete slice when the bounded root snapshot omitted
      // data before or within this sublist.
      List<Value> items = readCollectionElements(root);
      if (items == null || (long) offset + size > items.size()) return null;
      return new ArrayList<>(items.subList(offset, offset + Math.min(size, maxItems + 1)));
    }
    if (isAssignableTo(object, "java.util.ArrayList") || type.equals("java.util.Arrays$ArrayList")
        || isAssignableTo(object, "java.util.Vector")
        || type.equals("java.util.concurrent.CopyOnWriteArrayList")) {
      ArrayReference array = listStorage(object);
      if (array == null) return null;
      Integer size = intField(object, "size");
      if (size == null) size = intField(object, "elementCount");
      if (size != null && (size < 0 || size > array.length())) return null;
      return take(array, Math.min(size == null ? array.length() : size, maxItems + 1));
    }
    if (type.equals("java.util.RegularEnumSet") || type.equals("java.util.JumboEnumSet")) {
      ArrayReference universe = arrayField(object, "universe");
      if (universe == null || universe.length() > MAX_LAYOUT_SLOTS) return null;
      Long bits = longField(object, "elements");
      ArrayReference words = arrayField(object, "elements");
      if (bits == null && words == null) return null;
      List<Value> out = new ArrayList<>();
      for (int i = 0; i < universe.length() && out.size() <= maxItems; i++) {
        Long mask = bits;
        if (words != null) {
          if (i / 64 >= words.length() || !(words.getValue(i / 64) instanceof LongValue word)) return null;
          mask = word.longValue();
        }
        if ((mask & (1L << (i % 64))) != 0) out.add(universe.getValue(i));
      }
      return out;
    }
    if (isAssignableTo(object, "java.util.LinkedList")) {
      return traverseNodes(asObject(fieldValue(object, "first")), "item", "next", false);
    }
    if (type.equals("java.util.concurrent.ConcurrentLinkedQueue")
        || type.equals("java.util.concurrent.ConcurrentLinkedDeque")
        || type.equals("java.util.concurrent.LinkedBlockingQueue")
        || type.equals("java.util.concurrent.LinkedBlockingDeque")) {
      ObjectReference head = asObject(firstPresent(object, "head", "first"));
      List<Value> out = traverseNodes(head, "item", "next", false);
      // Concurrent queues retain dummy/deleted nodes with null item fields;
      // these collections themselves prohibit null elements.
      if (out != null) out.removeIf(Objects::isNull);
      return out;
    }
    if (type.equals("java.util.ArrayDeque")) {
      ArrayReference array = arrayField(object, "elements");
      Integer head = intField(object, "head"), tail = intField(object, "tail");
      if (array == null || head == null || tail == null || array.length() == 0
          || head < 0 || tail < 0 || head >= array.length() || tail >= array.length()) return null;
      int size = (tail - head + array.length()) % array.length();
      return circularSlice(array, head, Math.min(size, maxItems + 1));
    }
    if (type.equals("java.util.concurrent.ArrayBlockingQueue")) {
      ArrayReference array = arrayField(object, "items");
      Integer head = intField(object, "takeIndex"), count = intField(object, "count");
      if (array == null || head == null || count == null || head < 0 || head >= array.length()
          || count < 0 || count > array.length()) return null;
      return circularSlice(array, head, Math.min(count, maxItems + 1));
    }
    if (isAssignableTo(object, "java.util.PriorityQueue")
        || type.equals("java.util.concurrent.PriorityBlockingQueue")) {
      ArrayReference array = arrayField(object, "queue");
      Integer size = intField(object, "size");
      if (array == null || size == null || size < 0 || size > array.length()) return null;
      return take(array, Math.min(size, maxItems + 1));
    }
    if (isAssignableTo(object, "java.util.Set")) {
      ObjectReference map = asObject(fieldValue(object, "map"));
      if (map == null) map = asObject(fieldValue(object, "m"));
      if (map != null) {
        List<MapEntry> entries = readMapEntries(unwrapCollection(map, true));
        if (entries != null) {
          List<Value> out = new ArrayList<>();
          for (MapEntry entry : entries) out.add(entry.key());
          return out;
        }
      }
    }
    return null;
  }

  private @Nullable ArrayReference listStorage(ObjectReference object) {
    String type = object.referenceType().name();
    if (!isAssignableTo(object, "java.util.ArrayList") && !isAssignableTo(object, "java.util.Vector")
        && !type.equals("java.util.Arrays$ArrayList")
        && !type.equals("java.util.concurrent.CopyOnWriteArrayList")
        && !type.equals("java.util.ImmutableCollections$ListN")) return null;
    ArrayReference array = arrayField(object, "elementData");
    if (array == null) array = arrayField(object, "a");
    if (array == null) array = arrayField(object, "array");
    if (array == null) array = arrayField(object, "elements");
    return array;
  }

  private List<Value> circularSlice(ArrayReference array, int start, int count) {
    List<Value> out = new ArrayList<>();
    int beforeWrap = Math.min(count, array.length() - start);
    out.addAll(slice(array, start, beforeWrap));
    if (beforeWrap < count) out.addAll(slice(array, 0, count - beforeWrap));
    return out;
  }

  private String collectionRemainder(ObjectReference object, int exported) {
    Integer size = intField(object, "size");
    if (size == null) size = intField(object, "elementCount");
    if (size == null) size = intField(object, "count");
    if (size == null && object.referenceType().name().equals("java.util.concurrent.CopyOnWriteArrayList")) {
      ArrayReference array = arrayField(object, "array");
      if (array != null) size = array.length();
    }
    return size != null && size > exported ? (size - exported) + " more elements"
        : "Additional elements omitted (limit " + maxItems + ")";
  }

  private JsonElement scanMap(ObjectReference object, int depth, String path) {
    ObjectReference actual = unwrapCollection(object, true);
    List<MapEntry> entries = readMapEntries(actual);
    if (entries == null) return skipped(object.referenceType().name(),
        "Map layout unsupported, inconsistent, or beyond the inspection budget");
    // Identity maps can contain two distinct String objects with equal text;
    // JSON object properties would merge those entries and lose data.
    boolean stringKeys = !isAssignableTo(actual, "java.util.IdentityHashMap");
    Set<String> keyTexts = new HashSet<>();
    for (MapEntry entry : entries) {
      if (!(entry.key() instanceof StringReference text)) { stringKeys = false; break; }
      String key = text.value();
      if (key.length() > MAX_STRING_LENGTH || !keyTexts.add(key)) { stringKeys = false; break; }
    }
    boolean truncated = entries.size() > maxItems;
    int total = Math.min(entries.size(), maxItems);
    // Avoid overwriting a legitimate map entry named "$truncated".
    boolean metadataConflict = truncated && entries.stream()
        .anyMatch(e -> e.key() instanceof StringReference s && s.value().equals("$truncated"));
    if (stringKeys && !metadataConflict) {
      JsonObject result = new JsonObject();
      for (int i = 0; i < total; i++) {
        MapEntry entry = entries.get(i);
        String key = ((StringReference) entry.key()).value();
        if (!reserveText(key.length())) return appendTruncation(result, "Maximum snapshot text budget reached while reading map keys");
        result.add(key, isSensitive(key) ? redacted()
            : scan(entry.value(), depth + 1, append(path, key)));
      }
      if (truncated) result.addProperty("$truncated", remainder(actual, total));
      return result;
    }
    // JSON object keys are *always* strings. Preserve numeric/object/null
    // keys in an explicit entry list instead of silently stringifying them.
    JsonObject result = new JsonObject();
    result.addProperty("$type", "map");
    JsonArray jsonEntries = new JsonArray();
    result.add("$entries", jsonEntries);
    int i = 0;
    for (int entryIndex = 0; entryIndex < total; entryIndex++) {
      MapEntry entry = entries.get(entryIndex);
      String entryPath = append(append(path, "$entries"), String.valueOf(i++));
      JsonObject item = new JsonObject();
      item.add("key", scan(entry.key(), depth + 1, append(entryPath, "key")));
      item.add("value", entry.key() instanceof StringReference s && isSensitive(s.value())
          ? redacted() : scan(entry.value(), depth + 1, append(entryPath, "value")));
      jsonEntries.add(item);
    }
    if (truncated) result.addProperty("$truncated", remainder(actual, total));
    return result;
  }

  private @Nullable List<MapEntry> readMapEntries(ObjectReference object) {
    String type = object.referenceType().name();
    List<MapEntry> out = new ArrayList<>();
    if (type.startsWith("java.util.Collections$Empty")
        || type.equals("java.util.ImmutableCollections$MapN") && arrayField(object, "table") == null) {
      return out;
    }
    if (type.equals("java.util.Collections$SingletonMap")) {
      out.add(new MapEntry(fieldValue(object, "k"), fieldValue(object, "v")));
      return out;
    }
    if (type.equals("java.util.ImmutableCollections$Map1")) {
      out.add(new MapEntry(fieldValue(object, "k0"), fieldValue(object, "v0")));
      return out;
    }
    if (type.equals("java.util.ImmutableCollections$MapN")) {
      ArrayReference table = arrayField(object, "table");
      if (table == null || table.length() > MAX_LAYOUT_SLOTS) return null;
      int length = table.length();
      for (int i = 0; i + 1 < length && out.size() <= maxItems; i += 2) {
        Value key = table.getValue(i);
        if (key != null) out.add(new MapEntry(key, table.getValue(i + 1)));
      }
      return out;
    }
    if (isAssignableTo(object, "java.util.EnumMap")) {
      ArrayReference keys = arrayField(object, "keyUniverse"), values = arrayField(object, "vals");
      if (keys == null || values == null || keys.length() != values.length()
          || keys.length() > MAX_LAYOUT_SLOTS) return null;
      Value nullSentinel = staticField(object, "java.util.EnumMap", "NULL");
      for (int i = 0; i < keys.length() && out.size() <= maxItems; i++) {
        Value value = values.getValue(i);
        if (value != null) out.add(new MapEntry(keys.getValue(i), value.equals(nullSentinel) ? null : value));
      }
      return out;
    }
    if (isAssignableTo(object, "java.util.IdentityHashMap")) {
      ArrayReference table = arrayField(object, "table");
      if (table == null || table.length() > MAX_LAYOUT_SLOTS) return null;
      Value nullSentinel = staticField(object, "java.util.IdentityHashMap", "NULL_KEY");
      for (int i = 0; i + 1 < table.length() && out.size() <= maxItems; i += 2) {
        Value key = table.getValue(i);
        if (key != null) out.add(new MapEntry(key.equals(nullSentinel) ? null : key, table.getValue(i + 1)));
      }
      return out;
    }
    if (isAssignableTo(object, "java.util.TreeMap")) {
      // Iterative inorder traversal preserves sorted key order without recursion.
      Deque<ObjectReference> stack = new ArrayDeque<>();
      Set<Long> seen = new HashSet<>();
      ObjectReference node = asObject(fieldValue(object, "root"));
      while ((node != null || !stack.isEmpty()) && out.size() <= maxItems) {
        while (node != null && seen.add(node.uniqueID())) {
          stack.push(node);
          node = asObject(fieldValue(node, "left"));
        }
        if (stack.isEmpty()) break;
        node = stack.pop();
        out.add(new MapEntry(fieldValue(node, "key"), fieldValue(node, "value")));
        node = asObject(fieldValue(node, "right"));
      }
      return out;
    }
    if (isAssignableTo(object, "java.util.LinkedHashMap")) {
      ObjectReference head = asObject(fieldValue(object, "head"));
      if (head == null && Integer.valueOf(0).equals(intField(object, "size"))) return out;
      if (head != null) {
        Set<Long> seen = new HashSet<>();
        while (head != null && seen.add(head.uniqueID()) && out.size() <= maxItems) {
          out.add(new MapEntry(fieldValue(head, "key"), fieldValue(head, "value")));
          head = asObject(fieldValue(head, "after"));
        }
        return out;
      }
    }
    // Only inspect documented JDK layouts, never an arbitrary user Map's
    // coincidentally named 'table' field.
    boolean weak = isAssignableTo(object, "java.util.WeakHashMap");
    boolean concurrent = isAssignableTo(object, "java.util.concurrent.ConcurrentHashMap");
    if (!weak && !concurrent && !isAssignableTo(object, "java.util.HashMap")
        && !isAssignableTo(object, "java.util.Hashtable")) return null;
    ArrayReference table = arrayField(object, "table");
    if (table == null) {
      if (Integer.valueOf(0).equals(intField(object, "size"))
          || concurrent && fieldValue(object, "baseCount") instanceof LongValue count && count.longValue() == 0) return out;
      return null;
    }
    if (table.length() > MAX_LAYOUT_SLOTS) return null;
    Value nullSentinel = weak ? staticField(object, "java.util.WeakHashMap", "NULL_KEY") : null;
    Set<Long> seen = new HashSet<>();
    int examined = 0;
    for (int i = 0; i < table.length() && out.size() <= maxItems; i++) {
      ObjectReference node = asObject(table.getValue(i));
      if (node != null && node.referenceType().name().endsWith("$ForwardingNode")) return null;
      if (node != null && node.referenceType().name().endsWith("$TreeBin")) {
        node = asObject(fieldValue(node, "first"));
      }
      while (node != null && seen.add(node.uniqueID()) && out.size() <= maxItems) {
        if (++examined > MAX_LAYOUT_SLOTS) return null;
        Value key = fieldValue(node, weak ? "referent" : "key");
        // A cleared weak reference is a stale entry; NULL_KEY is a real null key.
        if (!weak || key != null) out.add(new MapEntry(key != null && key.equals(nullSentinel) ? null : key,
            firstPresent(node, "value", "val")));
        node = asObject(fieldValue(node, "next"));
      }
    }
    return out;
  }

  private String remainder(ObjectReference object, int exported) {
    Integer declaredSize = intField(object, "size");
    return declaredSize != null && declaredSize > exported
        ? (declaredSize - exported) + " more entries"
        : "Additional entries omitted (limit " + maxItems + ")";
  }

  private ObjectReference unwrapCollection(ObjectReference object, boolean map) {
    Set<Long> seen = new HashSet<>();
    ObjectReference current = object;
    for (int i = 0; i < 6 && seen.add(current.uniqueID()); i++) {
      String type = current.referenceType().name();
      if (!map && type.equals("java.util.concurrent.CopyOnWriteArraySet")) {
        ObjectReference list = asObject(fieldValue(current, "al"));
        if (list == null) break;
        current = list;
        continue;
      }
      if (map && type.equals("java.util.Properties")) {
        ObjectReference entries = asObject(fieldValue(current, "map"));
        if (entries != null) { current = entries; continue; }
      }
      if (!(type.startsWith("java.util.Collections$")
          && (type.contains("Unmodifiable") || type.contains("Synchronized") || type.contains("Checked")))) break;
      Value candidate = map ? firstPresent(current, "m", "sm")
          : firstPresent(current, "list", "c", "ss", "s");
      if (!(candidate instanceof ObjectReference next)) break;
      current = next;
    }
    return current;
  }

  private @Nullable List<Value> traverseNodes(ObjectReference node, String elementField,
                                               String nextField, boolean skipHead) {
    List<Value> result = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    if (skipHead && node != null) node = asObject(fieldValue(node, nextField));
    int examined = 0;
    while (node != null && seen.add(node.uniqueID()) && result.size() <= maxItems) {
      if (++examined > MAX_LAYOUT_SLOTS) return null;
      Value element = fieldValue(node, elementField);
      if (element != null || !node.referenceType().name().startsWith("java.util.concurrent.")) result.add(element);
      node = asObject(fieldValue(node, nextField));
    }
    return result;
  }

  private JsonObject scanPojo(ObjectReference object, int depth, String path) {
    JsonObject result = new JsonObject();
    // allFields can include shadowed inherited names: qualify collisions.
    List<Field> fields = object.referenceType().allFields();
    Map<String, Integer> nameCount = new HashMap<>();
    for (Field f : fields) if (!f.isStatic() && !f.isSynthetic())
      nameCount.merge(f.name(), 1, Integer::sum);
    for (Field field : fields) {
      if (field.isStatic() || field.isSynthetic()) continue;
      String name = nameCount.get(field.name()) > 1
          ? field.declaringType().name() + "." + field.name() : field.name();
      if (!reserveText(name.length())) return appendTruncation(result, "Maximum snapshot text budget reached while reading field names");
      if (isSensitive(field.name())) {
        result.add(name, redacted());
        continue;
      }
      try {
        result.add(name, scan(object.getValue(field), depth + 1, append(path, name)));
      } catch (RuntimeException error) {
        result.add(name, marker("$error", error.getClass().getSimpleName() + ": " + safeMessage(error)));
      }
    }
    return result;
  }

  private static boolean isScalarType(ObjectReference object, String type) {
    return isWrapper(type) || type.equals("java.lang.Class")
        || isAssignableTo(object, "java.lang.Enum")
        || type.equals("java.lang.StringBuilder") || type.equals("java.lang.StringBuffer")
        || type.equals("java.util.Currency")
        || type.equals("java.net.URI") || type.equals("java.io.File")
        || type.equals("java.util.regex.Pattern")
        || type.equals("java.util.UUID") || type.equals("java.math.BigInteger")
        || type.equals("java.math.BigDecimal") || type.startsWith("java.time.")
        || type.equals("java.util.Date") || type.startsWith("java.sql.")
        || type.equals("java.util.concurrent.atomic.AtomicBoolean")
        || type.equals("java.util.concurrent.atomic.AtomicInteger") || type.equals("java.util.concurrent.atomic.AtomicLong")
        || type.startsWith("java.util.OptionalInt")
        || type.startsWith("java.util.OptionalLong") || type.startsWith("java.util.OptionalDouble");
  }

  private static boolean isTransparentWrapper(ObjectReference object) {
    String type = object.referenceType().name();
    return type.equals("java.util.Optional") || type.equals("java.util.concurrent.atomic.AtomicReference");
  }

  private static boolean isWrapper(String type) {
    return switch (type) {
      case "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
           "java.lang.Integer", "java.lang.Long", "java.lang.Float",
           "java.lang.Double", "java.lang.Character" -> true;
      default -> false;
    };
  }

  private static boolean isAssignableTo(ObjectReference object, String desired) {
    if (!(object.referenceType() instanceof ClassType clazz)) return false;
    for (ClassType current = clazz; current != null; current = current.superclass())
      if (desired.equals(current.name())) return true;
    for (InterfaceType iface : clazz.allInterfaces()) if (desired.equals(iface.name())) return true;
    return false;
  }

  private static @Nullable Field findField(ObjectReference object, String name) {
    Field fallback = null;
    for (Field field : object.referenceType().allFields()) {
      if (field.isStatic() || !field.name().equals(name)) continue;
      if (fallback == null) fallback = field;
      // Layout readers must not mistake a subclass's shadow field for the
      // private JDK storage (HashMap.table, ArrayList.size, Enum.name, etc.).
      String declaring = field.declaringType().name();
      if (declaring.startsWith("java.") || declaring.startsWith("jdk.")
          || declaring.startsWith("sun.")) return field;
    }
    return fallback;
  }
  private static @Nullable Value fieldValue(ObjectReference object, String name) {
    if (object == null) return null;
    Field field = findField(object, name);
    return field == null ? null : object.getValue(field);
  }
  private static @Nullable Value firstPresent(ObjectReference object, String... names) {
    for (String name : names) if (findField(object, name) != null) return fieldValue(object, name);
    return null;
  }
  private static @Nullable ObjectReference asObject(Value value) {
    return value instanceof ObjectReference object ? object : null;
  }
  private static @Nullable ArrayReference arrayField(ObjectReference object, String name) {
    Value value = fieldValue(object, name);
    return value instanceof ArrayReference array ? array : null;
  }
  private static @Nullable Integer intField(ObjectReference object, String name) {
    Value value = fieldValue(object, name);
    if (value instanceof IntegerValue v) return v.intValue();
    if (value instanceof ShortValue v) return (int) v.shortValue();
    if (value instanceof ByteValue v) return (int) v.byteValue();
    return null;
  }
  private static @Nullable Long longField(ObjectReference object, String name) {
    Value value = fieldValue(object, name);
    return value instanceof LongValue v ? v.longValue() : null;
  }
  private static @Nullable Boolean booleanField(ObjectReference object, String name) {
    Value value = fieldValue(object, name);
    return value instanceof BooleanValue v ? v.booleanValue() : null;
  }
  private static @Nullable String stringField(ObjectReference object, String name) {
    Value value = fieldValue(object, name);
    return value instanceof StringReference s ? s.value() : null;
  }
  private List<Value> take(ArrayReference array, int limit) {
    return slice(array, 0, Math.min(array.length(), Math.min(limit, maxItems + 1)));
  }
  private static List<Value> slice(ArrayReference array, int start, int count) {
    List<Value> out = new ArrayList<>();
    for (int offset = 0; offset < count; offset += BATCH_SIZE)
      out.addAll(array.getValues(start + offset, Math.min(BATCH_SIZE, count - offset)));
    return out;
  }

  private static @Nullable Value staticField(ObjectReference object, String declaringType, String name) {
    for (ReferenceType type : object.virtualMachine().classesByName(declaringType)) {
      Field field = type.fieldByName(name);
      if (field != null && field.isStatic()) return type.getValue(field);
    }
    return null;
  }

  private @Nullable JsonElement scanMutableString(ObjectReference object) {
    Integer count = intField(object, "count");
    ArrayReference array = arrayField(object, "value");
    if (count == null || array == null || count < 0) return null;
    int exported = Math.min(count, Math.min(MAX_STRING_LENGTH, Math.max(0, maxTextUnits - textUnits)));
    char[] chars = new char[exported];
    String component = ((ArrayType) array.referenceType()).componentTypeName();
    if (component.equals("char")) {
      if (count > array.length()) return null;
      List<Value> values = slice(array, 0, exported);
      for (int i = 0; i < exported; i++) {
        if (!(values.get(i) instanceof CharValue c)) return null;
        chars[i] = c.charValue();
      }
    } else if (component.equals("byte")) {
      Integer coder = intField(object, "coder");
      if (coder == null || coder < 0 || coder > 1 || (long) count << coder > array.length()) return null;
      int highShift = 0, lowShift = 0;
      if (coder == 1) {
        Value high = staticField(object, "java.lang.StringUTF16", "HI_BYTE_SHIFT");
        Value low = staticField(object, "java.lang.StringUTF16", "LO_BYTE_SHIFT");
        if (!(high instanceof IntegerValue h) || !(low instanceof IntegerValue l)) return null;
        highShift = h.intValue(); lowShift = l.intValue();
      }
      List<Value> values = slice(array, 0, exported << coder);
      for (int i = 0; i < exported; i++) {
        if (!(values.get(i << coder) instanceof ByteValue b)) return null;
        int first = Byte.toUnsignedInt(b.byteValue());
        if (coder == 0) chars[i] = (char) first;
        else {
          if (!(values.get((i << 1) + 1) instanceof ByteValue second)) return null;
          chars[i] = (char) ((first << highShift) | (Byte.toUnsignedInt(second.byteValue()) << lowShift));
        }
      }
    } else return null;
    String text = new String(chars);
    reserveText(exported);
    if (count == exported) return new JsonPrimitive(text);
    JsonObject shortened = new JsonObject();
    shortened.addProperty("$value", text);
    shortened.addProperty("$truncated", (count - exported) + " more characters"
        + (textUnits >= maxTextUnits ? " (snapshot text budget reached)" : ""));
    return shortened;
  }
  private static List<Value> one(Value value) {
    List<Value> out = new ArrayList<>(1);
    out.add(value);
    return out;
  }

  private static boolean isEmptySentinel(Value value) {
    if (!(value instanceof ObjectReference object)) return false;
    for (ReferenceType type : object.virtualMachine().classesByName("java.util.ImmutableCollections")) {
      for (Field field : type.fields()) {
        if (field.name().equals("EMPTY") && field.isStatic()) {
          return value.equals(type.getValue(field));
        }
      }
    }
    return false;
  }

  private static @Nullable BigInteger readBigInteger(ObjectReference number) {
    Integer sign = intField(number, "signum");
    ArrayReference magnitude = arrayField(number, "mag");
    if (sign == null || magnitude == null || magnitude.length() > 4096) return null;
    byte[] bytes = new byte[magnitude.length() * 4];
    for (int i = 0; i < magnitude.length(); i++) {
      Value item = magnitude.getValue(i);
      if (!(item instanceof IntegerValue part)) return null;
      int n = part.intValue();
      for (int b = 0; b < 4; b++) bytes[i * 4 + b] = (byte) (n >>> (24 - b * 8));
    }
    return new BigInteger(sign, bytes);
  }

  private static @Nullable BigDecimal readBigDecimal(ObjectReference decimal) {
    Integer scale = intField(decimal, "scale");
    Long compact = longField(decimal, "intCompact");
    if (scale == null || compact == null) return null;
    if (compact != Long.MIN_VALUE) return BigDecimal.valueOf(compact, scale);
    ObjectReference large = asObject(fieldValue(decimal, "intVal"));
    BigInteger unscaled = large == null ? null : readBigInteger(large);
    return unscaled == null ? null : new BigDecimal(unscaled, scale);
  }

  private static @Nullable LocalDate readDate(ObjectReference value) {
    Integer year = intField(value, "year"), month = intField(value, "month"), day = intField(value, "day");
    return year == null || month == null || day == null ? null : LocalDate.of(year, month, day);
  }
  private static @Nullable LocalTime readTime(ObjectReference value) {
    Integer hour = intField(value, "hour"), minute = intField(value, "minute"), second = intField(value, "second"), nano = intField(value, "nano");
    return hour == null || minute == null || second == null || nano == null ? null : LocalTime.of(hour, minute, second, nano);
  }
  private static @Nullable LocalDateTime readDateTime(ObjectReference value) {
    ObjectReference date = asObject(fieldValue(value, "date"));
    ObjectReference time = asObject(fieldValue(value, "time"));
    LocalDate d = date == null ? null : readDate(date);
    LocalTime t = time == null ? null : readTime(time);
    return d == null || t == null ? null : LocalDateTime.of(d, t);
  }
  private static @Nullable ZoneOffset readOffset(ObjectReference value) {
    Integer seconds = intField(value, "totalSeconds");
    return seconds == null ? null : ZoneOffset.ofTotalSeconds(seconds);
  }
  private static @Nullable String formatDateTime(ObjectReference object, String type) {
    switch (type) {
      case "java.time.Year" -> {
        Integer year = intField(object, "year");
        return year == null ? null : Year.of(year).toString();
      }
      case "java.time.YearMonth" -> {
        Integer year = intField(object, "year"), month = intField(object, "month");
        return year == null || month == null ? null : YearMonth.of(year, month).toString();
      }
      case "java.time.MonthDay" -> {
        Integer month = intField(object, "month"), day = intField(object, "day");
        return month == null || day == null ? null : MonthDay.of(month, day).toString();
      }
      case "java.time.OffsetTime" -> {
        ObjectReference time = asObject(fieldValue(object, "time")), offset = asObject(fieldValue(object, "offset"));
        LocalTime t = time == null ? null : readTime(time);
        ZoneOffset o = offset == null ? null : readOffset(offset);
        return t == null || o == null ? null : OffsetTime.of(t, o).toString();
      }
      case "java.time.LocalDate" -> { LocalDate d = readDate(object); return d == null ? null : d.toString(); }
      case "java.time.LocalTime" -> { LocalTime t = readTime(object); return t == null ? null : t.toString(); }
      case "java.time.LocalDateTime" -> { LocalDateTime t = readDateTime(object); return t == null ? null : t.toString(); }
      case "java.time.Instant" -> {
        Long seconds = longField(object, "seconds"); Integer nanos = intField(object, "nanos");
        return seconds == null || nanos == null ? null : Instant.ofEpochSecond(seconds, nanos).toString();
      }
      case "java.time.OffsetDateTime" -> {
        ObjectReference dateTime = asObject(fieldValue(object, "dateTime"));
        ObjectReference offset = asObject(fieldValue(object, "offset"));
        LocalDateTime dt = dateTime == null ? null : readDateTime(dateTime);
        ZoneOffset zo = offset == null ? null : readOffset(offset);
        return dt == null || zo == null ? null : OffsetDateTime.of(dt, zo).toString();
      }
      case "java.time.ZoneOffset" -> {
        ZoneOffset offset = readOffset(object); return offset == null ? null : offset.toString();
      }
      case "java.time.ZoneRegion" -> { return stringField(object, "id"); }
      case "java.time.ZonedDateTime" -> {
        ObjectReference dateTime = asObject(fieldValue(object, "dateTime"));
        ObjectReference offset = asObject(fieldValue(object, "offset"));
        ObjectReference zone = asObject(fieldValue(object, "zone"));
        LocalDateTime dt = dateTime == null ? null : readDateTime(dateTime);
        ZoneOffset zo = offset == null ? null : readOffset(offset);
        String zid = zone == null ? null : formatDateTime(zone, zone.referenceType().name());
        return dt == null || zo == null || zid == null ? null
            : OffsetDateTime.of(dt, zo) + (zid.equals(zo.toString()) ? "" : "[" + zid + "]");
      }
      case "java.time.Duration" -> {
        Long seconds = longField(object, "seconds"); Integer nanos = intField(object, "nanos");
        return seconds == null || nanos == null ? null : Duration.ofSeconds(seconds, nanos).toString();
      }
      case "java.time.Period" -> {
        Integer y = intField(object, "years"), m = intField(object, "months"), d = intField(object, "days");
        return y == null || m == null || d == null ? null : Period.of(y, m, d).toString();
      }
      default -> {
        if (type.equals("java.util.Date") || type.equals("java.sql.Date") || type.equals("java.sql.Time")
            || type.equals("java.sql.Timestamp")) {
          ObjectReference calendar = asObject(fieldValue(object, "cdate"));
          if (calendar != null && Boolean.FALSE.equals(booleanField(calendar, "normalized"))) return null;
          Long timestamp = longField(object, "fastTime");
          if (timestamp == null) return null;
          Instant instant = Instant.ofEpochMilli(timestamp);
          if (type.equals("java.sql.Timestamp")) {
            Integer nanos = intField(object, "nanos");
            if (nanos != null) instant = Instant.ofEpochSecond(Math.floorDiv(timestamp, 1000), nanos);
          }
          return instant.toString();
        }
        return null;
      }
    }
  }

  private static JsonElement finiteNumber(double value) {
    // JSON has no NaN or Infinity numeric literals.
    return Double.isFinite(value) ? new JsonPrimitive(value) : new JsonPrimitive(String.valueOf(value));
  }
  private JsonElement stringValue(String value) {
    int exported = Math.min(value.length(), Math.min(MAX_STRING_LENGTH, Math.max(0, maxTextUnits - textUnits)));
    reserveText(exported);
    if (exported == value.length()) return new JsonPrimitive(value);
    JsonObject shortened = new JsonObject();
    shortened.addProperty("$value", value.substring(0, exported));
    shortened.addProperty("$truncated", (value.length() - exported) + " more characters"
        + (textUnits >= maxTextUnits ? " (snapshot text budget reached)" : ""));
    return shortened;
  }
  private JsonElement numericValue(Number value) {
    return reserveText(value.toString().length()) ? new JsonPrimitive(value)
        : marker("$truncated", "Maximum snapshot text budget reached");
  }
  private boolean reserveText(int count) {
    if (count > maxTextUnits - textUnits) { textUnits = maxTextUnits; return false; }
    textUnits += count;
    return true;
  }
  private static JsonObject appendTruncation(JsonObject result, String message) {
    String name = "$truncated";
    for (int i = 1; result.has(name); i++) name = "$truncated_" + i;
    result.addProperty(name, message);
    return result;
  }
  private static boolean isSensitive(String name) { return SECRET_FIELD.matcher(name).matches(); }
  private static JsonElement redacted() { return new JsonPrimitive("[REDACTED]"); }
  private static JsonObject marker(String key, String message) {
    JsonObject object = new JsonObject(); object.addProperty(key, message); return object;
  }
  private static JsonObject skipped(String type, String reason) {
    JsonObject object = marker("$skipped", reason); object.addProperty("$type", type); return object;
  }
  private static String append(String path, String name) {
    return path + "/" + name.replace("~", "~0").replace("/", "~1");
  }
  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null ? "JDI read failed" : message.substring(0, Math.min(message.length(), 160));
  }
  private record MapEntry(Value key, Value value) { }
}
