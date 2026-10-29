import importlib.util, json, re, time, sys, os
from pathlib import Path

spec = importlib.util.spec_from_file_location('base_ui', Path(__file__).parents[1] / 'qa-freezer-groups-2026-10-29/check-ui.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)
u.ADB = ['/Users/j.golt/Library/Android/sdk/platform-tools/adb', '-s', os.environ.get('NMS_QA_SERIAL', 'emulator-5596')]
u.OUT = Path(__file__).resolve().parent / (sys.argv[1] if len(sys.argv) > 1 else 'a10-debug')
u.OUT.mkdir(exist_ok=True)

def resumed():
    dump = u.shell('dumpsys', 'activity', 'activities')
    lines = [s for s in dump.splitlines() if 'mResumedActivity' in s or 'topResumedActivity' in s]
    return '\n'.join(lines)

def folder_task():
    for _ in range(20):
        active = resumed()
        if '.IceFolderActivity' in active: return int(re.search(r'\bt(\d+)', active).group(1))
        time.sleep(.3)
    raise AssertionError(active)

def assert_retained(task):
    recents = u.shell('dumpsys', 'activity', 'recents')
    (u.OUT / 'recent-tasks.txt').write_text(recents)
    assert '.IceFolderActivity' in recents
    assert re.search(r'(?:#|taskId=)' + str(task) + r'\b', recents), recents

def open_clock():
    component = u.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a',
        'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', 'com.google.android.deskclock').strip().splitlines()[-1]
    assert '/' in component, component
    # Exactly the app launch mechanism used by Freezer.launchScript, after thawing.
    u.shell('am', 'start', '-W', '--user', '0', '-n', component)
    time.sleep(.5)
    assert 'com.google.android.deskclock' in resumed(), resumed()

if __name__ == '__main__':
    assert u.shell('getprop', 'ro.kernel.qemu').strip() == '1'
    assert u.shell('id', '-u').strip() == '0'
    u.shell('settings', 'put', 'system', 'font_scale', '1.0')
    u.shell('cmd', 'uimode', 'night', 'no')
    u.shell('wm', 'size', '1080x2160'); u.shell('wm', 'density', '480')
    u.shell('input', 'keyevent', 'KEYCODE_WAKEUP'); u.shell('wm', 'dismiss-keyguard')
    for pkg in ['com.android.chrome', 'com.google.android.gm', 'com.google.android.apps.photos', 'com.google.android.deskclock']:
        u.shell('pm', 'unsuspend', '--user', '0', pkg); u.shell('pm', 'enable', '--user', '0', pkg)
    if int(u.shell('getprop', 'ro.build.version.sdk').strip()) >= 33:
        u.shell('pm', 'grant', 'com.google.android.deskclock', 'android.permission.POST_NOTIFICATIONS')
    u.shell('pm', 'disable-user', '--user', '0', 'com.android.chrome')
    now = int(time.time() * 1000)
    fixture = [
        {'key':'com.android.chrome', 'group':'Работа', 'as':'DEEP'},
        {'key':'com.google.android.deskclock', 'group':'Работа', 'thawed':now},
        {'key':'com.google.android.gm', 'group':'Личное', 'thawed':now},
        {'key':'com.google.android.apps.photos', 'thawed':now},
    ]
    u.update(ice_apps=json.dumps(fixture), ice_groups=json.dumps(['Работа','Личное']),
        ice_columns=3, ice_rows=0, ice_labels=True, ice_sort='NAME', ice_delay=-1,
        ice_screen_off=False, ice_native_instant=False, ice_boot_check=False, backend='ROOT')
    # The old component and extras are deliberately used: existing home-screen shortcuts work.
    u.start('ThawActivity', '--es', 'ice_group', 'Работа', '--es', 'ice_group_action', 'OPEN')
    task = folder_task()
    assert u.find(rid='summary').get('text') == 'Работа · заморожено 1 из 2'
    assert not any(n.get('resource-id') in {u.PKG+':id/groupOrder',u.PKG+':id/state'} for n in u.nodes())
    clock_label = next(label for label in u.labels() if label != 'Chrome')
    u.capture('folder-colour-snowflake')
    u.passed('legacy group shortcut opens persistent task; no order icon or extra state labels')
    u.tap(rid='groupMore'); u.tap('Порядок групп')
    u.tap('Опустить Работа'); u.tap('Готово')
    assert json.loads(u.pref('ice_groups')) == ['Личное','Работа']
    u.passed('group order is available and saves from group menu')
    u.tap(rid='search'); u.shell('input','text','c'); u.back()
    query = u.find(rid='search').get('text')
    assert query and clock_label in u.labels(), (query, u.labels())
    u.tap(clock_label); time.sleep(1)
    # No root backend is installed in the emulator: a failed open also keeps the folder.
    assert folder_task() == task
    assert u.find(rid='search').get('text') == query
    u.passed('tap without a privileged backend does not close folder')
    open_clock()
    assert_retained(task)
    u.capture('clock-foreground')
    u.back()
    assert folder_task() == task
    assert u.find(rid='search').get('text') == query
    assert u.find(rid='summary').get('text').startswith('Работа ·')
    u.capture('back-retains-folder')
    u.passed('back from launched app returns to same task, group and search')
    open_clock()
    u.shell('input','keyevent','KEYCODE_APP_SWITCH'); time.sleep(.5)
    u.capture('recents')
    assert_retained(task)
    # Pixel Launcher does not expose task previews through UIAutomator on Android 10.
    # The captured 1080x2160 screenshot shows the freezer card at the left edge.
    u.shell('input','tap','80','900'); time.sleep(.5)
    assert folder_task() == task
    assert u.find(rid='search').get('text') == query
    u.capture('recents-return')
    u.passed('freezer can be selected from native recent-apps UI')
    u.shell('input','keyevent','KEYCODE_HOME')
    u.start('ThawActivity','--ez','folder','true')
    assert folder_task() == task
    assert u.find(rid='search').get('text') == query
    u.passed('legacy common folder shortcut reuses task and current filter')
    u.start('ThawActivity','--es','ice_group','Личное','--es','ice_group_action','OPEN')
    assert folder_task() == task
    assert u.find(rid='search').get('text') in ('','Поиск приложений')
    assert u.find(rid='summary').get('text').startswith('Личное ·')
    u.passed('another group shortcut updates existing task instead of duplicating it')
    u.tap(rid='manage')
    assert '.FreezerActivity' in resumed()
    u.back()
    assert folder_task() == task
    u.passed('configure and back keep the folder')
    u.shell('settings','put','system','font_scale','1.5')
    u.shell('cmd','uimode','night','yes'); u.shell('wm','size','960x1920')
    u.capture('folder-dark-large')
    assert '.IceFolderActivity' in resumed()
    assert u.find(rid='summary').get('text').startswith('Личное ·')
    u.passed('selected group survives recreation at 320 dp and 150 percent font')
    print('ALL RETURN UI CHECKS PASSED', flush=True)
