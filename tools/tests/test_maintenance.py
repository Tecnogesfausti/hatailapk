import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


maintain = load("maintenance", "maintain.py")
prepare = load("prepare", "prepare-integrated.py")


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True, stderr=subprocess.DEVNULL).strip()


def repository(path):
    path.mkdir(parents=True)
    git(path, "init", "-b", "main")
    git(path, "config", "user.name", "Maintenance tests")
    git(path, "config", "user.email", "tests@example.invalid")


def commit_file(path, text):
    (path / "sample").write_text(text)
    git(path, "add", ".")
    git(path, "commit", "-m", text)
    return git(path, "rev-parse", "HEAD")


class SafeguardsTest(unittest.TestCase):
    def test_engine_follows_android_module_instead_of_latest_go_release(self):
        version = "v1.105.0-pre.0.20261001110716-689a0c155f57"
        self.assertEqual(maintain.engine_version(f"require (\n tailscale.com {version}\n)\n"), version)
        self.assertEqual(maintain.engine_ref(version), "689a0c155f57")
        self.assertEqual(maintain.engine_ref("v1.104.2"), "v1.104.2")
        with self.assertRaises(ValueError):
            maintain.engine_version("require tailscale.com v1.104.2\nreplace tailscale.com => ../custom")
        with self.assertRaises(ValueError):
            maintain.engine_version("require tailscale.com v1.104.2\nreplace (\n tailscale.com => ../custom\n)")

    def test_install_preserves_data_and_rejects_wrong_signature_or_downgrade(self):
        candidate = {"package": maintain.PACKAGE, "certificates": ["abc"], "versionCode": 10}
        self.assertEqual(maintain.install_plan(candidate, candidate), ["install", "--user", "0", "-r"])
        self.assertEqual(maintain.install_plan(candidate, None)[-1], "-r")
        for installed in (dict(candidate, certificates=["other"]), dict(candidate, versionCode=11)):
            with self.assertRaises(ValueError):
                maintain.install_plan(candidate, installed)
        with self.assertRaises(ValueError):
            maintain.install_plan(dict(candidate, package="io.homeassistant.companion.android"), None)

    def test_report_must_match_current_pins_and_original_urls(self):
        lock = json.loads((TOOLS.parent / "upstream.lock.json").read_text())
        report = {"schemaVersion": 1, "current": lock, "proposed": copy.deepcopy(lock)}
        maintain.validate_report(report, lock)
        changed = copy.deepcopy(report)
        changed["proposed"]["sources"]["tailscale"]["url"] = "https://example.invalid/fake.git"
        with self.assertRaises(ValueError):
            maintain.validate_report(changed, lock)
        changed = copy.deepcopy(lock)
        changed["sources"]["tailscale"]["commit"] = "0" * 40
        with self.assertRaises(ValueError):
            maintain.validate_report(report, changed)

    def test_apk_metadata_comes_from_verified_certificate_and_manifest(self):
        with patch.object(maintain, "android_tool", side_effect=lambda name: name), patch.object(
            maintain, "output", side_effect=[
                f"package: name='{maintain.PACKAGE}' versionCode='42' versionName='test'\n",
                "Signer #1 certificate SHA-256 digest: AABBCC\nV2 Signer: certificate SHA-256 digest: AABBCC\n",
            ],
        ):
            info = maintain.apk_info(Path("test.apk"))
        self.assertEqual(info["certificates"], ["aabbcc"])
        self.assertEqual(info["versionCode"], 42)

    def test_modified_apk_is_rejected_before_accessing_phone(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            artifact = folder / "hatailapk.apk"
            artifact.write_bytes(b"original")
            maintain.save(folder / "manifest.json", {"schemaVersion": 1, "checks": "passed", "apk": artifact.name,
                                                    "sha256": maintain.digest(artifact)})
            artifact.write_bytes(b"modified")
            args = argparse.Namespace(manifest=folder / "manifest.json", serial=None, dry_run=False)
            with patch.object(maintain, "apk_info") as inspect:
                with self.assertRaises(ValueError):
                    maintain.install(args)
                inspect.assert_not_called()


class WorktreeTest(unittest.TestCase):
    def test_new_pin_archives_old_generated_worktree_with_local_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "upstream/example"
            repository(source)
            first = commit_file(source, "first")
            second = commit_file(source, "second")
            git(source, "checkout", "--detach", first)
            with patch.object(prepare, "ROOT", root), patch.object(prepare, "OUT", root / ".integration"):
                (root / ".integration").mkdir()
                generated = prepare.worktree("example")
                (generated / "sample").write_text("local work to preserve")
                git(source, "checkout", "--detach", second)
                prepare.worktree("example")
                self.assertEqual(git(generated, "rev-parse", "HEAD"), second)
                archives = list((root / ".integration/archive").iterdir())
                self.assertEqual(len(archives), 1)
                self.assertEqual((archives[0] / "sample").read_text(), "local work to preserve")
                self.assertEqual(git(source, "rev-parse", "HEAD"), second)

    def test_trial_changes_only_isolated_branch_and_submodule_worktrees(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "project"
            repository(root)
            (root / ".gitignore").write_text("/.maintenance/\n/.integration/\n")
            tools = root / "tools"
            tools.mkdir()
            shutil.copyfile(TOOLS / "upstreams.py", tools / "upstreams.py")
            (tools / "prepare-integrated.py").write_text("print('Integration fixture prepared')\n")
            lock = {"schemaVersion": 1, "sources": {}}
            modules = []
            newer = None
            for name, repo in maintain.REPOS.items():
                source = root / "upstream" / name
                repository(source)
                git(source, "remote", "add", "origin", f"https://github.com/{repo}.git")
                if name == "tailscale-android":
                    (source / "go.mod").write_text("require tailscale.com v1.0.0\n")
                first = commit_file(source, "first")
                if name == "home-assistant-android":
                    newer = commit_file(source, "second")
                    git(source, "checkout", "--detach", first)
                lock["sources"][name] = {"url": f"https://github.com/{repo}.git", "commit": first}
                modules.append(f'[submodule "upstream/{name}"]\n path = upstream/{name}\n url = https://github.com/{repo}.git\n')
            lock["sources"]["tailscale"]["moduleVersion"] = "v1.0.0"
            (root / ".gitmodules").write_text("\n".join(modules))
            (root / "upstream.lock.json").write_text(json.dumps(lock))
            git(root, "add", ".")
            git(root, "commit", "-m", "Pin fixtures")
            original_head = git(root, "rev-parse", "HEAD")
            target = copy.deepcopy(lock)
            target["sources"]["home-assistant-android"]["commit"] = newer
            report = root / ".maintenance/report.json"
            maintain.save(report, {"schemaVersion": 1, "current": lock, "proposed": target})
            args = argparse.Namespace(report=report, name="fixture", allow_non_forward=False, build=False)
            with patch.object(maintain, "ROOT", root):
                maintain.trial(args)
            trial = root / ".maintenance/trials/fixture"
            self.assertEqual(git(root, "rev-parse", "HEAD"), original_head)
            self.assertEqual(git(root, "status", "--porcelain"), "")
            self.assertEqual(json.loads((root / "upstream.lock.json").read_text()), lock)
            self.assertEqual(git(root / "upstream/home-assistant-android", "rev-parse", "HEAD"),
                             lock["sources"]["home-assistant-android"]["commit"])
            self.assertEqual(git(trial / "upstream/home-assistant-android", "rev-parse", "HEAD"), newer)
            self.assertEqual(json.loads((trial / "upstream.lock.json").read_text()), target)


if __name__ == "__main__":
    unittest.main()
