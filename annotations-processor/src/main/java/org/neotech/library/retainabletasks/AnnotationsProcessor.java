package org.neotech.library.retainabletasks;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

public final class AnnotationsProcessor extends AbstractProcessor {

    private static final String LIBRARY_PACKAGE = "org.neotech.library.retainabletasks";

    private static final ClassName CLASS_TASKATTACHBINDING = ClassName.get(LIBRARY_PACKAGE + ".internal", "TaskAttachBinding");
    private static final ClassName CLASS_TASK = ClassName.get(LIBRARY_PACKAGE, "Task");
    private static final ClassName CLASS_TASK_CALLBACK = CLASS_TASK.nestedClass("Callback");
    private static final ClassName CLASS_TASK_ADVANCEDCALLBACK = CLASS_TASK.nestedClass("AdvancedCallback");
    private static final ClassName CLASS_TASKMANAGEROWNER = ClassName.get(LIBRARY_PACKAGE, "TaskManagerOwner");

    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        final Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }
        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        final Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
        annotations.add(TaskAttach.class);
        annotations.add(TaskPreExecute.class);
        annotations.add(TaskPostExecute.class);
        annotations.add(TaskCancel.class);
        annotations.add(TaskProgress.class);
        return annotations;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        final HashMap<TypeElement, TaskBindingContainer> classMap = new HashMap<>();

        for(TypeElement annotationType: annotations){
            processAnnotation(classMap, roundEnv, annotationType);
        }

        for(Map.Entry<TypeElement, TaskBindingContainer> entry: classMap.entrySet()) {
            try {
                createJavaFile(entry.getKey(), entry.getValue()).writeTo(filer);
            } catch (IOException e) {
                error(entry.getKey(), "Unable to write generate java binding file for class %s: %s", entry.getKey(), e.getMessage());
            }
        }
        return true;
    }

    private void processAnnotation(HashMap<TypeElement, TaskBindingContainer> classMap, RoundEnvironment roundEnvironment, TypeElement type){
        for (Element element : roundEnvironment.getElementsAnnotatedWith(type)) {
            final TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
            TaskBindingContainer binding = classMap.get(enclosingElement);
            System.out.println(enclosingElement);
            if(binding == null){
                binding = new TaskBindingContainer();
                classMap.put(enclosingElement, binding);
            }
            final Class<? extends Annotation> classType;
            try {
                //noinspection unchecked
                classType = (Class<? extends Annotation>) Class.forName(type.getQualifiedName().toString());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                error(element, "Could not find the Class object for TypeElement %s", type);
                return;
            }
            binding.add(element, classType);
        }
    }

    private JavaFile createJavaFile(TypeElement forTypeElement, TaskBindingContainer binding){

        final String packageName = processingEnv.getElementUtils().getPackageOf(forTypeElement).getQualifiedName().toString();
        final String className = forTypeElement.getQualifiedName().toString().substring(packageName.length() + 1).replace('.', '$');
        final ClassName bindingClassName = ClassName.get(packageName, className + "_TaskBinding");

        // Create the constructor which takes the target class (the class containing the annotated
        // methods) as argument.
        final MethodSpec constructor = MethodSpec.constructorBuilder()
                .addParameter(TypeVariableName.get("T"), "target", Modifier.FINAL)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("this.target = target").build();


        // create (and implement) the getListenerFor method from the TaskAttachBinding interface
        final MethodSpec.Builder getListenerForMethod = MethodSpec.methodBuilder("getListenerFor")
                .addParameter(CLASS_TASK, "task")
                .addParameter(TypeName.BOOLEAN, "isReAttach")
                .addModifiers(Modifier.PUBLIC)
                .returns(CLASS_TASK_CALLBACK)
                .addAnnotation(Override.class);

        // Create a switch statement to switch between the different tasks
        getListenerForMethod.beginControlFlow("switch(task.getTag())");

        // Loop through the tasks and create switch cases for them.
        for(Map.Entry<String, TaskBinding> entry: binding.getTaskBindings().entrySet()){

            final TaskBinding methods = entry.getValue();

            if(methods.getElementForPostExecute() == null){
                note(forTypeElement, "No @TaskPostExecute annotated method found for task with tag '%s' while other annotated methods for that task have been found!");
            }

            getListenerForMethod.beginControlFlow("case \"$L\":\n", entry.getKey());

            // The getListenerFor method requires us to return an implementation of the
            // Task.Callback class, in this case (even though we might not need it) we use the
            // Task.AdvancedCallback instead of a simple Task.Callback implementation.
            final TypeSpec.Builder callbackImplementation = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(CLASS_TASK_ADVANCEDCALLBACK);


            callbackImplementation.addMethod(createTaskCallbackMethod("onPreExecute", methods.getElementForPreExecute()));
            callbackImplementation.addMethod(createTaskCallbackMethod("onPostExecute", methods.getElementForPostExecute()));
            callbackImplementation.addMethod(createTaskCallbackMethod("onCanceled", methods.getElementForCancel()));

            MethodSpec.Builder progressUpdateMethod = MethodSpec.methodBuilder("onProgressUpdate")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(CLASS_TASK, "task")
                    .addParameter(TypeName.OBJECT, "object");

            if(methods.getElementForProgress() != null) {
                progressUpdateMethod.addStatement("target.$L(task, object)", methods.getElementForProgress().getSimpleName());
            }
            callbackImplementation.addMethod(progressUpdateMethod.build());


            if(methods.getElementForAttach() != null) {
                final boolean onlyCallOnReAttach = methods.getElementForAttach().getAnnotation(TaskAttach.class).onlyCallOnReAttach();
                if(onlyCallOnReAttach){
                    getListenerForMethod.beginControlFlow("if(isReAttach)");
                }
                getListenerForMethod.addStatement("target.$L(task)", methods.getElementForAttach().getSimpleName());
                if(onlyCallOnReAttach){
                    getListenerForMethod.endControlFlow();
                }
            }
            getListenerForMethod.addStatement("return $L", callbackImplementation.build());
            getListenerForMethod.endControlFlow();

        }

        // Add the default switch case
        getListenerForMethod.addCode("default:\n");
        getListenerForMethod.addStatement("return null");
        getListenerForMethod.endControlFlow();


        // Create an implementation of TaskAttachBinding interface.
        final TypeSpec generatedClass = TypeSpec.classBuilder(bindingClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeVariableName.get("T"), "target", Modifier.FINAL)
                .addMethod(constructor)
                .addTypeVariable(TypeVariableName.get("T", TypeName.get(forTypeElement.asType()), CLASS_TASKMANAGEROWNER))
                .addSuperinterface(CLASS_TASKATTACHBINDING)
                .addMethod(getListenerForMethod.build())
                .build();

        return JavaFile.builder(bindingClassName.packageName(), generatedClass)
                .addFileComment("Generated code from the Android Retainable Tasks annotations processor. Do not modify!")
                .build();
    }

    private MethodSpec createTaskCallbackMethod(String methodName, @Nullable Element methodToCall){
        // Create the implementation of the onPreExecute method.
        final MethodSpec.Builder onPreExecuteMethod = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(CLASS_TASK, "task");

        if(methodToCall != null) {
            onPreExecuteMethod.addStatement("target.$L(task)", methodToCall.getSimpleName());
        } else {
            onPreExecuteMethod.addComment("No annotated method found for $L", methodName);
        }
        return onPreExecuteMethod.build();
    }




    private void error(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, element, message, args);
    }

    private void note(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, element, message, args);
    }

    private void printMessage(Diagnostic.Kind kind, Element element, String message, Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(kind, message, element);
    }
}
