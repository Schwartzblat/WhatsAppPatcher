from patches.Patch import Patch
import re
from hashlib import md5


class DexMD5BypassPatch(Patch):
    DEX_HASH_BODY = re.compile(
        ":try[^\n]+\s+[^\n]+getPackageCodePath\(\).*?invoke-virtual \{(?P<mac>\w+),\s*(?P<reg>\w+)\},\s*Ljavax\/crypto\/Mac;->update\(\[B\)V",
        re.DOTALL,
    )

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Bypassing dex file md5 hash"

    def get_dex_md5(self) -> bytes:
        with open(self.extracted_path + "/classes.dex", "rb") as f:
            return md5(f.read()).digest()

    def class_filter(self, class_data: str) -> bool:
        if "app/md5/bytes/error" in class_data:
            return True
        return False

    def class_modifier(self, class_data: str) -> str:
        dex_hash = self.get_dex_md5()
        dex_hash_body = list(self.DEX_HASH_BODY.finditer(class_data))[0]
        array_register = dex_hash_body.groupdict().get("reg")
        mac_register = dex_hash_body.groupdict().get("mac")
        array_size = len(dex_hash)
        dex_hash_new_body = f"""
    const v2, {hex(array_size)}

    new-array {array_register}, v2, [B
            """
        i = 0
        for byte in dex_hash:
            dex_hash_new_body += f"""
    const v2, {hex(byte)}

    const v1, {hex(i)}

    aput-byte v2, {array_register}, v1
                    """
            i += 1
        dex_hash_new_body += f"""
    invoke-virtual {{{mac_register}, {array_register}}}, Ljavax/crypto/Mac;->update([B)V
        """
        return class_data.replace(dex_hash_body.group(0), dex_hash_new_body)
