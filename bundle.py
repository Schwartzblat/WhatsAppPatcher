import argparse
import json
import os
import shutil
import sys
from pathlib import Path
from typing import List

import requests
from cryptography.hazmat.primitives import serialization
from stitch.bundle import BundleError, BundleModule, build_bundle, build_gradle_module, verify_bundle

from constants import FEATURES_PATH, MODULE_PROVIDER, PACKAGE, PATCHER_VERSION, PAYWALL_PROVIDER

MODULE_PATH = Path(__file__).parent / 'smali_generator'
PAYWALL_PROVIDER_CLASS = PAYWALL_PROVIDER.rsplit('.', 1)[-1]
UPLOAD_TIMEOUT_SECONDS = 120


def get_args_from(argv: List[str]):
    parser = argparse.ArgumentParser(description='Build a signed patch bundle for the on-device patcher')
    parser.add_argument('--paywall', required=True, help='Path of the PatchPaywall checkout')
    parser.add_argument('--signing-key', dest='signing_key', required=True, help='EC P-256 private key PEM')
    parser.add_argument('-o', '--output', dest='output', default=f'bundle-{PACKAGE}-{PATCHER_VERSION}.zip',
                        help='Output bundle zip')
    parser.add_argument('--min-store-version-code', dest='min_store_version_code', type=int, default=0,
                        help='Oldest store version code that may install this bundle')
    parser.add_argument('--work', default='./.bundle-work', help='Work dir (must not exist; removed afterwards)')
    parser.add_argument('--upload', action='store_true',
                        help='Upload the built bundle to $PATCH_SERVER_URL using $BUNDLE_UPLOAD_SECRET')
    args = parser.parse_args(argv)
    return args


def public_key_pem(private_key_path: Path) -> bytes:
    key = serialization.load_pem_private_key(private_key_path.read_bytes(), password=None)
    return key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)


def upload_bundle(out: Path, server_url: str, secret: str) -> int:
    try:
        response = requests.post(f'{server_url.rstrip("/")}/api/internal/bundles', data=out.read_bytes(),
                                 headers={'Content-Type': 'application/zip', 'X-Internal-Token': secret},
                                 timeout=UPLOAD_TIMEOUT_SECONDS)
    except requests.RequestException as e:
        print(f'Upload failed: {type(e).__name__}', file=sys.stderr)
        return 1
    try:
        body = response.json()
    except ValueError:
        body = None
    if not response.ok:
        detail = body.get('detail') if isinstance(body, dict) else None
        if detail is None:
            detail = response.reason
        if not isinstance(detail, str):
            detail = json.dumps(detail)
        print(f'Upload rejected (HTTP {response.status_code}): {detail}', file=sys.stderr)
        return 1
    if not isinstance(body, dict) or 'revision' not in body:
        print('Upload failed: unexpected server response', file=sys.stderr)
        return 1
    print(f'Uploaded {body.get("package")} {body.get("patcher_version")}: revision {body["revision"]}')
    return 0


def main(argv: List[str]) -> int:
    args = get_args_from(argv)
    server_url = secret = ''
    if args.upload:
        for name in ('PATCH_SERVER_URL', 'BUNDLE_UPLOAD_SECRET'):
            if not os.environ.get(name):
                print(f'--upload requires the {name} environment variable', file=sys.stderr)
                return 2
        server_url = os.environ['PATCH_SERVER_URL']
        secret = os.environ['BUNDLE_UPLOAD_SECRET']
    work = Path(args.work)
    if work.exists():
        print(f'Work dir {work} already exists; remove it first', file=sys.stderr)
        return 1
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        module_apk = build_gradle_module(MODULE_PATH, work / 'module', {})
        paywall_apk = build_gradle_module(Path(args.paywall), work / 'paywall',
                                          {'PAYWALL_PROVIDER_CLASS': PAYWALL_PROVIDER_CLASS})
        meta = build_bundle(out, PACKAGE, PATCHER_VERSION,
                            [BundleModule('module.apk', module_apk), BundleModule('paywall.apk', paywall_apk)],
                            [MODULE_PROVIDER, PAYWALL_PROVIDER], FEATURES_PATH, Path(args.signing_key),
                            args.min_store_version_code)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(json.dumps(meta, indent=2))
    if not args.upload:
        return 0
    try:
        verify_bundle(out, public_key_pem(Path(args.signing_key)))
    except BundleError as e:
        print(f'Built bundle failed verification, not uploading: {e}', file=sys.stderr)
        return 1
    return upload_bundle(out, server_url, secret)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
