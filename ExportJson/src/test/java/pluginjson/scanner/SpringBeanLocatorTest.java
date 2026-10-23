package pluginjson.scanner;

import com.google.gson.JsonObject;
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pluginjson.settings.SpringLookupLimits;
import static org.junit.jupiter.api.Assertions.*;

/** Uses real Spring Boot, Spring AOP and JDI; no mocked framework classes. */
public class SpringBeanLocatorTest {
  @Test
  void exportsBootAndCustomContextsWithoutExecutingApplicationMethods() throws Exception {
    var connector = Bootstrap.virtualMachineManager().defaultConnector();
    var args = connector.defaultArguments();
    args.get("main").setValue(SpringBootFixture.class.getName());
    args.get("options").setValue("-cp \"" + fixtureClasspath() + "\"");
    VirtualMachine vm = connector.launch(args);
    Process process = vm.process();
    Thread.ofVirtual().start(() -> drain(process.getInputStream()));
    Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
    SpringBeanLocator locator = new SpringBeanLocator();
    Value previousPayload = null;
    try {
      for (String type : List.of(SpringBootFixture.class.getName(), SpringBootFixture.PauseHolder.class.getName())) {
        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(type);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
      }
      int stops = 0;
      int sparseStops = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      while ((stops < 3 || sparseStops < 1) && System.nanoTime() < deadline) {
        EventSet events = vm.eventQueue().remove(5_000);
        if (events == null) continue;
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent loaded) {
            for (String method : List.of("pauseHere", "pauseSparse")) {
              for (Method candidate : loaded.referenceType().methodsByName(method)) {
                BreakpointRequest breakpoint = vm.eventRequestManager().createBreakpointRequest(candidate.location());
                breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                breakpoint.enable();
              }
            }
          } else if (event instanceof BreakpointEvent stopped) {
            if (stopped.location().method().name().equals("pauseSparse")) {
              sparseStops++;
              ReferenceType fixture = vm.classesByName(SpringBootFixture.class.getName()).getFirst();
              String name = ((StringReference) fixture.getValue(fixture.fieldByName("SPARSE_NAME"))).value();
              assertTrue(locator.findBean(vm, name) instanceof ObjectReference,
                  "A bean in a hash bucket beyond 100,000 must not be silently missed");
            } else if (stops++ < 2) {
              Value currentPayload = locator.findBean(vm, "payload");
              assertNotEquals(previousPayload, currentPayload,
                  "A reused locator must read the active context after the JVM resumes");
              previousPayload = currentPayload;
              if (stops == 1) assertConfiguredLimits(vm, locator);
              assertExports(locator, vm);
              assertAutomaticResolution(locator, stopped.thread().frame(0));
            } else {
              expectMissing(locator, vm, "payload", "più contesti");
              var ambiguity = assertThrows(SpringBeanLocator.BeanLookupException.class,
                  () -> locator.findBeanByType(vm, SpringBootFixture.UniqueSettings.class.getName()));
              assertEquals(SpringBeanLocator.Reason.AMBIGUOUS, ambiguity.reason());
              SpringLookupLimits defaults = SpringLookupLimits.defaults();
              var contexts = new SpringBeanLocator(new SpringLookupLimits(
                  1, defaults.maxMapEntries(), defaults.maxMapBuckets(),
                  defaults.maxProxyDepth(), defaults.maxAliasDepth()));
              assertEquals(SpringBeanLocator.Reason.LIMIT_EXCEEDED,
                  assertThrows(SpringBeanLocator.BeanLookupException.class,
                      () -> contexts.findBean(vm, "payload")).reason());
            }
          } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            fail("Fixture JVM terminated before all breakpoints");
          }
        }
        events.resume();
      }
      assertEquals(1, sparseStops, "Sparse singleton table must be tested");
      assertEquals(3, stops, "Boot, custom subclass, and ambiguous contexts must be tested");
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture must exit");
      assertEquals(0, process.exitValue(), "Lazy/prototype beans and methods must stay untouched");
    } finally {
      try { vm.dispose(); } catch (VMDisconnectedException ignored) {}
      process.destroyForcibly();
    }
  }

  private static void assertExports(SpringBeanLocator locator, VirtualMachine vm) throws Exception {
    Value bean = locator.findBean(vm, "payload");
    assertTrue(new RuntimeScannerService(true).scanValue(locator.findBean(vm, "nullBean")).isJsonNull());
    assertTrue(locator.matchesBeanType(locator.findBean(vm, "nullBean"), SpringBootFixture.UniqueSettings.class.getName()));
    assertFalse(locator.matchesBeanType(locator.findBean(vm, "nullBean"), "int"));
    assertEquals(bean, locator.findBean(vm, "payloadAlias"));
    JsonObject json = new RuntimeScannerService(true).scanValue(bean).getAsJsonObject();
    assertEquals(42, json.get("count").getAsInt());
    assertEquals("actual Spring Boot value", json.get("message").getAsString());
    assertEquals("#", json.getAsJsonObject("self").get("$ref").getAsString());
    assertEquals("JDK proxy target", json.getAsJsonObject("dependency").get("value").getAsString());
    assertEquals("#/dependency", json.getAsJsonObject("repeated").get("$ref").getAsString());
    assertTrue(json.getAsJsonObject("lazyDependency").has("$skipped"));
    assertTrue(json.getAsJsonObject("fakeDependency").has("$skipped"));
    assertTrue(json.getAsJsonObject("context").has("$skipped"));
    assertFalse(json.toString().contains("CGLIB$CALLBACK"));
    assertFalse(json.toString().contains("\"$error\""), json.toString());

    for (boolean springMode : new boolean[] {false, true}) {
      JsonObject cglib = new RuntimeScannerService(springMode)
          .scanValue(locator.findBean(vm, "cglibProxied")).getAsJsonObject();
      assertEquals(42, cglib.get("count").getAsInt());
      assertEquals("#", cglib.getAsJsonObject("self").get("$ref").getAsString());
      JsonObject jdk = new RuntimeScannerService(springMode)
          .scanValue(locator.findBean(vm, "jdkProxied")).getAsJsonObject();
      assertEquals("JDK proxy target", jdk.get("value").getAsString());
    }
    assertEquals(42, new RuntimeScannerService(true)
        .scanValue(locator.findBean(vm, "cached")).getAsJsonObject().get("count").getAsInt());
    ObjectReference factory = assertInstanceOf(ObjectReference.class,
        locator.findBean(vm, "&product"));
    assertEquals(SpringBootFixture.ProductFactory.class.getName(), factory.referenceType().name());
    assertEquals(factory, locator.findBean(vm, "&factoryAlias"));
    assertEquals(factory, locator.findBeanByType(vm, SpringBootFixture.ProductFactory.class.getName()));
    assertEquals(locator.findBean(vm, "settings"), locator.findBeanByType(vm,
        SpringBootFixture.UniqueSettings.class.getName().replace('$', '.')));
    assertTrue(locator.matchesBeanType(locator.findBean(vm, "numberBean"), "int"));
    assertEquals(locator.findBean(vm, "jdkProxied"), locator.findBeanByType(vm,
        SpringBootFixture.ServiceImpl.class.getName()));
    assertEquals(locator.findBean(vm, "textArray"), locator.findBeanByType(vm, "java.lang.String[]"));
    assertTrue(locator.matchesBeanType(locator.findBean(vm, "textArray"), "java.lang.Object[]"));
    assertFalse(locator.matchesBeanType(locator.findBean(vm, "primitiveArray"), "java.lang.Object[]"));
    assertTrue(locator.matchesBeanType(locator.findBean(vm, "matrix"), "java.lang.Object[]"));
    assertEquals(locator.findBean(vm, "matrix"), locator.findBeanByType(vm, "int[][]"));
    assertTrue(locator.isSpringBean(vm, bean));
    ReferenceType fixture = vm.classesByName(SpringBootFixture.class.getName()).getFirst();
    assertFalse(locator.isSpringBean(vm, fixture.getValue(fixture.fieldByName("UNMANAGED"))));
    ObjectReference staticSource = (ObjectReference) fixture.getValue(fixture.fieldByName("STATIC_SOURCE"));
    int calls = ((IntegerValue) staticSource.getValue(staticSource.referenceType().fieldByName("calls"))).intValue();
    for (String name : List.of("frozenOpaque", "capturedStaticTarget")) {
      Value proxy = locator.findBean(vm, name);
      assertEquals(42, new RuntimeScannerService(true).scanValue(proxy)
          .getAsJsonObject().get("count").getAsInt());
      assertTrue(locator.isSpringBean(vm, SpringProxyReader.singletonTarget((ObjectReference) proxy)));
    }
    assertEquals(calls, ((IntegerValue) staticSource.getValue(staticSource.referenceType().fieldByName("calls"))).intValue(),
        "Export must not call a static TargetSource.getTarget()");
    var ambiguity = assertThrows(SpringBeanLocator.BeanLookupException.class,
        () -> locator.findBeanByType(vm, SpringBootFixture.Payload.class.getName()));
    assertEquals(SpringBeanLocator.Reason.AMBIGUOUS, ambiguity.reason());
    expectMissing(locator, vm, "product", "FactoryBean");
    expectMissing(locator, vm, "lazyPayload", "non trovato");
    expectMissing(locator, vm, "prototypePayload", "non trovato");
    expectMissing(locator, vm, "missing", "non trovato");
    expectMissing(locator, vm, "&payload", "non trovato");
  }

  private static void assertConfiguredLimits(VirtualMachine vm, SpringBeanLocator locator)
      throws Exception {
    SpringLookupLimits defaults = SpringLookupLimits.defaults();
    var entries = new SpringBeanLocator(new SpringLookupLimits(
        defaults.maxContextInstancesPerClass(), 1, defaults.maxMapBuckets(),
        defaults.maxProxyDepth(), defaults.maxAliasDepth()));
    assertEquals(SpringBeanLocator.Reason.LIMIT_EXCEEDED,
        assertThrows(SpringBeanLocator.BeanLookupException.class,
            () -> entries.findBeanByType(vm, SpringBootFixture.UniqueSettings.class.getName())).reason());
    var buckets = new SpringBeanLocator(new SpringLookupLimits(
        defaults.maxContextInstancesPerClass(), defaults.maxMapEntries(), 1,
        defaults.maxProxyDepth(), defaults.maxAliasDepth()));
    assertEquals(SpringBeanLocator.Reason.LIMIT_EXCEEDED,
        assertThrows(SpringBeanLocator.BeanLookupException.class,
            () -> buckets.findBeanByType(vm, SpringBootFixture.UniqueSettings.class.getName())).reason());
    var aliases = new SpringBeanLocator(new SpringLookupLimits(
        defaults.maxContextInstancesPerClass(), defaults.maxMapEntries(), defaults.maxMapBuckets(),
        defaults.maxProxyDepth(), 1));
    assertEquals(locator.findBean(vm, "payload"), aliases.findBean(vm, "payloadAlias"),
        "An alias depth of one permits exactly one followed alias");
    assertEquals(SpringBeanLocator.Reason.LIMIT_EXCEEDED,
        assertThrows(SpringBeanLocator.BeanLookupException.class,
            () -> aliases.findBean(vm, "payloadAliasChain")).reason());
    var proxies = new SpringBeanLocator(new SpringLookupLimits(
        defaults.maxContextInstancesPerClass(), defaults.maxMapEntries(), defaults.maxMapBuckets(),
        1, defaults.maxAliasDepth()));
    Value jdkProxy = locator.findBean(vm, "jdkProxied");
    assertEquals(SpringBeanLocator.Reason.LIMIT_EXCEEDED,
        assertThrows(SpringBeanLocator.BeanLookupException.class,
            () -> proxies.matchesBeanType(jdkProxy, SpringBootFixture.ServiceImpl.class.getName())).reason());
  }

  private static void assertAutomaticResolution(SpringBeanLocator locator, StackFrame frame) throws Exception {
    RuntimeValueResolver resolver = new RuntimeValueResolver();
    Value registered = locator.findBean(frame.virtualMachine(), "payload");
    var local = resolver.resolve(frame, target("localBean", ExportTarget.Kind.LOCAL,
        SpringBootFixture.Payload.class.getName(), null, false, false, null));
    assertEquals(registered, local.value());
    assertTrue(local.springMode(), "A plain local holding a real Spring singleton is recognized by identity");

    var unmanaged = resolver.resolve(frame, target("injected", ExportTarget.Kind.LOCAL,
        SpringBootFixture.Payload.class.getName(), null, false, false, null));
    assertNotEquals(registered, unmanaged.value());
    assertFalse(unmanaged.springMode(), "Same type does not make an unrelated object a Spring bean");
    var fieldPath = new ExportTarget.FieldPath(ExportTarget.Root.THIS, null, List.of(
        new ExportTarget.FieldKey(SpringBootFixture.PauseHolder.class.getName(), "injected")));
    var injected = resolver.resolve(frame, target("injected", ExportTarget.Kind.FIELD,
        SpringBootFixture.Payload.class.getName(), "wrongName", true, true, fieldPath));
    assertEquals(registered, injected.value(), "Read the actual private field even when a local shadows it");
    assertTrue(injected.springMode());

    var nullLocal = resolver.resolve(frame, target("nullBean", ExportTarget.Kind.LOCAL,
        SpringBootFixture.Payload.class.getName(), "payload", true, true, null));
    assertNull(nullLocal.value(), "Never replace a real null local with another Spring bean");
    var nullPath = new ExportTarget.FieldPath(ExportTarget.Root.THIS, null, List.of(
        new ExportTarget.FieldKey(SpringBootFixture.PauseHolder.class.getName(), "nullField")));
    assertNull(resolver.resolve(frame, target("nullField", ExportTarget.Kind.FIELD,
        SpringBootFixture.Payload.class.getName(), "payload", true, true, nullPath)).value());

    var missingPath = new ExportTarget.FieldPath(ExportTarget.Root.THIS, null, List.of(
        new ExportTarget.FieldKey("unavailable.Owner", "unknownName")));
    var fallback = resolver.resolve(frame, target("unknownName", ExportTarget.Kind.FIELD,
        SpringBootFixture.UniqueSettings.class.getName(), "unknownName", false, true, missingPath));
    assertEquals(locator.findBean(frame.virtualMachine(), "settings"), fallback.value());
    assertTrue(fallback.springMode());
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        target("payload", ExportTarget.Kind.LOCAL, null, null, false, false, null)),
        "An unrelated unavailable local is not guessed from a matching bean name");
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve(frame,
        target("unknownName", ExportTarget.Kind.FIELD, SpringBootFixture.UniqueSettings.class.getName(),
            "payload", true, true, missingPath)), "An explicit qualifier never falls back to an incompatible type");
    var missingQualifier = assertThrows(SpringBeanLocator.BeanLookupException.class, () -> resolver.resolve(frame,
        target("unknownName", ExportTarget.Kind.FIELD, SpringBootFixture.UniqueSettings.class.getName(),
            "missingQualifier", true, true, missingPath)));
    assertEquals(SpringBeanLocator.Reason.NOT_FOUND, missingQualifier.reason());
    var method = resolver.resolve(frame, target("factoryMethod", ExportTarget.Kind.BEAN,
        null, "payloadAlias", true, true, null));
    assertEquals(registered, method.value());
    assertTrue(method.springMode());
    var ambiguity = assertThrows(SpringBeanLocator.BeanLookupException.class, () -> resolver.resolve(frame,
        target("unknownName", ExportTarget.Kind.FIELD, SpringBootFixture.Payload.class.getName(),
            "unknownName", false, true, missingPath)));
    assertEquals(SpringBeanLocator.Reason.AMBIGUOUS, ambiguity.reason());
  }

  private static ExportTarget target(String name, ExportTarget.Kind kind, String type,
      String beanName, boolean explicit, boolean spring, ExportTarget.FieldPath path) {
    return new ExportTarget(name, kind, type, beanName, explicit, spring, path);
  }

  private static void expectMissing(SpringBeanLocator locator, VirtualMachine vm,
      String name, String message) {
    var error = assertThrows(SpringBeanLocator.BeanLookupException.class,
        () -> locator.findBean(vm, name));
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  private static String fixtureClasspath() throws Exception {
    List<Class<?>> types = new ArrayList<>();
    // Gradle test workers use an isolated classloader; java.class.path alone
    // does not include the fixture and its actual runtime dependencies.
    for (String type : new String[] {
        SpringBootFixture.class.getName(), "org.springframework.boot.SpringApplication",
        "org.springframework.context.ApplicationContext", "org.springframework.beans.factory.BeanFactory",
        "org.springframework.core.SpringVersion", "org.springframework.expression.Expression",
        "org.springframework.aop.SpringProxy", "org.apache.commons.logging.LogFactory",
        "io.micrometer.observation.Observation", "io.micrometer.common.KeyValues"}) {
      types.add(Class.forName(type));
    }
    return DebuggeeClasspath.forClasses(types.toArray(Class<?>[]::new));
  }

  private static void drain(java.io.InputStream stream) {
    try (stream) { stream.transferTo(System.err); } catch (java.io.IOException ignored) {}
  }
}
