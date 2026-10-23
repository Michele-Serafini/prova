package benchmark;

import org.springframework.context.support.GenericApplicationContext;

public final class SpringBenchmarkFixture {
  public static final class Bean { int value=42; }
  public static final class Selected { int value=43; }
  public static GenericApplicationContext CONTEXT;
  public static Selected SELECTED;
  public static void main(String[] args) {
    CONTEXT=new GenericApplicationContext();
    SELECTED=new Selected();
    CONTEXT.getBeanFactory().registerSingleton("selected", SELECTED);
    for(int i=0;i<500;i++) CONTEXT.getBeanFactory().registerSingleton("bean"+i,new Bean());
    CONTEXT.refresh();
    pauseHere(SELECTED);
  }
  public static void pauseHere(Selected selected) { System.out.println("breakpoint"); }
}
