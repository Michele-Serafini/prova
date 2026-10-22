package pluginjson;

import com.intellij.core.JavaCoreApplicationEnvironment;
import com.intellij.core.JavaCoreProjectEnvironment;
import com.intellij.core.CoreApplicationEnvironment;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.PsiElementFinderImpl;
import com.intellij.lang.jvm.facade.JvmElementProvider;
import java.io.File;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import pluginjson.scanner.ExportTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Real Java PSI parsing and symbol resolution, without UI/editor mocks. */
public class EditorExportTargetResolverTest {
  @Test
  void recognizesBeanConstantsQualifiersFieldsAndComponentsFromJavaPsi() {
    Disposable disposable = Disposer.newDisposable();
    try {
      var application = new JavaCoreApplicationEnvironment(disposable);
      CoreApplicationEnvironment.registerApplicationExtensionPoint(
          PsiAugmentProvider.EP_NAME, PsiAugmentProvider.class);
      CoreApplicationEnvironment.registerApplicationExtensionPoint(
          JavaModuleSystem.EP_NAME, JavaModuleSystem.class);
      var environment = new JavaCoreProjectEnvironment(disposable, application);
      CoreApplicationEnvironment.registerExtensionPoint(environment.getProject().getExtensionArea(),
          PsiElementFinder.EP.getName(), PsiElementFinder.class);
      CoreApplicationEnvironment.registerExtensionPoint(environment.getProject().getExtensionArea(),
          JvmElementProvider.EP_NAME, JvmElementProvider.class);
      environment.getProject().getExtensionArea().getExtensionPoint(PsiElementFinder.EP.getName())
          .registerExtension(new PsiElementFinderImpl(environment.getProject()), disposable);
      environment.addJarToClassPath(new File(jarPath(org.springframework.context.annotation.Bean.class)));
      environment.addJarToClassPath(new File(jarPath(org.springframework.beans.factory.annotation.Autowired.class)));
      environment.addJarToClassPath(new File(jarPath(org.springframework.core.annotation.AliasFor.class)));
      application.getApplication().runReadAction(() -> {
        PsiJavaFile file = (PsiJavaFile) PsiFileFactory.getInstance(environment.getProject())
            .createFileFromText("Config.java", JavaFileType.INSTANCE, SOURCE);
        EditorExportTargetResolver resolver = new EditorExportTargetResolver();

        PsiMethod factory = PsiTreeUtil.findChildrenOfType(file, PsiMethod.class).stream()
            .filter(method -> method.getName().equals("create")).findFirst().orElseThrow();
        ExportTarget bean = resolver.resolveElement(factory.getNameIdentifier());
        assertEquals(ExportTarget.Kind.BEAN, bean.kind());
        assertEquals("customerBean", bean.beanName());
        assertTrue(bean.explicitBeanName());
        assertTrue(bean.springHint());

        PsiMethod factoryBeanMethod = PsiTreeUtil.findChildrenOfType(file, PsiMethod.class).stream()
            .filter(method -> method.getName().equals("makeFactory")).findFirst().orElseThrow();
        ExportTarget factoryBean = resolver.resolveElement(factoryBeanMethod.getNameIdentifier());
        assertEquals(ExportTarget.Kind.BEAN, factoryBean.kind());
        assertEquals("&makeFactory", factoryBean.beanName(),
            "Selecting a @Bean method returning FactoryBean exports the returned factory object");

        PsiField dependency = PsiTreeUtil.findChildrenOfType(file, PsiField.class).stream()
            .filter(field -> field.getName().equals("dependency")).findFirst().orElseThrow();
        ExportTarget injected = resolver.resolveElement(dependency.getNameIdentifier());
        assertEquals(ExportTarget.Kind.FIELD, injected.kind());
        assertEquals("customerBean", injected.beanName());
        assertTrue(injected.springHint());
        assertTrue(injected.explicitBeanName());
        assertEquals("sample.Config$Payload", injected.typeName());
        assertEquals("sample.Config", injected.fieldPath().fields().getFirst().declaringType());

        PsiParameter local = PsiTreeUtil.findChildrenOfType(file, PsiParameter.class).stream()
            .filter(parameter -> parameter.getName().equals("dependency")).findFirst().orElseThrow();
        assertEquals(ExportTarget.Kind.LOCAL, resolver.resolveElement(local.getNameIdentifier()).kind());
        PsiReferenceExpression actualField = reference(file, "this.dependency");
        ExportTarget field = resolver.resolveElement(actualField.getReferenceNameElement());
        assertEquals(ExportTarget.Kind.FIELD, field.kind());
        assertEquals(ExportTarget.Root.THIS, field.fieldPath().root());
        assertEquals("dependency", field.fieldPath().fields().getFirst().name());

        ExportTarget qualified = resolver.resolveElement(reference(file, "holder.payload.value").getReferenceNameElement());
        assertEquals(ExportTarget.Root.LOCAL, qualified.fieldPath().root());
        assertEquals("holder", qualified.fieldPath().rootName());
        assertEquals(2, qualified.fieldPath().fields().size());
        assertEquals("sample.Config$Holder", qualified.fieldPath().fields().get(0).declaringType());
        assertEquals("payload", qualified.fieldPath().fields().get(0).name());
        assertEquals("sample.Config$Payload", qualified.fieldPath().fields().get(1).declaringType());

        PsiField singleton = PsiTreeUtil.findChildrenOfType(file, PsiField.class).stream()
            .filter(candidate -> candidate.getName().equals("singleton")).findFirst().orElseThrow();
        ExportTarget staticField = resolver.resolveElement(singleton.getNameIdentifier());
        assertEquals(ExportTarget.Root.STATIC, staticField.fieldPath().root());
        assertEquals("sample.Config", staticField.fieldPath().rootName());

        PsiClass service = PsiTreeUtil.findChildrenOfType(file, PsiClass.class).stream()
            .filter(candidate -> "ServiceType".equals(candidate.getName())).findFirst().orElseThrow();
        ExportTarget component = resolver.resolveElement(service.getNameIdentifier());
        assertEquals(ExportTarget.Kind.SPRING_TYPE, component.kind());
        assertEquals("sample.ServiceType", component.typeName());
      });
    } finally {
      Disposer.dispose(disposable);
    }
  }

