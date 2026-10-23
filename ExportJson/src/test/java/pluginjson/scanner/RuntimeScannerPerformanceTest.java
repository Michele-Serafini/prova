package pluginjson.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.jdi.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pluginjson.settings.ScanLimits;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic debugger command budgets, independent of machine timing. */
class RuntimeScannerPerformanceTest {
  @Test
  void batchesPojoFieldsAndReusesMetadataWithoutReadingSecretsOrSyntheticFields() {
    Mirrors mirrors = new Mirrors();
    ClassType rowType = mirrors.type("example.Row");
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < 12; i++) fields.add(mirrors.field(rowType, "column" + i));
    Field secret = mirrors.field(rowType, "password");
    Field synthetic = mirrors.field(rowType, "this$0", true);
    List<Value> rows = new ArrayList<>();
    for (int row = 0; row < 100; row++) {
      Map<Field, Value> values = new LinkedHashMap<>();
      for (int i = 0; i < fields.size(); i++) values.put(fields.get(i), integer(i));
      values.put(secret, integer(999));
      values.put(synthetic, integer(888));
      rows.add(mirrors.object(rowType, values, Set.of(secret, synthetic)));
    }
    JsonArray json = new RuntimeScannerService().scanValue(mirrors.array(rows)).getAsJsonArray();
    assertEquals(100, json.size());
    assertEquals(11, json.get(0).getAsJsonObject().get("column11").getAsInt());
    assertEquals("[REDACTED]", json.get(0).getAsJsonObject().get("password").getAsString());
    assertFalse(json.get(0).getAsJsonObject().has("this$0"));
    assertEquals(100, mirrors.objectBatches.get(), "One remote read per row instead of one per field");
    assertEquals(0, mirrors.objectSingles.get());
    assertEquals(1, mirrors.fieldLists.get(), "allFields is cached per reference type");
    assertEquals(1, mirrors.interfaceLists.get(), "All classification checks share the hierarchy cache");
  }

  @Test
  void readsSparseHashTableInBoundedBatches() {
    Mirrors mirrors = new Mirrors();
    ClassType nodeType = mirrors.type("java.util.HashMap$Node");
    Field key = mirrors.field(nodeType, "key"), value = mirrors.field(nodeType, "value"), next = mirrors.field(nodeType, "next");
    ObjectReference node = mirrors.object(nodeType, fields(key, text("answer"), value, integer(42), next, null));
    List<Value> slots = new ArrayList<>(Collections.nCopies(4096, null));
    slots.set(4095, node);
    ClassType mapType = mirrors.type("java.util.HashMap", "java.util.Map");
    Field table = mirrors.field(mapType, "table"), size = mirrors.field(mapType, "size");
    ObjectReference map = mirrors.object(mapType, fields(table, mirrors.array(slots), size, integer(1)));
    JsonObject json = new RuntimeScannerService().scanValue(map).getAsJsonObject();
    assertEquals(42, json.get("answer").getAsInt());
    assertEquals(16, mirrors.arrayBatches.get(), "4096 buckets require 16 debugger array commands");
    assertEquals(0, mirrors.arraySingles.get());
    assertEquals(2, mirrors.objectBatches.get(), "Map header and its node are each read once");
  }

  @Test
  void batchesBigIntegerMagnitudeWithoutChangingTheNumber() {
    Mirrors mirrors = new Mirrors();
    ClassType type = mirrors.type("java.math.BigInteger");
    Field sign = mirrors.field(type, "signum"), magnitude = mirrors.field(type, "mag");
    ObjectReference number = mirrors.object(type, fields(sign, integer(1), magnitude,
        mirrors.array(Collections.nCopies(1024, integer(-1)))));
    JsonElement json = new RuntimeScannerService().scanValue(number);
    byte[] bytes = new byte[4096];
    Arrays.fill(bytes, (byte) 255);
    assertEquals(new BigInteger(1, bytes), json.getAsBigInteger());
    assertEquals(4, mirrors.arrayBatches.get());
    assertEquals(0, mirrors.arraySingles.get());
    assertEquals(1, mirrors.objectBatches.get());
  }

  @Test
  void exhaustedValueBudgetStopsRemoteArrayReadsAndProducesOneMarker() {
    Mirrors mirrors = new Mirrors();
    ScanLimits defaults = ScanLimits.defaults(false);
    ScanLimits limits = new ScanLimits(defaults.maxDepth(), defaults.maxItems(), defaults.maxObjects(),
        3, defaults.maxTextUnits(), defaults.maxStringLength(), defaults.maxLayoutSlots());
    JsonArray json = new RuntimeScannerService(limits)
        .scanValue(mirrors.array(Collections.nCopies(1000, integer(7)))).getAsJsonArray();
    assertEquals(3, json.size());
    assertEquals(7, json.get(0).getAsInt());
    assertEquals(7, json.get(1).getAsInt());
    assertTrue(json.get(2).getAsJsonObject().has("$truncated"));
    assertEquals(1, mirrors.arrayBatches.get());
    assertEquals(2, mirrors.arrayValuesRead.get(), "Do not fetch the rest of an exhausted snapshot");
  }

  @Test
  void scalarStringsRespectConfiguredLengthAndTextBudget() {
    Mirrors mirrors = new Mirrors();
    ClassType type = mirrors.type("java.util.UUID");
    Field most = mirrors.field(type, "mostSigBits"), least = mirrors.field(type, "leastSigBits");
    ObjectReference uuid = mirrors.object(type, fields(most, longValue(0), least, longValue(0)));
    ScanLimits defaults = ScanLimits.defaults(false);
    ScanLimits limits = new ScanLimits(defaults.maxDepth(), defaults.maxItems(), defaults.maxObjects(),
        defaults.maxValues(), 2, 4, defaults.maxLayoutSlots());
    JsonObject json = new RuntimeScannerService(limits).scanValue(uuid).getAsJsonObject();
    assertEquals("00", json.get("$value").getAsString());
    assertTrue(json.get("$truncated").getAsString().contains("text budget"));
  }

  @Test
  void scannerReuseRefreshesValuesAndMetadataBetweenSnapshots() {
    Mirrors mirrors = new Mirrors();
    ClassType type = mirrors.type("example.Row");
    Field field = mirrors.field(type, "count");
    Map<Field, Value> values = fields(field, integer(1));
    ObjectReference object = mirrors.object(type, values);
    RuntimeScannerService scanner = new RuntimeScannerService();
    assertEquals(1, scanner.scanValue(object).getAsJsonObject().get("count").getAsInt());
    values.put(field, integer(2));
    assertEquals(2, scanner.scanValue(object).getAsJsonObject().get("count").getAsInt());
    assertEquals(2, mirrors.objectBatches.get());
    assertEquals(2, mirrors.fieldLists.get());
  }

  private static IntegerValue integer(int value) {
    return mirror(IntegerValue.class, (method, args) -> switch (method) {
      case "intValue", "value" -> value;
      default -> unsupported(method);
    });
  }

  private static LongValue longValue(long value) {
    return mirror(LongValue.class, (method, args) -> switch (method) {
      case "longValue", "value" -> value;
      default -> unsupported(method);
    });
  }

  private static StringReference text(String value) {
    return mirror(StringReference.class, (method, args) -> method.equals("value") ? value : unsupported(method));
  }

  private static Map<Field, Value> fields(Object... pairs) {
    Map<Field, Value> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) values.put((Field) pairs[i], (Value) pairs[i + 1]);
    return values;
  }

  private static final class Mirrors {
    final AtomicInteger objectBatches = new AtomicInteger(), objectSingles = new AtomicInteger();
    final AtomicInteger arrayBatches = new AtomicInteger(), arraySingles = new AtomicInteger(), arrayValuesRead = new AtomicInteger();
    final AtomicInteger fieldLists = new AtomicInteger(), interfaceLists = new AtomicInteger();
    long nextId;

    ClassType type(String name, String... interfaces) {
      List<Field> fields = new ArrayList<>();
      return mirror(ClassType.class, (method, args) -> switch (method) {
        case "name" -> name;
        case "superclass" -> null;
        case "allInterfaces" -> {
          interfaceLists.incrementAndGet();
          yield Arrays.stream(interfaces).map(iface -> mirror(InterfaceType.class,
              (m, a) -> m.equals("name") ? iface : unsupported(m))).toList();
        }
        case "allFields" -> { fieldLists.incrementAndGet(); yield fields; }
        default -> unsupported(method);
      }, fields);
    }

    Field field(ReferenceType type, String name) { return field(type, name, false); }

    Field field(ReferenceType type, String name, boolean synthetic) {
      Field field = mirror(Field.class, (method, args) -> switch (method) {
        case "name" -> name;
        case "declaringType" -> type;
        case "isStatic" -> false;
        case "isSynthetic" -> synthetic;
        default -> unsupported(method);
      });
      @SuppressWarnings("unchecked") List<Field> fields = (List<Field>) ((TaggedHandler) Proxy.getInvocationHandler(type)).tag;
      fields.add(field);
      return field;
    }

    ObjectReference object(ClassType type, Map<Field, Value> values) { return object(type, values, Set.of()); }

    ObjectReference object(ClassType type, Map<Field, Value> values, Set<Field> forbidden) {
      long id = ++nextId;
      return mirror(ObjectReference.class, (method, args) -> switch (method) {
        case "uniqueID" -> id;
        case "referenceType" -> type;
        case "getValues" -> {
          objectBatches.incrementAndGet();
          Map<Field, Value> batch = new HashMap<>();
          for (Object item : (List<?>) args[0]) {
            Field field = (Field) item;
            assertFalse(forbidden.contains(field), "Sensitive or synthetic field was fetched");
            batch.put(field, values.get(field));
          }
          yield batch;
        }
        case "getValue" -> { objectSingles.incrementAndGet(); yield values.get(args[0]); }
        default -> unsupported(method);
      });
    }

    ArrayReference array(List<? extends Value> values) {
      long id = ++nextId;
      ArrayType type = mirror(ArrayType.class, (method, args) -> method.equals("name") ? "example.Row[]" : unsupported(method));
      return mirror(ArrayReference.class, (method, args) -> switch (method) {
        case "uniqueID" -> id;
        case "referenceType" -> type;
        case "length" -> values.size();
        case "getValues" -> {
          arrayBatches.incrementAndGet();
          int start = (int) args[0], count = (int) args[1];
          arrayValuesRead.addAndGet(count);
          yield new ArrayList<>(values.subList(start, start + count));
        }
        case "getValue" -> { arraySingles.incrementAndGet(); yield values.get((int) args[0]); }
        default -> unsupported(method);
      });
    }
  }

  private interface Call { Object invoke(String method, Object[] args); }

  private static final class TaggedHandler implements InvocationHandler {
    final Call call;
    final Object tag;
    TaggedHandler(Call call, Object tag) { this.call = call; this.tag = tag; }
    @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
      return switch (method.getName()) {
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "toString" -> "JDI test mirror@" + Integer.toHexString(System.identityHashCode(proxy));
        default -> call.invoke(method.getName(), args == null ? new Object[0] : args);
      };
    }
  }

  private static <T> T mirror(Class<T> type, Call call) { return mirror(type, call, null); }
  private static <T> T mirror(Class<T> type, Call call, Object tag) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new TaggedHandler(call, tag)));
  }
  private static Object unsupported(String method) { throw new UnsupportedOperationException("Unexpected JDI call: " + method); }
}
