# Third party notices

## XTLS/libXray

The Android runtime bundles the official XTLS/libXray release `v26.9.9` and Xray-core. libXray is distributed under the MIT License. Xray-core is distributed under the Mozilla Public License 2.0.

- libXray source and license: https://github.com/XTLS/libXray
- Xray-core source and license: https://github.com/XTLS/Xray-core
- Pinned Android artifact: `libxray-android.zip` from the libXray `v26.9.9` release. CI verifies SHA-256 `4998a8b56e4a78a164b5359d5690036f83da3b575465cea57ddf29c0149c345f` before extracting the AAR.

The AAR is downloaded during CI and is not stored in this repository.
