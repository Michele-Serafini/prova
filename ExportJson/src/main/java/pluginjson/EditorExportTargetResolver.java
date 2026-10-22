package pluginjson;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import pluginjson.scanner.ExportTarget;

/** Resolves a source symbol without evaluating Java expressions or calling getters. */
final class EditorExportTargetResolver {
  private static final Set<String> COMPONENTS = Set.of(
      "org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
      "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller",
      "org.springframework.web.bind.annotation.RestController",
      "org.springframework.context.annotation.Configuration",
      "jakarta.inject.Named", "javax.inject.Named");
  private static final Set<String> INJECTION = Set.of(
      "org.springframework.beans.factory.annotation.Autowired",
      "org.springframework.beans.factory.annotation.Qualifier",
      "jakarta.inject.Inject", "javax.inject.Inject",
      "jakarta.annotation.Resource", "javax.annotation.Resource");

  ExportTarget resolve(Project project, Editor editor) {
    PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
    documents.commitDocument(editor.getDocument());
    PsiFile file = documents.getPsiFile(editor.getDocument());
    if (!(file instanceof PsiJavaFile)) return null;
    int offset = editor.getCaretModel().getOffset();
    if (editor.getSelectionModel().hasSelection()) {
      String selected = editor.getSelectionModel().getSelectedText();
      // A selected dotted field expression identifies its final member.
      // Reject arbitrary statements rather than exporting a different symbol.
      if (selected == null || !selected.trim().matches(
          "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
              + "(\\s*\\.\\s*[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")) {
        throw new IllegalArgumentException("Seleziona soltanto il nome Java o una catena di campi, "
            + "ad esempio this.cliente oppure ordine.cliente.");
      }
      offset = editor.getSelectionModel().getSelectionEnd() - 1;
      while (offset > editor.getSelectionModel().getSelectionStart()
          && Character.isWhitespace(editor.getDocument().getCharsSequence().charAt(offset))) offset--;
    }
    PsiElement element = file.findElementAt(offset);
    if (!(element instanceof PsiIdentifier) && offset > 0) {
      PsiElement previous = file.findElementAt(offset - 1);
      if (previous instanceof PsiIdentifier) element = previous;
    }
    return resolveElement(element);
  }

  ExportTarget resolveElement(PsiElement element) {
    if (element == null) return null;
    if (element.getParent() instanceof PsiThisExpression) {
      return new ExportTarget("this", ExportTarget.Kind.THIS, null, null, false, false, null);
    }
    PsiIdentifier identifier = element instanceof PsiIdentifier id ? id
        : PsiTreeUtil.getParentOfType(element, PsiIdentifier.class, false);
    if (identifier == null) return null;
    PsiElement parent = identifier.getParent();
    PsiReferenceExpression reference = parent instanceof PsiReferenceExpression ref ? ref : null;
    PsiElement symbol = reference == null ? parent : reference.resolve();
    if (symbol instanceof PsiMethod method) {
      PsiAnnotation bean = method.getModifierList().findAnnotation(
          "org.springframework.context.annotation.Bean");
      if (bean == null) return null;
      String name = beanName(bean, method.getName());
      String lookupName = factoryBean(method.getReturnType()) ? "&" + name : name;
      return new ExportTarget(name, ExportTarget.Kind.BEAN, typeName(method.getReturnType()),
          lookupName, true, true, null);
    }
    if (symbol instanceof PsiClass type && component(type, new HashSet<>(), 0)) {
      return new ExportTarget(type.getName(), ExportTarget.Kind.SPRING_TYPE,
          ClassUtil.getJVMClassName(type), null, false, true, null);
    }
    if (!(symbol instanceof PsiVariable variable)) return null;
    PsiType type = variable.getType();
    PsiClass typeClass = type instanceof PsiClassType classType ? classType.resolve() : null;
    boolean springHint = injected(variable)
        || (typeClass != null && component(typeClass, new HashSet<>(), 0));
    String explicit = explicitBeanName(variable);
    if (variable instanceof PsiField field) {
      ExportTarget.FieldPath path = path(field, reference);
      return new ExportTarget(field.getName(), ExportTarget.Kind.FIELD, typeName(type),
          explicit == null ? field.getName() : explicit, explicit != null, springHint, path);
    }
    return new ExportTarget(variable.getName(), ExportTarget.Kind.LOCAL, typeName(type),
        explicit == null ? variable.getName() : explicit, explicit != null, springHint, null);
  }

  private ExportTarget.FieldPath path(PsiField field, PsiReferenceExpression reference) {
    PsiClass declaringClass = field.getContainingClass();
    if (declaringClass == null) throw new IllegalArgumentException(
        "Seleziona l'array stesso: questo membro sintetico non è un campo leggibile tramite JDI.");
    String owner = ClassUtil.getJVMClassName(declaringClass);
    if (owner == null) throw new IllegalArgumentException("Classe del campo non risolvibile.");
    ExportTarget.FieldKey key = new ExportTarget.FieldKey(owner, field.getName());
    if (field.hasModifierProperty(PsiModifier.STATIC)) {
      return new ExportTarget.FieldPath(ExportTarget.Root.STATIC, owner, List.of(key));
    }
    PsiExpression qualifier = reference == null ? null : reference.getQualifierExpression();
    ExportTarget.FieldPath base = qualifierPath(qualifier);
    List<ExportTarget.FieldKey> fields = new ArrayList<>(base.fields());
    fields.add(key);
    return new ExportTarget.FieldPath(base.root(), base.rootName(), fields);
  }

