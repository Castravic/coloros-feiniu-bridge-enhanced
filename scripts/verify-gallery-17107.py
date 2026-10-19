"""Check inspected Gallery 17.10.7 JADX contracts; not a device-runtime test.

Usage: python scripts/verify-gallery-17107.py <analysis-directory>
Expected inputs: m1s.java, observer.java, lifecycle.java, network.java,
tls.java and tls-policy.java, produced from the supplied APK by JADX.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
repo = pathlib.Path(__file__).resolve().parents[1]
hook = (repo / 'app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt').read_text(encoding='utf-8')
contracts = {
    'm1s.java': (r'PauseReason a\(boolean z, boolean z2\)', r'!NetworkMonitor\.e\(\)', r'!z2 && !ActivityLifecycle\.c\(\)', r'ps90\.a\(\)'),
    'observer.java': (r'class NasBackupConditionObserver', r'void a\(boolean z\)', r'if \(z\)', r'new dispatchChangeNow\.1\(this, true,', r'onConditionChanged'),
    'lifecycle.java': (r'implements Application\.ActivityLifecycleCallbacks', r'boolean c\(\)\s*\{\s*return d > 0;'),
    'network.java': (r'boolean c\(\)\s*\{\s*return c == ConnectType\.MOBILE && d;', r'boolean e\(\)\s*\{\s*return c == ConnectType\.WIFI && d;'),
    'tls.java': (r'std\.a\(str, bi7Var\.h\)', r'b3f0\.a\(\)\.component1\(\)', r'useTransportSecurity\(\)'),
    'tls-policy.java': (r'b a\(String str, boolean z\)', r'b\.TRIM_SELF_SIGNED', r'b\.SKIP'),
}
for file, patterns in contracts.items():
    text = (root / file).read_text(encoding='utf-8')
    for pattern in patterns:
        assert re.search(pattern, text), (file, pattern)
    print('PASS', file)
for guard in (
    'com.oplus.gallery.framework.abilities.cloudsync.nas.backup.condition.NasBackupConditionObserver',
    'com.oplus.aiunit.vision.m1s',
    'in CONTROL_FLAG_CHECKER_CLASSES',
    'MobileDataBackupPolicy.availability.ready',
    'MobileDataBackupPolicy.isValidatedMobileNetwork()',
    'mobileNetworkEvaluationDepth.get()',
):
    assert guard in hook, guard
print('PASS observer mapping, lifecycle fallback and mobile network scope guards')
