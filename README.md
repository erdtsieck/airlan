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
- have it switch off automatically after 15 or 30 minutes or 1–6 hours
- set the fan speed (auto, quiet, low, medium, high) and the direction, at the bottom of the
  screen: up/down (swing, or highest to lowest) and, on units that have them, left/right
  (swing, five directions, wide or narrow) and 3D auto, which aims both vanes itself

That is all, on purpose. Indoor and outdoor temperature are shown along the way, and with
more than one unit a compact tile per unit shows at a glance which ones run. Both
follow the device's language (English and Dutch so far) and light/dark theme.

> AirLAN is an independent project. It is not affiliated with, endorsed by or supported by
> Mitsubishi Heavy Industries. "Works with" means: tested against WF-RAC modules, see below.

## Beta testing

The Android app is in a closed beta on Google Play. Anyone can join:

1. Join the [AirLAN testers group](https://groups.google.com/g/airlan-testers) with the
   Google account you use on your phone. Other members cannot see who is in the group, and
   the group sends no mail.
2. Open [the beta page](https://play.google.com/apps/testing/io.github.erdtsieck.airlan)
   with that same account and tap *Become a tester*.
3. Install AirLAN from Google Play. It can take a few minutes after step 2 before the
   install button appears.

Please stay in the beta for at least 14 days: Google only lets a new developer publish the
app for everyone after a closed test with enough testers for that long.

What is confirmed to work, and what is not:

- **Confirmed:** Mitsubishi Heavy Industries indoor units with the WF-RAC module on the
  HTTP firmware (wireless 010, MCU 131), on the developer's own three units.
- **Untested:** the HTTPS firmware branches (`WF-RAC-HTTPS`, `WCBN4612L`), see
  [Supported firmware](#supported-firmware). Reports are very welcome.
- **Not supported:** Mitsubishi **Electric** units (MELCloud, MAC-xxx Wi-Fi interfaces).
  They use a different system.

Found a problem, or does it work with your unit? Please
[open an issue](https://github.com/erdtsieck/airlan/issues/new/choose); the form asks for
the unit model and the versions that help most.

## Android app

The app finds the units on your Wi-Fi network, asks you to name them, and runs the
switch-off timer as an alarm on the phone: it fires with the app closed, survives a
restart, keeps trying for 75 minutes if the unit cannot be reached (see *Limitations*) and
then notifies you. The
phone has to be on the home network when the timer fires.

No air conditioner at hand? *Try a demo unit* (on the *Manage units* screen) adds a
simulated unit, marked *Demo*, that works like a real one: power, cooling or heating,
temperature, and the switch-off timer as a real alarm. It reports an indoor temperature
that moves towards the set temperature while it runs. Its state is kept in memory only and
starts afresh (off, cooling, 21 °C) whenever the app's process starts; the unit itself stays
until you forget it. Network scans never find or touch it.

On Android 17 and newer the app asks for the *Nearby devices* permission, which Android
requires for talking to devices on the local network. Without it the app still opens
and the demo unit works; real units are not searched or contacted until it is allowed
(the *Allow* button on the *Manage units* screen). Older versions need no permission
for that. Android 16 has an opt-in test mode for this protection (the compatibility change
`RESTRICT_LOCAL_NETWORK`, off unless enabled with `adb`); with it enabled, allow *Nearby
devices* for AirLAN in the app's permission settings.

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
one it finds. Not sure which unit is which? Switch it on from the app and listen, or match
the MAC address shown with the one printed on the Wi-Fi module. The gear icon opens *Manage
units*: search the network again (for a new unit), add a unit by IP address, put the units
in the order you like, rename or forget one.

Searching asks the network by mDNS (the units announce themselves as `_beaver._tcp`) and
also probes the module's port on every address, because not every unit answers mDNS every
time. The probe covers networks up to /22 completely; on larger networks only the /24
around the server's own address. If your units live on a separate network (an IoT VLAN, for
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
  against it. How a write deals with the account table and the write lock follows the
  [WF-RAC module reference](https://github.com/blues-sechseck/Mitsubishi-WF-RAC-Integration/blob/main/docs/wf-rac-module-reference.md)
  of the Mitsubishi WF-RAC Home Assistant integration.
- `mdns.js`: just enough multicast DNS to find the units by name.
- `discovery.js`: which addresses to probe, and the port probe.
- `timer-policy.js`: when the switch-off timer tries again, and when it gives up.
- `server.js`: HTTP server and API. It discovers units, follows them when DHCP gives them
  a new address (asking mDNS first), registers AirLAN's account with each unit and runs the
  switch-off timers.
- `public/`: the web app.
- `data/state.json`: unit names, addresses, timers and this installation's `deviceId`.
  Timers survive a restart; a timer that expired while the server was down fires at
  startup.

### API

| Request | Effect |
|---|---|
| `GET /api/units` | All units with their current state; `name` is `null` until the user names it |
| `POST /api/units` | Body `{ "host": "192.168.1.50" }`: add the unit at that address |
| `PATCH /api/units/:id` | Body with any of `power` (bool), `mode` (`cool`, `heat`, `auto`, `fan`, `dry`), `presetTemp`, `fanSpeed` (`"auto"` or 1–4: quiet, low, medium, high), `vane` (up/down direction: `"swing"` or 1–4, highest to lowest), `vaneLR` (left/right direction: `"swing"`, 1–5 from left to right, 6 wide, 7 narrow), `"3d"` as `vane` and/or `vaneLR` for 3D auto, `name`. `vaneLR` is `null`, and `vaneLR` and `"3d"` are refused, for a unit that does not report its left/right vane (see below) |
| `PUT /api/units/order` | Body `{ "order": ["<airconId>", …] }`: the order of the units; units left out keep theirs after the named ones |
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
- **Account slots.** A unit holds at most four accounts (apps or phones paired with it) and
  refuses new ones when they are taken; it never pushes one out. Writing needs an account,
  reading does not. All AirLAN clients, every phone and the server, register the same
  account, `98c7ec9c-1dac-4e5f-8d16-a145f58a38db`, so AirLAN takes one slot however many
  phones use it. That id is no secret and guards nothing: the module has no authentication
  (see *Home network only*). Writers are still told apart by a per-installation `deviceId`.
  AirLAN 0.1.1 and older registered an account per installation; newer versions free that
  one and move to the shared account the first time they reach the unit. When a unit is
  full of other apps' accounts, AirLAN says so (`no_account_slot`) when you add or name it.
  Remove the unit from an app you no longer use to make room.
- **Write lock.** After a write, the writer holds an exclusive 60-second write lock. If
  someone just used Smart M-Air or a voice assistant, AirLAN may be refused briefly
  briefly. AirLAN reads when the lock lapses (`expires` in the unit's status): a lock that
  lapses within 20 seconds is waited out, otherwise the app says how long it still runs
  (`unit_busy`). The timer waits it out by itself.
- **Hourly dropouts.** The module drops off Wi-Fi about once an hour, a reset commanded by the
  air conditioner itself. Usually it is back within a minute, but sometimes it does not
  accept connections again until the next hour; roaming between access points or bands can
  cost 15–35 minutes as well. The switch-off timer therefore keeps trying for 75 minutes,
  quickly at first and then every 5 minutes. A dedicated 2.4 GHz network for the units, with
  802.11r and 802.11v off, makes them more reliable.
- **Heating overshoots on purpose.** In heating mode the unit aims for the set temperature
  plus 2 °C, so a thermometer at seating height can read warmer than the setting. That is
  the air conditioner, not AirLAN.
- **Left/right vane and 3D auto.** Offered once a unit has shown that it reports them. Units
  with the older bus protocol take the commands but always read back as swinging, so AirLAN
  cannot show what they are set to and leaves both out; ceiling cassettes have no left/right
  vane. A unit whose left/right vane is on swing looks the same as such a unit until it is
  set to a direction once, with its remote control or another app.
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
