# Karoo Lupine Controls

Karoo app and Karoo extension for controlling a Lupine SL Grano F over BLE.

## About

Lupine Controls is a Karoo app plus ride-field extension for controlling the Lupine SL Grano F over BLE.

- Karoo-native remote-style control screen for pairing and light actions
- in-ride split field for quick light status and app launch
- English and German strings for Karoo system language support

## Beta Status

This repository now contains the current Lupine Controls beta release for Karoo.

- Lupine app metadata and install manifest are published under the new package name
- the Lupine BLE service and characteristic handles are integrated from packet captures
- the app UI follows the Lupine remote workflow with connect, feedback, and ride-field support
- fresh pairing now stays on one stable BLE target per attempt instead of switching across seen candidates

## Target Control Flow

1. Open the app on the Karoo.
2. Select the Lupine light once in the chooser.
3. Hold the main remote button to pair or reconnect.
4. Use the Lupine remote-style controls for low beam, high beam, profile change, and power handling.
5. Use the ride field for quick access while riding.

## Current Caveats

- I am still migrating the BLE protocol and not all Lupine notify states are decoded yet.
- I still need protocol details from Lupine for the encrypted BLE session after pairing before low/high/off, feedback, and unpair behavior can be completed with confidence.

## License

MIT License
