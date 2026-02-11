# JugaPlatform

Лёгкая endless-runner игра на Android (Kotlin + Jetpack Compose) в латиноамериканском стиле.

## Что реализовано

- Главный экран до старта игры:
  - логотип **JugaPlatform**,
  - личный рекорд (дистанция),
  - share-кнопки для соцсетей,
  - большая кнопка старта и маленькая кнопка политики.
- Перед показом кнопок:
  - запрашивается `installReferrer`,
  - генерируется и сохраняется `uuid` (client id),
  - загружается JSON-конфиг с `https://jugalatamgame.com/json.php?...`.
- `policy` открывается в `WebView` с cookies, JS, file chooser и круговым progress overlay.
- `startgame` запускает игру; при первом запуске запрашивается никнейм и сохраняется.
- После смерти:
  - показывается таблица лидеров из JSON + место игрока,
  - можно сохранить картинку с результатом в галерею.

## Технологии

- Kotlin, Jetpack Compose, Canvas rendering.
- OkHttp + Kotlin Serialization.
- Google Play Install Referrer API.
- Android WebView / AndroidX WebKit.
