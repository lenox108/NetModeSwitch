import subprocess as S, xml.etree.ElementTree as E, json, re, time, shlex
from pathlib import Path

ADB = ['/Users/j.golt/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5594']
PKG = 'com.netmode.switcher'
OUT = Path('/Users/j.golt/Documents/NetworkSwitcher/NetModeSwitch/store/qa-freezer-sort-2026-10-28')
OUT.mkdir(exist_ok=True)
RESULTS = []

def call(*args): return S.check_output(ADB + list(args), text=True)
def shell(*args): return call('shell', shlex.join(args))
def tree():
    shell('uiautomator', 'dump', '/sdcard/freezer-sort.xml')
    return E.fromstring(shell('cat', '/sdcard/freezer-sort.xml'))
def nodes(t=None): return list((t if t is not None else tree()).iter('node'))
def texts(t=None): return [n.get('text') for n in nodes(t) if n.get('text')]
def find(text=None, rid=None, t=None):
    for n in nodes(t):
        if (text is None or text in (n.get('text'), n.get('content-desc'))) and (rid is None or n.get('resource-id') == PKG + ':id/' + rid): return n
    raise AssertionError(('Missing', text, rid, texts(t)))
def point(n):
    a,b,c,d = map(int, re.findall(r'\d+', n.get('bounds')))
    return str((a+c)//2), str((b+d)//2)
def tap(text=None, rid=None):
    shell('input', 'tap', *point(find(text, rid))); time.sleep(.3)
def back(): shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.3)
def labels(): return [n.get('text') for n in nodes() if n.get('resource-id') == PKG + ':id/label']
def capture(name):
    t = tree()
    (OUT / (name + '.xml')).write_text(E.tostring(t, encoding='unicode'))
    with (OUT / (name + '.png')).open('wb') as f: S.run(ADB + ['exec-out', 'screencap', '-p'], stdout=f, check=True)
def wait_picker():
    for _ in range(40):
        if not any(n.get('resource-id') == PKG + ':id/progress' for n in nodes()): return
        time.sleep(1)
    raise AssertionError('App picker is still loading')
