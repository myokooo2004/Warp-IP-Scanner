The scanner engine binary goes here as a file named exactly `cf-scanner`
(no extension): a fully-static aarch64 musl build of
https://github.com/QMahyar/cf-scanner (v0.15.0).

It is copied to the app's private files dir on first run and executed
directly — no Ubuntu/proot needed. See ScanEngine.ensureBinary().
