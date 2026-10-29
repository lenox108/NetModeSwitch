import importlib.util, json, time
from pathlib import Path

spec = importlib.util.spec_from_file_location('group_ui', Path(__file__).with_name('check-ui.py'))
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)

if __name__ == '__main__':
    assert u.shell('getprop', 'ro.kernel.qemu').strip() == '1'
    u.RESULTS = json.loads((u.OUT / 'checks.json').read_text())
    u.shell('settings', 'put', 'system', 'font_scale', '1.0')
    u.shell('cmd', 'uimode', 'night', 'no')
    u.shell('wm', 'size', '1080x2160')
    u.shell('pm', 'disable-user', '--user', '0', 'com.android.chrome')
    u.shell('pm', 'suspend', '--user', '0', 'com.google.android.gm')
    now = int(time.time() * 1000)
    fixture = [
        {'key':'com.android.chrome', 'group':'Работа', 'as':'DEEP'},
        {'key':'com.google.android.gm', 'group':'Личное', 'as':'SOFT'},
        {'key':'com.google.android.apps.maps', 'group':'Работа', 'thawed':now},
        {'key':'com.google.android.apps.photos', 'thawed':now},
    ]
    u.update(ice_apps=json.dumps(fixture), ice_groups=json.dumps(['Личное', 'Работа', 'Пустая']),
        ice_columns=3, ice_labels=True, ice_view='list', ice_rows=0)
    u.tap('Работа · 2')
    assert u.find(rid='summary').get('text') == 'Работа · заморожено 1 из 2'
    assert u.find(rid='groupFreeze').get('enabled') == 'true'
    assert u.find(rid='groupThaw').get('enabled') == 'true'
    assert 'Все · 2' in u.texts() and 'Заморожены · 1' in u.texts()
    u.capture('release-manager-mixed-light')
    u.passed('release manager mixed group state and scoped filter counts')
    u.start('ThawActivity', '--es', 'ice_group', 'Работа', '--es', 'ice_group_action', 'OPEN')
    assert u.labels() == ['Chrome', 'Maps'], u.labels()
    state = [n.get('text') for n in u.nodes() if n.get('resource-id') == u.PKG + ':id/state']
    assert state == ['Заморожено', 'Разморожено'], state
    u.capture('release-folder-mixed-light')
    u.passed('release folder frozen and thawed labels; icon and snowflake checked visually')
    u.tap(rid='manage')
    assert u.find(rid='summary').get('text') == 'Работа · заморожено 1 из 2'
    u.passed('configure retains selected group')
    u.shell('am', 'force-stop', u.PKG)
    u.start('FreezerActivity', '--ez', 'ice_ungrouped', 'true')
    assert u.labels() == ['Photos'], u.labels()
    u.passed('ungrouped filter is independent from named groups')
    u.start('ThawActivity', '--es', 'ice_group', 'Пустая', '--es', 'ice_group_action', 'OPEN')
    assert u.find(rid='groupFreeze').get('enabled') == 'false'
    assert u.find(rid='groupThaw').get('enabled') == 'false'
    assert 'В этой группе пока нет приложений' in u.texts()
    u.capture('release-folder-empty')
    u.passed('empty group shortcut opens group with disabled actions')
    u.shell('settings', 'put', 'system', 'font_scale', '1.5')
    u.shell('wm', 'size', '960x1920')
    u.shell('cmd', 'uimode', 'night', 'yes')
    u.shell('am', 'force-stop', u.PKG)
    u.start('ThawActivity', '--es', 'ice_group', 'Работа', '--es', 'ice_group_action', 'OPEN')
    u.capture('release-folder-mixed-dark-large')
    assert u.find('Работа · 2').get('checked') == 'true'
    u.passed('selected chip scrolls into view at 320 dp and 150 percent font')
    # Reach the bottom of the first row: state labels stay in the scrollable grid.
    u.shell('input', 'swipe', '480', '1390', '480', '1140', '350')
    u.capture('release-folder-mixed-dark-large-scrolled')
    assert {'Заморожено', 'Разморожено'}.issubset(set(u.texts()))
    u.passed('state labels remain reachable at large font')
    u.tap(rid='groupOrder'); u.capture('release-group-order-dark-large'); u.back()
    (u.OUT / 'final-checks.json').write_text(json.dumps(u.RESULTS, ensure_ascii=False, indent=2))
