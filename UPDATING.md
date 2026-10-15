# Сборка и перенос на новую версию YouTube TV

Актуальная логика модификации находится в `src/`, а автоматизация — в `tools/`. Временные файлы создаются в `.build/`, распакованный APK ожидается в `work/decoded_modified/`.

## Что потребуется

- JDK 11–21;
- Python 3.8+;
- Android SDK с `d8`, `zipalign`, `apksigner` и `android.jar`;
- официальный APK YouTube TV для нужной архитектуры.

Android SDK определяется через `ANDROID_HOME` или `ANDROID_SDK_ROOT`. При необходимости путь к `android.jar` можно задать переменной `ANDROID_JAR_PATH`.

## Подготовка

```powershell
java -jar tools/apktool_3.0.3.jar d -f "YouTubeTV.apk" -o work/decoded_modified
```

Для новой версии YouTube проверьте имена обфусцированных классов в `src/dev/cobalt/coat/auo.java`, `bnj.java` и шаблоны smali-хуков в `tools/generate_proxy_helper.py`.

## Сборка

```powershell
python tools/generate_proxy_helper.py
```

Скрипт:

1. компилирует `ProxyHelper.java` и `VotManager.java`;
2. преобразует классы в smali;
3. обновляет хуки в `work/decoded_modified`;
4. собирает, выравнивает и подписывает APK ключом `mod-debug.keystore`;
5. сохраняет результат как `MODIFIED_FILE.apk`.

Вариантные параметры пакетов и портов находятся в `tools/patch_variant_ports.ps1`, перекраска иконок — в `tools/recolor_youtube_assets.ps1`.

## Проверка

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r ".\MODIFIED_FILE.apk"
& $adb shell monkey -p <имя_пакета> 1
```

Проверьте запуск, авторизацию, SponsorBlock без прокси, перемотку, историю просмотров и — для V2 — автоматический и ручной VOT.

> `mod-debug.keystore` необходимо сохранять: без прежнего ключа Android не позволит обновлять уже установленные сборки.