  private static PsiReferenceExpression reference(PsiFile file, String text) {
    return PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression.class).stream()
        .filter(expression -> expression.getText().equals(text))
        .min(Comparator.comparingInt(PsiElement::getTextOffset)).orElseThrow();
  }

  private static String jarPath(Class<?> type) {
    String resource = type.getResource("/" + type.getName().replace('.', '/') + ".class").getPath();
    int separator = resource.lastIndexOf("!/");
    return java.nio.file.Path.of(java.net.URI.create(resource.substring(0, separator))).toString();
  }

  private static final String SOURCE = """
      package sample;
      class Config {
        static final java.lang.String NAME = "customer" + "Bean";
        @org.springframework.context.annotation.Bean(name = {NAME, "alias"})
        Payload create() { return null; }
        @org.springframework.context.annotation.Bean
        ProductFactory makeFactory() { return null; }
        @org.springframework.beans.factory.annotation.Autowired
        @org.springframework.beans.factory.annotation.Qualifier(NAME)
        Payload dependency;
        static Payload singleton;
        void use(Payload dependency, Holder holder) {
          this.dependency.toString();
          System.out.println(holder.payload.value);
        }
        static class Payload { String value; }
        static class Holder { Payload payload; }
        static class ProductFactory implements org.springframework.beans.factory.FactoryBean<Payload> {
          public Payload getObject() { return null; }
          public Class<?> getObjectType() { return Payload.class; }
        }
      }
      @org.springframework.stereotype.Service
      class ServiceType {}
      """;
}
