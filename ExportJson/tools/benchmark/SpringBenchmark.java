package benchmark;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public final class SpringBenchmark {
  private record Locator(Constructor<?> constructor, java.lang.reflect.Method byType, java.lang.reflect.Method identity) {
    static Locator load(String directory) throws Exception {
      var loader=new URLClassLoader(new URL[]{Path.of(directory).toUri().toURL()},SpringBenchmark.class.getClassLoader());
      var type=loader.loadClass("pluginjson.scanner.SpringBeanLocator");
      return new Locator(type.getConstructor(),type.getMethod("findBeanByType",VirtualMachine.class,String.class),type.getMethod("isSpringBean",VirtualMachine.class,Value.class));
    }
    Object run(VirtualMachine vm,Value selected,boolean byType) throws Exception {
      Object locator=constructor.newInstance();
      return byType?this.byType.invoke(locator,vm,"benchmark.SpringBenchmarkFixture$Selected"):identity.invoke(locator,vm,selected);
    }
  }
  private static final class Commands extends OutputStream {
    private final StringBuilder line=new StringBuilder();
    private final Map<String,Integer> commands=new TreeMap<>();
    @Override public void write(int b) {
      if(b=='\n') {
        String text=line.toString();line.setLength(0);
        if(text.startsWith("[JDI: Sending Command(")) commands.merge(text.substring(text.indexOf("JDWP."),text.length()-1),1,Integer::sum);
      }else if(b!='\r')line.append((char)b);
    }
    int count(){return commands.values().stream().mapToInt(Integer::intValue).sum();}
  }
  private static Commands count(VirtualMachine vm,Locator locator,Value root,boolean byType)throws Exception {
    PrintStream previous=System.err;var commands=new Commands();
    try(var trace=new PrintStream(commands)) {
      System.setErr(trace);vm.setDebugTraceMode(VirtualMachine.TRACE_SENDS);locator.run(vm,root,byType);
    }finally{vm.setDebugTraceMode(VirtualMachine.TRACE_NONE);System.setErr(previous);}
    return commands;
  }
  private static double median(double[] values){Arrays.sort(values);return values[values.length/2];}
  private static void benchmark(VirtualMachine vm,ReferenceType fixture,Locator before,Locator after)throws Exception {
    Value root=fixture.getValue(fixture.fieldByName("SELECTED"));
    for(boolean byType:new boolean[]{true,false}) {
      String name=byType?"SPRING_TYPE_500":"SPRING_IDENTITY_500";
      Object expected=byType?root:Boolean.TRUE;
      if(!expected.equals(before.run(vm,root,byType)))throw new AssertionError("baseline result");
      if(after!=null&&!expected.equals(after.run(vm,root,byType)))throw new AssertionError("current result");
      before.run(vm,root,byType);if(after!=null)after.run(vm,root,byType);
      double[] baseline=new double[5],current=new double[5];
      for(int i=0;i<5;i++)for(int j=0;j<(after==null?1:2);j++){
        boolean old=after==null||(i+j)%2==0;long start=System.nanoTime();
        Object result=(old?before:after).run(vm,root,byType);
        double elapsed=(System.nanoTime()-start)/1_000_000.0;
        if(!expected.equals(result))throw new AssertionError("non-repeatable result");
        if(old)baseline[i]=elapsed;else current[i]=elapsed;
      }
      Commands oldCount=count(vm,before,root,byType),newCount=after==null?null:count(vm,after,root,byType);
      System.out.printf(Locale.ROOT,"%s baseline_ms=%.3f baseline_jdwp=%d",name,median(baseline),oldCount.count());
      if(after!=null)System.out.printf(Locale.ROOT," current_ms=%.3f current_jdwp=%d speedup=%.2fx command_reduction=%.2f%%",median(current),newCount.count(),median(baseline)/median(current),100.0*(oldCount.count()-newCount.count())/oldCount.count());
      System.out.println();System.out.println("  baseline commands="+oldCount.commands);
      if(after!=null)System.out.println("  current commands="+newCount.commands);
    }
  }
  public static void main(String[] args)throws Exception {
    Locator before=Locator.load(args[0]),after=args.length>1?Locator.load(args[1]):null;
    LaunchingConnector connector=Bootstrap.virtualMachineManager().defaultConnector();
    Map<String,Connector.Argument> options=connector.defaultArguments();
    options.get("main").setValue("benchmark.SpringBenchmarkFixture");
    options.get("options").setValue("-cp \""+System.getProperty("java.class.path")+"\"");
    VirtualMachine vm=connector.launch(options);
    try {
      ClassPrepareRequest prepare=vm.eventRequestManager().createClassPrepareRequest();
      // Resume the launcher's queued VMStartEvent in the loop exactly once.
      prepare.addClassFilter("benchmark.SpringBenchmarkFixture");prepare.enable();
      long deadline=System.nanoTime()+60_000_000_000L;
      while(System.nanoTime()<deadline){
        EventSet events=vm.eventQueue().remove(5_000);if(events==null)continue;
        for(Event event:events){
          if(event instanceof ClassPrepareEvent ready){var breakpoint=vm.eventRequestManager().createBreakpointRequest(ready.referenceType().methodsByName("pauseHere").getFirst().location());breakpoint.enable();}
          if(event instanceof BreakpointEvent stopped){benchmark(vm,stopped.location().declaringType(),before,after);return;}
          if(event instanceof VMDeathEvent||event instanceof VMDisconnectEvent)throw new AssertionError("Fixture terminated before breakpoint");
        }
        events.resume();
      }
      throw new AssertionError("Breakpoint timeout");
    }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}}
  }
}
