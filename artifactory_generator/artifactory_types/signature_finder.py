from cryptography.hazmat.primitives import serialization

from cryptography.hazmat.primitives.serialization import pkcs7
from pathlib import Path
from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from ultimate_patcher.common import EXTRACTED_PATH


class SignatureFinder(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return True

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        with open(Path(self.args.temp_path) / EXTRACTED_PATH / "./unknown/META-INF/IMPORTED.DSA", "rb") as f:
            public_key = f.read()
        der_cert = pkcs7.load_der_pkcs7_certificates(public_key)[0]
        bytes_signature = der_cert.public_bytes(serialization.Encoding.DER)
        signature = ""
        for byte in bytes_signature:
            signature_byte = hex(byte).split("x")[-1]
            if len(signature_byte) == 1:
                signature_byte = "0" + signature_byte
            signature += signature_byte
        artifacts['PACKAGE_SIGNATURE'] = signature
        self.is_found = True
