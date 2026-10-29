"""Visual checks on this task's disposable read-only emulator; never target a phone."""
import importlib.util
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('freezer_ui', ROOT / 'store/qa-freezer-sort-2026-10-28/check-ui.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)
u.ADB = ['/Users/j.golt/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5584']
u.OUT = Path(__file__).resolve().parent
u.RESULTS = []
DARK = False


def reliable_tree():
    for _ in range(5):
        result = u.shell('uiautomator', 'dump', '/sdcard/freezer-indicator.xml')
        if 'dumped to:' in result:
            return E.fromstring(u.shell('cat', '/sdcard/freezer-indicator.xml'))
        time.sleep(1)
    raise AssertionError('UI hierarchy did not become available')


u.tree = reliable_tree


def stable_capture(name):
    def signature(tree):
        return [(n.get('resource-id'), n.get('bounds'), n.get('text'), n.get('content-desc'))
            for n in tree.iter('node') if n.get('resource-id', '').rsplit('/', 1)[-1]
            in {'open', 'toggle', 'label', 'status', 'freezeMode', 'launch'}]
    for _ in range(3):
        before = u.tree()
        with (u.OUT / (name + '.png')).open('wb') as image:
            subprocess.run(u.ADB + ['exec-out', 'screencap', '-p'], stdout=image, check=True)
        after = u.tree()
        if signature(before) == signature(after):
            (u.OUT / (name + '.xml')).write_text(E.tostring(before, encoding='unicode'))
            return
    raise AssertionError('App rows moved while capturing ' + name)


u.capture = stable_capture


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds'))))


def ids(name, tree):
    return [n for n in u.nodes(tree) if n.get('resource-id') == u.PKG + ':id/' + name]


def check_colours(name, tree, hidden_actions):
    image = Image.open(u.OUT / (name + '.png')).convert('RGB')
    palette = {
        'soft': ('8BD2F4', '19303D') if DARK else ('176688', 'E6F3FA'),
        'deep': ('352052', 'D3B8FA') if DARK else ('FFFFFF', '67449B'),
    }
    checked = []
    controls = ids('open' if hidden_actions else 'toggle', tree)
    for control in controls:
        description = control.get('content-desc', '')
        mode = 'soft' if 'Заморожено мягко' in description else 'deep' if 'Заморожено глубоко' in description else None
        if mode is None:
            continue
        x1, y1, x2, y2 = bounds(control)
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        diameter = 108
        if hidden_actions:
            cx = x2 + 48  # 4 dp margin + half of the 24 dp marker, at density 3.
            diameter = 72
        fg, bg = [tuple(bytes.fromhex(colour)) for colour in palette[mode]]
        radius = diameter // 2
        whole = list(image.crop((cx-radius, cy-radius, cx+radius, cy+radius)).getdata())
        inset = diameter // 4
        centre = list(image.crop((cx-inset, cy-inset, cx+inset, cy+inset)).getdata())
        assert whole.count(bg) > 100, (name, description, 'Missing mode background', image.getpixel((cx, cy)))
        assert centre.count(fg) > 8, (name, description, 'Snowflake tint was lost')
        xs = [i % diameter for i, pixel in enumerate(whole) if pixel == bg]
        assert abs((min(xs) + max(xs)) / 2 - radius) <= 3, (name, description, 'Mode circle is off-centre or clipped')
        checked.append(mode)
    assert {'soft', 'deep'}.issubset(set(checked)), (name, checked)


def capture_and_check(name, columns=1, selecting=False):
    u.capture(name)
    # Use the hierarchy captured with the PNG: the asynchronous access warning can move rows later.
    tree = E.parse(u.OUT / (name + '.xml')).getroot()
    labels = ids('label', tree)
    assert labels, u.texts(tree)
    actions = ids('toggle', tree)
    markers = ids('freezeMode', tree)
    descriptions = [n.get('content-desc', '') for n in ids('open', tree)]
    if columns > 1 or selecting:
        assert not actions, actions
        # The extra snowflakes are decorative; the row announces their mode to accessibility.
        assert any('Заморожено мягко' in d for d in descriptions), descriptions
        assert any('Заморожено глубоко' in d for d in descriptions), descriptions
    else:
        assert actions and not markers
        descs = [a.get('content-desc') for a in actions]
        assert any('Заморожено мягко' in d for d in descs), descs
        assert any('Заморожено глубоко' in d for d in descs), descs
        assert all(bounds(a)[2] - bounds(a)[0] >= 144 for a in actions), 'Touch targets below 48 dp'
    for marker in markers:
        assert marker.get('clickable') == 'false'
        x1, y1, x2, y2 = bounds(marker)
        assert x2 > x1 and y2 > y1
        for label in labels:
            a, b, c, d = bounds(label)
            assert x2 <= a or x1 >= c or y2 <= b or y1 >= d, 'Mode indicator overlaps a label'
    check_colours(name, tree, columns > 1 or selecting)
    u.passed(name, columns=columns, selecting=selecting,
        buttons=len(actions), accessible_modes=descriptions)


if __name__ == '__main__':
    assert u.shell('getprop', 'ro.kernel.qemu').strip() == '1'
    assert 'NMS_A10' in u.call('emu', 'avd', 'name')
    u.call('root')
    u.call('wait-for-device')
    assert u.shell('id', '-u').strip() == '0'
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'store/NetModeSwitch-3.4-freezer-indicator-a10.apk'
    u.call('install', '-r', str(apk))
    # This read-only AVD may contain old QA rules. Start with this app's empty test data.
    assert 'Success' in u.shell('pm', 'clear', u.PKG)
    u.call('logcat', '-b', 'crash', '-c')
    u.passed('installed test APK', apk=str(apk))
    u.shell('settings', 'put', 'system', 'font_scale', '1.0')
    u.shell('cmd', 'uimode', 'night', 'no')
    u.shell('wm', 'size', '1080x2280')
    u.shell('wm', 'density', '480')
    u.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    u.shell('wm', 'dismiss-keyguard')
    u.shell('pm', 'disable-user', '--user', '0', 'com.android.chrome')
    u.shell('pm', 'suspend', '--user', '0', 'com.google.android.gm')
    now = int(time.time() * 1000)
    fixture = [
        {'key': 'com.android.chrome', 'mode': 'SOFT', 'as': 'DEEP'},
        {'key': 'com.google.android.gm', 'mode': 'DEEP', 'as': 'SOFT'},
        {'key': 'com.google.android.apps.maps', 'mode': 'DEEP', 'thawed': now, 'keepUntil': -1},
        {'key': 'com.google.android.apps.photos', 'mode': 'SOFT', 'thawed': now, 'keepUntil': -1},
    ]
    base = dict(ice_apps=json.dumps(fixture), ice_groups='[]', ice_sort='NAME', ice_view='compact',
        ice_columns=1, ice_rows=0, ice_labels=True, ice_delay=-1, ice_screen_off=False,
        ice_native_instant=False, ice_boot_check=False)
    u.update(**base)
    capture_and_check('compact-light')
    actual = {a['key']: a.get('as') for a in u.apps()}
    assert actual['com.android.chrome'] == 'DEEP' and actual['com.google.android.gm'] == 'SOFT'
    u.passed('observed state survives a different configured mode')

    u.update(ice_columns=2)
    capture_and_check('two-columns-light', columns=2)
    u.update(ice_columns=3)
    capture_and_check('three-columns-light', columns=3)

    u.update(ice_columns=1)
    for _ in range(3):
        # The access warning may appear during the first long press and move the row.
        node = u.find('Chrome')
        x, y = u.point(node)
        u.shell('input', 'swipe', x, y, x, y, '700')
        menu = u.tree()
        if 'Выбрать' in u.texts(menu):
            u.shell('input', 'tap', *u.point(u.find('Выбрать', t=menu)))
            break
    else:
        raise AssertionError('App actions menu did not open')
    capture_and_check('selection-light', selecting=True)
    u.back()
    capture_and_check('selection-ended-light')

    u.shell('settings', 'put', 'system', 'font_scale', '1.5')
    u.shell('wm', 'size', '960x2160')
    u.shell('cmd', 'uimode', 'night', 'yes')
    DARK = True
    u.shell('am', 'force-stop', u.PKG)
    u.start()
    capture_and_check('compact-dark-large')
    u.update(ice_columns=2)
    capture_and_check('two-columns-dark-large', columns=2)
    u.update(ice_columns=3)
    capture_and_check('three-columns-dark-large', columns=3)

    # Rebinding a formerly deep row after an external thaw clears its filled mode indicator.
    u.update(ice_columns=1)
    u.shell('pm', 'enable', '--user', '0', 'com.android.chrome')
    u.shell('input', 'keyevent', 'KEYCODE_HOME')
    u.start()
    tree = u.tree()
    descs = [n.get('content-desc') for n in ids('toggle', tree)]
    assert any(d.startswith('Заморозить Chrome,') for d in descs), descs
    assert not any(d.startswith('Разморозить Chrome,') for d in descs), descs
    u.capture('after-thaw-dark-large')
    tree = E.parse(u.OUT / 'after-thaw-dark-large.xml').getroot()
    chrome = next(n for n in ids('toggle', tree) if n.get('content-desc', '').startswith('Заморозить Chrome,'))
    pixels = list(Image.open(u.OUT / 'after-thaw-dark-large.png').convert('RGB').crop(bounds(chrome)).getdata())
    assert tuple(bytes.fromhex('D3B8FA')) not in pixels, 'Reused thawed row retained its deep fill'
    assert tuple(bytes.fromhex('19303D')) not in pixels, 'Thawed row displays a soft fill'
    u.passed('external thaw updates action and clears deep mode')

    # The regular list keeps its detailed status and has no compact-only extra indicator.
    u.update(ice_view='list')
    tree = u.tree()
    assert not ids('freezeMode', tree)
    assert ids('status', tree)
    u.capture('regular-list-dark-large')
    u.passed('regular list retains detailed status')

    crashes = u.call('logcat', '-b', 'crash', '-d')
    (u.OUT / 'crash-log.txt').write_text(crashes)
    assert 'FATAL EXCEPTION' not in crashes, crashes
    u.passed('no runtime crashes')
    print('ALL INDICATOR UI CHECKS PASSED', flush=True)
