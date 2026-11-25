#!/usr/bin/env bash
# Nadaje aplikacji Strażnik Baterii uprawnienia programistyczne przez ADB.
# Wymaga podłączonego telefonu z włączonym debugowaniem USB/bezprzewodowym.
set -uo pipefail

PKG=pl.foxsoftware.batterywatch
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}

"$ADB" get-state >/dev/null || { echo "Brak urządzenia ADB"; exit 1; }
"$ADB" shell pm path "$PKG" >/dev/null || { echo "Najpierw zainstaluj aplikację ($PKG)"; exit 1; }

failed=0
# PACKAGE_USAGE_STATS: Android 16 wymaga i uprawnienia, i appopsa GET_USAGE_STATS, żeby wolno było zrzucić batterystats
for perm in DUMP BATTERY_STATS WRITE_SECURE_SETTINGS PACKAGE_USAGE_STATS; do
  "$ADB" shell pm grant "$PKG" "android.permission.$perm" >/dev/null 2>&1
  if "$ADB" shell dumpsys package "$PKG" | grep -q "android.permission.$perm: granted=true"; then
    echo "✓ $perm"
  else
    echo "✗ $perm"; failed=1
  fi
done
for op in GET_USAGE_STATS WRITE_SETTINGS; do
  "$ADB" shell appops set "$PKG" "$op" allow >/dev/null 2>&1
  if "$ADB" shell appops get "$PKG" "$op" | grep -q "$op: allow"; then
    echo "✓ $op"
  else
    echo "✗ $op"; failed=1
  fi
done

# Monitor ładowania: powiadomienia + wyjątek z optymalizacji baterii, żeby usługa mogła wystartować
# z tła po podłączeniu ładowarki (Android 12+). Aplikacja i tak pracuje w tle tylko na zasilaniu.
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 \
  && echo "✓ POST_NOTIFICATIONS" || echo "• POST_NOTIFICATIONS (nadaj w aplikacji)"
if "$ADB" shell dumpsys deviceidle whitelist +"$PKG" 2>&1 | grep -q "Added\|already"; then
  echo "✓ wyjątek z optymalizacji baterii (auto-start monitora ładowania)"
else
  echo "• wyjątek z optymalizacji baterii nie powiódł się – monitor ładowania trzeba będzie włączać z powiadomienia"
fi

if [ "$failed" -ne 0 ]; then
  cat <<'MSG'

Telefon odrzucił część poleceń (powłoka ADB nie ma GRANT_RUNTIME_PERMISSIONS / MANAGE_APP_OPS_MODES).
Na OnePlus / OPPO / realme włącz w Opcjach programisty:
  „Wyłącz monitorowanie uprawnień” (Disable permission monitoring)
i uruchom skrypt ponownie. Po nadaniu uprawnień przełącznik można wyłączyć.
MSG
  exit 1
fi
echo "Gotowe. Uprawnienia przetrwają restart telefonu; trzeba je nadać ponownie tylko po odinstalowaniu aplikacji."
