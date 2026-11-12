# Agent language — read first

Owner's rule (taken from propda2, 12.11.2026); mandatory for cloud and local agents alike:

- Everything during a task is **in English**: reasoning, planning, notes, tool descriptions, and
  **progress messages shown to the owner mid-task** ("looking into X", "build running", etc.).
  A message being addressed to the owner does NOT make it Russian.
- Only the **final report** at the end of a task, and direct answers to the owner's questions, are
  **in Russian**.
- Tasks handed off to the local agent (spawn_task): title, summary and prompt **in English**, always.
- Keep English updates terse to save tokens.

# Работа с NetModeSwitch

Android-проект находится в этом каталоге. Текущий контекст, изменения и ограничения описаны в [HANDOFF.md](HANDOFF.md); подробности последней работы — в [HANDOFF_2026-10-22.md](HANDOFF_2026-10-22.md).

- В рабочем дереве много изменений, сделанных до последних задач. Не откатывайте их и не включайте весь каталог в коммит без разбора.
- IPv6 отключается только для пересылаемого трафика клиентов раздачи. Сам телефон, AdGuard и VPN должны сохранять свои настройки IPv6. Глобальные sysctl-записи разрешены в коде только для восстановления состояния, сохранённого старой версией 3.13.
- Повторные события не должны перезапускать исправный VPN/Proxy-туннель. Сохранённой записи недостаточно: перед повторным использованием проверяются реальные правила и процессы.
- При перестроении VPN/Proxy-маршрута сохраняйте защиту от прямого выхода клиентов. Не удаляйте временные защитные правила до проверки готового маршрута.
- SOCKS-посредник должен работать с UID приложения и привязывать исходящие сокеты к Android VPN Network. Запуск его с UID root может изменить попадание трафика в VPN.
- Не вызывайте `requestListeningState()` из `onStartListening()` плиток. Это уже вызывало бесконечный цикл root-команд и перегрев; см. [HANDOFF_2026-09-19.md](HANDOFF_2026-09-19.md).
- Проверки: `./gradlew --offline :app:testModernDebugUnitTest :app:testA10DebugUnitTest`. Сборки: `:app:assembleModernRelease :app:assembleA10Release`. При необходимости используйте JDK Android Studio.
- Скрипт `tools/check-tethering-emulator.py` предназначен только для пустого одноразового userdebug-эмулятора. Он отказывается работать с физическими устройствами. Не направляйте испытания сетевых правил на телефон владельца автоматически.
- Локальная проверка TCP/UDP не доказывает прирост скорости через SIM/WireGuard. Результаты измерений и предположения записывайте отдельно.
