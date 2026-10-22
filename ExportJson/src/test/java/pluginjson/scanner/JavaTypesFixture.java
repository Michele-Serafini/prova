package pluginjson.scanner;

import java.time.Month;
import java.time.MonthDay;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.util.AbstractMap;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Ordinary JDK objects in a real debuggee JVM, with no serializer dependencies. */
public final class JavaTypesFixture {
  enum Small {
    FIRST, SECOND, THIRD;
    @SuppressWarnings("unused") private final String name = "wrong";
  }
  enum Large {
    E00, E01, E02, E03, E04, E05, E06, E07, E08, E09,
    E10, E11, E12, E13, E14, E15, E16, E17, E18, E19,
    E20, E21, E22, E23, E24, E25, E26, E27, E28, E29,
    E30, E31, E32, E33, E34, E35, E36, E37, E38, E39,
    E40, E41, E42, E43, E44, E45, E46, E47, E48, E49,
    E50, E51, E52, E53, E54, E55, E56, E57, E58, E59,
    E60, E61, E62, E63, E64, E65, E66, E67, E68, E69
  }
  record Details(String name, int count, List<String> tags) {}
  static class Base { private final String inherited = "base value"; }
  static final class Child extends Base { private final int own = 7; }
  static final class Person { String name = "Ada"; }
  static final class ShadowList extends ArrayList<String> {
    @SuppressWarnings("unused") private final Object[] elementData = {"wrong"};
    @SuppressWarnings("unused") private final int size = 1;
    ShadowList() { super(List.of("a", "b")); }
  }
  static final class ShadowMap extends HashMap<String, String> {
    @SuppressWarnings("unused") private final Object[] table = {new PretendEntry()};
    @SuppressWarnings("unused") private final int size = 999;
    ShadowMap() { put("sample", "value"); }
  }

  /** A random table field does not establish a known JDK map representation. */
  static final class UnknownMap extends AbstractMap<String, String> {
    @SuppressWarnings("unused") private final Object[] table = {new PretendEntry()};
    public Set<Entry<String, String>> entrySet() {
      throw new AssertionError("Do not invoke unknown map methods");
    }
  }
  static final class UnknownList extends AbstractList<String> {
    @SuppressWarnings("unused") private final Object[] elementData = {"wrong-0", "wrong-1", "wrong-2"};
    public String get(int index) { throw new AssertionError("Do not invoke unknown list methods"); }
    public int size() { return 3; }
  }
  static final class PretendEntry {
    @SuppressWarnings("unused") String key = "invented";
    @SuppressWarnings("unused") String value = "must not be exported as an entry";
    @SuppressWarnings("unused") PretendEntry next;
  }

