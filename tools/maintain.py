#!/usr/bin/env python3
"""Revisar upstreams, ensayar cambios, compilar e instalar HATailAPK sin borrar sus datos."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "es.tecnogesfausti.hatailapk.minimal.debug"
REPOS = {"home-assistant-android": "home-assistant/android",
         "tailscale-android": "tailscale/tailscale-android", "tailscale": "tailscale/tailscale"}
SHA = re.compile(r"[0-9a-f]{40}")


def run(args, cwd=None, **kwargs):
    kwargs.setdefault("check", True)
    return subprocess.run([str(a) for a in args], cwd=ROOT if cwd is None else cwd, **kwargs)


def output(args, cwd=None):
    return run(args, cwd, stdout=subprocess.PIPE, text=True).stdout.strip()


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def api(path, raw=False):
    headers = {"User-Agent": "HATailAPK-maintenance", "Accept": "application/vnd.github+json"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request("https://api.github.com/" + path, headers=headers)
    with urllib.request.urlopen(request, timeout=40) as response:
        data = response.read()
    return data.decode() if raw else json.loads(data)


def commit(repo, ref):
    value = api(f"repos/{repo}/commits/{urllib.parse.quote(ref, safe='')}")["sha"]
    if not SHA.fullmatch(value):
        raise ValueError("GitHub devolvió una revisión no válida")
    return value


def engine_version(go_mod):
    if re.search(r"^\s*(?:replace\s+)?tailscale\.com\s*(?:\S+\s*)?=>", go_mod, re.M):
        raise ValueError("El cliente reemplaza tailscale.com; revisar la compatibilidad manualmente")
    versions = re.findall(r"^\s*(?:require\s+)?tailscale\.com\s+(v\S+)", go_mod, re.M)
    if len(versions) != 1:
        raise ValueError("No se pudo determinar una única versión del motor en go.mod")
    return versions[0]


def engine_ref(version):
    pseudo = re.search(r"[.-]\d{14}-([0-9a-f]{12})$", version)
    return pseudo[1] if pseudo else version


def check(args):
    lock = json.loads((ROOT / "upstream.lock.json").read_text())
    proposed = json.loads(json.dumps(lock))
    releases = {}
    for name, override in (("home-assistant-android", args.ha_ref), ("tailscale-android", args.tailscale_ref)):
        repo = REPOS[name]
        release = None if override else api(f"repos/{repo}/releases/latest")
        ref = override or release["tag_name"]
        proposed["sources"][name]["commit"] = commit(repo, ref)
        releases[name] = {"ref": ref, "url": release["html_url"] if release else f"https://github.com/{repo}/tree/{ref}"}
    ts_sha = proposed["sources"]["tailscale-android"]["commit"]
    content = api(f"repos/{REPOS['tailscale-android']}/contents/go.mod?ref={ts_sha}")
    import base64
    version = engine_version(base64.b64decode(content["content"]).decode())
    proposed["sources"]["tailscale"].update(commit=commit(REPOS["tailscale"], engine_ref(version)), moduleVersion=version)
    comparisons = {}
    for name, repo in REPOS.items():
        before = lock["sources"][name]["commit"]
        after = proposed["sources"][name]["commit"]
        comparison = api(f"repos/{repo}/compare/{before}...{after}")
        comparisons[name] = {key: comparison[key] for key in ("status", "ahead_by", "behind_by")}
        comparisons[name]["url"] = f"https://github.com/{repo}/compare/{before}...{after}"
    report = {"schemaVersion": 1, "createdAt": stamp(), "current": lock, "proposed": proposed,
              "releases": releases, "comparisons": comparisons}
    folder = args.output.resolve()
    save(folder / "upstream-report.json", report)
    lines = ["# Revisión de upstreams", "", "Solo informa; no cambia las versiones ni instala una APK.", "",
             "| Fuente | Actual | Candidata | Relación |", "| --- | --- | --- | --- |"]
    for name in REPOS:
        before = lock["sources"][name]["commit"][:12]
        after = proposed["sources"][name]["commit"][:12]
        relation = comparisons[name]["status"]
        lines.append(f"| {name} | `{before}` | `{after}` | [{relation}]({comparisons[name]['url']}) |")
    lines += ["", "`behind` o `diverged` requiere revisión: una versión estable puede ser anterior a nuestra base de desarrollo.", "",
              f"Motor Go tomado del cliente Android: `{version}`.", ""]
    for name, release in releases.items():
        lines.append(f"- [{name}: {release['ref']}]({release['url']})")
    (folder / "upstream-report.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nInforme: {folder / 'upstream-report.json'}")


def validate_report(report, lock):
    if report.get("schemaVersion") != 1 or report.get("current") != lock:
        raise ValueError("Informe incompatible o antiguo; ejecutar check de nuevo")
    target = report.get("proposed", {})
    if set(target.get("sources", {})) != set(REPOS):
        raise ValueError("Fuentes candidatas incompletas")
    for name, source in target["sources"].items():
        if source.get("url") != lock["sources"][name]["url"] or not SHA.fullmatch(source.get("commit", "")):
            raise ValueError("Origen o revisión candidata no válidos")
    return target


def trial(args):
    lock = json.loads((ROOT / "upstream.lock.json").read_text())
    report = json.loads(args.report.read_text())
    target = validate_report(report, lock)
    if output(["git", "status", "--porcelain"]):
        raise ValueError("Guardar los cambios del repositorio antes de crear el ensayo")
    run([sys.executable, "tools/upstreams.py", "verify"])
    # Check ancestry from Git objects as well: the JSON report is not an authority.
    for name, source in target["sources"].items():
        original = ROOT / "upstream" / name
        sha = source["commit"]
        if run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], original,
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode:
            run(["git", "fetch", "--no-tags", "origin", sha], original)
        forward = subprocess.run(["git", "merge-base", "--is-ancestor", lock["sources"][name]["commit"], sha], cwd=original)
        if forward.returncode not in (0, 1):
            raise ValueError(f"No se pudo comprobar el historial de {name}")
        if forward.returncode and not args.allow_non_forward:
            raise ValueError(f"{name}: retroceso o historial divergente; revisar antes de usar --allow-non-forward")
    name = args.name or stamp()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
        raise ValueError("Nombre del ensayo no válido")
    folder = ROOT / ".maintenance/trials" / name
    branch = f"maintenance/upstream-{name}"
    if folder.exists():
        raise ValueError(f"El ensayo ya existe: {folder}")
    folder.parent.mkdir(parents=True, exist_ok=True)
    run(["git", "worktree", "add", "-b", branch, folder, "HEAD"])
    result = {"branch": branch, "path": str(folder), "status": "preparing"}
    result_file = ROOT / ".maintenance" / f"trial-{name}.json"
    save(result_file, result)
    try:
        for source_name, source in target["sources"].items():
            run(["git", "worktree", "add", "--detach", folder / "upstream" / source_name, source["commit"]],
                ROOT / "upstream" / source_name)
        save(folder / "upstream.lock.json", target)
        run(["git", "add", "upstream.lock.json", *[f"upstream/{n}" for n in REPOS]], folder)
        run([sys.executable, "tools/upstreams.py", "verify"], folder)
        run([sys.executable, "tools/prepare-integrated.py"], folder)
        result["status"] = "prepared"
        if args.build:
            build(folder)
            result["status"] = "built"
    except Exception:
        result["status"] = "failed"
        print(f"Ensayo conservado para revisar el fallo: {folder}", file=sys.stderr)
        raise
    finally:
        save(result_file, result)
    print(f"Ensayo: {folder}\nRama: {branch}\nRevisar el diff y probar antes de incorporar los cambios a main.")


def sdk():
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not value and (ROOT / "local.properties").exists():
        match = re.search(r"^sdk.dir=(.+)$", (ROOT / "local.properties").read_text(), re.M)
        if match:
            value = match[1].replace("\\:", ":").replace("\\\\", "\\")
    for candidate in ([Path(value)] if value else [Path.home() / "android/sdk", Path.home() / "Android/Sdk"]):
        if candidate.is_dir():
            return candidate.resolve()
    raise ValueError("No se encontró Android SDK; configurar ANDROID_HOME")


def android_tool(name):
    choices = [p / name for p in (sdk() / "build-tools").iterdir() if (p / name).is_file()]
    if not choices:
        raise ValueError(f"No se encontró {name} en Android SDK/build-tools")
    return max(choices, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))


def apk_info(path):
    badging = output([android_tool("aapt"), "dump", "badging", path])
    line = next(line for line in badging.splitlines() if line.startswith("package:"))
    fields = dict(re.findall(r"(\w+)='([^']*)'", line))
    certs = output([android_tool("apksigner"), "verify", "--print-certs", path])
    fingerprints = sorted(set(re.findall(
        r"^(?:Signer #\d+|V\d+ Signer(?: #\d+)?):? certificate SHA-256 digest: ([a-fA-F0-9]+)\s*$",
        certs, re.M,
    )))
    if not fingerprints:
        raise ValueError("No se encontró el certificado de firma del APK")
    return {"package": fields["name"], "versionCode": int(fields["versionCode"]),
            "versionName": fields["versionName"], "certificates": [c.lower() for c in fingerprints]}


def build(root=ROOT):
    folder = root / ".maintenance/builds" / stamp()
    folder.mkdir(parents=True)
    env = dict(os.environ, ANDROID_HOME=str(sdk()))
    print(f"Compilando; registro completo: {folder / 'build.log'}", flush=True)
    with (folder / "build.log").open("w") as log:
        for command in ([sys.executable, "tools/upstreams.py", "verify"],
                        ["./gradlew", "--no-daemon", "--max-workers=2", ":connection-core:test",
                         ":selector-ui:assembleDebug", ":selector-ui:lintDebug"],
                        ["./tools/build-integrated.sh"]):
            run(command, root, env=env, stdout=log, stderr=subprocess.STDOUT)
    artifact = folder / "hatailapk.apk"
    shutil.copyfile(root / "dist/hatailapk-casa-debug.apk", artifact)
    info = apk_info(artifact)
    if info["package"] != PACKAGE:
        raise ValueError("La compilación produjo un paquete inesperado")
    manifest = {"schemaVersion": 1, "checks": "passed", "apk": artifact.name, "sha256": digest(artifact),
                "revision": output(["git", "rev-parse", "HEAD"], root),
                "workingTreeDirty": bool(output(["git", "status", "--porcelain"], root)),
                "upstreams": json.loads((root / "upstream.lock.json").read_text()), **info}
    save(folder / "manifest.json", manifest)
    save(root / ".maintenance/latest-build.json", {"manifest": str(folder / "manifest.json")})
    print(f"Compilación y verificaciones correctas.\nAPK: {artifact}\nManifiesto: {folder / 'manifest.json'}")
    return folder / "manifest.json"


def install_plan(candidate, installed):
    if candidate["package"] != PACKAGE:
        raise ValueError("El APK no pertenece a HATailAPK")
    if installed is not None:
        if installed["package"] != PACKAGE or installed["certificates"] != candidate["certificates"]:
            raise ValueError("El certificado no coincide con la aplicación instalada; no se desinstalará ni borrará nada")
        if candidate["versionCode"] < installed["versionCode"]:
            raise ValueError("La versión candidata es anterior; se rechaza el downgrade")
    return ["install", "--user", "0", "-r"]


def install(args):
    manifest_path = args.manifest
    if manifest_path is None:
        manifest_path = Path(json.loads((ROOT / ".maintenance/latest-build.json").read_text())["manifest"])
    manifest_path = manifest_path.resolve()
    manifest = json.loads(manifest_path.read_text())
    artifact = (manifest_path.parent / manifest["apk"]).resolve()
    if (manifest.get("schemaVersion") != 1 or artifact.parent != manifest_path.parent or
            manifest.get("checks") != "passed" or digest(artifact) != manifest["sha256"]):
        raise ValueError("El APK o el manifiesto no corresponden a una compilación verificada")
    candidate = apk_info(artifact)
    if any(candidate[key] != manifest[key] for key in ("package", "certificates", "versionCode", "versionName")):
        raise ValueError("Los datos reales del APK no coinciden con su manifiesto")
    adb = sdk() / "platform-tools/adb"
    devices = [line.split()[0] for line in output([adb, "devices"]).splitlines()[1:]
               if len(line.split()) >= 2 and line.split()[1] == "device"]
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial is None or serial not in devices:
        raise ValueError("Conectar y autorizar un móvil; con varios, indicar --serial SERIAL")
    base = [adb, "-s", serial]
    folder = ROOT / ".maintenance/installations" / stamp()
    folder.mkdir(parents=True)
    paths = output([*base, "shell", "pm", "path", "--user", "0", PACKAGE]).splitlines()
    installed = None
    if paths:
        if any(not path.startswith("package:") for path in paths):
            raise ValueError("Respuesta inesperada al consultar la aplicación instalada")
        base_apk = next((p[8:] for p in paths if p.endswith("/base.apk")), None)
        if base_apk is None:
            raise ValueError("No se encontró el APK base instalado")
        previous = folder / "previous.apk"
        run([*base, "pull", base_apk, previous])
        installed = apk_info(previous)
    command = install_plan(candidate, installed)
    record = {"manifest": str(manifest_path), "candidate": candidate, "previous": installed,
              "status": "verified", "note": "previous.apk no contiene una copia de los datos de HA o Tailscale"}
    save(folder / "installation.json", record)
    if args.dry_run:
        print("Comprobación correcta; --dry-run no instaló el APK.")
        return
    try:
        run([*base, *command, artifact])
        record["status"] = "installed"
    except Exception:
        record["status"] = "failed"
        raise
    finally:
        save(folder / "installation.json", record)
    print("APK instalada conservando los datos. Comprobar Casa, Sol, reapertura y Atrás en el teléfono.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    checker = sub.add_parser("check", help="Consultar versiones estables y compatibilidad; no cambia fuentes")
    checker.add_argument("--output", type=Path, default=ROOT / ".maintenance")
    checker.add_argument("--ha-ref", help="Revisión explícita de Companion en lugar de la última estable")
    checker.add_argument("--tailscale-ref", help="Revisión explícita del cliente Android; su motor se deduce de go.mod")
    prepare = sub.add_parser("trial", help="Preparar una rama y worktrees aislados desde un informe")
    prepare.add_argument("--report", type=Path, default=ROOT / ".maintenance/upstream-report.json")
    prepare.add_argument("--name")
    prepare.add_argument("--allow-non-forward", action="store_true", help="Permitir una revisión previamente evaluada que retrocede o diverge")
    prepare.add_argument("--build", action="store_true")
    sub.add_parser("build", help="Verificar módulos y compilar APK con manifiesto y SHA-256")
    installer = sub.add_parser("install", help="Actualizar por ADB tras comprobar firma y versión")
    installer.add_argument("--manifest", type=Path)
    installer.add_argument("--serial")
    installer.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.command == "check": check(args)
    elif args.command == "trial": trial(args)
    elif args.command == "build": build()
    elif args.command == "install": install(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Mantenimiento interrumpido: {error}", file=sys.stderr)
        sys.exit(1)
