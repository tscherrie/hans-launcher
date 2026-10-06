"""Offline invariants for the retained current-release acquisition evidence."""
import base64
import hashlib
import json
from pathlib import Path
import ssl
import unittest

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((ROOT / "runtime/runtime.lock.json").read_bytes())
EVIDENCE = ROOT / "runtime/evidence" / LOCK["runtime"]["version"]


def load(name):
    return json.loads((EVIDENCE / name).read_bytes())


class ReleaseProvenanceTest(unittest.TestCase):
    def test_official_stable_assets_and_source_tag_match_exact_lock(self):
        release, tag = load("release-metadata.json"), load("tag-metadata.json")
        self.assertEqual(release["tag_name"], LOCK["upstream"]["tag"])
        self.assertFalse(release["prerelease"])
        self.assertFalse(release["draft"])
        self.assertEqual(tag["object"]["type"], "commit")
        self.assertEqual(tag["object"]["sha"], LOCK["upstream"]["commit"])
        assets = {item["name"]: item for item in release["assets"]}
        for key in ("runtime", "codeModeHost", "schemaGenerator"):
            pin = LOCK[key]
            asset = assets[pin["releaseAsset"]]
            self.assertEqual(asset["browser_download_url"], pin["releaseUrl"])
            self.assertEqual(asset["digest"], "sha256:" + pin["archiveSha256"])
            self.assertEqual(asset["size"], pin["archiveBytes"])

    def test_cryptographic_checks_bind_all_three_payloads_and_reject_nine_controls(self):
        proof = load("verification.json")
        self.assertEqual(proof["upstream"], LOCK["upstream"])
        self.assertFalse(proof["nativeSourceBuildProven"])
        self.assertFalse(proof["publicReleaseGatePassed"])
        self.assertEqual({row["component"] for row in proof["results"]}, {"runtime", "codeModeHost", "schemaGenerator"})
        self.assertEqual(len(proof["results"]), 3)
        for row in proof["results"]:
            self.assertEqual(row["artifactSha256"], LOCK[row["component"]]["extractedSha256"])
            self.assertEqual(row["conversion"]["exitCode"], 0)
            self.assertEqual(row["verification"]["exitCode"], 0)
            self.assertIn("Verified OK", row["verification"]["stdout"] + row["verification"]["stderr"])
            args = row["verification"]["arguments"]
            self.assertEqual(args[:3], ["/usr/bin/sandbox-exec", "-p", "(version 1) (allow default) (deny network*)"])
            for flag, expected in (
                ("--certificate-github-workflow-sha", LOCK["upstream"]["commit"]),
                ("--certificate-github-workflow-ref", "refs/tags/" + LOCK["upstream"]["tag"]),
                ("--certificate-github-workflow-repository", "openai/codex"),
                ("--certificate-oidc-issuer", "https://token.actions.githubusercontent.com"),
                ("--certificate-identity", "https://github.com/openai/codex/.github/workflows/rust-release.yml@refs/tags/" + LOCK["upstream"]["tag"]),
            ):
                self.assertEqual(args.count(flag), 1)
                self.assertEqual(args[args.index(flag) + 1], expected)
            self.assertFalse(any("ignore" in arg for arg in args))
            self.assertEqual({item["control"] for item in row["negativeControls"]}, {"wrong-commit", "wrong-workflow", "wrong-artifact"})
            self.assertEqual(len(row["negativeControls"]), 3)
            self.assertTrue(all(item["exitCode"] != 0 for item in row["negativeControls"]))

    def test_original_signatures_and_certificates_survive_standardization(self):
        for row in load("verification.json")["results"]:
            original_raw = (EVIDENCE / (row["component"] + ".original.sigstore.json")).read_bytes()
            bundle_raw = (EVIDENCE / (row["component"] + ".sigstore.json")).read_bytes()
            self.assertEqual(hashlib.sha256(original_raw).hexdigest(), row["originalBundleSha256"])
            self.assertEqual(hashlib.sha256(bundle_raw).hexdigest(), row["standardizedBundleSha256"])
            original, bundle = json.loads(original_raw), json.loads(bundle_raw)
            self.assertEqual(original["base64Signature"], bundle["messageSignature"]["signature"])
            self.assertEqual(base64.b64decode(bundle["messageSignature"]["messageDigest"]["digest"]).hex(), row["artifactSha256"])
            self.assertEqual(base64.b64decode(bundle["verificationMaterial"]["certificate"]["rawBytes"]),
                             ssl.PEM_cert_to_DER_cert(base64.b64decode(original["cert"]).decode()))

    def test_bounded_memory_source_and_notices_do_not_claim_transitive_clearance(self):
        proof = load("source-compatibility.json")
        self.assertFalse(proof["publicReady"])
        self.assertEqual(proof["runtimeCommit"], LOCK["upstream"]["commit"])
        self.assertEqual(proof["runtimeArtifactSha256"], LOCK["runtime"]["extractedSha256"])
        self.assertEqual(proof["memoryMigrationFileCount"], 2)
        self.assertEqual(len(proof["memoryMigrations"]), 2)
        for item in proof["memoryMigrations"]:
            raw = (EVIDENCE / "source" / item["name"]).read_bytes()
            self.assertEqual(len(raw), item["bytes"])
            self.assertEqual(hashlib.sha384(raw).hexdigest(), item["sha384"])
            self.assertEqual(hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest(), item["gitBlobSha1"])
        for item in proof["topLevelNoticesUnchanged"]:
            raw = (EVIDENCE / "source" / item["name"]).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), item["sha256"])
            self.assertEqual(raw, (ROOT / "android/app/src/main/assets/hans/licenses" / ("codex-" + item["name"] + ".txt")).read_bytes())

    def test_native_smoke_is_offline_synthetic_and_not_an_android_or_model_claim(self):
        proof = load("native-smoke.json")
        self.assertEqual(proof["runtimeVersion"], LOCK["runtime"]["version"])
        self.assertEqual(proof["platform"], "linux-arm64-not-android")
        self.assertEqual(proof["status"], "passed")
        self.assertEqual(proof["modelTurns"], 0)
        self.assertFalse(proof["ownerAuthAccess"])
        self.assertTrue(proof["childrenStopped"])
        self.assertIn("namespaces17-effective-model-effort", proof["checks"])
        self.assertIn("initialize-experimental", proof["checks"])


if __name__ == "__main__":
    unittest.main()
