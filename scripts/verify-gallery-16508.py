"""Verify hook contracts against actual JADX output, not synthetic fixtures.

Usage: python scripts/verify-gallery-16508.py <analysis-directory>
The directory must contain jadx, jadx-core and jadx-connection outputs.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
repo = pathlib.Path(__file__).resolve().parents[1]
hook = (repo / 'app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt').read_text(encoding='utf-8')


def source(name):
    matches = list(root.glob('jadx*/sources/' + name.replace('.', '/') + '.java'))
    assert len(matches) == 1, (name, matches)
    return matches[0].read_text(encoding='utf-8')


contracts = {
    'naq': [r'PauseReason a\(boolean \w+, boolean \w+\)', r'!NetworkMonitor\.e\(\)', r'return PauseReason.NO_WLAN', r'oh70\.a\(\)'],
    'zaq': [r'void a\(boolean \w+\)', r'new taq\(this, true,', r'onConditionChanged'],
    'wdq': [r'static PauseReason d\(boolean \w+\)', r'!NetworkMonitor\.e\(\)', r'final sdq g;', r'String m\(Context'],
    'sdq$h': [r'final PauseReason a;', r'Paused\(reason='],
    'oh70': [r'static final float a\(\)', r'TemperatureUtil'],
    'k11': [r'implements Application.ActivityLifecycleCallbacks', r'static final boolean b\(\)', r'return d > 0;'],
    'y190': [r'String b\(String encryptedToken, String deviceId\)', r'String e\(\)', r'String strE = e\(\)', r'CryptoEngManager'],
    'j6d0': [r'static boolean k\(String', r'if \(q7d0Var.h \|\| k\(str\)\)', r'jxc0.a\(\).component1\(\)'],
}
for name, patterns in contracts.items():
    text = source('com.oplus.aiunit.vision.' + name)
    for pattern in patterns:
        assert re.search(pattern, text), (name, pattern)
    if name not in ('y190', 'naq', 'oh70', 'sdq$h'):
        assert 'com.oplus.aiunit.vision.' + name.replace('$', '\\$') in hook, name
    print('PASS', name)

monitor = source('com.oplus.gallery.standard_lib.util.network.NetworkMonitor')
assert re.search(r'boolean c\(\)\s*\{\s*return c == ConnectType.MOBILE && d;', monitor)
assert re.search(r'boolean e\(\)\s*\{\s*return c == ConnectType.WIFI && d;', monitor)
assert 'com.oplus.aiunit.vision.wdq\\$a' in hook
for guard in ('mobileNetworkEvaluationDepth.get()', 'MobileDataBackupPolicy.isEnabled()', 'MobileDataBackupPolicy.isValidatedMobileNetwork()'):
    assert guard in hook, guard
print('PASS validated network semantics and opt-in scope guards')

resolver = (repo / 'app/src/main/java/io/github/colorosfeiniu/bridge/resolver/SemanticDexResolver.kt').read_text(encoding='utf-8')
token = source('com.oplus.aiunit.vision.y190')
for anchor in ('TokenDecryptor', 'AES/GCM/NoPadding', 'SHA-256', 'com.oplus.hardware.cryptoeng.CryptoEngManager'):
    assert anchor in token and anchor in resolver, anchor
assert 'ConnectionResolutionBootstrap.install(' in hook
assert 'GalleryStatFallback.install(this, hooks)' in hook
assert 'tokenCandidates.singleOrNull()' in resolver
assert 'galleryCandidates.singleOrNull()' in resolver
assert 'org.luckypray:dexkit:2.2.0' in (repo / 'app/build.gradle.kts').read_text(encoding='utf-8')
print('PASS semantic resolver wiring and new token anchors (device scan still required)')

locator = (repo / 'app/src/main/java/io/github/colorosfeiniu/bridge/resolver/EnhancementLocator.kt').read_text(encoding='utf-8')
for anchor, name in (('NasBackupCondChk', 'naq'), ('debug.gallery.temperature.test', 'oh70'), ('debug.gallery.temperature.level', 'oh70')):
    assert anchor in locator and anchor in source('com.oplus.aiunit.vision.' + name), anchor
assert 'MobileDataBackupPolicy.availability.ready' in hook
# naq.a reads the real lifecycle separately from both boolean control flags.
assert '!z2 && !k11.b()' in source('com.oplus.aiunit.vision.naq')
assert 'candidate.invoke(null) as Boolean' in hook
print('PASS dynamic enhancement anchors, lifecycle and capability guards')
