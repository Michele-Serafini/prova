package pluginjson.scanner;

/** No Spring or IDE dependency: actual local/field values for the unified action. */
public final class ResolutionFixture {
  static final String STATIC_VALUE = "static runtime value";

  public static class Base {
    private String shadow = "private base field";
  }

  public static final class Holder {
    String value;
    Holder(String value) { this.value = value; }
  }

  public static final class Child extends Base {
    String shadow = "child field";
    String nullField;
    Holder holder = new Holder("this holder value");

    void instancePause(String shadow, String nullLocal, Holder other, Holder missingReceiver) {
      // The parameters are initialized at method entry and retained in the LVT.
      System.out.println(shadow);
      System.out.println(nullLocal);
      System.out.println(other);
      System.out.println(missingReceiver);
    }
  }

  static void staticPause(String local) { System.out.println(local); }

  public static void main(String[] args) {
    new Child().instancePause("local shadows field", null, new Holder("other holder value"), null);
    staticPause("static frame local");
  }
}
