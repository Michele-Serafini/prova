package pluginjson.scanner;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the same field-only resolver used by the one editor action. */
public class RuntimeValueResolverTest {
  @Test
  void selectsActualLocalsFieldsAndReceiversWithoutReplacingNullOrWrongInstances() throws Exception {
    var connector = Bootstrap.virtualMachineManager().defaultConnector();
    var options = connector.defaultArguments();
    options.get("main").setValue(ResolutionFixture.class.getName());
    options.get("options").setValue("-cp \"" + DebuggeeClasspath.forClasses(ResolutionFixture.class) + "\"");
    VirtualMachine vm = connector.launch(options);
    Process process = vm.process();
    Thread.ofVirtual().start(() -> drain(process.getInputStream()));
    Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
    try {
      var requests = vm.eventRequestManager();
      for (String name : List.of(ResolutionFixture.class.getName(), ResolutionFixture.Child.class.getName())) {
        ClassPrepareRequest prepare = requests.createClassPrepareRequest();
        prepare.addClassFilter(name);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
      }
      int stops = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (stops < 2 && System.nanoTime() < deadline) {
        EventSet events = vm.eventQueue().remove(5_000);
        if (events == null) continue;
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent loaded) {
            String method = loaded.referenceType().name().equals(ResolutionFixture.class.getName())
                ? "staticPause" : "instancePause";
            BreakpointRequest breakpoint = requests.createBreakpointRequest(
                loaded.referenceType().methodsByName(method).getFirst().location());
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            breakpoint.enable();
          } else if (event instanceof BreakpointEvent stopped) {
            StackFrame frame = stopped.thread().frame(0);
            if (stops++ == 0) assertInstanceSelection(frame);
            else assertStaticSelection(frame);
          } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            fail("Resolution fixture exited before both breakpoints");
          }
        }
        events.resume();
      }
      assertEquals(2, stops);
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } finally {
      try { vm.dispose(); } catch (VMDisconnectedException ignored) {}
      process.destroyForcibly();
    }
  }

  private static void assertInstanceSelection(StackFrame frame) throws Exception {
    RuntimeValueResolver resolver = new RuntimeValueResolver();
    assertText("local shadows field", resolver.resolve(frame, local("shadow", false)));
    assertText("child field", resolver.resolve(frame, field(ExportTarget.Root.THIS, null, false,
        key(ResolutionFixture.Child.class, "shadow"))));
    assertText("private base field", resolver.resolve(frame, field(ExportTarget.Root.THIS, null, false,
        key(ResolutionFixture.Base.class, "shadow"))));
    assertText("other holder value", resolver.resolve(frame, field(ExportTarget.Root.LOCAL, "other", false,
        key(ResolutionFixture.Holder.class, "value"))));
    assertText("this holder value", resolver.resolve(frame, field(ExportTarget.Root.THIS, null, false,
        key(ResolutionFixture.Child.class, "holder"), key(ResolutionFixture.Holder.class, "value"))));
    assertText("static runtime value", resolver.resolve(frame, staticField()));

    // Available nulls retain their actual value even with a named Spring hint.
    assertNull(resolver.resolve(frame, local("nullLocal", true)).value());
    assertNull(resolver.resolve(frame, field(ExportTarget.Root.THIS, null, true,
        key(ResolutionFixture.Child.class, "nullField"))).value());
    var self = new ExportTarget("this", ExportTarget.Kind.THIS, null, null, false, false, null);
    assertEquals(frame.thisObject(), resolver.resolve(frame, self).value());

    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame, local("notVisible", false)));
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame, local("holder", false)),
        "An unavailable local symbol must not be replaced with this.holder");
    // Exact declaring type prevents a same-named field on the wrong receiver.
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        field(ExportTarget.Root.THIS, null, false, key(ResolutionFixture.Holder.class, "value"))));
    // A qualified selection cannot be replaced by an unrelated Spring singleton.
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        field(ExportTarget.Root.LOCAL, "notVisible", true, key(ResolutionFixture.Holder.class, "value"))));
    var nullReceiver = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        field(ExportTarget.Root.LOCAL, "missingReceiver", true, key(ResolutionFixture.Holder.class, "value"))));
    assertTrue(nullReceiver.getMessage().contains("null"));
  }

  private static void assertStaticSelection(StackFrame frame) throws Exception {
    assertNull(frame.thisObject());
    RuntimeValueResolver resolver = new RuntimeValueResolver();
    assertText("static runtime value", resolver.resolve(frame, staticField()));
    assertText("static frame local", resolver.resolve(frame, local("local", false)));
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        field(ExportTarget.Root.THIS, null, false, key(ResolutionFixture.Child.class, "shadow"))));
  }

  private static ExportTarget local(String name, boolean springHint) {
    return new ExportTarget(name, ExportTarget.Kind.LOCAL, "java.lang.String", "replacementBean",
        true, springHint, null);
  }

  private static ExportTarget.FieldKey key(Class<?> owner, String name) {
    return new ExportTarget.FieldKey(owner.getName(), name);
  }

  private static ExportTarget field(ExportTarget.Root root, String rootName, boolean hint,
      ExportTarget.FieldKey... keys) {
    return new ExportTarget(keys[keys.length - 1].name(), ExportTarget.Kind.FIELD,
        "java.lang.String", "replacementBean", true, hint,
        new ExportTarget.FieldPath(root, rootName, List.of(keys)));
  }

  private static ExportTarget staticField() {
    return field(ExportTarget.Root.STATIC, ResolutionFixture.class.getName(), false,
        key(ResolutionFixture.class, "STATIC_VALUE"));
  }

  private static void assertText(String expected, RuntimeValueResolver.ResolvedValue actual) {
    assertInstanceOf(StringReference.class, actual.value());
    assertEquals(expected, ((StringReference) actual.value()).value());
    assertFalse(actual.springMode(), "Ordinary values must remain ordinary when Spring is absent");
  }

  private static void drain(java.io.InputStream stream) {
    try (stream) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
    catch (java.io.IOException ignored) {}
  }
}
