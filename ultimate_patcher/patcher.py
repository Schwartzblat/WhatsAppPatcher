import glob
import json
import os
import pathlib
import re
import shutil
import subprocess
import lxml.etree
from androguard.core.apk import APK
from androguard.util import set_log
from ultimate_patcher import config
from ultimate_patcher.apk_utils import find_smali_file_by_class_name, extract_apk
from ultimate_patcher.config import ManifestKeys

set_log('CRITICAL')
INVOKE_LINE = '\n\tinvoke-static {}, Lcom/smali_generator/TheAmazingPatch;->on_load()V\n\t'


def patch_artifacts(args, smali_generator_temp_path: pathlib.Path) -> None:
    with open(args.artifactory, 'r') as file:
        artifactory = json.load(file)
    for file in glob.iglob(str(smali_generator_temp_path / '**' / '**'), recursive=True, include_hidden=True):
        if os.path.isdir(file):
            continue
        with open(file, 'rb') as f:
            old_data = f.read()
        data = old_data
        for key, value in artifactory.items():
            data = data.replace(f'{{{{{key}}}}}'.encode(), value.encode())
        if data != old_data:
            with open(file, 'wb') as f:
                f.write(data)


def prepare_smali(args) -> None:
    smali_generator_temp_path = args.temp_path / config.SMALI_GENERATOR_PATH
    print('[+] Copying the smali generator...')
    shutil.copytree(config.SMALI_GENERATOR_PATH, smali_generator_temp_path)
    print('[+] Patching the artifacts...')
    patch_artifacts(args, smali_generator_temp_path)
    print('[+] Assembling the java...')
    subprocess.check_call(['./gradlew', 'assembleRelease'], cwd=smali_generator_temp_path)
    print('[+] Extracting the smali...')
    extract_apk(smali_generator_temp_path / config.SMALI_GENERATOR_OUTPUT_PATH,
                smali_generator_temp_path / config.SMALI_GENERATOR_SMALI_PATH)


def get_activities_with_entry_points(apk_path: str) -> list:
    manifest: lxml.etree.Element = APK(apk_path).get_android_manifest_xml()
    activities = []
    for element in manifest.find('.//application').getchildren():
        should_patch = False
        if element.tag == 'activity' or element.tag == 'activity-alias':
            should_patch = element.get(ManifestKeys.EXPORTED) == 'true'
        elif element.tag == 'provider' or element.tag == 'receiver' or element.tag == 'service':
            should_patch = True
        if should_patch:
            activities.append(element)
    return activities


def patch_or_add_function(smali_file_path: pathlib.Path, function_name: str) -> None:
    with open(smali_file_path, 'r') as file:
        smali_file = file.read()
    matches = re.findall(fr'\.method public [^\n]*{function_name}[^\n]*\n[^\n]+', smali_file)
    if len(matches) == 0:
        pass
    for match in matches:
        smali_file = smali_file.replace(match, match + INVOKE_LINE)
    with open(smali_file_path, 'w') as file:
        file.write(smali_file)


def add_static_call_to_on_load(args, class_name: str, function_name: str) -> None:
    smali_file_path = find_smali_file_by_class_name(args.temp_path / config.EXTRACTED_TEMP_DIR,
                                                    class_name)
    if smali_file_path is None:
        print(f'[-] Failed to find smali file for {class_name}')
        return
    patch_or_add_function(smali_file_path, function_name)


def patch_entries(args) -> None:
    print('[+] Searching for activities with entry points...')
    activities_to_patch = get_activities_with_entry_points(args.apk_path)
    print(f'[+] Found {len(activities_to_patch)} activities with entry points')
    for activity in activities_to_patch:
        add_static_call_to_on_load(args, activity.get(
            ManifestKeys.TARGET_ACTIVITY if activity.tag == 'activity-alias' else ManifestKeys.NAME),
                                   'onCreate' if 'activity' in activity.tag else '<init>')


def patch_apk(args) -> None:
    print('[+] Preparing the smali...')
    prepare_smali(args)
    print('[+] Applying the custom smali...')
    shutil.copytree(args.temp_path / config.SMALI_GENERATOR_PATH / config.SMALI_GENERATOR_SMALI_PATH / 'smali',
                    args.temp_path / config.EXTRACTED_TEMP_DIR / 'smali',
                    dirs_exist_ok=True)
    print('[+] Injecting the custom so...')
    os.makedirs(args.temp_path / config.EXTRACTED_TEMP_DIR / 'lib' / args.arch, exist_ok=True)
    shutil.copytree(
        args.temp_path / config.SMALI_GENERATOR_PATH / config.SMALI_GENERATOR_SMALI_PATH / 'lib' / args.arch,
        args.temp_path / config.EXTRACTED_TEMP_DIR / 'lib' / args.arch,
        dirs_exist_ok=True)
    print('[+] Adding calls to the custom smali...')
    patch_entries(args)