def passed(name, **data):
    RESULTS.append({'check': name, **data})
    (OUT / 'checks.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    print('PASS', name, data, flush=True)
def prefs(): return E.fromstring(shell('cat', f'/data/data/{PKG}/shared_prefs/netmode.xml'))
def apps(): return json.loads(prefs().find("string[@name='ice_apps']").text)
def pref(k):
    n = prefs().find(f"*[@name='{k}']")
    return n.text if n is not None else None
def start(activity='FreezerActivity', *args):
    shell('am', 'start', '-n', f'{PKG}/.{activity}', *args); time.sleep(.8)
def update(**values):
    shell('am', 'force-stop', PKG)
    try: r = prefs()
    except S.CalledProcessError: r = E.Element('map')
    for k,v in values.items():
        n = r.find(f"*[@name='{k}']")
        if n is not None: r.remove(n)
        if isinstance(v, bool): E.SubElement(r, 'boolean', name=k, value=str(v).lower())
        elif isinstance(v, int): E.SubElement(r, 'int', name=k, value=str(v))
        else: E.SubElement(r, 'string', name=k).text=v
    path = Path('/tmp/nms-freezer-sort-prefs.xml')
    E.ElementTree(r).write(path, encoding='utf-8', xml_declaration=True)
    call('push', str(path), '/data/local/tmp/nms-freezer-sort-prefs.xml')
    shell('mkdir', '-p', f'/data/data/{PKG}/shared_prefs')
    shell('cp', '/data/local/tmp/nms-freezer-sort-prefs.xml', f'/data/data/{PKG}/shared_prefs/netmode.xml')
    uid = shell('stat', '-c', '%u', f'/data/data/{PKG}').strip()
    shell('chown', '-R', uid + ':' + uid, f'/data/data/{PKG}/shared_prefs')
    shell('restorecon', '-RF', f'/data/data/{PKG}/shared_prefs')
    start()
def sort_dialog():
    tap('More options')
    tap('Сортировка')
def choose(text, value):
    sort_dialog(); tap(text)
    assert pref('ice_sort') == value

if __name__ == '__main__':
    assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
    call('install', '-r', '/tmp/nms-freezer-sort-build/app/outputs/apk/a10/debug/app-a10-debug.apk')
    shell('settings', 'put', 'system', 'font_scale', '1.0')
    shell('cmd', 'uimode', 'night', 'no')
    shell('wm', 'size', '1080x2160'); shell('wm', 'density', '480')
    shell('input', 'keyevent', 'KEYCODE_WAKEUP'); shell('wm', 'dismiss-keyguard')
    now = int(time.time() * 1000)
    fixture = [
        {'key':'com.android.chrome', 'thawed':now},
        {'key':'com.google.android.gm', 'thawed':now},
        {'key':'com.google.android.apps.maps', 'thawed':now, 'addedAt':200},
        {'key':'com.google.android.apps.photos', 'thawed':now, 'addedAt':100},
    ]
    update(ice_apps=json.dumps(fixture), ice_sort='NAME', ice_view='list', ice_columns=1,
        ice_rows=0, ice_delay=-1, ice_screen_off=False, ice_native_instant=False, ice_boot_check=False)
    assert labels() == ['Chrome','Gmail','Maps','Photos'], labels()
    sort_dialog(); t = texts()
    for text in ['По названию', 'По дате добавления: новые сверху', 'По дате добавления: старые сверху', 'Сначала размороженные', 'Вручную']:
        assert text in t, t
    capture('sort-light'); tap('По дате добавления: новые сверху')
    assert labels() == ['Maps','Photos','Gmail','Chrome'], labels()
    passed('date sort including old entries', order=labels())
    capture('newest-light')
    choose('По дате добавления: старые сверху', 'OLDEST')
    assert labels() == ['Chrome','Gmail','Photos','Maps'], labels()
    shell('am', 'force-stop', PKG); start()
    assert labels() == ['Chrome','Gmail','Photos','Maps'], labels()
    passed('oldest and preference after process restart')
    start('ThawActivity', '--ez', 'folder', 'true')
    # EXTRA_FOLDER is verified from the production constant before running this script.
    assert labels() == ['Chrome','Gmail','Photos'], labels()
    capture('folder-oldest')
    shell('input', 'swipe', '540', '1550', '540', '700', '350'); time.sleep(.3)
    assert labels()[-1] == 'Maps', labels()
    back()
    passed('shared folder order')
    start(); tap(rid='add'); wait_picker()
    assert find(rid='showUnselected').get('checked') == 'true'
    shown = labels()
    assert shown and not set(shown).intersection({'Chrome','Gmail','Maps','Photos'}), shown
    capture('unselected-light')
    passed('add starts on unselected and hides existing apps', first_apps=shown)
    first = shown[0]; tap(first)
    assert first not in labels()
    tap(rid='showSelected'); assert first in labels()
    tap(first); assert first not in labels()
    tap(rid='showUnselected'); assert first in labels()
    passed('selection disappears and can be undone in selected filter', app=first)
    tap(first); tap(rid='done'); time.sleep(1)
    added = next(a for a in apps() if a['key'] not in [x['key'] for x in fixture])
    assert added['addedAt'] >= now, added
    old = {a['key']: a['addedAt'] for a in apps()}
    assert old['com.android.chrome'] == 0 and old['com.google.android.apps.maps'] == 200, old
    choose('По дате добавления: новые сверху', 'NEWEST')
    assert labels()[0] == first, labels()
    passed('new addition saves timestamp, legacy dates survive serialization and new app sorts first', app=added['key'])
    tap(rid='add'); wait_picker()
    assert find(rid='showUnselected').get('checked') == 'true'
    assert first not in labels()
    tap(rid='showAll'); tap(rid='search'); shell('input', 'text', 'Chrome'); back()
    assert labels() == ['Chrome'], labels()
    shell('settings', 'put', 'system', 'font_scale', '1.5'); time.sleep(1); wait_picker()
    assert find(rid='showAll').get('checked') == 'true'
    assert labels() == ['Chrome'], labels()
    assert find(rid='search').get('text') == 'Chrome'
    passed('picker filter, search and selection survive activity recreation')
    back()
    shell('am', 'force-stop', PKG)
    shell('wm', 'size', '960x1920')
    shell('cmd', 'uimode', 'night', 'yes'); start()
    sort_dialog()
    capture('sort-dark-large')
    assert 'По дате добавления: старые сверху' in texts()
    tap('По дате добавления: старые сверху')
    tap(rid='add'); wait_picker()
    assert find(rid='showUnselected').get('checked') == 'true'
    capture('unselected-dark-large')
    passed('dark theme at 320 dp and 150 percent font')
    back()
    # Opening the common picker directly without the freezer extra keeps its default.
    start('AppPickerActivity')
    assert find(rid='showAll').get('checked') == 'true'
    passed('other picker callers keep all-apps default')
    print('ALL UI CHECKS PASSED', flush=True)
