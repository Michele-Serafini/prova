package benchmark;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Measures only scans; JVM startup, JSON rendering, warmup and tracing are outside timing. */
public final class ScannerBenchmark {
  private record Scanner(Constructor<?> constructor, java.lang.reflect.Method scan) {
    static Scanner load(String directory) throws Exception {
      var loader = new URLClassLoader(new URL[]{Path.of(directory).toUri().toURL()}, ScannerBenchmark.class.getClassLoader());
      var type = loader.loadClass("pluginjson.scanner.RuntimeScannerService");
      return new Scanner(type.getConstructor(), type.getMethod("scanValue", Value.class));
    }
    Object scan(Value root) throws Exception { return scan.invoke(constructor.newInstance(), root); }
  }
  private static final class Commands extends OutputStream {
    private final StringBuilder line = new StringBuilder();
    private final Map<String,Integer> commands = new TreeMap<>();
    @Override public void write(int b) {
      if (b == '\n') {
        String text = line.toString(); line.setLength(0);
        if (text.startsWith("[JDI: Sending Command(")) {
          String name = text.substring(text.indexOf("JDWP."), text.length()-1);
          commands.merge(name, 1, Integer::sum);
        }
      } else if (b != '\r') line.append((char)b);
    }
    int count() { return commands.values().stream().mapToInt(Integer::intValue).sum(); }
  }
  private static Commands count(VirtualMachine vm, Scanner scanner, Value root) throws Exception {
    PrintStream previous=System.err;
    var commands=new Commands();
    try (var trace=new PrintStream(commands)) {
      System.setErr(trace);
      vm.setDebugTraceMode(VirtualMachine.TRACE_SENDS);
      scanner.scan(root);
    } finally {
      vm.setDebugTraceMode(VirtualMachine.TRACE_NONE);
      System.setErr(previous);
    }
    return commands;
  }
  private static String hash(String json) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  private static double median(double[] items) { Arrays.sort(items); return items[items.length/2]; }
  private static void benchmark(VirtualMachine vm, ReferenceType fixture, Scanner before, Scanner after) throws Exception {
    for (String name : List.of("ROWS", "WIDE", "SPARSE", "INTEGER")) {
      Value root = fixture.getValue(fixture.fieldByName(name));
      String expected=before.scan(root).toString();
      if (after!=null && !expected.equals(after.scan(root).toString())) throw new AssertionError(name+" JSON mismatch");
      before.scan(root); if (after!=null) after.scan(root);
      double[] baseline=new double[5], current=new double[5];
      for (int i=0;i<5;i++) {
        // Alternate execution order to reduce warmup and scheduling bias.
        for (int j=0;j<(after==null?1:2);j++) {
          boolean old=after==null || ((i+j)%2==0);
          long start=System.nanoTime();
          Object snapshot=(old?before:after).scan(root);
          double elapsed=(System.nanoTime()-start)/1_000_000.0;
          if (old) baseline[i]=elapsed; else current[i]=elapsed;
          if (!expected.equals(snapshot.toString())) throw new AssertionError(name+" non-repeatable snapshot");
        }
      }
      Commands oldCount=count(vm,before,root);
      Commands newCount=after==null?null:count(vm,after,root);
      System.out.printf(Locale.ROOT,"%s chars=%d sha256=%s baseline_ms=%.3f baseline_jdwp=%d",name,expected.length(),hash(expected),median(baseline),oldCount.count());
      if(after!=null) System.out.printf(Locale.ROOT," current_ms=%.3f current_jdwp=%d speedup=%.2fx command_reduction=%.2f%%",median(current),newCount.count(),median(baseline)/median(current),100.0*(oldCount.count()-newCount.count())/oldCount.count());
      System.out.println();
      System.out.println("  baseline commands="+oldCount.commands);
      if(after!=null) System.out.println("  current commands="+newCount.commands);
    }
  }
  public static void main(String[] args) throws Exception {
    Scanner before=Scanner.load(args[0]), after=args.length>1?Scanner.load(args[1]):null;
    LaunchingConnector connector=Bootstrap.virtualMachineManager().defaultConnector();
    Map<String,Connector.Argument> options=connector.defaultArguments();
    options.get("main").setValue("benchmark.BenchmarkFixture");
    options.get("options").setValue("-cp \""+System.getProperty("java.class.path")+"\"");
    VirtualMachine vm=connector.launch(options);
    try {
      ClassPrepareRequest prepare=vm.eventRequestManager().createClassPrepareRequest();
      // launch() has already queued a suspending VMStartEvent. Resume that
      // event in the loop, once, so a second resume cannot release ClassPrepare.
      prepare.addClassFilter("benchmark.BenchmarkFixture"); prepare.enable();
      long deadline=System.nanoTime()+60_000_000_000L;
      while(System.nanoTime()<deadline) {
        EventSet events=vm.eventQueue().remove(5_000); if(events==null)continue;
        for(Event event:events) {
          if(event instanceof ClassPrepareEvent ready) {
            var breakpoint=vm.eventRequestManager().createBreakpointRequest(ready.referenceType().methodsByName("pauseHere").getFirst().location());
            breakpoint.enable();
          }
          if(event instanceof BreakpointEvent stopped) {benchmark(vm,stopped.location().declaringType(),before,after); return;}
          if(event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) throw new AssertionError("Fixture terminated before breakpoint");
        }
        events.resume();
      }
      throw new AssertionError("Breakpoint timeout");
    } finally { try{vm.dispose();}catch(VMDisconnectedException ignored){} }
  }
}
