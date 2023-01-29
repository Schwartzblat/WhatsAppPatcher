class Patch:
    def __init__(self, extracted_path):
        self.extracted_path = extracted_path
        self.print_message = "Patching patch..."

    def class_filter(self, class_data: str) -> bool:
        raise NotImplementedError

    def class_modifier(self, class_data: str) -> str:
        raise NotImplementedError
