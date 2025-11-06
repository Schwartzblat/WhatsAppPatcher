import argparse
import shutil
import os
from pathlib import Path
from ultimate_patcher.apk_utils import extract_apk, compile_apk, sign_apk
from artifactory import prepare_artifactory
from ultimate_patcher.common import SMALI_GENERATOR_TEMP_PATH, EXTRACTED_PATH
from ultimate_patcher.patcher import patch_apk
from downloader import download_latest_whatsapp


def get_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK path', required=False, default='latest')
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False, default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('--arch', dest='arch', help='Architecture', required=False, default='arm64-v8a',
                        choices=['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'])
    parser.add_argument('--artifactory', dest='artifactory', help='Artifactory path', required=False,
                        default='./artifactory.json')
    return parser.parse_args()


def clean_up(args):
    shutil.rmtree(args.temp_path, ignore_errors=True)
    if os.path.exists(args.artifactory):
        os.remove(args.artifactory)


def main():
    args = get_args()
    if args.apk_path == 'latest':
        args.apk_path = download_latest_whatsapp('Latest-WhatsApp.apk')
        if not args.apk_path:
            print('[-] Failed to download latest WhatsApp APK')
            exit(1)
    try:
        print('[+] Extracting APK...')
        extract_apk(Path(args.apk_path), Path(args.temp_path))

        print('[+] Finding artifacts...')
        prepare_artifactory(args)

        print('[+] Patching APK...')
        patch_apk(Path(args.apk_path), Path(args.temp_path), Path(args.artifactory), Path(SMALI_GENERATOR_TEMP_PATH),
                  args.arch)

        print('[+] Compiling APK...')
        compile_apk(Path(args.temp_path) / EXTRACTED_PATH, Path(args.output))

        print('[+] Signing APK...')
        sign_apk(Path(args.apk_path), Path(args.output), Path('signed_' + args.output))

    finally:
        print('[+] Cleaning up...')
        clean_up(args)


if __name__ == '__main__':
    main()
