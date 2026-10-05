# AirLAN

Simple, cloud-free control for Mitsubishi Heavy Industries air conditioners with the
**WF-RAC** Wi-Fi module (the module behind the Smart M-Air app). AirLAN talks to the units
directly over your home network. It comes in two forms:

- an **Android app** ([`android/`](android)) that talks to the units itself, and
- a small **server with a web app**, for any phone or computer on the network.

Per unit you can:

- switch it on or off
- choose cooling or heating
- set the temperature (16–30 °C in 0.5° steps)
- have it switch off automatically after 30 minutes or 1–6 hours

That is all, on purpose. Indoor and outdoor temperature are shown along the way. Both
follow the device's language (English and Dutch so far) and light/dark theme.

> AirLAN is an independent project. It is not affiliated with, endorsed by or supported by
> Mitsubishi Heavy Industries. "Works with" means: tested against WF-RAC modules, see below.

## Android app

The app finds the units on your Wi-Fi network, asks you to name them, and runs the
switch-off timer as an alarm on the phone: it fires with the app closed, survives a
restart, retries for ten minutes if the unit cannot be reached and then notifies you. The
phone has to be on the home network when the timer fires.

On Android 17 and newer the app asks for the *Nearby devices* permission, which Android
requires for talking to devices on the local network.

Build it with Android Studio, or from the command line with JDK 21 and the Android SDK:

```sh
cd android
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Publishing on Google Play is described in [docs/play-store](docs/play-store).

## Server and web app

### Requirements

- Node.js 22 or newer. No other dependencies.
- A machine on the same network as the air conditioners that stays on: the switch-off
  timer runs in the server, so it only fires while the server is running. A Raspberry Pi,
  NAS or always-on PC works well.
- The units must already be connected to Wi-Fi (for example with the Smart M-Air app).
  AirLAN does not do the initial Wi-Fi setup.

### Getting started

```sh
git clone https://github.com/erdtsieck/airlan.git
cd airlan
npm start
```

Open `http://<ip-of-that-machine>:8321` on your phone and use "Add to Home screen".

On first start AirLAN scans the local network for units, and the app asks you to name each
one it finds. Not sure which unit is which? Switch it on from the app and listen. The gear
icon opens *Manage units*: search the network again (for a new unit), add a unit by IP
address, rename or forget one.

The scan covers networks up to /22 completely; on larger networks only the /24 around the
server's own address. If your units live on a separate network (an IoT VLAN, for
instance), add them by IP address and make sure the server can reach port 51443 there.

| Variable | Default | |
|---|---|---|
| `AIRLAN_PORT` | `8321` | Port of the web app and API |
| `AIRLAN_DATA_DIR` | `./data` | Where `state.json` is kept |

On Windows, `start.ps1 -Install` registers a task that starts the server at logon and
opens the firewall for the local subnet (it asks for elevation). `start.ps1 -Uninstall`
removes both. Logs go to `data/server.log`.

## Supported firmware

The WF-RAC module comes in three firmware branches:

| Branch | Transport | Status |
|---|---|---|
| `WF-RAC` (e.g. wireless 010, MCU 131) | HTTP | Tested on real units |
| `WF-RAC-HTTPS` | HTTPS, legacy mbedTLS | Implemented, **not yet tested on real units** |
| `WCBN4612L` | HTTPS, legacy mbedTLS | Implemented, **not yet tested on real units** |

AirLAN detects the transport per unit. If you have an HTTPS unit, please open an issue
with your result (the firmware branch and versions are in the `getAirconStat` reply;
`data/server.log` shows which transport was detected).

## How it works

- `wfrac.js`: the local protocol. `POST <scheme>://<unit>:51443/beaver/command/<command>`
  with a base64 `airconStat` frame. The encoder and decoder are a port of
  [pywfrac](https://github.com/blues-sechseck/pywfrac) and are tested byte for byte
  against it.
- `discovery.js`: which addresses to scan, and the port probe.
- `server.js`: HTTP server and API. It discovers units, follows them when DHCP gives them
  a new address, registers itself as an account with each unit and runs the switch-off
  timers.
- `public/`: the web app.
- `data/state.json`: unit names, addresses, timers and AirLAN's own account id. Timers
  survive a restart; a timer that expired while the server was down fires at startup.

### API

| Request | Effect |
|---|---|
| `GET /api/units` | All units with their current state; `name` is `null` until the user names it |
| `POST /api/units` | Body `{ "host": "192.168.1.50" }`: add the unit at that address |
| `PATCH /api/units/:id` | Body with any of `power` (bool), `mode` (`cool`, `heat`, `auto`, `fan`, `dry`), `presetTemp`, `name` |
| `DELETE /api/units/:id` | Forget the unit |
| `PUT /api/units/:id/timer` | Body `{ "minutes": 1–360 }`: switch off after that time |
| `DELETE /api/units/:id/timer` | Cancel the timer |
| `POST /api/scan` | Scan the network for units again |

Errors come back as `{ "error": { "code", "message" } }` with `code` one of
`unreachable`, `unit_refused`, `bad_response`, `invalid`, `not_found`,
`no_unit_at_address`.

## Limitations and quirks

- **Home network only.** The units are reachable on the local network only, and so is
  AirLAN. Anyone on that network can control the units. That is also true of the module
  itself, which has no authentication.
- **Account slot.** Each unit has four account slots. AirLAN uses one.
- **Write lock.** After a write, the writer holds an exclusive 60-second write lock. If
  someone just used Smart M-Air or a voice assistant, AirLAN may be refused briefly
  (`unit_refused`); the timer retries for ten minutes.
- **One request at a time.** The module accepts one connection at a time and about one
  request per second. AirLAN queues requests per unit.
- **No `fetch`.** The module only reads a request body that arrives in the same TCP packet
  as the headers. Node's `fetch` sends them separately and gets
  "501 Not supported this command", so AirLAN uses `node:http`.

## Development

```sh
npm test
```

The tests use frames captured from real units (`test/fixtures/live-frames.json`) and
reference vectors generated with pywfrac (`test/fixtures/pywfrac-vectors.json`). To
regenerate those vectors:

```sh
pip install pywfrac==0.1.7
python tools/gen_pywfrac_vectors.py
```

`test/fixtures/test-unit.key` is a throwaway key for the local HTTPS test server. The
Android tests in `android/app/src/test` use the same fixtures.

No air conditioner at hand? `node tools/fake-unit.mjs` runs a stand-in that answers like
an HTTP-firmware unit, keeps its state and logs every change. It also refuses what the real
module refuses: writes from unregistered operators and request bodies with `\/` escapes.

## Disclaimer

AirLAN controls heating and cooling equipment through an undocumented protocol. Use it at
your own risk. The authors accept no liability for damage, energy costs or discomfort.

## Translations

English is the source language. The app's text lives in `public/locales/<language>.json`;
it picks the first of the browser's preferred languages it has a file for, and falls back
to English for anything missing. To add a language, copy `en.json` to your language code
(for example `de.json`) and translate the values. Keep the `{placeholders}` as they are;
`npm test` checks that every translation has the same keys and placeholders as English.

The Android app keeps its text in `android/app/src/main/res/values/strings.xml` (English)
with one `values-<language>/strings.xml` per translation.

Available: English, Dutch.

## Contributing

We take Pull Requests! See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). Contains code derived from pywfrac, see [NOTICE](NOTICE).
