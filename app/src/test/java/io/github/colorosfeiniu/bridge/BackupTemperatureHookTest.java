package io.github.colorosfeiniu.bridge;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.After;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

/** Runs the production scope hook and thermal policy against the NAS checker contract. */
public class BackupTemperatureHookTest {
    private Method checkerMethod;
    private Object evaluateThroughRealHook(boolean foreground, boolean forceRefresh) throws Throwable {
        return evaluateThroughRealHook(foreground, forceRefresh, 44.0f, true);
    }

    private Object evaluateThroughRealHook(boolean foreground, boolean forceRefresh, float temperature, boolean reset) throws Throwable {
        Class<?> policyClass = Class.forName("io.github.colorosfeiniu.bridge.FeiniuBridgeHook$CloudBackupTemperaturePolicy");
        Field instance = policyClass.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Object policy = instance.get(null);
        Field blocked = policyClass.getDeclaredField("blocked");
        blocked.setAccessible(true);
        if (reset) blocked.set(null, false);
        Method evaluate = policyClass.getDeclaredMethod("evaluate", float.class);
        evaluate.setAccessible(true);
        Class<?> scopeClass = Class.forName("io.github.colorosfeiniu.bridge.FeiniuBridgeHook$BackupConditionEvaluationScopeHook");
        Field scopeInstance = scopeClass.getDeclaredField("INSTANCE");
        scopeInstance.setAccessible(true);
        Hooker hooker = (Hooker) scopeInstance.get(null);
        Chain chain = (Chain) Proxy.newProxyInstance(Chain.class.getClassLoader(), new Class<?>[] { Chain.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getArg")) return ((Integer) args[0]) == 0 ? foreground : forceRefresh;
                if (method.getName().equals("getExecutable")) return checkerMethod;
                if (method.getName().equals("proceed")) return evaluate.invoke(policy, temperature);
                return null;
            });
        return hooker.intercept(chain);
    }

    private Object property(Object decision, String name) throws Exception {
        Method getter = decision.getClass().getDeclaredMethod(name);
        getter.setAccessible(true);
        return getter.invoke(decision);
    }

    @Test public void foregroundAt44ShouldUse45DegreeLimit() throws Throwable {
        Object decision = evaluateThroughRealHook(true, false);
        assertEquals(true, property(decision, "getForeground"));
        assertEquals(45.0f, (Float) property(decision, "getMaxTemperature"), 0.01f);
        assertEquals(true, property(decision, "getAllow"));
    }

    @Test public void backgroundAt44ShouldUse43DegreeLimit() throws Throwable {
        Object decision = evaluateThroughRealHook(false, true);
        assertEquals(false, property(decision, "getForeground"));
        assertEquals(43.0f, (Float) property(decision, "getMaxTemperature"), 0.01f);
        assertEquals(false, property(decision, "getAllow"));
    }