  private ExportTarget.FieldPath qualifierPath(PsiExpression qualifier) {
    if (qualifier == null || qualifier instanceof PsiSuperExpression) {
      return new ExportTarget.FieldPath(ExportTarget.Root.THIS, null, List.of());
    }
    if (qualifier instanceof PsiThisExpression self && self.getQualifier() == null) {
      return new ExportTarget.FieldPath(ExportTarget.Root.THIS, null, List.of());
    }
    if (qualifier instanceof PsiParenthesizedExpression parentheses) {
      return qualifierPath(parentheses.getExpression());
    }
    if (qualifier instanceof PsiReferenceExpression reference) {
      PsiElement resolved = reference.resolve();
      if (resolved instanceof PsiField field) return path(field, reference);
      if (resolved instanceof PsiVariable variable) {
        return new ExportTarget.FieldPath(ExportTarget.Root.LOCAL, variable.getName(), List.of());
      }
    }
    throw new IllegalArgumentException("Seleziona una variabile o una catena di campi senza "
        + "chiamate a metodi. Il plugin non esegue codice nell'applicazione sospesa.");
  }

  private String typeName(PsiType type) {
    if (type == null) return null;
    if (type instanceof PsiClassType classType) {
      PsiClass resolved = classType.resolve();
      if (resolved != null) return ClassUtil.getJVMClassName(resolved);
    }
    return type.getCanonicalText();
  }

  private boolean factoryBean(PsiType type) {
    if (!(type instanceof PsiClassType classType)) return false;
    PsiClass resolved = classType.resolve();
    if (resolved == null) return false;
    java.util.ArrayDeque<PsiClass> pending = new java.util.ArrayDeque<>();
    Set<PsiClass> seen = new HashSet<>();
    pending.add(resolved);
    while (!pending.isEmpty() && seen.size() < 128) {
      PsiClass current = pending.removeFirst();
      if (!seen.add(current)) continue;
      if ("org.springframework.beans.factory.FactoryBean".equals(current.getQualifiedName())) return true;
      java.util.Collections.addAll(pending, current.getSupers());
    }
    return false;
  }

  private String beanName(PsiAnnotation bean, String defaultName) {
    for (String attribute : List.of("name", "value")) {
      PsiAnnotationMemberValue value = bean.findDeclaredAttributeValue(attribute);
      if (value instanceof PsiArrayInitializerMemberValue array) {
        for (PsiAnnotationMemberValue entry : array.getInitializers()) {
          String name = constant(entry);
          if (name != null && !name.isBlank()) return name;
        }
      } else {
        String name = constant(value);
        if (name != null && !name.isBlank()) return name;
      }
    }
    return defaultName;
  }

  private String constant(PsiAnnotationMemberValue value) {
    if (value == null) return null;
    Object constant = JavaPsiFacade.getInstance(value.getProject())
        .getConstantEvaluationHelper().computeConstantExpression(value);
    return constant instanceof String text ? text : null;
  }

  private boolean injected(PsiModifierListOwner variable) {
    PsiModifierList modifiers = variable.getModifierList();
    if (modifiers == null) return false;
    for (PsiAnnotation annotation : modifiers.getAnnotations()) {
      if (INJECTION.contains(annotation.getQualifiedName())) return true;
    }
    return false;
  }

  private String explicitBeanName(PsiModifierListOwner variable) {
    PsiModifierList modifiers = variable.getModifierList();
    if (modifiers == null) return null;
    for (PsiAnnotation annotation : modifiers.getAnnotations()) {
      String type = annotation.getQualifiedName();
      String name = null;
      if ("org.springframework.beans.factory.annotation.Qualifier".equals(type)) {
        name = constant(annotation.findDeclaredAttributeValue("value"));
      } else if ("jakarta.annotation.Resource".equals(type) || "javax.annotation.Resource".equals(type)) {
        name = constant(annotation.findDeclaredAttributeValue("name"));
      }
      if (name != null && !name.isBlank()) return name;
    }
    return null;
  }

  private boolean component(PsiModifierListOwner owner, Set<String> visited, int depth) {
    if (depth > 4 || owner.getModifierList() == null) return false;
    for (PsiAnnotation annotation : owner.getModifierList().getAnnotations()) {
      String name = annotation.getQualifiedName();
      if (name == null || !visited.add(name)) continue;
      if (COMPONENTS.contains(name)) return true;
      if (name.startsWith("java.")) continue;
      PsiClass annotationType = annotation.resolveAnnotationType();
      if (annotationType != null && component(annotationType, visited, depth + 1)) return true;
    }
    return false;
  }
}
