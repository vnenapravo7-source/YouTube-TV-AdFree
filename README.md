# YouTube 7M — Android TV

Три сборки на базе **YouTube TV 7.12.300** и [yttvaf](https://github.com/chattytoaster/yttvaf). Реклама отключена; **SponsorBlock работает без прокси**. Для `armeabi-v7a`, Android 7.0+.

## Скачать

| Сборка | Возможности | APK |
| --- | --- | --- |
| **YouTube 7M** | Стабильная версия | [Скачать](https://github.com/vnenapravo7-source/YouTube-TV-AdFree/releases/latest/download/YouTubeTV-Mod-Main.apk) |
| **YouTube 7M+** | 7M + зелёные отметки SponsorBlock на шкале видео | [Скачать](https://github.com/vnenapravo7-source/YouTube-TV-AdFree/releases/latest/download/YouTubeTV-Mod-Main-Plus.apk) |
| **YouTube 7M v2** | 7M+ + перевод видео через Яндекс VOT | [Скачать](https://github.com/vnenapravo7-source/YouTube-TV-AdFree/releases/latest/download/YouTubeTV-Mod-V2.apk) |

[Что нового и ограничения V2 →](https://github.com/vnenapravo7-source/YouTube-TV-AdFree/releases/latest)

У сборок разные иконки и пакеты Android: они устанавливаются **рядом друг с другом и с обычным YouTube**. При переходе со старой сборки с другим пакетом потребуется войти в аккаунт заново.

## Перевод в V2

Нажмите **i** на пульте. Переключатель «Автоперевод» запускает перевод подходящих англоязычных роликов при воспроизведении; в выключенном положении используйте «Перевести видео» для текущего ролика. Громкость оригинала и перевода регулируется кнопками **← / →**. После перемотки с выключенным автопереводом ручной запуск может потребоваться снова. «Живой голос» пока недоступен — работает обычный голос Яндекса.

## Установка через ADB

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect <IP_ТЕЛЕВИЗОРА>:5555
& $adb install -r "YouTubeTV-Mod-Main.apk"
& $adb install -r "YouTubeTV-Mod-Main-Plus.apk"
& $adb install -r "YouTubeTV-Mod-V2.apk"
```

Указывайте путь к скачанным APK. Можно установить все три или только нужную сборку.

Проект не связан с Google или Яндексом. YouTube — товарный знак Google LLC.