    private ThreadLocal<?> threadLocal(String name) throws Exception {
        Field field = FeiniuBridgeHook.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadLocal<?>) field.get(null);
    }

    @After public void clearThreadState() throws Exception {
        threadLocal("backupConditionEvaluationDepth").remove();
        threadLocal("backupConditionForeground").remove();
        threadLocal("mobileNetworkEvaluationDepth").remove();
        Class<?> policy = Class.forName("io.github.colorosfeiniu.bridge.FeiniuBridgeHook$CloudBackupTemperaturePolicy");
        Field lifecycle = policy.getDeclaredField("activityLifecycleMethod");
        lifecycle.setAccessible(true);
        lifecycle.set(null, null);
    }

    private static boolean actualForeground;
    public static boolean lifecycleForeground() { return actualForeground; }

    @Test public void actualLifecycleWinsOverRefreshAndBackgroundCheckFlags() throws Throwable {
        Class<?> policy = Class.forName("io.github.colorosfeiniu.bridge.FeiniuBridgeHook$CloudBackupTemperaturePolicy");
        Field lifecycle = policy.getDeclaredField("activityLifecycleMethod");
        lifecycle.setAccessible(true);
        lifecycle.set(null, BackupTemperatureHookTest.class.getMethod("lifecycleForeground"));
        for (boolean foreground : new boolean[] { false, true }) {
            actualForeground = foreground;
            for (boolean refresh : new boolean[] { false, true }) {
                for (boolean backgroundCheck : new boolean[] { false, true }) {
                    Object decision = evaluateThroughRealHook(refresh, backgroundCheck);
                    assertEquals(foreground, property(decision, "getForeground"));
                    assertEquals(foreground, property(decision, "getAllow"));
                }
            }
        }
    }

    @Test public void thresholdsAreInclusiveAndPausePersistsUntil41() throws Throwable {
        for (boolean foreground : new boolean[] { false, true }) {
            float threshold = foreground ? 45.0f : 43.0f;
            assertEquals(true, property(evaluateThroughRealHook(foreground, false, threshold, true), "getAllow"));
            assertEquals(false, property(evaluateThroughRealHook(foreground, false, threshold + 0.1f, false), "getAllow"));
            assertEquals(false, property(evaluateThroughRealHook(true, true, 42.0f, false), "getAllow"));
            assertEquals(true, property(evaluateThroughRealHook(foreground, false, 41.0f, false), "getAllow"));
            assertEquals(true, property(evaluateThroughRealHook(foreground, false, 42.0f, false), "getAllow"));
        }
    }

    @Test public void controlFlagsCannotSelectForegroundWhenLifecycleIsUnavailable() throws Throwable {
        for (Class<?> checker : new Class<?>[] { com.oplus.aiunit.vision.naq.class, com.oplus.aiunit.vision.m1s.class }) {
            checkerMethod = checker.getMethod("a", boolean.class, boolean.class);
            for (boolean refresh : new boolean[] { false, true }) {
                for (boolean skipCheck : new boolean[] { false, true }) {
                    Object decision = evaluateThroughRealHook(refresh, skipCheck);
                    assertEquals(false, property(decision, "getForeground"));
                    assertEquals(false, property(decision, "getAllow"));
                }
            }
        }
    }

    @Test public void refreshFlagDoesNotChangeForegroundLimit() throws Throwable {
        for (boolean foreground : new boolean[] { false, true }) {
            for (boolean refresh : new boolean[] { false, true }) {
                Object decision = evaluateThroughRealHook(foreground, refresh);
                assertEquals(foreground, property(decision, "getForeground"));
                assertEquals(foreground ? 45.0f : 43.0f, (Float) property(decision, "getMaxTemperature"), 0.01f);
                assertEquals(foreground, property(decision, "getAllow"));
                assertNull(threadLocal("backupConditionForeground").get());
                assertEquals(0, threadLocal("backupConditionEvaluationDepth").get());
                assertEquals(0, threadLocal("mobileNetworkEvaluationDepth").get());
            }
        }
    }

    @Test public void nestedCheckerRestoresOuterForegroundEvenOnException() throws Throwable {
        Class<?> scopeClass = Class.forName("io.github.colorosfeiniu.bridge.FeiniuBridgeHook$BackupConditionEvaluationScopeHook");
        Field instance = scopeClass.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Hooker hooker = (Hooker) instance.get(null);
        RuntimeException expected = new RuntimeException("checker failure");
        Chain inner = (Chain) Proxy.newProxyInstance(Chain.class.getClassLoader(), new Class<?>[] { Chain.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getArg")) return false;
                if (method.getName().equals("proceed")) {
                    assertEquals(false, threadLocal("backupConditionForeground").get());
                    throw expected;
                }
                return null;
            });
        Chain outer = (Chain) Proxy.newProxyInstance(Chain.class.getClassLoader(), new Class<?>[] { Chain.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getArg")) return true;
                if (method.getName().equals("proceed")) {
                    assertEquals(true, threadLocal("backupConditionForeground").get());
                    try { hooker.intercept(inner); } catch (RuntimeException actual) { assertEquals(expected, actual); }
                    assertEquals(true, threadLocal("backupConditionForeground").get());
                    assertEquals(1, threadLocal("backupConditionEvaluationDepth").get());
                    assertEquals(1, threadLocal("mobileNetworkEvaluationDepth").get());
                }
                return null;
            });
        hooker.intercept(outer);
        assertNull(threadLocal("backupConditionForeground").get());
        assertEquals(0, threadLocal("backupConditionEvaluationDepth").get());
        assertEquals(0, threadLocal("mobileNetworkEvaluationDepth").get());
    }
}
