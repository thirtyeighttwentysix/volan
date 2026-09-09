package verify;

import com.example.blog.User;
import com.example.blog.UserRepository;
import com.example.blog.VolanClient;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class JavaSignatureTest {
    @Test
    void allGeneratedJavaSignaturesUseJavaTypes() throws Exception {
        var names = generatedClasses();
        assertTrue(names.size() > 100, "The check must cover every generated scope, not just the client");
        var violations = new ArrayList<String>();
        for (String name : names) {
            var type = Class.forName(name, false, VolanClient.class.getClassLoader());
            if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic()) continue;
            for (var method : type.getMethods()) {
                if (method.isSynthetic() || enumEntries(method)) continue;
                inspect(method.getGenericReturnType(), method.toString(), violations);
                for (var parameter : method.getGenericParameterTypes()) inspect(parameter, method.toString(), violations);
            }
            for (var constructor : type.getConstructors()) {
                if (constructor.isSynthetic()) continue;
                for (var parameter : constructor.getGenericParameterTypes()) inspect(parameter, constructor.toString(), violations);
            }
            for (var field : type.getFields()) {
                if (!field.isSynthetic()) inspect(field.getGenericType(), field.toString(), violations);
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void signatureCheckAlsoRejectsNestedKotlinTypes() throws Exception {
        var violations = new ArrayList<String>();
        inspect(Forbidden.class.getDeclaredField("nested").getGenericType(), "fixture", violations);
        assertFalse(violations.isEmpty());
    }

    @Test
    void nullabilitySurvivesCompilationIncludingFuturePayloads() throws Exception {
        assertTrue(User.class.isAnnotationPresent(NullMarked.class));
        assertTrue(User.class.getMethod("getName").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        var builderArgument = User.Builder.class.getMethod("name", String.class).getAnnotatedParameterTypes()[0];
        assertTrue(builderArgument.isAnnotationPresent(Nullable.class));
        var future = (AnnotatedParameterizedType) UserRepository.class
                .getMethod("findFirstAsync", Consumer.class).getAnnotatedReturnType();
        assertTrue(future.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class));
        assertFalse(User.class.getMethod("getEmail").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        var nullableRow = io.github.thirtyeighttwentysix.volan.runtime.Row.class.getMethod("getScalarListOrNull", String.class);
        assertTrue(nullableRow.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        var list = (AnnotatedParameterizedType) nullableRow.getAnnotatedReturnType();
        assertTrue(list.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class));
    }

    @Test
    void everyRepositoryOperationHasAnAsyncEquivalent() throws Exception {
        for (var method : UserRepository.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.getName().endsWith("Async")) continue;
            var async = UserRepository.class.getMethod(method.getName() + "Async", method.getParameterTypes());
            assertEquals(java.util.concurrent.CompletableFuture.class, async.getReturnType());
        }
    }

    @Test
    void entitiesEnumsAndDefaultArgumentsAreUsableFromJava() {
        var tag = com.example.blog.Tag.builder().id(1).name("java").build();
        assertEquals(tag, com.example.blog.Tag.builder().id(1).name("java").build());
        assertEquals("java", tag.getName());
        assertThrows(io.github.thirtyeighttwentysix.volan.runtime.VolanRelationNotLoadedException.class, tag::getPosts);
        assertEquals(com.example.blog.Role.ADMIN, com.example.blog.Role.fromDatabaseValue("administrator"));
        assertEquals(2, new io.github.thirtyeighttwentysix.volan.runtime.RetryPolicy(2).getAttempts());
        assertNull(new io.github.thirtyeighttwentysix.volan.runtime.Pagination().getTake());
        assertEquals("{\"value\":1}", io.github.thirtyeighttwentysix.volan.Json.of("{\"value\":1}").getRaw());
    }

    private static boolean enumEntries(Method method) {
        // Kotlin emits this non-synthetic accessor itself; Java uses the standard values() method.
        return method.getDeclaringClass().isEnum() && method.getName().equals("getEntries") && method.getParameterCount() == 0;
    }

    private static void inspect(Type type, String location, List<String> violations) {
        inspect(type, location, violations, new HashSet<>());
    }

    private static void inspect(Type type, String location, List<String> violations, Set<Type> seen) {
        if (!seen.add(type)) return;
        if (type instanceof Class<?> raw) {
            if (raw.isArray()) inspect(raw.getComponentType(), location, violations, seen);
            else if (raw.getName().startsWith("kotlin.")) violations.add(location + " exposes " + raw.getName());
        } else if (type instanceof ParameterizedType generic) {
            inspect(generic.getRawType(), location, violations, seen);
            for (Type argument : generic.getActualTypeArguments()) inspect(argument, location, violations, seen);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) inspect(bound, location, violations, seen);
            for (Type bound : wildcard.getLowerBounds()) inspect(bound, location, violations, seen);
        } else if (type instanceof GenericArrayType array) {
            inspect(array.getGenericComponentType(), location, violations, seen);
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) inspect(bound, location, violations, seen);
        }
    }

    private static List<String> generatedClasses() throws Exception {
        Path source = Path.of(VolanClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(source)) {
            try (var paths = Files.walk(source.resolve("com/example/blog"))) {
                return paths.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> source.relativize(p).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", ""))
                        .toList();
            }
        }
        try (var jar = new JarFile(source.toFile())) {
            return jar.stream().map(e -> e.getName())
                    .filter(n -> n.startsWith("com/example/blog/") && n.endsWith(".class"))
                    .map(n -> n.replace('/', '.').replaceAll("\\.class$", "")).toList();
        }
    }

    private static class Forbidden {
        List<? extends kotlin.Unit> nested;
    }
}
