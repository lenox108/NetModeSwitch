package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeParserTest {

    private fun code(text: String) = SmsCodeParser.find(text)

    @Test fun plainCodes() {
        assertEquals("482910", code("Ваш код: 482910"))
        assertEquals("482916", code("Ваш код 482916"))
        assertEquals("1234", code("Код подтверждения 1234. Никому не сообщайте"))
        assertEquals("5521", code("5521 — код для входа в приложение"))
        assertEquals("739201", code("Your verification code is 739201"))
        assertEquals("88231", code("Kod: 88231"))
        assertEquals("4417", code("Код:4417"))
        assertEquals("123456", code("Пароль для входа: 123-456"))
        assertEquals("123456", code("Одноразовый пароль 12-34-56"))
        assertEquals("A7K29Q", code("Код: A7K29Q"))
    }

    @Test fun bankMessagesPickTheCodeNotTheAmount() {
        assertEquals("638104", code("Покупка 1 500,00 р в MAGNIT, карта *4417. Код: 638104. Не сообщайте его никому"))
        assertEquals("2914", code("Списание 2500р. Код подтверждения 2914. Баланс 12345.67р"))
        assertEquals("55120", code("Никому не говорите код! Перевод 3000 RUB на карту *1234. Код 55120"))
        assertEquals("903311", code("Оплата 4999 руб. на сайте SHOP. Код: 903311"))
        assertEquals("4821", code("Вход в Онлайн. Код 4821. Действует 5 мин. Не сообщайте никому"))
    }

    @Test fun messagesWithoutACode() {
        assertNull(code("Покупка 1500р, карта *4417. Баланс 23456,78р"))
        assertNull(code("Зачисление 10000 RUB. Доступно 25000 RUB"))
        assertNull(code("Ваш заказ 123456 доставлен"))
        assertNull(code("Перезвоните по номеру +7 900 123-45-67"))
        assertNull(code("Встреча 07.11.2026 в 18:30"))
        assertNull(code("Никому не сообщайте код из СМС"))
        assertNull(code("Код региона 77, телефон 4951234567"))
        assertNull(code(""))
    }

    /** Shapes taken from real bank and service messages; codes, cards and names changed. */
    @Test fun realWorldShapes() {
        assertEquals("3711", code("Списание 37000.00 р. НИКОМУ не сообщайте код 3711. Sovcombank.ru"))
        assertEquals("6440", code("Никому не говорите код 6440! Вход в Т-Банк в 22:29 30.06.26"))
        assertEquals("5612", code("Код активации Mir Pay 5612. Карта *1111. Код действителен 10 минут."))
        assertEquals("787111", code("Никому не сообщайте этот код для подтверждения : 787111. ВТБ"))
        assertEquals("9611", code("Для оплаты в SBER*5411*SAMOKAT 4029.00 RUB Карта *1111 введите код: 9611"))
        assertEquals("31211", code("31211 — ваш код для входа по Сбер ID. Никому его не сообщайте. \n@id.sber.ru #31211"))
        assertEquals("4811", code("Никому не сообщайте код 4811 Списание 5973р с MIR-1111 YANDEX PAY @acs3.sbrf.ru #4811"))
        assertEquals("54111", code("Никому не сообщайте код: 54111. СПИСАНИЕ 348.00 RUB в Y.M*lk"))
        assertEquals("119111", code("Code: 119111\nWith the code, you can confirm a 10.00 USD payment with your card ***1111 at SHOP"))
        assertEquals("8011", code("The PIN code for your card (last 4 digits: *** 1111)  is  8011. We recommend changing your PIN"))
        assertEquals("527611", code("G-527611 – Ваш проверочный код."))
        assertEquals("252511", code("Сообщите сотруднику код подтверждения 252511 КБ\"Ренессанс Кредит\"(ООО)"))
        assertEquals("3111", code("Не сообщайте этот код никому. Введите этот код, чтобы подтвердить мобильный платеж в качестве способа оплаты для сервисов Apple: 3111. Применяются Положения"))
        assertEquals("407311", code("Это ваш код для входа в Личный кабинет. Чтобы им не воспользовались злоумышленники, не сообщайте код никому: 407311"))
        assertEquals("572611", code("НИКОМУ НЕ СООБЩАЙТЕ КОД: 572611 выпуск карты Sovcombank.ru"))
        assertEquals("2711", code("Пароль:2711 для доступа к cafe_free. Логин:79000000000."))
        assertEquals("6711C7", code("Пароль для доступа к Личному кабинету:\n6711C7"))
        assertEquals("07211", code("Exist.RU Ключ для восстановления пароля 07211"))
        assertEquals("223111", code("<#>\n[223111] Verification code sent by KakaoTalk. Don't share it with others."))
    }

    @Test fun realWorldNonCodes() {
        assertNull(code("Счёт карты MIR-1111 12:56 Покупка 30р 33 PINGVINA ATTRAKTSIO Баланс: 466 560.22р"))
        assertNull(code("Последний день! -8% по коду TOAST8 на https://clck.ru/xxxx"))
        assertNull(code("Заглядывайте! Доставка 0₽. Промокод — LOVE100: https://eats.go.link/xx"))
        assertNull(code("Личный кабинет LK.RT.RU. Логин <lk_92351111> Пароль <HS5SprjdCiQK>"))
        assertNull(code("Установлен ПИН-код для карты MIR-1111."))
        assertNull(code("Пин-код к карте *1111 изменен. Приятных покупок с вашей картой!"))
        assertNull(code("Запись 15381111(№156181111) подтверждена: https://lk.example.ru/mc/x"))
        assertNull(code("Ваш заказ Е-17791111 на сумму 1 299 руб. подтвержден и уже в пути"))
        assertNull(code("Мы отклонили операцию. Для подтверждения операции позвоните в банк по номеру 1000."))
    }

    @Test fun skipsCardTailsDatesAndUnits() {
        assertEquals("7781", code("Код 7781 для операции по карте *4417"))
        assertEquals("330211", code("Остаток 15 ГБ. Код для продления 330211"))
        assertEquals("6210", code("Пароль 6210 действует до 2026 года"))
        assertNull(code("Код действует до 2026"))
    }
}
