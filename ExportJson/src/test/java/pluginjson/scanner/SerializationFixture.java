package pluginjson.scanner;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs in the suspended debuggee JVM, not in the IDE. */
public final class SerializationFixture {
  static final class Person {
    String name = "Mario";
    String password = "should-not-leak";
    List<String> roles = List.of("ADMIN", "USER");
  }
  static class Base { String value = "base"; }
  static final class Child extends Base { String value = "child"; }
  enum State { READY }
  static final class Snapshot {
    List<Integer> arrayList = new ArrayList<>(List.of(1, 2, 3));
    State state = State.READY;
    Class<?> kind = String.class;
    Child shadowed = new Child();
    List<String> immutableOne = List.of("only");
    List<String> immutableTwo = List.of("a", "b");
    List<String> immutableMany = List.of("a", "b", "c");
    List<Integer> linkedList = new LinkedList<>(List.of(4, 5));
    List<String> singleton = Collections.singletonList("one");
    List<String> unmodifiable = Collections.unmodifiableList(new ArrayList<>(List.of("x", "y")));
    List<String> subList = new ArrayList<>(List.of("a", "b", "c")).subList(1,3);
    Set<Integer> hashSet = new LinkedHashSet<>(List.of(5, 6));
    Set<String> immutableSet = Set.of("x", "y", "z");
    Map<String, Object> map = new LinkedHashMap<>();
    Map<Integer, String> numericKeys = new LinkedHashMap<>();
    Map<Object, String> nullableKeyMap = new LinkedHashMap<>();
    Map<String, Integer> immutableMap = Map.of("x", 5, "y", 7);
    Map<String, Integer> immutableSingletonMap = Map.of("single", 9);
    Map<String, Integer> sorted = new TreeMap<>();
    Map<String, String> concurrent = new ConcurrentHashMap<>();
    List<Integer> longList = new ArrayList<>();
    Map<String, Integer> longMap = new LinkedHashMap<>();
    java.net.URI uri = java.net.URI.create("https://example.org/example?q=x");
    java.io.File file = new java.io.File("project/JSON");
    java.util.regex.Pattern regex = java.util.regex.Pattern.compile("[a-z]+");
    Object[] cycleArray = new Object[1];
    Optional<String> present = Optional.of("here");
    Optional<String> empty = Optional.empty();
    OptionalInt optionalNumber = OptionalInt.of(12);
    LocalDate date = LocalDate.of(2025, 12, 31);
    LocalTime time = LocalTime.of(23, 45, 16);
    LocalDateTime dateTime = LocalDateTime.of(2025, 12, 31, 23, 45);
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2025-12-31T23:45:00+02:00");
    Instant instant = Instant.parse("2025-01-01T01:01:01Z");
    Duration duration = Duration.ofMinutes(6);
    Period period = Period.of(1,2,3);
    UUID uuid = UUID.fromString("12345678-1234-5678-1234-567812345678");
    BigDecimal decimal = new BigDecimal("12345678901234567890.2345");
    BigDecimal compactDecimal = new BigDecimal("1234.50");
    BigInteger integer = new BigInteger("123456789012345678901234567890");
    Double infinity = Double.POSITIVE_INFINITY;
    Person person = new Person();
    Person samePerson = person;
    Snapshot self = this;
    Snapshot() {
      map.put("name", "ok");
      map.put("password", "would-leak");
      map.put("nullValue", null);
      numericKeys.put(7, "seven");
      nullableKeyMap.put(null, "none");
      sorted.put("b", 2); sorted.put("a", 1);
      concurrent.put("status", "ready");
      for (int i = 0; i < 2_100; i++) {
        longList.add(i);
        longMap.put("key" + i, i);
      }
      cycleArray[0] = cycleArray;
    }
  }
  public static Snapshot ROOT;
  public static void main(String[] args) {
    ROOT = new Snapshot();
    pauseHere();
  }
  public static void pauseHere() {
    System.out.println("marker");
  }
}
