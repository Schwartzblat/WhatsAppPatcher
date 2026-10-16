import sys
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from pyaxml import AXML

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import bundle
from constants import FEATURES_PATH
from stitch.bundle import verify_bundle

ANDROID_NS = 'http://schemas.android.com/apk/res/android'


def ec_keypair(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    private = tmp_path / 'key.pem'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    public = tmp_path / 'key.pub.pem'
    public.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,
                                                     serialization.PublicFormat.SubjectPublicKeyInfo))
    return private, public


def minimal_apk(path, min_sdk):
    axml = AXML()
    axml.from_xml(f'<manifest xmlns:android="{ANDROID_NS}" package="com.example">'
                  f'<uses-sdk android:minSdkVersion="{min_sdk}"/><application/></manifest>')
    axml.compute()
    with zipfile.ZipFile(path, 'w') as zip_file:
        zip_file.writestr('AndroidManifest.xml', axml.pack())
        zip_file.writestr('classes.dex', b'dex\n035\x00' + b'\x00' * 32)
    return path


def test_bundle_cli_builds_a_verifiable_bundle(tmp_path, monkeypatch):
    key_pem, pub_pem = ec_keypair(tmp_path)
    fake = {'smali_generator': minimal_apk(tmp_path / 'm.apk', min_sdk=33),
            'PatchPaywall': minimal_apk(tmp_path / 'p.apk', min_sdk=30)}
    seen = []

    def fake_build(module_path, work_dir, substitutions):
        seen.append((Path(module_path).name, substitutions))
        return fake[Path(module_path).name]

    monkeypatch.setattr(bundle, 'build_gradle_module', fake_build)
    out = tmp_path / 'b.zip'
    assert bundle.main(['--paywall', str(tmp_path / 'PatchPaywall'), '--signing-key', str(key_pem), '-o', str(out),
                        '--work', str(tmp_path / 'work')]) == 0
    meta = verify_bundle(out, pub_pem.read_bytes())
    assert meta['package'] == 'com.whatsapp' and meta['patcher_version'] == '3.0.0' and meta['min_sdk'] == 33
    assert meta['dex_order'] == ['module.apk', 'paywall.apk']
    assert seen == [('smali_generator', {}),
                    ('PatchPaywall', {'PAYWALL_PROVIDER_CLASS': 'InitProviderPaywallWhatsApp'})]
    assert zipfile.ZipFile(out).read('features.json') == FEATURES_PATH.read_bytes()
    assert not (tmp_path / 'work').exists()
