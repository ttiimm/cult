import org.cult.Tests;
import org.cult.UnitTest;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Arrays;

void main(String[] args) {
    if (args.length != 1) {
        System.err.println("usage: Tester <path-to-jar>");
        System.exit(1);
    }

    var jarUnderTest = args[0];
    try {
        var urlToJar = Paths.get(jarUnderTest).toUri().toURL();
        tryToTest(urlToJar);
    } catch (ClassNotFoundException e) {
        System.err.println("error: could not find `Main` class");
        System.err.println(e.getMessage());
    } catch (ReflectiveOperationException e) {
        System.err.println("error: could not run tests");
        System.err.println(e.getMessage());
    } catch (IOException e) {
        System.err.println(STR."error: could not load jar `\{jarUnderTest}`");
        System.err.println(e.getMessage());
    }
}

private void tryToTest(URL urlToJar) throws ReflectiveOperationException, IOException {
    try (var classLoader = new URLClassLoader(new URL[]{urlToJar})) {
        var mainClass = classLoader.loadClass("Main");
        var testClasses = Arrays.stream(mainClass.getDeclaredClasses())
                .filter(clazz -> clazz.getAnnotation(Tests.class) != null).toList();
        for (var testClass : testClasses) {
            var methods = testClass.getDeclaredMethods();
            for (var method : methods) {
                method.setAccessible(true);
                var unitTest = method.getAnnotation(UnitTest.class);
                if (unitTest != null) {
                    runTest(method);
                }
            }
        }
        System.out.println();
    }
}


void runTest(Method method) throws ReflectiveOperationException {
    try {
        method.invoke(null);
        System.out.print(".");
    } catch (InvocationTargetException e) {
        System.err.println("F");
        Throwable cause = e.getCause();
        if (cause instanceof AssertionError) {
            System.err.println(STR."error: test \{method.getDeclaringClass()}#\{method.getName()} failed");
            System.err.println(cause.getMessage());
        } else {
            throw new RuntimeException(cause);
        }
    }
}