package pluginjson.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public class JsonExporterService {

  private static final Logger LOG =
      Logger.getInstance(JsonExporterService.class);

  private static final String DIALOG_TITLE =
      "Export Object to JSON";

  private static final String OUTPUT_DIRECTORY =
      "JSON";
  private static final Pattern UNSAFE_FILE_NAME = Pattern.compile("[^a-zA-Z0-9._-]");

  /*
   * I millisecondi evitano collisioni quando due esportazioni
   * vengono effettuate nello stesso secondo.
   */
  private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern(
          "yyyy-MM-dd_HH-mm-ss-SSS"
      );

  /*
   * Gson è thread-safe dopo la configurazione,
   * quindi possiamo riutilizzare una singola istanza.
   */
  private static final Gson GSON =
      new GsonBuilder()
          .setPrettyPrinting()
          .serializeNulls()
          .create();

  public void write(
      @NotNull Project project,
      @NotNull String fileName,
      @NotNull JsonElement json
  ) {

    /*
     * Serializzazione e scrittura vengono eseguite
     * fuori dal thread dell'interfaccia.
     */
    ApplicationManager
        .getApplication()
        .executeOnPooledThread(() ->
            writeInBackground(
                project,
                fileName,
                json
            )
        );
  }

  private void writeInBackground(
      @NotNull Project project,
      @NotNull String fileName,
      @NotNull JsonElement json
  ) {

    if (project.isDisposed()) {
      return;
    }

    try {

      String basePath = project.getBasePath();

      if (basePath == null) {

        showError(
            project,
            "Il progetto non ha una directory di base."
        );

        return;
      }

      Path outputFolder =
          Path.of(
              basePath,
              OUTPUT_DIRECTORY
          );

      Path outputFile = writeSnapshot(outputFolder, fileName, json);

      refreshAndNotify(
          project,
          outputFile
      );

    } catch (Exception exception) {

      LOG.warn(
          "Errore durante la scrittura del file JSON",
          exception
      );

      showError(
          project,
          "Errore durante la scrittura del JSON:\n"
              + getErrorMessage(exception)
      );
    }
  }

  /** Streams directly to disk without retaining a second, formatted JSON copy. */
  static Path writeSnapshot(Path outputFolder, String fileName, JsonElement json) throws java.io.IOException {
    Files.createDirectories(outputFolder);
    String safeFileName = sanitizeFileName(fileName);
    if (safeFileName.length() > 100) safeFileName = safeFileName.substring(0, 100);
    String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
    // Atomically reserve a unique path even for simultaneous exports.
    Path outputFile = Files.createTempFile(outputFolder, safeFileName + "_" + timestamp + "_", ".json");
    try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
      GSON.toJson(json, writer);
    } catch (java.io.IOException | RuntimeException failure) {
      try { Files.deleteIfExists(outputFile); }
      catch (java.io.IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
      throw failure;
    }
    return outputFile;
  }

  private void refreshAndNotify(
      @NotNull Project project,
      @NotNull Path outputFile
  ) {

    // Refresh asynchronously: synchronous disk refresh on the UI thread can
    // freeze the IDE for large snapshots or slow/network-mounted projects.
    LocalFileSystem.getInstance().refreshNioFiles(List.of(outputFile), true, false,
        () -> ApplicationManager.getApplication().invokeLater(() -> {

          if (project.isDisposed()) {
            return;
          }

          Messages.showInfoMessage(
              project,
              "JSON creato in:\n"
                  + outputFile.toAbsolutePath(),
              DIALOG_TITLE
          );
        }));
  }

  private void showError(
      @NotNull Project project,
      @NotNull String message
  ) {

    ApplicationManager
        .getApplication()
        .invokeLater(() -> {

          if (project.isDisposed()) {
            return;
          }

          Messages.showErrorDialog(
              project,
              message,
              DIALOG_TITLE
          );
        });
  }

  private static String sanitizeFileName(
      @NotNull String fileName
  ) {

    if (fileName.isBlank()) {
      return "Object";
    }

    String sanitized =
        UNSAFE_FILE_NAME.matcher(fileName).replaceAll("_");

    return sanitized.isBlank()
        ? "Object"
        : sanitized;
  }

  private String getErrorMessage(
      @NotNull Exception exception
  ) {

    String message = exception.getMessage();

    if (message == null || message.isBlank()) {
      return exception
          .getClass()
          .getSimpleName();
    }

    return message;
  }
}
