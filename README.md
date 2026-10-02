# 🐝 WARP IP Scanner

Android app that finds working Cloudflare WARP endpoints on restricted
networks (e.g. MPT in Myanmar). One tap → real WireGuard handshake probes →
stable endpoints sorted by latency.

## Features

- **စကင် (Quick scan)** — preset WARP pools, real handshake probes, only
  0%-loss (stable) endpoints shown, sorted by ms ascending
- **Range scan** — custom CIDRs (e.g. `8.34.146.0/24`) → results appended to
  the persistent backup list + CSV export
- **Per-endpoint /24 button** — neighbor-scan that endpoint's /24 straight
  into the backup list
- **Backup list** — saved endpoints survive restarts; tap to copy, export CSV

## Engine

The scan engine is a fully-static `aarch64` musl build of
[cf-scanner](https://github.com/QMahyar/cf-scanner) (MIT), bundled in
`app/src/main/assets/cf-scanner`. It is extracted to the app's private dir
on first run and executed directly — no Ubuntu/proot/Termux needed.

## Build

GitHub Actions builds a signed release APK on every push to `main`.
Required repository secrets (same keystore as the CEIR app):

- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Scan tip

Run scans on **mobile data with all VPNs off** — a scan through a VPN or
Wi-Fi tests the wrong network.
