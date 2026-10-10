#!/usr/bin/env python3
"""Fail CI if Smart Party Plus accidentally exposes more than one launcher icon."""

from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
manifest_path = Path("app/src/main/AndroidManifest.xml")
root = ET.parse(manifest_path).getroot()
application = root.find("application")
if application is None:
    raise SystemExit("AndroidManifest.xml has no <application>")

launchers: list[str] = []
for component in list(application.findall("activity")) + list(application.findall("activity-alias")):
    for intent_filter in component.findall("intent-filter"):
        actions = {item.get(ANDROID + "name") for item in intent_filter.findall("action")}
        categories = {item.get(ANDROID + "name") for item in intent_filter.findall("category")}
        if (
            "android.intent.action.MAIN" in actions
            and "android.intent.category.LAUNCHER" in categories
        ):
            launchers.append(component.get(ANDROID + "name", "<unnamed>"))

expected = [".MainActivity"]
if launchers != expected:
    raise SystemExit(f"Expected exactly one launcher {expected}, found {launchers}")

inbox = next(
    (item for item in application.findall("activity") if item.get(ANDROID + "name") == ".InboxActivity"),
    None,
)
if inbox is None or inbox.get(ANDROID + "exported") != "false":
    raise SystemExit("InboxActivity must remain internal (android:exported=false)")

print("Launcher manifest policy OK: one Smart Party Plus icon via .MainActivity")