  static final class Snapshot {
    boolean bool = true;
    byte signedByte = -128;
    short shortValue = 32767;
    char character = '\u03bb';
    int integer = Integer.MIN_VALUE;
    long longValue = 9_007_199_254_740_993L;
    float floatValue = 0.1f;
    double doubleValue = 0.125d;
    Float floatNaN = Float.NaN;
    Double negativeInfinity = Double.NEGATIVE_INFINITY;
    Object nullable;
    boolean[] booleans = {true, false};
    byte[] bytes = {-128, 0, 127};
    short[] shorts = {-1, 2};
    char[] chars = {'A', '\u03bb'};
    int[] ints = {1, -2};
    long[] longs = {0L, 9_007_199_254_740_993L};
    float[] floats = {0.1f, Float.POSITIVE_INFINITY};
    double[] doubles = {0.125d, Double.NaN};
    int[][] matrix = {{1, 2}, {3}};
    Details details = new Details("record value", 3, List.of("x", "y"));
    Child inherited = new Child();
    Person firstPerson = new Person();
    Person samePerson = firstPerson;
    Object[] cyclicArray = new Object[1];
    Snapshot self = this;
    EnumMap<Small, String> enumMap = new EnumMap<>(Small.class);
    EnumSet<Small> enumSet = EnumSet.of(Small.FIRST, Small.THIRD);
    EnumSet<Large> jumboEnumSet = EnumSet.of(Large.E00, Large.E64, Large.E69);
    Map<Object, String> identityMap = new IdentityHashMap<>();
    Map<String, String> stringIdentityMap = new IdentityHashMap<>();
    Map<Object, String> weakMap = new WeakHashMap<>();
    CopyOnWriteArrayList<String> copyList = new CopyOnWriteArrayList<>(List.of("a", "b"));
    CopyOnWriteArraySet<String> copySet = new CopyOnWriteArraySet<>(List.of("a", "b"));
    ShadowList shadowList = new ShadowList();
    ShadowMap shadowMap = new ShadowMap();
    ConcurrentHashMap<String, String> emptyConcurrent = new ConcurrentHashMap<>();
    ConcurrentLinkedQueue<String> concurrentQueue = new ConcurrentLinkedQueue<>(List.of("discard", "a", "b"));
    ConcurrentLinkedDeque<String> concurrentDeque = new ConcurrentLinkedDeque<>(List.of("discard", "a", "b"));
    ArrayBlockingQueue<String> arrayBlockingQueue = new ArrayBlockingQueue<>(4);
    LinkedBlockingQueue<String> linkedBlockingQueue = new LinkedBlockingQueue<>(List.of("a", "b"));
    LinkedBlockingDeque<String> linkedBlockingDeque = new LinkedBlockingDeque<>(List.of("a", "b"));
    Set<String> sparseSet;
    List<Integer> farSubList;
    List<Integer> farImmutableSubList;
    List<String> unknownSubList = new UnknownList().subList(1, 3);
    UnknownMap unsupportedMap = new UnknownMap();
    StringBuilder builder = new StringBuilder("caffè 😀");
    StringBuffer buffer = new StringBuffer("日本語");
    Year year = Year.of(2026);
    YearMonth yearMonth = YearMonth.of(2026, 10);
    MonthDay monthDay = MonthDay.of(2, 29);
    OffsetTime offsetTime = OffsetTime.parse("21:30:15+02:00");
    Month month = Month.OCTOBER;
    Currency currency = Currency.getInstance("EUR");
    Properties properties = new Properties();
    Date dirtyLegacyDate = changedLegacyDate();
    Optional<Person> optionalPayload = Optional.of(new Person());
    AtomicReference<Person> optionalAtomicAlias = new AtomicReference<>(optionalPayload.get());
    Person optionalDirectAlias = optionalPayload.get();
    AtomicReference<Person> atomicPayload = new AtomicReference<>(new Person());
    Optional<Person> atomicOptionalAlias = Optional.of(atomicPayload.get());
    Person atomicDirectAlias = atomicPayload.get();
    AtomicReference<Object> atomicSelf = new AtomicReference<>();
    AtomicReference<Object> atomicCycleA = new AtomicReference<>();
    AtomicReference<Object> atomicCycleB = new AtomicReference<>();
    AtomicBoolean atomicTrue = new AtomicBoolean(true);
    AtomicBoolean atomicFalse = new AtomicBoolean(false);
    AtomicReference<String> atomicText = new AtomicReference<>("atomic text");
    AtomicReference<String> sameAtomicText = atomicText;

    Snapshot() {
      cyclicArray[0] = cyclicArray;
      enumMap.put(Small.FIRST, "first");
      enumMap.put(Small.SECOND, null);
      identityMap.put(null, "null key");
      identityMap.put(new String("same"), "identity one");
      identityMap.put(new String("same"), "identity two");
      stringIdentityMap.put(new String("same"), "identity one");
      stringIdentityMap.put(new String("same"), "identity two");
      weakMap.put(null, "weak null key");
      weakMap.put(Small.FIRST, "weak enum key");
      concurrentQueue.remove();
      concurrentDeque.removeFirst();
      arrayBlockingQueue.addAll(List.of("discard-1", "discard-2", "discard-3", "a"));
      arrayBlockingQueue.remove();
      arrayBlockingQueue.remove();
      arrayBlockingQueue.remove();
      arrayBlockingQueue.add("b");
      properties.setProperty("sample", "value");
      String[] values = new String[2_100];
      for (int i = 0; i < values.length; i++) values[i] = "entry-" + i;
      sparseSet = Set.of(values);
      List<Integer> large = new ArrayList<>();
      for (int i = 0; i < 10_030; i++) large.add(i);
      farSubList = large.subList(10_010, 10_015);
      farImmutableSubList = List.copyOf(large).subList(10_010, 10_015);
      atomicSelf.set(atomicSelf);
      atomicCycleA.set(atomicCycleB);
      atomicCycleB.set(atomicCycleA);
    }
  }

  @SuppressWarnings("deprecation")
  private static Date changedLegacyDate() {
    Date value = new Date(0);
    value.setYear(123);
    return value;
  }

  public static Snapshot ROOT;
  public static final String[] TEXT_BUDGET = new String[60];
  static {
    String longValue = "x".repeat(100_000);
    java.util.Arrays.fill(TEXT_BUDGET, longValue);
  }
  public static void main(String[] args) {
    ROOT = new Snapshot();
    pauseHere();
  }
  public static void pauseHere() { System.out.println("Java type fixture breakpoint"); }
}
