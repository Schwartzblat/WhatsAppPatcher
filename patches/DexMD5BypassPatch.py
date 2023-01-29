from patches.Patch import Patch
import re
from hashlib import md5


class DexMD5BypassPatch(Patch):
    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Bypassing dex file md5 hash"

    DEX_HASH_FUNCTION_RE = re.compile(
        "\.method public static \w+\(Landroid\/content\/Context;\)\[B\s*(.*?)\.end method",
        re.DOTALL,
    )

    def get_dex_md5(self) -> bytes:
        with open(self.extracted_path + "/classes.dex", "rb") as f:
            return md5(f.read()).digest()

    def class_filter(self, class_data: str) -> bool:
        if "app/md5/bytes/error" in class_data:
            return True
        return False

    def class_modifier(self, class_data: str) -> str:
        dex_hash = self.get_dex_md5()
        dex_hash_function = list(self.DEX_HASH_FUNCTION_RE.finditer(class_data))[0]
        array_size = len(dex_hash)
        new_dex_hash_function = f""".registers 7

            const v2, {hex(array_size)}

            new-array v0, v2, [B
            """
        i = 0
        for byte in dex_hash:
            new_dex_hash_function += f"""
            const v5, {hex(byte)}

            const v1, {hex(i)}

            aput-byte v5, v0, v1
                    """
            i += 1
        new_dex_hash_function += """

            return-object v0
            """
        return class_data.replace(dex_hash_function.group(1), new_dex_hash_function)
