package pluginjson.scanner;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.TargetSource;
import org.springframework.aop.SpringProxy;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.target.AbstractLazyCreationTargetSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;

/** Runs only in the debuggee JVM. Any accidental lazy invocation fails the test. */
public final class SpringBootFixture {
  public interface Service { String text(); }
  public static final class ServiceImpl implements Service {
    public String value = "JDK proxy target";
    public String text() { throw new AssertionError("Do not invoke target methods"); }
  }
  public static class Payload {
    public String message = "actual Spring Boot value";
    public int count = 42;
    public Payload self = this;
    public Object dependency;
    public Object repeated;
    public Object lazyDependency;
    public Object fakeDependency;
    public ConfigurableApplicationContext context;
  }
  public static final class ProductFactory implements FactoryBean<Payload> {
    public Payload getObject() { throw new AssertionError("Do not invoke getObject"); }
    public Class<?> getObjectType() { return Payload.class; }
    public boolean isSingleton() { return true; }
  }
  public static final class CachedFactory implements FactoryBean<Payload> {
    public Payload getObject() { return new Payload(); }
    public Class<?> getObjectType() { return Payload.class; }
    public boolean isSingleton() { return true; }
  }
  public static final class UniqueSettings {
    public String mode = "managed settings";
  }
  public static final class StaticPayloadSource implements TargetSource {
    private final Payload value = new Payload();
    private int calls;
    public Class<?> getTargetClass() { return Payload.class; }
    public boolean isStatic() { return true; }
    public Object getTarget() { calls++; return value; }
    public void releaseTarget(Object target) { throw new AssertionError("No target release during export"); }
  }
  static StaticPayloadSource STATIC_SOURCE;
  static UniqueSettings UNMANAGED = new UniqueSettings();
  static String SPARSE_NAME;
  public static final class PauseHolder {
    private final Payload injected;
    private final Payload nullField = null;
    PauseHolder(Payload bean) { injected = bean; }
    void pauseHere(Payload localBean, Payload injected, Payload nullBean) {
      System.out.println("instance fixture breakpoint");
    }
  }
  @Configuration(proxyBeanMethods = false)
  public static class Config {
    @Bean(name = {"payload", "payloadAlias"})
    public Payload differentMethodName(ConfigurableApplicationContext context) {
      Payload payload = new Payload();
      payload.dependency = new ProxyFactory(new ServiceImpl()).getProxy();
      payload.repeated = payload.dependency;
      payload.fakeDependency = Proxy.newProxyInstance(Config.class.getClassLoader(),
          new Class<?>[] {Service.class, SpringProxy.class},
          (proxy, method, args) -> { throw new AssertionError("Fake handler must not execute"); });
      payload.context = context;
      ProxyFactory lazy = new ProxyFactory();
      lazy.setTargetSource(new AbstractLazyCreationTargetSource() {
        public Class<?> getTargetClass() { return Payload.class; }
        protected Object createObject() { throw new AssertionError("Lazy target invoked"); }
      });
      lazy.setProxyTargetClass(true);
      payload.lazyDependency = lazy.getProxy();
      return payload;
    }
    @Bean @Lazy public Payload lazyPayload() { throw new AssertionError("Lazy bean initialized"); }
    @Bean @Scope("prototype") public Payload prototypePayload() {
      throw new AssertionError("Prototype bean created");
    }
    @Bean(name = {"product", "factoryAlias"}) public ProductFactory product() { return new ProductFactory(); }
    @Bean public CachedFactory cached() { return new CachedFactory(); }
    @Bean public UniqueSettings settings() { return new UniqueSettings(); }
    @Bean public Object nullBean() { return null; }
    @Bean public String[] textArray() { return new String[] {"first", "second"}; }
    @Bean public int[] primitiveArray() { return new int[] {1, 2}; }
    @Bean public int[][] matrix() { return new int[][] {{1, 2}}; }
    @Bean public Integer numberBean() { return 123; }
    @Bean public Object frozenOpaque() {
      ProxyFactory proxy = new ProxyFactory(new Payload());
      proxy.setProxyTargetClass(true);
      proxy.setFrozen(true);
      proxy.setOpaque(true);
      return proxy.getProxy();
    }
    @Bean public Object capturedStaticTarget() {
      STATIC_SOURCE = new StaticPayloadSource();
      ProxyFactory proxy = new ProxyFactory();
      proxy.setTargetSource(STATIC_SOURCE);
      proxy.setProxyTargetClass(true);
      proxy.setFrozen(true);
      proxy.setOpaque(true);
      return proxy.getProxy();
    }
    @Bean public Object jdkProxied() { return new ProxyFactory(new ServiceImpl()).getProxy(); }
    @Bean public Object cglibProxied() {
      ProxyFactory proxy = new ProxyFactory(new Payload());
      proxy.setProxyTargetClass(true);
      return proxy.getProxy();
    }
  }
  // Discovery must use type inheritance, not the class name.
  public static class Store extends AnnotationConfigApplicationContext {
    // Intentionally shadow private Spring fields: the locator must read the
    // framework declarations rather than unrelated application state.
    private boolean active = false;
    private Object beanFactory = "not a factory";
    Store() { super(Config.class); }
  }
  static ConfigurableApplicationContext boot;
  static Store first;
  static Store second;

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(Config.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setBannerMode(Banner.Mode.OFF);
    app.setLogStartupInfo(false);
    boot = app.run();
    boot.getBean("cached"); // Materialize exactly one FactoryBean product.
    Payload bootBean = (Payload) boot.getBean("payload");
    new PauseHolder(bootBean).pauseHere(bootBean, new Payload(), null);
    boot.close();
    first = new Store();
    first.getBean("cached");
    Payload firstBean = (Payload) first.getBean("payload");
    new PauseHolder(firstBean).pauseHere(firstBean, new Payload(), null);
    widenSingletonTable(first);
    pauseSparse();
    second = new Store();
    pauseHere();
    second.close();
    first.close();
  }
  private static void widenSingletonTable(Store context) {
    try {
      var field = org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.class
          .getDeclaredField("singletonObjects");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var previous = (java.util.Map<String, Object>) field.get(context.getBeanFactory());
      var wide = new ConcurrentHashMap<String, Object>(1 << 18);
      wide.putAll(previous);
      for (int index = 0; ; index++) {
        String name = "farBucketBean" + index;
        int hash = name.hashCode() ^ (name.hashCode() >>> 16);
        if (((1 << 19) - 1 & hash) > 100_000) {
          SPARSE_NAME = name;
          wide.put(name, new UniqueSettings());
          break;
        }
      }
      field.set(context.getBeanFactory(), wide);
    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }
  static void pauseSparse() { System.out.println("sparse cache breakpoint"); }
  static void pauseHere() { System.out.println("fixture breakpoint"); }
}
