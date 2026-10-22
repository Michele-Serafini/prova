package pluginjson;

import com.google.gson.JsonElement;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.engine.managerThread.DebuggerCommand;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import pluginjson.scanner.*;

/** One editor command for runtime variables, fields and Spring-managed objects. */
public final class ExportRuntimeFromEditorAction extends AnAction {
  private static final Logger LOG = Logger.getInstance(ExportRuntimeFromEditorAction.class);
  private static final String TITLE = "Export Object to JSON";

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    if (project == null || editor == null || project.isDisposed()) return;
    ExportTarget target;
    try {
      target = new EditorExportTargetResolver().resolve(project, editor);
    } catch (IllegalArgumentException error) {
      warning(project, error.getMessage());
      return;
    }
    if (target == null) {
      warning(project, "Seleziona una variabile, un campo Java, un metodo @Bean "
          + "oppure una classe componente Spring.");
      return;
    }
    DebuggerSession session = session(project);
    StackFrameProxyImpl frame = session == null ? null
        : session.getContextManager().getContext().getFrameProxy();
    if (frame == null) {
      warning(project, "Il debugger deve essere fermo su un breakpoint con un frame selezionato.");
      return;
    }
    session.getProcess().getManagerThread().invokeCommand(new DebuggerCommand() {
      @Override
      public void action() {
        try {
          RuntimeValueResolver.ResolvedValue resolved =
              new RuntimeValueResolver().resolve(frame.getStackFrame(), target);
          JsonElement json = new RuntimeScannerService(resolved.springMode()).scanValue(resolved.value());
          new JsonExporterService().write(project, target.name(), json);
        } catch (Exception error) {
          LOG.warn("Impossibile esportare " + target.name(), error);
          String message = error.getMessage();
          warning(project, "Impossibile esportare '" + target.name() + "':\n"
              + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message));
        }
      }
      @Override
      public void commandCancelled() {
        LOG.debug("Esportazione annullata: " + target.name());
      }
    });
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    boolean visible = project != null && !project.isDisposed()
        && event.getData(CommonDataKeys.EDITOR) != null
        && event.getData(CommonDataKeys.PSI_FILE) instanceof PsiJavaFile;
    DebuggerSession session = visible ? session(project) : null;
    event.getPresentation().setVisible(visible);
    event.getPresentation().setEnabled(session != null
        && session.getContextManager().getContext().getFrameProxy() != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  private DebuggerSession session(Project project) {
    return DebuggerManagerEx.getInstanceEx(project).getContext().getDebuggerSession();
  }

  private void warning(Project project, String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) Messages.showWarningDialog(project, message, TITLE);
    });
  }
}
