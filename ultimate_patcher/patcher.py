import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import lxml.etree
from androguard.core.apk import APK
from androguard.util import set_log
from ultimate_patcher.apk_utils import find_smali_file_by_class_name, extract_apk, is_bundle
from ultimate_patcher.common import ManifestKeys, SMALI_GENERATOR_TEMP_PATH, SMALI_GENERATOR_OUTPUT_PATH, \
    SMALI_EXTRACTED_PATH, \
    BUNDLE_APK_EXTRACTED_PATH, EXTRACTED_PATH

set_log('CRITICAL')
INVOKE_LINE = '\n\tinvoke-static {}, Lcom/smali_generator/TheAmazingPatch;->on_load()V\n\t'


def patch_artifacts(artifactory: Path, smali_generator_temp_path: Path) -> None:
    with open(artifactory, 'r') as file:
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


def prepare_smali(temp_path: Path, artifactory: Path, external_module: Path) -> None:
    smali_generator_temp_path = temp_path / SMALI_GENERATOR_TEMP_PATH
    print('[+] Copying the smali generator...')
    shutil.copytree(external_module, smali_generator_temp_path)
    print('[+] Patching the artifacts...')
    patch_artifacts(artifactory, smali_generator_temp_path)
    print('[+] Assembling the java...')
    subprocess.check_call(['./gradlew', 'assembleRelease'], cwd=smali_generator_temp_path)
    print('[+] Extracting the smali...')
    extract_apk(smali_generator_temp_path / SMALI_GENERATOR_OUTPUT_PATH, temp_path,
                smali_generator_temp_path / SMALI_EXTRACTED_PATH)


def get_activities_with_entry_points(apk_path: Path) -> list:
    manifest: lxml.etree.Element = APK(str(apk_path)).get_android_manifest_xml()
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


def patch_or_add_function(smali_file_path: Path, function_name: str) -> None:
    with open(smali_file_path, 'r') as file:
        smali_file = file.read()
    matches = re.findall(fr'\.method \w+ [^\n]*{function_name}[^\n]*\n[^\n]+', smali_file)
    if len(matches) == 0:
        pass
    for match in matches:
        smali_file = smali_file.replace(match, match + INVOKE_LINE)
    with open(smali_file_path, 'w') as file:
        file.write(smali_file)


def add_static_call_to_on_load(temp_path: Path, class_name: str, function_name: str) -> None:
    smali_file_path = find_smali_file_by_class_name(temp_path / EXTRACTED_PATH, class_name)
    if smali_file_path is None:
        print(f'[-] Failed to find smali file for {class_name}')
        return
    patch_or_add_function(smali_file_path, function_name)


def patch_entries(apk_path: Path, temp_path: Path) -> None:
    print('[+] Searching for activities with entry points...')
    activities_to_patch = get_activities_with_entry_points(
        Path(temp_path) / BUNDLE_APK_EXTRACTED_PATH / 'base.apk' if is_bundle(
            apk_path) else apk_path)
    print(f'[+] Found {len(activities_to_patch)} activities with entry points')
    for activity in activities_to_patch:
        add_static_call_to_on_load(temp_path, activity.get(
            ManifestKeys.TARGET_ACTIVITY if activity.tag == 'activity-alias' else ManifestKeys.NAME),
            'onCreate' if 'activity' in activity.tag else '<init>'
        )


def patch_apk(apk_path: Path, temp_path: Path, artifactory: Path, external_module: Path, arch: str) -> None:
    print('[+] Preparing the smali...')
    prepare_smali(temp_path, artifactory, external_module)
    print('[+] Applying the custom smali...')
    shutil.copytree(temp_path / SMALI_GENERATOR_TEMP_PATH / SMALI_EXTRACTED_PATH / 'smali',
                    temp_path / EXTRACTED_PATH / 'smali',
                    dirs_exist_ok=True)
    print('[+] Injecting the custom so...')
    os.makedirs(temp_path / EXTRACTED_PATH / 'lib' / arch, exist_ok=True)
    shutil.copytree(
        temp_path / SMALI_GENERATOR_TEMP_PATH / SMALI_EXTRACTED_PATH / 'lib' / arch,
        temp_path / EXTRACTED_PATH / 'lib' / arch,
        dirs_exist_ok=True)
    print('[+] Adding calls to the custom smali...')
    patch_entries(apk_path, temp_path)
