import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from typing import Optional

import lxml.etree
from androguard.core.apk import APK
from androguard.util import set_log
from androguard.core.axml import ARSCParser
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
    from ultimate_patcher.apk_utils import main_apk_name
    print('[+] Searching for activities with entry points...')
    activities_to_patch = get_activities_with_entry_points(
        Path(temp_path) / BUNDLE_APK_EXTRACTED_PATH / main_apk_name if is_bundle(
            apk_path) else apk_path)
    print(f'[+] Found {len(activities_to_patch)} activities with entry points')
    for activity in activities_to_patch:
        add_static_call_to_on_load(temp_path, activity.get(
            ManifestKeys.TARGET_ACTIVITY if activity.tag == 'activity-alias' else ManifestKeys.NAME),
            'onCreate' if 'activity' in activity.tag else '<init>'
        )


def patch_google_api_key(temp_path: Path, package_name: str, custom_google_api_key: str) -> None:
    print('[+] Searching for google api key...')
    resources_path = temp_path / EXTRACTED_PATH / 'resources.arsc'
    resources = ARSCParser(resources_path.read_bytes())
    _, original_google_api_key = resources.get_string(package_name, 'google_api_key')
    print(f'[+] Original google api key: {original_google_api_key}')
    with open(resources_path, 'rb') as file:
        resources_data = file.read()
    resources_data = resources_data.replace(original_google_api_key.encode(), custom_google_api_key.encode())
    with open(resources_path, 'wb') as file:
        file.write(resources_data)


def get_new_smali_folder(smali_path: Path) -> Path:
    smali_folders = [folder for folder in smali_path.iterdir() if
                     folder.is_dir() and folder.name.startswith('smali_classes')]
    if not smali_folders:
        return smali_path / 'smali'
    smali_folders.sort(key=lambda x: int(x.name.replace('smali_classes', '')))
    smali_index = int(smali_folders[-1].name.replace('smali_classes', '')) + 1
    (smali_path / f'smali_classes{smali_index}').mkdir()
    return smali_path / f'smali_classes{smali_index}'


def patch_apk(apk_path: Path, temp_path: Path, artifactory: Path, external_module: Path, arch: str,
              api_key: Optional[str] = None) -> None:
    print('[+] Preparing the smali...')
    prepare_smali(temp_path, artifactory, external_module)

    new_smali_folder = get_new_smali_folder(temp_path / EXTRACTED_PATH)

    print(f'[+] Applying the custom smali into {new_smali_folder.name}...')
    shutil.copytree(temp_path / SMALI_GENERATOR_TEMP_PATH / SMALI_EXTRACTED_PATH / 'smali',
                    new_smali_folder,
                    dirs_exist_ok=True)

    smali_folders = [folder for folder in
                     (temp_path / EXTRACTED_PATH).iterdir() if
                     folder.is_dir() and (folder.name.startswith('smali_classes') or folder.name == 'smali')]
    for folder in smali_folders:
        # move every first folder within to the new smali folder
        for file in folder.iterdir():
            if not (new_smali_folder / file.name).exists():
                shutil.move(file, new_smali_folder)
                break

    print('[+] Injecting the custom so...')
    os.makedirs(temp_path / EXTRACTED_PATH / 'lib' / arch, exist_ok=True)
    shutil.copytree(
        temp_path / SMALI_GENERATOR_TEMP_PATH / SMALI_EXTRACTED_PATH / 'lib' / arch,
        temp_path / EXTRACTED_PATH / 'lib' / arch,
        dirs_exist_ok=True)
    print('[+] Adding calls to the custom smali...')
    patch_entries(apk_path, temp_path)

    if api_key is not None:
        print('[+] Patching google api key...')
        if is_bundle(apk_path):
            from ultimate_patcher.apk_utils import main_apk_name
            package_name = APK(str(temp_path / BUNDLE_APK_EXTRACTED_PATH / main_apk_name)).get_package()
        else:
            package_name = APK(str(apk_path)).get_package()
        patch_google_api_key(temp_path, package_name, api_key)
