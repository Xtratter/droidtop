# 📊 DroidTop

Монитор процессов и ресурсов для Android по мотивам **htop**: какие процессы работают,
сколько они едят процессора и памяти, как загружено каждое ядро — и всё это ещё и в
плавающем окошке поверх других приложений.

*An htop-like process & resource monitor for Android with root mode and a floating overlay.*

## Скачать

Готовые APK — в разделе [Releases](https://github.com/Xtratter/droidtop/releases). Нужен Android 8.0 или новее.

## Возможности

- **Метры как в htop** — загрузка и частота каждого ядра, память, подкачка (zram), задачи,
  средняя нагрузка, время работы, температура CPU и батареи
- **Список процессов** — PID, USER, S, CPU%, MEM%, RES, THR, TIME+; нажатие на заголовок колонки сортирует
  (повторное — меняет направление). На узком экране лишние колонки прячутся, в альбомной ориентации появляются
- **Названия приложений** вместо `com.example.app`, поиск по имени, PID или пользователю
- **Нажатие на процесс** — подробности (командная строка, пик памяти, подкачка, `oom_score_adj`) и действия:
  SIGTERM, SIGKILL, SIGSTOP/SIGCONT, остановить приложение, открыть «О приложении»
- **Плавающий оверлей** (⋮ → «Плавающий оверлей»): CPU, столбики ядер, память и топ процессов.
  Перетаскивается пальцем, нажатие открывает DroidTop, закрыть — из уведомления

## Root и без root

| | Без root | С root |
|---|---|---|
| Память, подкачка, частоты ядер, температуры | ✅ | ✅ |
| Загрузка CPU (общая и по ядрам) | ❌ | ✅ |
| Список процессов | только свои | все |
| Завершение процессов, остановка приложений | ❌ | ✅ |

Так решил Android: с версии 7 приложение видит только свои процессы, с версии 8 скрыт `/proc/stat`.
Для root подойдёт KernelSU, Magisk или APatch: включите ⋮ → «Режим root» и разрешите DroidTop в менеджере root.

> [!WARNING]
> Завершение системных процессов (`system_server`, `surfaceflinger` и т. п.) может подвесить или перезагрузить телефон.
> DroidTop спрашивает подтверждение, но будьте внимательны.

## Как это устроено

Никаких нативных библиотек: приложение держит открытую оболочку (`sh` или `su`) и раз в
несколько секунд одной командой читает `/proc/stat`, `/proc/meminfo`, `/proc/[pid]/stat`,
частоты из `/sys/devices/system/cpu` и температуры из `/sys/class/thermal`. Полные имена
процессов берутся из `ps` только для новых PID.

```
app/src/main/java/io/github/xtratter/droidtop/
├── Shell.kt          ← постоянная оболочка sh / su
├── ProcParser.kt     ← скрипт опроса и разбор /proc
├── Sampler.kt        ← фоновый поток опроса, общий для экрана и оверлея
├── Model.kt          ← Snapshot, ProcInfo, сортировки
├── MainActivity.kt   ← экран, меню, поиск
├── MetersView.kt     ← полоски ядер и памяти
├── Table.kt, HeaderView.kt, ProcRowView.kt  ← таблица процессов
├── ProcessDialog.kt  ← подробности и действия с процессом
├── SettingsDialog.kt, Prefs.kt              ← настройки
└── OverlayService.kt, OverlayView.kt        ← плавающий оверлей
```

## Сборка

```sh
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # тесты разбора /proc
```

Требуется JDK 17 и Android SDK (platform 35, build-tools 35.0.0).
Подробная инструкция по сборке на телефоне — в репозитории
[cat-hunt](https://github.com/Xtratter/cat-hunt/blob/main/docs/GUIDE.md).

## Лицензия

[GPL-3.0-or-later](LICENSE)
