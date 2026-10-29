import importlib.util, json, re, time
from pathlib import Path

spec = importlib.util.spec_from_file_location('group_ui', Path(__file__).with_name('check-ui.py'))
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)

if __name__ == '__main__':
    u.RESULTS = json.loads((u.OUT / 'checks.json').read_text())
    assert u.shell('getprop', 'ro.kernel.qemu').strip() == '1'
    u.shell('settings', 'put', 'system', 'font_scale', '1.0')
    u.shell('cmd', 'uimode', 'night', 'no')
    u.shell('wm', 'size', '1080x2160')
    for action, label in [('OPEN', 'Открыть группу'), ('FREEZE', 'Заморозить группу'), ('THAW', 'Разморозить группу')]:
        u.start('FreezerActivity', '--es', 'ice_group', 'Работа')
        try: u.find('Работа · 2')
        except AssertionError: pass
        if 'Работа · заморожено 0 из 2' not in u.texts(): u.tap('Работа · 2')
        u.tap(rid='groupMore'); u.tap('Добавить ярлык группы'); u.tap(label)
        u.capture('pin-' + action.lower())
        texts = u.texts()
        # Pixel Launcher uses the system confirmation sheet in this emulator.
        if 'Add automatically' in texts: u.tap('Add automatically')
        elif 'Добавить автоматически' in texts: u.tap('Добавить автоматически')
        elif 'Add to Home screen' in texts: u.tap('Add to Home screen')
        elif 'Добавить' in texts: u.tap('Добавить')
        else: raise AssertionError(('No launcher pin confirmation', texts))
        time.sleep(1)
        dump = u.shell('dumpsys', 'shortcut')
        (u.OUT / ('shortcuts-' + action.lower() + '.txt')).write_text(dump)
        assert 'ice-group-' + action + '-' in dump, dump
        u.passed('launcher accepted ' + action + ' group shortcut')
    u.shell('input', 'keyevent', 'KEYCODE_HOME'); time.sleep(1)
    u.capture('home-group-shortcuts')
    u.tap('Работа'); time.sleep(1)
    assert u.labels() == ['Chrome', 'Maps'], u.labels()
    u.passed('real home screen group shortcut opens group')
    u.start('FreezerActivity', '--es', 'ice_group', 'Работа')
    u.tap(rid='groupMore'); u.tap('Удалить'); u.tap('Удалить')
    time.sleep(1)
    dump = u.shell('dumpsys', 'shortcut')
    (u.OUT / 'shortcuts-after-group-removal.txt').write_text(dump)
    assert 'Работа' not in json.loads(u.pref('ice_groups'))
    assert all(a.get('group') != 'Работа' for a in u.apps())
    assert len(u.apps()) == 4
    blocks = re.findall(r'ShortcutInfo \{id=ice-group-.*?(?=ShortcutInfo|\Z)', dump, re.S)
    assert len(blocks) == 3 and all('Disabled: by app' in block for block in blocks)
    u.passed('group removal keeps its apps and refreshes pinned shortcuts')
    (u.OUT / 'shortcut-checks.json').write_text(json.dumps(u.RESULTS, ensure_ascii=False, indent=2))
