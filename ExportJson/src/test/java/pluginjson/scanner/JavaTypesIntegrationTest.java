package pluginjson.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Semantic regression coverage for standard Java layouts through real JDI. */
public class JavaTypesIntegrationTest {
  @Test
  void preservesJavaValuesAndMarksUnsupportedRepresentations() throws Exception {
    Snapshots snapshots = readSuspendedFixture();
    JsonObject json = snapshots.ordinary();
    assertTrue(json.get("bool").getAsBoolean());
    assertEquals(-128, json.get("signedByte").getAsInt());
    assertEquals(32767, json.get("shortValue").getAsInt());
    assertEquals("λ", json.get("character").getAsString());
    assertEquals(Integer.MIN_VALUE, json.get("integer").getAsInt());
    assertEquals(9_007_199_254_740_993L, json.get("longValue").getAsLong());
    assertEquals("0.1", json.get("floatValue").toString(), "Avoid widening a Float into a noisy double");
    assertEquals(0.125d, json.get("doubleValue").getAsDouble());
    assertEquals("NaN", json.get("floatNaN").getAsString());
    assertEquals("-Infinity", json.get("negativeInfinity").getAsString());
    assertTrue(json.get("nullable").isJsonNull());
    assertJson("[true,false]", json.get("booleans"));
    assertJson("[-128,0,127]", json.get("bytes"));
    assertJson("[-1,2]", json.get("shorts"));
    assertJson("[\"A\",\"λ\"]", json.get("chars"));
    assertJson("[1,-2]", json.get("ints"));
    assertJson("[0,9007199254740993]", json.get("longs"));
    assertJson("[0.1,\"Infinity\"]", json.get("floats"));
    assertJson("[0.125,\"NaN\"]", json.get("doubles"));
    assertJson("[[1,2],[3]]", json.get("matrix"));
    assertJson("{\"name\":\"record value\",\"count\":3,\"tags\":[\"x\",\"y\"]}", json.get("details"));
    assertJson("{\"inherited\":\"base value\",\"own\":7}", json.get("inherited"));
    assertEquals("#/firstPerson", json.getAsJsonObject("samePerson").get("$ref").getAsString());
    assertEquals("#", json.getAsJsonObject("self").get("$ref").getAsString());
    assertEquals("#/cyclicArray", json.getAsJsonArray("cyclicArray").get(0).getAsJsonObject().get("$ref").getAsString());

    JsonArray enumEntries = json.getAsJsonObject("enumMap").getAsJsonArray("$entries");
    assertEquals(2, enumEntries.size());
    assertEquals("first", entryValue(enumEntries, "FIRST").getAsString());
    assertTrue(entryValue(enumEntries, "SECOND").isJsonNull());
    assertEquals(Set.of("FIRST", "THIRD"), strings(json.getAsJsonArray("enumSet")));
    assertEquals(Set.of("E00", "E64", "E69"), strings(json.getAsJsonArray("jumboEnumSet")));

    JsonArray identityEntries = json.getAsJsonObject("identityMap").getAsJsonArray("$entries");
    assertEquals(3, identityEntries.size(), "Distinct identity String keys must not collapse into one JSON property");
    assertEquals("null key", entryValue(identityEntries, null).getAsString());
    Set<String> identityValues = new HashSet<>();
    for (JsonElement item : identityEntries) identityValues.add(item.getAsJsonObject().get("value").getAsString());
    assertEquals(Set.of("null key", "identity one", "identity two"), identityValues);
    JsonArray stringIdentityEntries = json.getAsJsonObject("stringIdentityMap").getAsJsonArray("$entries");
    assertEquals(2, stringIdentityEntries.size(), "Identity maps need entry arrays even when every key is a String");
    JsonArray weakEntries = json.getAsJsonObject("weakMap").getAsJsonArray("$entries");
    assertEquals("weak null key", entryValue(weakEntries, null).getAsString());
    assertEquals("weak enum key", entryValue(weakEntries, "FIRST").getAsString());

    for (String field : new String[] {"copyList", "copySet", "concurrentQueue", "concurrentDeque",
        "arrayBlockingQueue", "linkedBlockingQueue", "linkedBlockingDeque"}) {
      assertJson("[\"a\",\"b\"]", json.get(field));
    }
    assertJson("{}", json.get("emptyConcurrent"));
    assertJson("[\"a\",\"b\"]", json.get("shadowList"));
    assertJson("{\"sample\":\"value\"}", json.get("shadowMap"));
    assertJson("[10010,10011,10012,10013,10014]", json.get("farSubList"));
    assertJson("[10010,10011,10012,10013,10014]", json.get("farImmutableSubList"));
    assertTrue(json.getAsJsonObject("unknownSubList").has("$skipped"));
    assertTrue(json.getAsJsonObject("unsupportedMap").has("$skipped"));
    assertFalse(json.getAsJsonObject("unsupportedMap").has("invented"));
    assertEquals("caffè 😀", json.get("builder").getAsString());
    assertEquals("日本語", json.get("buffer").getAsString());
    assertEquals("2026", json.get("year").getAsString());
    assertEquals("2026-10", json.get("yearMonth").getAsString());
    assertEquals("--02-29", json.get("monthDay").getAsString());
    assertEquals("21:30:15+02:00", json.get("offsetTime").getAsString());
    assertEquals("OCTOBER", json.get("month").getAsString());
    assertEquals("EUR", json.get("currency").getAsString());
    assertJson("{\"sample\":\"value\"}", json.get("properties"));
    assertTrue(json.getAsJsonObject("dirtyLegacyDate").has("$skipped"),
        "A legacy Date with unnormalized calendar fields must not export stale fastTime");
    assertSharedPayload(json, "optionalPayload", "optionalAtomicAlias", "optionalDirectAlias");
    assertSharedPayload(json, "atomicPayload", "atomicOptionalAlias", "atomicDirectAlias");
    for (String field : new String[] {"atomicSelf", "atomicCycleA", "atomicCycleB"}) {
      assertTrue(json.getAsJsonObject(field).has("$skipped"),
          "A transparent-wrapper-only cycle must be marked instead of referring to itself: " + field);
      assertFalse(json.getAsJsonObject(field).has("$ref"));
    }
    assertTrue(json.get("atomicTrue").getAsJsonPrimitive().isBoolean());
    assertTrue(json.get("atomicTrue").getAsBoolean());
    assertTrue(json.get("atomicFalse").getAsJsonPrimitive().isBoolean());
    assertFalse(json.get("atomicFalse").getAsBoolean());
    assertEquals("atomic text", json.get("atomicText").getAsString());
    assertEquals("atomic text", json.get("sameAtomicText").getAsString());
    assertFalse(json.toString().contains("\"$error\""), json.toString());
    JsonArray bounded = snapshots.boundedSet();
    assertEquals(2_001, bounded.size(), "Export 2000 set elements plus one explicit truncation marker");
    assertTrue(bounded.get(2_000).getAsJsonObject().has("$truncated"));
    Set<String> retained = new HashSet<>();
    for (int i = 0; i < 2_000; i++) assertTrue(retained.add(bounded.get(i).getAsString()));
    long retainedCharacters = 0;
    boolean textBudgetMarker = false;
    for (JsonElement value : snapshots.textBudget()) {
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
        retainedCharacters += value.getAsString().length();
      } else if (value.isJsonObject()) {
        JsonObject marker = value.getAsJsonObject();
        if (marker.has("$value") && marker.get("$value").isJsonPrimitive()) {
          retainedCharacters += marker.get("$value").getAsString().length();
        }
        if (marker.has("$truncated")) {
          String reason = marker.get("$truncated").getAsString().toLowerCase(java.util.Locale.ROOT);
          textBudgetMarker |= reason.contains("text") && reason.contains("budget");
        }
      }
    }
    assertTrue(textBudgetMarker, "The total text budget needs an explicit truncation marker");
    assertTrue(retainedCharacters > 0 && retainedCharacters <= 5_000_000,
        "Default text payload budget exceeded: " + retainedCharacters);
  }

  private static void assertSharedPayload(JsonObject root, String... fields) {
    String payloadPath = null;
    for (String field : fields) {
      JsonObject value = root.getAsJsonObject(field);
      if (value.has("name")) {
        assertNull(payloadPath, "A shared POJO should be emitted only once");
        assertEquals("Ada", value.get("name").getAsString());
        payloadPath = "#/" + field;
      }
    }
    assertNotNull(payloadPath, "A wrapper must flatten to an actual payload object");
    for (String field : fields) {
      JsonObject value = root.getAsJsonObject(field);
      if (value.has("$ref")) {
        assertEquals(payloadPath, value.get("$ref").getAsString(),
            "References must target the payload location, not an omitted wrapper");
      } else {
        assertTrue(value.has("name"));
      }
    }
  }

  private static JsonElement entryValue(JsonArray entries, String key) {
    for (JsonElement item : entries) {
      JsonObject entry = item.getAsJsonObject();
      if (key == null ? entry.get("key").isJsonNull()
          : entry.get("key").isJsonPrimitive() && key.equals(entry.get("key").getAsString())) {
        return entry.get("value");
      }
    }
    fail("Missing map key " + key + " in " + entries);
    return null;
  }

  private static Set<String> strings(JsonArray array) {
    Set<String> result = new HashSet<>();
    for (JsonElement item : array) result.add(item.getAsString());
    return result;
  }
  private static void assertJson(String expected, JsonElement actual) {
    // A Float and a parsed decimal can compare differently inside Gson even
    // when their encoded JSON is identical. Compare the exported JSON values.
    assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual.toString()));
  }

  private static Snapshots readSuspendedFixture() throws Exception {
    var connector = Bootstrap.virtualMachineManager().defaultConnector();
    var args = connector.defaultArguments();
    args.get("main").setValue(JavaTypesFixture.class.getName());
    args.get("options").setValue("-cp \"" + DebuggeeClasspath.forClasses(JavaTypesFixture.class) + "\"");
    VirtualMachine vm = connector.launch(args);
    Process process = vm.process();
    Thread.ofVirtual().start(() -> drain(process.getInputStream()));
    Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
    try {
      ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
      prepare.addClassFilter(JavaTypesFixture.class.getName());
      prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      prepare.enable();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
      Snapshots snapshots = null;
      while (snapshots == null && System.nanoTime() < deadline) {
        EventSet events = vm.eventQueue().remove(5_000);
        if (events == null) continue;
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent ready) {
            BreakpointRequest breakpoint = vm.eventRequestManager().createBreakpointRequest(
                ready.referenceType().methodsByName("pauseHere").getFirst().location());
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            breakpoint.enable();
          } else if (event instanceof BreakpointEvent stopped) {
            ReferenceType fixture = stopped.location().declaringType();
            ObjectReference root = (ObjectReference) fixture.getValue(fixture.fieldByName("ROOT"));
            assertNotNull(root);
            JsonObject ordinary = new RuntimeScannerService().scanValue(root).getAsJsonObject();
            JsonArray boundedSet = new RuntimeScannerService(true).scanValue(
                root.getValue(root.referenceType().fieldByName("sparseSet"))).getAsJsonArray();
            JsonArray textBudget = new RuntimeScannerService().scanValue(
                fixture.getValue(fixture.fieldByName("TEXT_BUDGET"))).getAsJsonArray();
            snapshots = new Snapshots(ordinary, boundedSet, textBudget);
          } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            fail("Fixture exited before its breakpoint");
          }
        }
        events.resume();
      }
      assertNotNull(snapshots, "Real JDI fixture breakpoint timeout");
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture process must exit");
      assertEquals(0, process.exitValue(), "No application methods may be invoked by the scanner");
      return snapshots;
    } finally {
      try { vm.dispose(); } catch (VMDisconnectedException ignored) {}
      process.destroyForcibly();
    }
  }

  private static void drain(InputStream stream) {
    try (stream) { stream.transferTo(OutputStream.nullOutputStream()); }
    catch (java.io.IOException ignored) {}
  }
  private record Snapshots(JsonObject ordinary, JsonArray boundedSet, JsonArray textBudget) {}
}
