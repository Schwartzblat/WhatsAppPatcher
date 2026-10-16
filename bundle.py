import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import List

from stitch.bundle import BundleModule, build_bundle, build_gradle_module

from constants import FEATURES_PATH, MODULE_PROVIDER, PACKAGE, PATCHER_VERSION, PAYWALL_PROVIDER

MODULE_PATH = Path(__file__).parent / 'smali_generator'
PAYWALL_PROVIDER_CLASS = PAYWALL_PROVIDER.rsplit('.', 1)[-1]


def get_args_from(argv: List[str]):
    parser = argparse.ArgumentParser(description='Build a signed patch bundle for the on-device patcher')
    parser.add_argument('--paywall', required=True, help='Path of the PatchPaywall checkout')
    parser.add_argument('--signing-key', dest='signing_key', required=True, help='EC P-256 private key PEM')
    parser.add_argument('-o', '--output', dest='output', default=f'bundle-{PACKAGE}-{PATCHER_VERSION}.zip',
                        help='Output bundle zip')
    parser.add_argument('--min-store-version-code', dest='min_store_version_code', type=int, default=0,
                        help='Oldest store version code that may install this bundle')
    parser.add_argument('--work', default='./.bundle-work', help='Work dir (must not exist; removed afterwards)')
    args, _ = parser.parse_known_args(argv)
    return args


def main(argv: List[str]) -> int:
    args = get_args_from(argv)
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
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
