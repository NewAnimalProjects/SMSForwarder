# SMSforwarder

SMSforwarder — Android-приложение на Kotlin, которое принимает входящие SMS даже в фоне, сохраняет их в локальную базу (Room) и пересылает содержимое в Telegram через Bot API. Приложение гарантирует доставку: WorkManager повторно пробует отправку всех сообщений со статусом PENDING/FAILED при следующем входящем SMS, открытии приложения или ручном запуске resend-воркера из UI.

## Возможности
- Приём SMS через `SmsReceiver`, даже если приложение закрыто.
- Сохранение сообщений в базе `sms_forwarder.db` со статусами PENDING/SENT/FAILED и текстом последней ошибки.
- Отправка контента сразу в несколько Telegram-чатов (укажите список ID в `TelegramClient`). В каждом сообщении отображается отправитель и номер SIM, на который пришла SMS.
- Автоматический рестарт отправки через WorkManager (`SmsForwardWorker`) при ошибках сети/Telegram.
- Простой UI (`MainActivity`): выдача разрешений (SMS + SIM), просмотр числа сохранённых/неотправленных сообщений, отображение списка SIM-карт и живой лог операций.

## Требования и зависимости
- Min SDK 24, Target SDK 35.
- Основные библиотеки: Room (runtime/ktx/kapt), WorkManager, OkHttp, kotlinx-coroutines-android, AndroidX Lifecycle.
- Разрешения: RECEIVE_SMS, INTERNET, READ_PHONE_STATE, READ_PHONE_NUMBERS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.

## Быстрый старт
1. Заполните токен и список чат-ID в `TelegramClient.kt`.
2. Соберите проект: `./gradlew assembleDebug`.
3. Установите APK, запустите приложение, выдайте разрешения на SMS и телефон.
4. Для надёжной работы разрешите игнорирование оптимизации батареи (кнопка в приложении).

## Структура
- `MainActivity` — UI, запрос разрешений, ручной запуск WorkManager и отображение логов.
- `SmsReceiver` — BroadcastReceiver, сохраняет SMS в Room и ставит очередь на отправку.
- `SmsForwardWorker` — CoroutineWorker, выбирает до 20 сообщений моложе 7 дней и отправляет в Telegram.
- `TelegramClient` — OkHttp-клиент к Bot API.
- Room-слой (`SmsMessageEntity`, `SmsDao`, `AppDatabase`) и миграция на версию 2 с колонкой `destination`.

## Дополнительно
- В `OperationLogger` собираются записи логов, которые видны на экране и в Logcat.
- UI автоматически обновляет счётчики и лог при каждом запуске/возвращении.
