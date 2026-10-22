package pluginjson.scanner;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Works with both IntelliJ's PathClassLoader and ordinary JUnit classloaders. */
final class DebuggeeClasspath {
  private DebuggeeClasspath() {}

  static String forClasses(Class<?>... types) throws Exception {
    Set<String> paths = new LinkedHashSet<>();
    for (Class<?> type : types) {
      String name = type.getName().replace('.', '/') + ".class";
      // PathClassLoader can deliberately return a null CodeSource location.
      // The class resource still identifies its real directory or JAR.
      URL resource = type.getResource("/" + name);
      if (resource == null) throw new IllegalStateException("Missing class resource: " + name);
      Path root;
      if (resource.getProtocol().equals("jar")) {
        // IntelliJ uses its own URLConnection for JAR resources.
        String path = resource.getPath();
        int separator = path.lastIndexOf("!/");
        if (separator < 0) throw new IllegalStateException("Invalid JAR URL: " + resource);
        root = Path.of(URI.create(path.substring(0, separator)));
      } else if (resource.getProtocol().equals("file")) {
        root = Path.of(resource.toURI());
        for (String ignored : name.split("/")) root = root.getParent();
      } else {
        throw new IllegalStateException("Unsupported fixture URL: " + resource);
      }
      paths.add(root.toString());
    }
    return String.join(File.pathSeparator, paths);
  }
}
