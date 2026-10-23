package pluginjson.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JsonExporterServiceTest {
  @TempDir Path directory;

  @Test
  void simultaneousSnapshotsPreserveUnicodeNullsAndNeverOverwriteEachOther() throws Exception {
    JsonObject json = new JsonObject();
    json.addProperty("text", "Caffè ☕ 漢字 ".repeat(10_000));
    json.add("missing", com.google.gson.JsonNull.INSTANCE);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Path>>();
      for (int i = 0; i < 12; i++) {
        jobs.add(() -> JsonExporterService.writeSnapshot(directory.resolve("JSON"), "../../cliente", json));
      }
      var paths = new HashSet<Path>();
      for (var result : workers.invokeAll(jobs)) {
        Path path = result.get();
        assertEquals(directory.resolve("JSON"), path.getParent());
        assertTrue(paths.add(path), "Each concurrent export must reserve a distinct file");
        assertEquals(json, JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)));
      }
      assertEquals(12, paths.size());
    }
  }
}
