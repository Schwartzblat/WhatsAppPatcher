from whatsapp_patcher.patches.Patch import Patch
import re


class SendReceiptPatch(Patch):
    RECEIPT_METHOD_RE = re.compile(
        r"\.method public constructor <init>\(.*?Z\)V\s*\.\w+ \w+\s*(.*?)\.end method",
        re.DOTALL,
    )
    RECEIPT_METHOD_REPLACE = """
    const p9, 0x1
    """

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Patching send read receipt method..."

    def class_filter(self, class_data: str) -> bool:
        if '"; shouldForceReadSelfReceipt="' in class_data:
            return True
        return False

    def class_modifier(self, class_data) -> str:
        return class_data
        """send_receipt_constructor_body = self.RECEIPT_METHOD_RE.findall(class_data)[0]
        return class_data.replace(
            send_receipt_constructor_body,
            self.RECEIPT_METHOD_REPLACE + send_receipt_constructor_body,
        )"""
