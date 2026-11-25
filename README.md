# Strażnik Baterii (BatteryWatch)

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![Licencja: MIT](https://img.shields.io/badge/licencja-MIT-blue)](LICENSE)

Aplikacja na Androida, która pokazuje, **co naprawdę zużywa baterię**, i pomaga ją oszczędzać.
Odczytuje te same dane, z których korzysta system (`dumpsys batterystats`, `dumpsys cpuinfo`),
zamienia je w czytelną diagnozę i proponuje konkretne poprawki. Każdą z nich można cofnąć.

> Aplikacja **nie ma uprawnienia do internetu**. Wszystkie pomiary i historia zostają na telefonie.

## Funkcje

| Ekran | Co pokazuje |
|---|---|
| **Na żywo** | Poziom naładowania, pobór prądu (mA / W), temperatura, tempo rozładowania w %/h i procesy, które najbardziej obciążają CPU. Dane odświeżają się tylko wtedy, gdy ekran jest otwarty. |
| **Ładowanie** | Monitor, który działa wyłącznie przy podłączonym zasilaniu i uruchamia się sam po podłączeniu ładowarki. Co 10 s zapisuje poziom, prąd, napięcie, moc, temperaturę i stan ekranu. Dla każdej sesji liczy tempo w %/h, czas do 80% i 100%, dodane mAh i szacowaną pojemność baterii, moc szczytową i średnią oraz czas powyżej 40 °C. Wykrywa dławienie temperaturowe i przerwy w ładowaniu. Rysuje wykresy, przechowuje historię z 30 dni i wysyła alerty o osiągnięciu progu (80/85/90/100%) oraz o przegrzaniu. |
| **Diagnoza** | Analiza `batterystats` od ostatniego ładowania: zużycie przy włączonym i zgaszonym ekranie, aplikacje, wakelocki, wybudzenia i sieć. Na końcu lista ustaleń posortowanych według wagi, każde z gotową akcją. |
| **Historia** | Próbka co 15 minut (WorkManager, bez stałej usługi w tle) i osobne tempo rozładowania dla ekranu zgaszonego i włączonego. |
| **Akcje** | Automatyczna jasność, czas wygaszania ekranu, skanowanie Wi-Fi i Bluetooth w tle, skróty do gestów i AOD oraz ograniczanie aplikacji w tle. Każda zmiana wymaga potwierdzenia i trafia do dziennika, z którego można ją cofnąć. |

## Wymagania

- Android 12 (API 31) lub nowszy. Aplikacja była rozwijana i testowana na **OnePlus CPH2609 z Androidem 16**. Na innych telefonach część danych może być niedostępna albo mieć inny format.
- Komputer z [ADB](https://developer.android.com/tools/adb) i włączonym debugowaniem USB lub bezprzewodowym w telefonie.
- Do zbudowania aplikacji: JDK 17+ (np. JDK dołączony do Android Studio) i Android SDK.
- Opcjonalnie [Shizuku](https://shizuku.rikka.app/) do akcji wymagających uprawnień powłoki.

## Instalacja

```bash
# JDK z Android Studio (macOS); na innych systemach ustaw własną ścieżkę
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./setup_permissions.sh
```

Gotowy plik APK z każdego commita na `main` można też pobrać z zakładki **Actions** (artefakt `app-debug`).

### Uprawnienia

Dane o baterii są w Androidzie chronione uprawnieniami systemowymi, których zwykła aplikacja nie może dostać.
Skrypt [`setup_permissions.sh`](setup_permissions.sh) nadaje je jednorazowo przez ADB:

| Uprawnienie | Po co |
|---|---|
| `DUMP`, `BATTERY_STATS`, `PACKAGE_USAGE_STATS` | odczyt `dumpsys batterystats` i `cpuinfo` |
| `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS` | akcje: jasność, wygaszanie, skanowanie Wi-Fi/BT |
| `POST_NOTIFICATIONS` | powiadomienia monitora ładowania |
| wyjątek z optymalizacji baterii | uruchamianie monitora ładowania z tła po podłączeniu ładowarki |

Uprawnienia przetrwają restart telefonu. Trzeba je nadać ponownie tylko po odinstalowaniu aplikacji.

> **OnePlus / OPPO / realme:** przed uruchomieniem skryptu włącz w Opcjach programisty
> **„Wyłącz monitorowanie uprawnień”**. Bez tego system odrzuci polecenia ADB.
> Po nadaniu uprawnień przełącznik można z powrotem wyłączyć.

### Shizuku (opcjonalnie)

Gdy [Shizuku](https://shizuku.rikka.app/) działa, akcje **„Ogranicz w tle”** i **„Zatrzymaj”** wykonują się od razu
(`appops RUN_ANY_IN_BACKGROUND`, `standby-bucket restricted`, `am force-stop`). Bez Shizuku aplikacja
otwiera ekran ustawień danej aplikacji, a zmianę trzeba wprowadzić ręcznie.

## Struktura projektu

```
app/src/main/java/pl/foxsoftware/batterywatch/
├── data/       odczyt urządzenia, parsery dumpsys, bazy historii i sesji ładowania
├── domain/     reguły diagnozy (FindingsEngine), analiza ładowania, wykonywanie i cofanie akcji
├── charging/   usługa monitora ładowania i jej automatyczne uruchamianie
└── ui/         ekrany Jetpack Compose i ViewModel
```

## Testy

```bash
./gradlew testDebugUnitTest
```

Parser i reguły diagnozy są sprawdzane na prawdziwym, **zanonimizowanym** zrzucie `batterystats`
z OnePlus CPH2609 ([`app/src/test/resources`](app/src/test/resources)). Adresy e-mail i nazwy
aplikacji firm trzecich zostały w nim zastąpione wartościami `example.com` i `com.example.appNN`.

Jeśli chcesz dodać zrzut z własnego telefonu (`adb shell dumpsys batterystats > dump.txt`),
przed publikacją usuń z niego adresy e-mail i listę zainstalowanych aplikacji.

## Bezpieczeństwo

- Aplikacja nie łączy się z internetem i nie wysyła żadnych danych.
- Każda zmiana ustawień wymaga potwierdzenia i zapisuje poprzednią wartość, więc można ją cofnąć z poziomu aplikacji.
- Uprawnienia nadawane przez ADB są silne (`WRITE_SECURE_SETTINGS`, `DUMP`). Instaluj tylko APK zbudowane z tego kodu.

## Zgłaszanie błędów

Jeśli coś nie działa na Twoim telefonie, [otwórz zgłoszenie](../../issues) i podaj model, wersję
Androida oraz to, które dane się nie wyświetlają. Nie wklejaj pełnego zrzutu `batterystats`,
bo zawiera on dane osobowe.

## Licencja

[MIT](LICENSE)
