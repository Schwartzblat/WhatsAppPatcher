from pathlib import Path

import pytest

from stitch.dex_hash import apk_sha256, dex_sha256

APK = Path(__file__).parent.parent / 'com.whatsapp-2.26.40.72.apk'


@pytest.mark.skipif(not APK.exists(), reason='real WhatsApp fixture APK not in repo root')
def test_real_apk_hashes():
    assert dex_sha256(APK) == 'd678187db51d144835a8b7609f365fe27db705cf010ec4734d93ae9d55efe8c8'
    assert apk_sha256(APK) == '4b92cf77d25492dcb381534341fb50b01d59418e408ad4c2bdffb18d2eeb807f'
