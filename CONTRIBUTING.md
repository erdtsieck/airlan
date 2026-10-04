# Contributing to AirLAN

We take Pull Requests!

## Before you send a Pull Request

1. Open a [GitHub issue](https://github.com/erdtsieck/airlan/issues/new) first and describe the
   problem and the change you have in mind. AirLAN deliberately does little (on/off,
   cool/heat, temperature, switch-off timer), so a new feature needs a short discussion
   before code.
2. Once the maintainer agrees on the approach, start on your change.
3. Cover your change with automated tests and keep breaking changes to a bare minimum.
   Changes to the protocol code in `wfrac.js` need test vectors: frames captured from a real
   unit, or vectors regenerated from pywfrac with `tools/gen_pywfrac_vectors.py`.
4. Update the README when behaviour, the API or the supported firmware changes.
5. Make sure `npm test` passes.

## After you have sent a Pull Request

1. Apply or answer all feedback from the maintainer.
2. The Pull Request is merged after the maintainer approves it.

## Reporting results from your unit

Reports on hardware are valuable, especially for the HTTPS firmware branches
(`WF-RAC-HTTPS`, `WCBN4612L`), which have not been tested on real units yet. Please include
the firmware branch and the wireless and MCU versions from the `getAirconStat` reply.
Remove your unit's `airconId` and MAC address if you prefer.

## Setting up your environment

You only need Node.js 22 or newer. AirLAN has no dependencies.

```sh
npm test     # run the tests
npm start    # run the server against the units on your network
```

Regenerating the pywfrac reference vectors also needs Python and `pip install pywfrac==0.1.7`.
