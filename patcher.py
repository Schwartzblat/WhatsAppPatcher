from termcolor import cprint
import os
import re
import glob

class Patcher:
    BOOLEAN_TEST_METHOD_NAME_REGEX_SMALI = re.compile(
        r"\.method public final (\w+)\(LX\/0uH;I\)Z"
    )
    BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
        "\.method public final (?:\w+)\(LX\/0uH;I\)Z.*?end method", re.DOTALL
    )
    RETURN_RE_SMALI = re.compile("[ ]*return v[0-9]")
    SIGN_VERIFICATION_RE = re.compile(
        "\.method public bridge synthetic \w+\(\[Ljava\/lang\/Object;\)Ljava\/lang\/Object;\s*(.*?)\.end method",
        re.DOTALL,
    )
    SIGN_VERIFICATION_REPLACE = """.registers 3

    const/4 v0, 0x0

    return-object v0
    """

    def __init__(self, extracted_path):
        self.extracted_path = extracted_path

    def patch(self):
        self.patch_ab_tests()
        self.bypass_signature_verifier()

    def patch_ab_tests(self):
        class_path = self.get_abtests_class_name_smali()
        cprint(
            f"[+] The ab testing class has been found: {class_path}", "green"
        )
        boolean_method_name = self.get_boolean_test_method_smali(class_path)
        cprint(
            f"[+] The boolean method name is: {boolean_method_name}", "green"
        )
        with open(class_path, "r") as f:
            class_code = f.read()
        function_body = self.get_function_body_smali(class_code)
        cprint("[+] Experiment function body extracted.", "green")
        new_function_body = self.replace_return_values_smali(function_body)
        cprint("[+] Function body has been modified.", "green")
        new_class_code = class_code.replace(function_body, new_function_body)
        cprint("[+] Class code has been modified.", "green")
        with open(class_path, "w") as f:
            f.write(new_class_code)
        cprint("[+] Class code has been written.", "green")

    def bypass_signature_verifier(self):
        cprint("[+] Bypassing signature verifier....", "green")
        class_path, class_body = self.get_sign_verification_class()
        cprint("[+] Signature verifier class has been found.", "green")
        new_class_body = self.get_new_sign_verification_class(class_body)
        with open(class_path, "w") as f:
            f.write(new_class_body)
        cprint("[+] Signature verifier class has been modified.", "green")

    def get_sign_verification_class(self):
        for filename in glob.iglob(
            os.path.join(self.extracted_path, "**", "*.smali"), recursive=True
        ):
            with open(filename, "r", encoding="utf8") as f:
                data = f.read()
                if "requestCodeForStandaloneVerification" in data:
                    return filename, data

    def get_new_sign_verification_class(self, class_data: str) -> str:
        sign_verification_method_body = list(self.SIGN_VERIFICATION_RE.finditer(class_data))[0]
        return class_data.replace(
            sign_verification_method_body.group(1),
            self.SIGN_VERIFICATION_REPLACE
        )

    def get_abtests_class_name(self):
        for filename in glob.iglob(
            os.path.join(self.extracted_path, "sources", "**", "*.java"), recursive=True
        ):
            with open(filename, "r", encoding="utf8") as f:
                data = f.read()
                if 'sb2.append("Unknown BooleanField: ");' in data:
                    return filename

    def get_abtests_class_name_smali(self):
        for filename in glob.iglob(
            os.path.join(self.extracted_path, "**", "*.smali"), recursive=True
        ):
            with open(filename, "r", encoding="utf8") as f:
                data = f.read()
                if 'const-string v0, "Unknown BooleanField: "' in data:
                    return filename

    def get_boolean_test_method_smali(self, path):
        with open(path, "r") as f:
            class_data = f.read()
        boolean_method = self.BOOLEAN_TEST_METHOD_NAME_REGEX_SMALI.findall(class_data)
        assert (
            len(boolean_method) == 1
        ), f"Didn't find a single boolean method at: {class_data}"
        assert (
            len(boolean_method) >= 1
        ), f"Found more then one boolean method at: {class_data}"
        return boolean_method[0]

    def get_function_body(self, class_data, method_name):
        method_re = re.compile(method_name + "\([^;]*\) {.*", re.DOTALL)
        brackets_counter = 0
        is_in_method = False
        method_body = ""
        for char in method_re.findall(class_data)[0]:
            if char == "{":
                is_in_method = True
                brackets_counter += 1
            elif char == "}":
                brackets_counter -= 1
            if not is_in_method:
                continue
            method_body += char
            if brackets_counter == 0 and is_in_method:
                break
        return method_body

    def get_function_body_smali(self, class_data):
        return self.BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI.findall(class_data)[0]

    def replace_return_values_smali(self, method_body):
        new_method_body = method_body
        arr = [6, 7]
        counter = 0
        for match in self.RETURN_RE_SMALI.finditer(method_body):
            register_name = match.group().strip().split(" ")[1]
            temp_register = "v2"
            if register_name == "v2":
                temp_register = "v0"
            new_method_body = new_method_body.replace(
                match.group(),
                f"""
    const {temp_register}, 0x33F                               
    if-eq p2, {temp_register}, :cond_{arr[counter]}
    const {register_name}, 1
    :cond_{arr[counter]}
    {match.group().strip()}
                    """,
            )
            counter += 1
        return new_method_body