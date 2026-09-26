# YouTube TV AdFree — Android TV

Модифицированная сборка [chattytoaster/yttvaf](https://github.com/chattytoaster/yttvaf) на базе **YouTube TV 7.12.300**.

![Android TV](https://img.shields.io/badge/Android_TV-7.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-blue)
![Package](https://img.shields.io/badge/package-com.chatty7.yttvaf-orange)

## Скачать

| Версия | Статус | Описание |
|---|---|---|
| **[fixed8](https://github.com/vnenapravo7-source/yttvaf/releases/tag/fixed8)** | ✅ Стабильная | Рекомендуемая версия |
| **[fixed13](https://github.com/vnenapravo7-source/yttvaf/releases/tag/fixed13)** | 🧪 Тестовая | Зелёные отметки рекламных сегментов на шкале воспроизведения |

> В `fixed13` возможны визуальные дефекты подсветки сегментов. Во время тестирования они не обнаружены.

## Что исправлено относительно исходного репозитория

- SponsorBlock работает **без включённого прокси**.
- Сохранена работа SponsorBlock при использовании SOCKS5-прокси.
- Убрано мигающее уведомление с адресом веб-настроек.
- Исправлены запуск приложения и упаковка APK для Android 11+.
- Пакет изменён на `com.chatty7.yttvaf`.
- В `fixed13` добавлены зелёные маркеры SponsorBlock на штатной шкале времени.

Остальные возможности исходного мода сохранены: блокировка рекламы, веб-настройки на порту `8888`, выбор категорий SponsorBlock, качества и скорости воспроизведения.

## Установка

```powershell
adb connect <IP_ТЕЛЕВИЗОРА>:5555
adb install -r YouTubeTV-Mod-com.chatty7.yttvaf-fixed8.apk
adb shell monkey -p com.chatty7.yttvaf 1
```

Для тестовой версии замените имя APK на `YouTubeTV-Mod-com.chatty7.yttvaf-fixed13.apk`.

---

Проект предназначен для личного и исследовательского использования. YouTube является товарным знаком Google LLC.
