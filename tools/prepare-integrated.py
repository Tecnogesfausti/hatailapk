#!/usr/bin/env python3
"""Prepare disposable fork worktrees; never patch the pinned upstream checkouts."""
from pathlib import Path
from datetime import datetime, timezone
import shutil
import subprocess
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".integration"


def worktree(name):
    source = ROOT / "upstream" / name
    target = OUT / name
    expected = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if target.exists():
        def revision(path, *args):
            return subprocess.check_output(["git", "-C", str(path), "rev-parse", *args], text=True).strip()
        if (revision(target, "--show-toplevel") != str(target.resolve()) or
                revision(target, "--path-format=absolute", "--git-common-dir") !=
                revision(source, "--path-format=absolute", "--git-common-dir")):
            raise ValueError(f"Refusing to replace an unrelated directory: {target}")
        observed = revision(target, "HEAD")
        if observed != expected:
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            archive = OUT / "archive" / f"{name}-{observed[:12]}-{stamp}"
            archive.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "-C", str(source), "worktree", "move", str(target), str(archive)], check=True)
            print(f"Preserved previous generated worktree at {archive}")
    if not target.exists():
        subprocess.run(["git", "-C", str(source), "worktree", "add", "--detach", str(target), "HEAD"], check=True)
    return target


def replace(path, old, new):
    content = path.read_text()
    if new in content:
        return
    if old not in content:
        raise ValueError(f"Upstream integration point changed: {path}")
    path.write_text(content.replace(old, new, 1))


def append(path, marker, content):
    existing = path.read_text()
    path.write_text(existing.split(marker, 1)[0].rstrip() + "\n\n" + marker + "\n" + content + "\n")


def build_information():
    lock = json.loads((ROOT / "upstream.lock.json").read_text())["sources"]
    version_file = ROOT / "upstream/tailscale-android/tailscale.version"
    versions = dict(re.findall(r'^([A-Z_]+)="([^"\n]*)"$', version_file.read_text(), re.M)) if version_file.exists() else {}
    native_matches = (versions.get("VERSION_GIT_HASH") == lock["tailscale"]["commit"] and
                      versions.get("VERSION_EXTRA_HASH") == lock["tailscale-android"]["commit"])
    components = []
    for name, source in lock.items():
        repo = source["url"].removeprefix("https://github.com/").removesuffix(".git")
        version = source.get("moduleVersion", source["commit"][:12])
        if native_matches and name.startswith("tailscale"):
            version = versions["VERSION_SHORT" if name == "tailscale" else "VERSION_LONG"]
        components.append({"repository": repo, "version": version, "commit": source["commit"]})
    revision = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--short=12", "HEAD"], text=True).strip()
    return {"revision": revision, "components": components}


