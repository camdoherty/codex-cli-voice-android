# Android Intents

Use `/data/data/com.termux/files/usr/bin/am` for Activity Manager actions on this phone. `termux-am` is optional and may fail when the Termux app socket server is unavailable.

## Activity Launches

```sh
am start -a android.settings.SETTINGS
am start -a android.settings.WIFI_SETTINGS
am start -a android.settings.APPLICATION_SETTINGS
am start -a android.settings.NOTIFICATION_SETTINGS
am start -a android.intent.action.VIEW -d https://example.com
am start -a android.intent.action.DIAL -d tel:5551234567
```

## Timers And Alarms

Use real Android timer/alarm intents instead of delayed notifications.

```sh
am start -W -a android.intent.action.SET_TIMER \
  --ei android.intent.extra.alarm.LENGTH 300 \
  --es android.intent.extra.alarm.MESSAGE "Check laundry" \
  --ez android.intent.extra.alarm.SKIP_UI true

am start -W -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 7 \
  --ei android.intent.extra.alarm.MINUTES 30 \
  --es android.intent.extra.alarm.MESSAGE "Wake up" \
  --ez android.intent.extra.alarm.SKIP_UI true
```

## Broadcasts And URI Conversion

Use `broadcast` only when the target app or component is known to receive the action.

```sh
am broadcast -a com.example.MY_EVENT --es msg "hello"
am to-uri -a android.intent.action.VIEW -d https://example.com
am to-intent-uri -a android.intent.action.VIEW -d https://example.com
am to-app-uri -a android.intent.action.VIEW -d https://example.com
```

## Intent Extras

Common extra flags:

```sh
--es key string
--ei key integer
--ez key true
--el key long
--eu key uri
```

Prefer `--dry-run` wrappers in `scripts/android-timer`, `scripts/android-alarm`, and `scripts/open-settings` when validating command shape without launching UI.
