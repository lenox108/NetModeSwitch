package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class RunFailureTest {
    @Test fun failuresAndSkipsCount() {
        listOf(
            "VPN Happ: состояние неизвестно, нажатие пропущено",
            "VPN Happ: изменение не подтвердилось, повторного нажатия нет",
            "VPN AdGuard: не подключился",
            "VPN AdGuard: не выключился",
            "VPN AdGuard: выключен, но приложение сразу подключилось снова само",
            "VPN AdGuard: без root выключается только плиткой, а её нет",
            "VPN: не выбрано приложение",
            "VPN: неверное имя приложения «x» — команда не выполнена",
            "SIM 2 не активна — режим сети не менялся",
            "SIM 1: ошибка timeout",
            "яркость: ошибка недопустимое значение",
            "HTTP 500",
            "HTTP: ошибка timeout",
            "команда: ошибка no shell",
            "раздача через VPN: VPN не подключён — раздача заблокирована",
            "Для быстрого запуска нужен root",
        ).forEach { assertTrue(it, RunFailure.isFailure(it)) }
    }

    @Test fun whatWasDoneDoesNotCount() {
        listOf(
            "Wi-Fi выключен",
            "VPN Happ вкл",
            "VPN уже выкл",
            "VPN Happ не подключён — пропущено",
            "Happ уже вкл",
            "SIM 1 → 4G",
            "HTTP 200",
            "HTTP 204",
            "уведомление",
            "команда выполнена",
            "команда выполнена: ошибка в выводе команды",
            "раздача через VPN (tun0)",
        ).forEach { assertFalse(it, RunFailure.isFailure(it)) }
    }
}
