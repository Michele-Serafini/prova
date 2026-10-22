package pluginjson.scanner;

import com.google.gson.JsonElement;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDI integration test, with a separate JVM suspended at a breakpoint. */
public class RuntimeScannerServiceTest {
  @Test
  void exportsStandardTypesWithoutCallingDebuggeeMethods() throws Exception {
    String json = exportFromSuspendedJvm();
    assertTrue(json.contains("\"arrayList\":[1,2,3]"), json);
    assertTrue(json.contains("\"state\":\"READY\""), json);
    assertTrue(json.contains("\"kind\":\"java.lang.String\""), json);
    assertTrue(json.contains("pluginjson.scanner.SerializationFixture$Base.value\":\"base\""), json);
    assertTrue(json.contains("pluginjson.scanner.SerializationFixture$Child.value\":\"child\""), json);
    assertTrue(json.contains("\"immutableOne\":[\"only\"]"), json);
    assertTrue(json.contains("\"immutableTwo\":[\"a\",\"b\"]"), json);
    assertTrue(json.contains("\"immutableMany\":[\"a\",\"b\",\"c\"]"), json);
    assertTrue(json.contains("\"linkedList\":[4,5]"), json);
    assertTrue(json.contains("\"singleton\":[\"one\"]"), json);
    assertTrue(json.contains("\"unmodifiable\":[\"x\",\"y\"]"), json);
    assertTrue(json.contains("\"subList\":[\"b\",\"c\"]"), json);
    assertTrue(json.contains("\"hashSet\":[5,6]"), json);
    assertTrue(json.contains("\"map\":{\"name\":\"ok\",\"password\":\"[REDACTED]\",\"nullValue\":null}"), json);
    assertTrue(json.contains("\"$entries\":[{\"key\":7,\"value\":\"seven\"}]"), json);
    assertTrue(json.contains("\"nullableKeyMap\":{\"$type\":\"map\",\"$entries\":[{\"key\":null,\"value\":\"none\"}]}"), json);
    assertTrue(json.contains("\"immutableMap\":{\""), json);
    assertTrue(json.contains("\"immutableSingletonMap\":{\"single\":9}"), json);
    assertTrue(json.contains("\"sorted\":{\"a\":1,\"b\":2}"), json);
    assertTrue(json.contains("\"present\":\"here\""), json);
    assertTrue(json.contains("\"uri\":\"https://example.org/example?q=x\""), json);
    assertTrue(json.contains("\"file\":\"project/JSON\""), json);
    assertTrue(json.contains("\"regex\":\"[a-z]+\""), json);
    assertTrue(json.contains("\"cycleArray\":[{\"$ref\":\"#/cycleArray\"}]"), json);
    assertTrue(json.contains("\"empty\":null"), json);
    assertTrue(json.contains("\"optionalNumber\":12"), json);
    assertTrue(json.contains("\"date\":\"2025-12-31\""), json);
    assertTrue(json.contains("\"time\":\"23:45:16\""), json);
    assertTrue(json.contains("\"dateTime\":\"2025-12-31T23:45\""), json);
    assertTrue(json.contains("\"instant\":\"2025-01-01T01:01:01Z\""), json);
    assertTrue(json.contains("\"duration\":\"PT6M\""), json);
    assertTrue(json.contains("\"period\":\"P1Y2M3D\""), json);
    assertTrue(json.contains("\"uuid\":\"12345678-1234-5678-1234-567812345678\""), json);
    assertTrue(json.contains("\"decimal\":12345678901234567890.2345"), json);
    assertTrue(json.contains("\"compactDecimal\":1234.50"), json);
    assertTrue(json.contains("\"integer\":123456789012345678901234567890"), json);
    assertTrue(json.contains("\"infinity\":\"Infinity\""), json);
    assertTrue(json.contains("\"password\":\"[REDACTED]\""), json);
    assertFalse(json.contains("should-not-leak"), json);
    assertFalse(json.contains("would-leak"), json);
    assertTrue(json.contains("\"samePerson\":{\"$ref\":\"#/person\"}"), json);
    assertTrue(json.contains("\"self\":{\"$ref\":\"#\"}"), json);
    assertFalse(json.contains("\"$error\""), json);
    assertFalse(json.contains("\"$skipped\""), json);
  }

  private String exportFromSuspendedJvm() throws Exception {
    LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
    Map<String, Connector.Argument> args = connector.defaultArguments();
    args.get("main").setValue("pluginjson.scanner.SerializationFixture");
    String testClasses = DebuggeeClasspath.forClasses(SerializationFixture.class);
    args.get("options").setValue("-cp \"" + testClasses + "\"");
    VirtualMachine vm = connector.launch(args);
    try {
      EventRequestManager requests = vm.eventRequestManager();
      ClassPrepareRequest prepare = requests.createClassPrepareRequest();
      prepare.addClassFilter("pluginjson.scanner.SerializationFixture");
      prepare.enable();
      vm.resume();
      long deadline = System.currentTimeMillis() + 25_000;
      while (System.currentTimeMillis() < deadline) {
        EventSet events = vm.eventQueue().remove(5_000);
        if (events == null) continue;
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent ready) {
            Method method = ready.referenceType().methodsByName("pauseHere").get(0);
            BreakpointRequest breakpoint = requests.createBreakpointRequest(method.location());
            breakpoint.enable();
          }
          if (event instanceof BreakpointEvent stopped) {
            ReferenceType fixture = stopped.location().declaringType();
            Value value = fixture.getValue(fixture.fieldByName("ROOT"));
            assertNotNull(value);
            JsonElement snapshot = new RuntimeScannerService().scanValue(value);
            String spring = new RuntimeScannerService(true).scanValue(value).toString();
            assertTrue(spring.contains("\"$truncated\":\"100 more elements\""), spring);
            assertTrue(spring.contains("\"$truncated\":\"100 more entries\""), spring);
            return snapshot.toString();
          }
          if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)
            fail("Fixture JVM terminated before reaching the breakpoint");
        }
        events.resume();
      }
      fail("JDI fixture breakpoint timeout");
      return null;
    } finally { try { vm.dispose(); } catch (VMDisconnectedException ignored) {} }
  }
}