def main():
    OUT.mkdir(exist_ok=True)
    ha = worktree("home-assistant-android")
    ts = worktree("tailscale-android")
    for relative in (
        "settings.gradle.kts", "app/build.gradle.kts",
        "app/src/main/kotlin/io/homeassistant/companion/android/HomeAssistantApplication.kt",
        "app/src/main/kotlin/io/homeassistant/companion/android/launch/LaunchActivity.kt",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/kotlin/io/homeassistant/companion/android/notifications/MessagingManager.kt",
        "app/src/main/kotlin/io/homeassistant/companion/android/frontend/FrontendScreen.kt",
    ):
        shutil.copyfile(ROOT / "upstream/home-assistant-android" / relative, ha / relative)
    shutil.copyfile(ROOT / "upstream/tailscale-android/android/src/main/java/com/tailscale/ipn/App.kt",
                    ts / "android/src/main/java/com/tailscale/ipn/App.kt")
    shutil.copyfile(ROOT / "integration/tailscale-library.gradle.kts", ts / "android/build.gradle.kts")
    replace(ts / "android/src/main/java/com/tailscale/ipn/App.kt", "class App : UninitializedApp()", "open class App : UninitializedApp()")
    # The host owns application attributes and its launcher. Keep Tailscale's real service/auth UI.
    android = "{http://schemas.android.com/apk/res/android}"
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    manifest = ET.parse(ROOT / "upstream/tailscale-android/android/src/main/AndroidManifest.xml")
    for permission in list(manifest.getroot().findall("uses-permission")):
        if permission.get(android + "name") == "android.permission.WRITE_EXTERNAL_STORAGE":
            manifest.getroot().remove(permission)
    application = manifest.getroot().find("application")
    application.attrib.clear()
    for activity in application.findall("activity"):
        if activity.get(android + "name") == "MainActivity":
            activity.set(android + "theme", "@style/Theme.App.SplashScreen")
            for intent in list(activity.findall("intent-filter")):
                if any(c.get(android + "name") in ("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER") for c in intent.findall("category")):
                    activity.remove(intent)
    # Avoid merging Tailscale's WorkManager foreground type into Companion's service.
    for service in list(application.findall("service")):
        if service.get(android + "name") == "androidx.work.impl.foreground.SystemForegroundService":
            application.remove(service)
    manifest.write(ts / "android/src/main/AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
    append(ha / "settings.gradle.kts", "// HATailAPK integration", '''
include(":tailscale-embedded")
project(":tailscale-embedded").projectDir = file("../tailscale-android/android")
dependencyResolutionManagement.versionCatalogs.create("tailscaleLibs") {
    from(files("../tailscale-android/android/gradle/libs.versions.toml"))
}
''')
    append(ha / "app/build.gradle.kts", "// HATailAPK integration", '''
android.defaultConfig {
    applicationId = "es.tecnogesfausti.hatailapk"
    minSdk = 26
}
dependencies {
    implementation(project(":tailscale-embedded"))
    implementation(files(rootProject.file("../../upstream/tailscale-android/android/libs/libtailscale.aar")))
    ksp(libs.androidx.room.compiler)
}
// The minimal build has no Firebase transport or Google Services credentials.
tasks.matching { it.name.contains("GoogleServices") }.configureEach { enabled = false }
''')
    replace(ha / "app/src/main/kotlin/io/homeassistant/companion/android/HomeAssistantApplication.kt",
            "open class HomeAssistantApplication : Application()", "open class HomeAssistantApplication : com.tailscale.ipn.App()")
    replace(ha / "app/src/main/AndroidManifest.xml", 'android:allowBackup="true"', 'android:allowBackup="false"')
    replace(ha / "app/src/main/AndroidManifest.xml", "</application>", '''
        <service android:name="io.homeassistant.companion.android.hatail.HatailTraccarService"
            android:exported="false" android:foregroundServiceType="location" />
        <service android:name="io.homeassistant.companion.android.hatail.HatailNotificationListener"
            android:label="@string/hatail_history_listener_label"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>''')
    replace(ha / "app/src/main/kotlin/io/homeassistant/companion/android/frontend/FrontendScreen.kt",
            "BackHandler(enabled = content?.canGoBack == true) { webView?.goBack() }",
            "val backgroundHatail = io.homeassistant.companion.android.hatail.LocalHatailExit.current\n    BackHandler(enabled = content?.canGoBack != true) { backgroundHatail() }\n    BackHandler(enabled = content?.canGoBack == true) { webView?.goBack() }")
    replace(ha / "app/src/main/kotlin/io/homeassistant/companion/android/notifications/MessagingManager.kt",
            "            val allowCommands = serverManager.integrationRepository(webhookServerId).isTrusted()",
            "            jsonData = io.homeassistant.companion.android.hatail.scopeHomeNotification(jsonData, webhookServerId)\n\n"
            "            val allowCommands = serverManager.integrationRepository(webhookServerId).isTrusted()")
    activity = ha / "app/src/main/kotlin/io/homeassistant/companion/android/launch/LaunchActivity.kt"
    replace(activity, "    private val viewModel: LaunchViewModel by viewModels(",
            "    internal val hatailRequestedNavigation: DeepLink.NavigateTo?\n"
            "        get() = IntentCompat.getParcelableExtra(intent, DEEP_LINK_KEY, DeepLink::class.java) as? DeepLink.NavigateTo\n\n"
            "    private val viewModel: LaunchViewModel by viewModels(")
    replace(activity, "viewModel.shouldShowSplashScreen()",
            "io.homeassistant.companion.android.hatail.HatailAccess.allowed && viewModel.shouldShowSplashScreen()")
    replace(activity, "        setContent {\n            HATheme {", "        setContent {\n            io.homeassistant.companion.android.hatail.HatailGate {\n            HATheme {")
    # Close the added composable wrapper at the end of setContent, before the next onCreate statement.
    text = activity.read_text()
    anchor = "        setContent {"
    start = text.index(anchor)
    # Braces in this block are Kotlin blocks (no brace-containing strings at this integration point).
    depth = 1
    index = text.index("{", start) + 1
    while depth:
        if text[index] == "{": depth += 1
        elif text[index] == "}": depth -= 1
        index += 1
    # On first preparation the inserted wrapper consumes the original onCreate closing brace.
    marker = "// HATailAPK gate end"
    if marker not in text:
        text = text[:index - 1] + "    }\n" + text[index - 1:index] + " " + marker + text[index:]
        activity.write_text(text)
    source = ha / "app/src/main/kotlin/io/homeassistant/companion/android/hatail"
    source.mkdir(parents=True, exist_ok=True)
    for path in (ROOT / "integration/companion").glob("*.kt"):
        shutil.copyfile(path, source / path.name)
    tests = ha / "app/src/test/kotlin/io/homeassistant/companion/android/hatail"
    tests.mkdir(parents=True, exist_ok=True)
    for path in (ROOT / "integration/tests").glob("*.kt"):
        shutil.copyfile(path, tests / path.name)
    shutil.copytree(ROOT / "connection-core/src/main/kotlin", ha / "app/src/main/kotlin", dirs_exist_ok=True)
    shutil.copytree(ROOT / "integration/drawable", ha / "common/src/main/res/drawable", dirs_exist_ok=True)
    shutil.copyfile(ROOT / "integration/hatail-strings.xml", ha / "common/src/main/res/values/hatail.xml")
    assets = ha / "app/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "hatail-build.json").write_text(json.dumps(build_information(), indent=2) + "\n")
    # Distinct launcher label and package preserve the already installed official apps.
    label = ha / "app/src/minimal/res/values/hatail.xml"
    label.parent.mkdir(parents=True, exist_ok=True)
    label.write_text('<resources><string name="app_name">HATailAPK · prueba</string></resources>\n')
    print(f"Prepared integrated Companion build at {ha}")


if __name__ == "__main__":
    main()
