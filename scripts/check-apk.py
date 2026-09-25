"""Fail a release if its supposedly standalone APK has missing/wrong native ABIs."""
import sys
import zipfile
path, requested = sys.argv[1:]
with zipfile.ZipFile(path) as apk:
    assert apk.testzip() is None, "Corrupt ZIP entry"
    assert {"AndroidManifest.xml", "classes.dex", "resources.arsc"} <= set(apk.namelist())
    abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
    assert abis == {"arm64-v8a"} if requested == "arm64-v8a" else {"arm64-v8a", "x86_64"} <= abis, abis
    assert all(item.compress_type == zipfile.ZIP_DEFLATED for item in apk.infolist() if item.filename.endswith(".so")), "Expected extracted native libraries"
    print(path, "verified native ABIs:", sorted(abis))
