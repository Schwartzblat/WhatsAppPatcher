import argparse
import json
import sys
from pathlib import Path
from typing import List

from stitch import Stitch
from stitch.records import build_record, load_features
from stitch.common import ExternalModule

from artifactory_generator.firebase_params import FirebaseParamsFinder
from artifactory_generator.fmessage import FMessage
from artifactory_generator.dex_copier import DexCopier
from artifactory_generator.signature_finder import SignatureFinder
from artifactory_generator.decrypt_protobuf_finder import DecryptProtobufFinder
from artifactory_generator.row_binder import RowBinderFinder
from artifactory_generator.settings_screen import SettingsScreenFinder
from artifactory_generator.read_receipts import ReadReceiptsFinder
from artifactory_generator.meta_ai_button import MetaAiButtonFinder, MetaAiCallsButtonFinder
from artifactory_generator.meta_ai_tab import MetaAiTabFinder
from artifactory_generator.message_search import MessageSearchFinder
from artifactory_generator.contact_search import ContactSearchFinder, ContactAccessorsFinder
from artifactory_generator.ab_props import AbPropsFinder
from artifactory_generator.mention_everyone import MentionEveryoneFinder
from artifactory_generator.contact_info import ContactInfoFinder
from artifactory_generator.group_info import GroupInfoFinder
from artifactory_generator.delete_for_everyone import DeleteForEveryoneFinder
from artifactory_generator.pinned_chats import PinnedChatsFinder
from artifactory_generator.upgrade import UpgradeCheckFinder
from constants import (ARTIFACTS_ASSET, FEATURES_PATH, MODULE_PROVIDER, PATCHER_VERSION,
                       PAYWALL_PROVIDER)


def get_args_from(argv: List[str]):
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK path', required=True)
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False, default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('-g', '--google-api-key', dest='api_key', help='Custom google api key', required=False,
                        default=None)
    parser.add_argument('--artifactory-only', dest='artifactory_only', metavar='OUT_JSON',
                        help='Only decode the APK and run the finders, then write a version record to OUT_JSON '
                             '(no build, no output APK)', required=False, default=None)
    parser.add_argument('--no-sign', dest='should_sign', help='Whether to sign the output APK', action='store_false',
                        required=False, default=True)
    parser.add_argument('--extra-artifacts', dest='extra_artifacts',
                        help='Extra artifact to add to the artifactory, in the format "key:value"',
                        required=False, default=[], nargs='+')
    parser.add_argument('--paywall', dest='paywall', help='Whether to add the paywall patch', required=False,
                        default=None)
    args, _ = parser.parse_known_args(argv)
    return args


def get_args():
    return get_args_from(sys.argv[1:])


def parse_extra_artifacts(args) -> dict:
    return {artifact.split(':')[0]: artifact.split(':')[1] for artifact in args.extra_artifacts}


def build_finders(args, artifactory_only: bool) -> list:
    artifactory_list = [
        FMessage(args),
        DexCopier(args),
        SignatureFinder(args),
        DecryptProtobufFinder(args),
        FirebaseParamsFinder(args),
        RowBinderFinder(args),
        SettingsScreenFinder(args),
        ReadReceiptsFinder(args),
        MetaAiButtonFinder(args),
        MetaAiCallsButtonFinder(args),
        MetaAiTabFinder(args),
        MessageSearchFinder(args),
        ContactSearchFinder(args),
        ContactAccessorsFinder(args),
        AbPropsFinder(args),
        MentionEveryoneFinder(args),
        GroupInfoFinder(args),
        ContactInfoFinder(args),
        DeleteForEveryoneFinder(args),
        PinnedChatsFinder(args),
        UpgradeCheckFinder(args)
    ]
    if artifactory_only:
        # DexCopier only prepares the dex for the on-device build, no record needs it.
        artifactory_list = [finder for finder in artifactory_list if not isinstance(finder, DexCopier)]
    return artifactory_list


def run_artifactory_only(args) -> int:
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=build_finders(args, True),
            google_api_key=args.api_key,
            should_sign=args.should_sign,
            extra_artifacts=parse_extra_artifacts(args),
    ) as stitch:
        artifacts = stitch.find_artifacts()
    record = build_record(args.apk_path, artifacts, load_features(FEATURES_PATH), PATCHER_VERSION)
    Path(args.artifactory_only).write_text(json.dumps(record, indent=2, sort_keys=True))
    print(f"dex_sha256: {record['dex_sha256']}")
    print(f"supported: {record['supported']}")
    print(f"features_off: {record['features_off']}")
    return 0 if record['supported'] else 3


def main():
    args = get_args()
    if args.artifactory_only is not None:
        sys.exit(run_artifactory_only(args))
    extra_artifacts = parse_extra_artifacts(args)
    external_modules = [
        ExternalModule(Path(__file__).parent / './smali_generator', MODULE_PROVIDER)
    ]
    if args.paywall is not None:
        extra_artifacts.setdefault('PAYWALL_PROVIDER_CLASS', PAYWALL_PROVIDER.rsplit('.', 1)[1])
        external_modules.append(ExternalModule(Path(args.paywall), PAYWALL_PROVIDER))
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=build_finders(args, False),
            google_api_key=args.api_key,
            external_modules=external_modules,
            should_sign=args.should_sign,
            extra_artifacts=extra_artifacts,
            artifacts_asset=ARTIFACTS_ASSET,
    ) as stitch:
        stitch.patch()


if __name__ == '__main__':
    main()
