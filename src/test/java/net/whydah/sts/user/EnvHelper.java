package net.whydah.sts.user;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Helper class for setting environment variables in tests.
 * Updated for Java 21 compatibility.
 */
public class EnvHelper {

    /**
     * Sets environment variables for testing purposes.
     * This is a workaround and should only be used in tests.
     */
    public static void setEnv(Map<String, String> newenv) {
        try {
            // Check if we're running on Windows or Unix
            String osName = System.getProperty("os.name").toLowerCase();
            boolean isWindows = osName.contains("windows");

            // Get the process environment
            Map<String, String> env = System.getenv();

            // Use the appropriate field based on the OS
            String className = isWindows ? "java.lang.ProcessEnvironment$ProcessEnvironmentMap"
                    : "java.lang.ProcessEnvironment";

            Class<?> envClass = Class.forName(className);

            // Get the modifiable map
            Field field = envClass.getDeclaredField(isWindows ? "theCaseInsensitiveEnvironment" : "theUnmodifiableEnvironment");

            // Use addOpens to make the module accessible
            addOpensForEnvironment();

            field.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> modifiableEnv = (Map<String, String>) field.get(null);
            if (modifiableEnv != null) {
                modifiableEnv.putAll(newenv);
            }
        } catch (Exception e) {
            // Fallback: Set system properties instead
            for (Map.Entry<String, String> entry : newenv.entrySet()) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Adds the necessary --add-opens for accessing environment variables.
     */
    private static void addOpensForEnvironment() {
        try {
            // Get the module for java.base
            Module javaBaseModule = Object.class.getModule();
            // Get the unnamed module of the current class loader
            Module unnamedModule = EnvHelper.class.getClassLoader().getUnnamedModule();

            // Open java.lang package to the unnamed module
            // This is equivalent to --add-opens java.base/java.lang=ALL-UNNAMED
            java.lang.reflect.Method getDeclaredMethod = Module.class.getDeclaredMethod("implAddOpens", String.class, Module.class);
            getDeclaredMethod.setAccessible(true);
            getDeclaredMethod.invoke(javaBaseModule, "java.lang", unnamedModule);
        } catch (Exception e) {
            // Ignore exceptions, will fall back to system properties
        }
    }
}