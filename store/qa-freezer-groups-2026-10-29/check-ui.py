import subprocess as S, xml.etree.ElementTree as E, json, re, time, shlex
from pathlib import Path

ADB = ['/Users/j.golt/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5596']
PKG = 'com.netmode.switcher'
OUT = Path(__file__).resolve().parent
RESULTS = []

def call(*args): return S.check_output(ADB + list(args), text=True)
def shell(*args): return call('shell', shlex.join(args))
def tree():
    for _ in range(5):
        shell('rm', '-f', '/sdcard/freezer-groups.xml')
        shell('uiautomator', 'dump', '/sdcard/freezer-groups.xml')
        try: return E.fromstring(shell('cat', '/sdcard/freezer-groups.xml'))
        except S.CalledProcessError: time.sleep(.5)
    raise AssertionError('No UI hierarchy')
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
def passed(name, **data):
    RESULTS[:] = [r for r in RESULTS if r['check'] != name]
    RESULTS.append({'check': name, **data})
    (OUT / 'checks.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    print('PASS', name, data, flush=True)
def prefs(): return E.fromstring(shell('cat', f'/data/data/{PKG}/shared_prefs/netmode.xml'))
def apps(): return json.loads(prefs().find("string[@name='ice_apps']").text)
def pref(k):
    n = prefs().find(f"*[@name='{k}']")
    return n.text if n is not None else None
def start(activity='FreezerActivity', *args):
    shell('am', 'start', '-W', '-n', f'{PKG}/.{activity}', *args); time.sleep(.8)
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
    path = Path('/tmp/nms-freezer-groups-prefs.xml')
    E.ElementTree(r).write(path, encoding='utf-8', xml_declaration=True)
    call('push', str(path), '/data/local/tmp/nms-freezer-groups-prefs.xml')
    shell('mkdir', '-p', f'/data/data/{PKG}/shared_prefs')
    shell('cp', '/data/local/tmp/nms-freezer-groups-prefs.xml', f'/data/data/{PKG}/shared_prefs/netmode.xml')
    uid = shell('stat', '-c', '%u', f'/data/data/{PKG}').strip()
    shell('chown', '-R', uid + ':' + uid, f'/data/data/{PKG}/shared_prefs')
    shell('restorecon', '-RF', f'/data/data/{PKG}/shared_prefs')
    start()

if __name__ == '__main__':
    assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
    assert shell('id', '-u').strip() == '0'
    shell('settings', 'put', 'system', 'font_scale', '1.0')
    shell('cmd', 'uimode', 'night', 'no')
    shell('wm', 'size', '1080x2160'); shell('wm', 'density', '480')
    shell('input', 'keyevent', 'KEYCODE_WAKEUP'); shell('wm', 'dismiss-keyguard')
    for pkg in ['com.android.chrome', 'com.google.android.gm', 'com.google.android.apps.maps', 'com.google.android.apps.photos']:
        shell('pm', 'unsuspend', '--user', '0', pkg)
        shell('pm', 'enable', '--user', '0', pkg)
    now = int(time.time() * 1000)
    fixture = [
        {'key':'com.android.chrome', 'thawed':now, 'group':'Работа'},
        {'key':'com.google.android.gm', 'thawed':now, 'group':'Личное'},
        {'key':'com.google.android.apps.maps', 'thawed':now, 'group':'Работа'},
        {'key':'com.google.android.apps.photos', 'thawed':now},
    ]
    update(ice_apps=json.dumps(fixture), ice_groups=json.dumps(['Работа', 'Личное', 'Пустая']),
        ice_sort='NAME', ice_view='list', ice_columns=3, ice_rows=0, ice_delay=-1,
        ice_screen_off=False, ice_native_instant=False, ice_boot_check=False, backend='ROOT')
    tap('Работа · 2')
    assert labels() == ['Chrome', 'Maps'], labels()
    assert find(rid='summary').get('text') == 'Работа · заморожено 0 из 2'
    assert find(rid='groupFreeze').get('enabled') == 'true'
    assert find(rid='groupThaw').get('enabled') == 'false'
    capture('manager-work-light')
    passed('manager group filter and explicit actions')
    tap(rid='search'); shell('input', 'text', 'Chrome'); back()
    assert labels() == ['Chrome'], labels()
    tap(rid='groupMore'); tap('Разморозить на время'); tap('15 мин')
    time.sleep(1)
    stored = apps()
    assert all(a['keepUntil'] > now for a in stored if a.get('group') == 'Работа'), stored
    assert all(a['keepUntil'] == 0 for a in stored if a.get('group') != 'Работа'), stored
    assert labels() == ['Chrome'] and find(rid='search').get('text') == 'Chrome'
    passed('group action includes members hidden by search and excludes other groups')
    tap(rid='groupOrder'); tap('Опустить Работа'); tap('Готово')
    assert json.loads(pref('ice_groups')) == ['Личное', 'Работа', 'Пустая']
    assert labels() == ['Chrome'], labels()
    tap(rid='groupOrder'); tap('Опустить Работа'); tap('Отмена')
    assert json.loads(pref('ice_groups')) == ['Личное', 'Работа', 'Пустая']
    passed('manual chip order saves and cancel preserves it')
    start('ThawActivity', '--ez', 'folder', 'true')
    tap('Работа · 2')
    assert labels() == ['Chrome', 'Maps'], labels()
    capture('folder-work-light')
    assert not any(n.get('resource-id') == PKG + ':id/freezeAll' for n in nodes())
    tap(rid='groupMore'); capture('folder-group-menu-light'); back()
    passed('folder has same picker, explicit actions and group menu')
    tap(rid='groupOrder'); capture('group-order-light'); back()
    shell('am', 'force-stop', PKG)
    start('ThawActivity', '--es', 'ice_group', 'Работа', '--es', 'ice_group_action', 'OPEN')
    assert labels() == ['Chrome', 'Maps'], labels()
    passed('group shortcut intent opens current group after process restart')
    shell('settings', 'put', 'system', 'font_scale', '1.5'); time.sleep(1)
    assert find(rid='summary').get('text') == 'Работа · заморожено 0 из 2'
    assert labels() == ['Chrome', 'Maps'], labels()
    passed('folder selected group survives activity recreation')
    shell('wm', 'size', '960x1920'); shell('cmd', 'uimode', 'night', 'yes'); time.sleep(1)
    capture('folder-dark-large')
    tap(rid='groupOrder'); capture('group-order-dark-large'); back()
    tap(rid='groupMore'); capture('folder-group-menu-dark-large'); back()
    passed('dark theme at 320 dp and 150 percent font')
    print('ALL UI CHECKS PASSED', flush=True)
