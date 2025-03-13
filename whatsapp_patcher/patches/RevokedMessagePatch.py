from whatsapp_patcher.patches.Patch import Patch
import re


class RevokedMessagePatch(Patch):
    REVOKE_MESSAGE_RE = re.compile(
        r"\.method (?:\w+ )*\w+\(LX\/\w+;Z\)Z\s*\.\w+ \w+\s*(.*?)\.end method",
        re.DOTALL,
    )
    REVOKE_MESSAGE_REPLACE = """
    if-eqz p2, :cond_self_delete
    return p2
    :cond_self_delete
    """

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Patching revoke message method..."

    def class_filter(self, class_data: str) -> bool:
        if '"msgstore/edit/revoke "' in class_data:
            return True
        return False

    def class_modifier(self, class_data) -> str:
        revoke_function_data = self.REVOKE_MESSAGE_RE.findall(class_data)[0]
        return class_data.replace(
            revoke_function_data, self.REVOKE_MESSAGE_REPLACE + revoke_function_data
        )
