# OpenBeam

OpenBeam is a modern open source P2P file transfer app for Android. Tap two phones together, NFC handles the handshake automatically, and the actual transfer runs over Wi-Fi Direct or Bluetooth LE at real speeds.

Think of it as what Android Beam should have been before Google killed it.

## How it works

**1. NFC Handshake**

When two devices get close, the Foreground Dispatch system intercepts the NDEF records and exchanges a token to negotiate transport parameters. This skips the discovery delay you normally get with Bluetooth and Wi-Fi scanning.

**2. Data Transfer**

Once the handshake is done, the payload gets routed through Quick Share (Nearby Connections API) using the best protocol for the job:

| Payload type | Protocol |
|---|---|
| Large files | TCP/UDP over Wi-Fi Direct |
| Small metadata bursts | RFCOMM/L2CAP (Bluetooth) |

**3. UI**

The interface follows MVVM with Android Data Binding. `ActivityMainBinding` handles real-time progress updates without touching the main thread.

## Stack

| | |
|---|---|
| Language | Java / Kotlin |
| Build System | Gradle 8.7 |
| AGP | 8.5.2 |
| Compiler | D8/R8 |
| UI | MVVM + Data Binding |

## Getting started

```bash
git clone https://github.com/jzadpy/OpenBeam.git
cd OpenBeam
./gradlew assembleDebug
```

You'll need two physical NFC-enabled devices to test transfers. Emulators won't cut it here.


## Contributing

Open an issue or send a PR, contributions are welcome.

made by jzadpy for basically everyone!
