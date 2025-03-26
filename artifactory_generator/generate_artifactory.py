"""
This module supposed to be edited by the user to generate the artifactory for their project.
"""
import glob
import json
import os

from artifactory_generator.artifactory_types.decrypt_protobuf_finder import DecryptProtobufFinder
from artifactory_generator.artifactory_types.dex_copier import DexCopier
from artifactory_generator.artifactory_types.signature_finder import SignatureFinder
from ultimate_patcher import config


def generate_artifactory(args):
    artifacts = dict()
    simple_artifacts_to_find = [
        DecryptProtobufFinder(args),
        SignatureFinder(args),
        DexCopier(args)
    ]
    for filename in glob.iglob(os.path.join(args.temp_path, config.EXTRACTED_TEMP_DIR, "**", "*.smali"), recursive=True):
        if len(simple_artifacts_to_find) == 0:
            break
        with open(filename, "r", encoding="utf8") as f:
            data = f.read()
        for artifact_finder in simple_artifacts_to_find:
            if not artifact_finder.class_filter(data):
                continue
            artifact_finder.extract_artifacts(artifacts, data)
            if artifact_finder.is_once and artifact_finder.is_found:
                simple_artifacts_to_find.remove(artifact_finder)

    print(f'[+] Found artifacts:\n{artifacts}')
    with open(args.artifactory, 'w') as file:
        json.dump(artifacts, file)
