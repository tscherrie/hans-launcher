#!/usr/bin/env python3
"""Verify a downloaded Codex release without account credentials or API calls.

Cosign bundle conversion may read public transparency proofs. The actual
signature checks (including negative controls) deny all network on macOS.
This proves publisher provenance, not a reproducible native source build.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import ssl
import subprocess


VERIFIER_SHA = "5cf948c2f4dfe59687bdd0b8523709067383e03982cc543475c8a7dc70e92a76"
TRUST_SHA = "6494e21ea73fa7ee769f85f57d5a3e6a08725eae1e38c755fc3517c9e6bc0b66"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_file(path, expected_sha, expected_size=None):
    if path.is_symlink() or not path.is_file() or digest(path) != expected_sha:
        raise ValueError(f"missing, symlinked or digest-mismatched evidence: {path}")
    if expected_size is not None and path.stat().st_size != expected_size:
        raise ValueError(f"size-mismatched evidence: {path}")


def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=60)
    return {"arguments": args, "exitCode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "release-metadata", "download-dir", "extracted-dir",
                 "verifier", "trusted-root", "output-dir"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    lock = json.loads(args.lock.read_bytes())
    release = json.loads(args.release_metadata.read_bytes())
    tag, commit = lock["upstream"]["tag"], lock["upstream"]["commit"]
    if release["tag_name"] != tag or release["prerelease"] or release["draft"]:
        raise ValueError("release is not the pinned official stable tag")
    check_file(args.verifier, VERIFIER_SHA)
    check_file(args.trusted_root, TRUST_SHA)
    assets = {row["name"]: row for row in release["assets"]}
    args.output_dir.mkdir(parents=True, exist_ok=False)
    results = []
    for key in ("runtime", "codeModeHost", "schemaGenerator"):
        pin = lock[key]
        name = pin.get("extractedName", pin["releaseAsset"].removesuffix(".tar.gz"))
        artifact = (args.extracted_dir / name).resolve()
        check_file(artifact, pin["extractedSha256"], pin["extractedBytes"])
        archive = assets[pin["releaseAsset"]]
        if (archive["digest"] != "sha256:" + pin["archiveSha256"] or
                archive["size"] != pin["archiveBytes"] or
                archive["browser_download_url"] != pin["releaseUrl"]):
            raise ValueError("published archive metadata differs from runtime pin")
        check_file(args.download_dir / pin["releaseAsset"],
                   pin["archiveSha256"], pin["archiveBytes"])
        sig_name = name + ".sigstore"
        signature = args.download_dir / sig_name
        sig_asset = assets[sig_name]
        check_file(signature, sig_asset["digest"].removeprefix("sha256:"), sig_asset["size"])
        standard = (args.output_dir / (key + ".sigstore.json")).resolve()
        converted = run([str(args.verifier.resolve()), "bundle", "create", "--artifact",
                         str(artifact), "--bundle", str(signature.resolve()), "--out", str(standard)])
        if converted["exitCode"]:
            raise ValueError("Cosign bundle conversion failed: " + converted["stderr"])
        original, bundle = json.loads(signature.read_bytes()), json.loads(standard.read_bytes())
        if (bundle["messageSignature"]["signature"] != original["base64Signature"] or
                base64.b64decode(bundle["messageSignature"]["messageDigest"]["digest"]).hex()
                != pin["extractedSha256"] or
                base64.b64decode(bundle["verificationMaterial"]["certificate"]["rawBytes"])
                != ssl.PEM_cert_to_DER_cert(base64.b64decode(original["cert"]).decode())):
            raise ValueError("signature, artifact digest or certificate changed in conversion")
        verify = ["/usr/bin/sandbox-exec", "-p", "(version 1) (allow default) (deny network*)",
                  str(args.verifier.resolve()), "verify-blob", "--bundle", str(standard),
                  "--new-bundle-format", "--trusted-root", str(args.trusted_root.resolve()),
                  "--certificate-identity",
                  f"https://github.com/openai/codex/.github/workflows/rust-release.yml@refs/tags/{tag}",
                  "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                  "--certificate-github-workflow-sha", commit,
                  "--certificate-github-workflow-repository", "openai/codex",
                  "--certificate-github-workflow-ref", "refs/tags/" + tag, str(artifact)]
        checked = run(verify)
        if checked["exitCode"] or "Verified OK" not in checked["stdout"] + checked["stderr"]:
            raise ValueError("offline cryptographic verification failed: " + checked["stderr"])
        controls = []
        for label, flag, wrong in (
            ("wrong-commit", "--certificate-github-workflow-sha", "0" * 40),
            ("wrong-workflow", "--certificate-identity", "https://example.invalid/not-openai"),
            ("wrong-artifact", None, str(args.lock.resolve())),
        ):
            negative = verify.copy()
            negative[negative.index(flag) + 1 if flag else -1] = wrong
            result = run(negative)
            if result["exitCode"] == 0:
                raise ValueError("cryptographic negative control accepted: " + label)
            controls.append({"control": label, **result})
        results.append({"component": key, "artifactSha256": digest(artifact),
                        "originalBundleSha256": digest(signature),
                        "standardizedBundleSha256": digest(standard),
                        "conversion": converted, "verification": checked, "negativeControls": controls})
    receipt = {"schemaVersion": 1, "upstream": lock["upstream"],
               "verifierSha256": VERIFIER_SHA, "trustedRootSha256": TRUST_SHA,
               "nativeSourceBuildProven": False, "publicReleaseGatePassed": False,
               "results": results}
    (args.output_dir / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Verified official release signatures and 9 rejecting negative controls; source rebuild remains separate.")


if __name__ == "__main__":
    main()
