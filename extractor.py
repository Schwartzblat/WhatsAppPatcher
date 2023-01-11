import pathlib
import platform
import shutil
import subprocess
import os
import glob
import re
import termcolor

BOOLEAN_TEST_METHOD_NAME_REGEX = re.compile(r"public final boolean (\w+)\(")
BOOLEAN_TEST_METHOD_NAME_REGEX_SMALI = re.compile(
    r"\.method public final (\w+)\(LX\/0uH;I\)Z"
)
BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
    "\.method public final (?:\w+)\(LX\/0uH;I\)Z.*?end method", re.DOTALL
)
RETURN_RE_SMALI = re.compile("[ ]*return v[0-9]")
RETURN_TO_REPLACE = """

"""
BOOLEAN_ABTEST_METHOD_TO_REPLACE = """{
        if (i===2358){
            return false;
        }
        return true;
    }
"""


def extract_apk(input_apk, output_path):
    if os.path.exists(output_path):
        shutil.rmtree(output_path)
    apktool_base_path = pathlib.Path(__file__).parent / "bin" / "apktool"
    apk_tool = apktool_base_path / "apktool_2.7.0.jar"
    subprocess.check_call(
        [
            "java",
            "-jar",
            str(apk_tool),
            "d",
            "--output",
            str(output_path),
            str(input_apk),
        ],
        timeout=20 * 60,
    )


def get_abtests_class_name(path):
    for filename in glob.iglob(
        os.path.join(path, "sources", "**", "*.java"), recursive=True
    ):
        with open(filename, "r", encoding="utf8") as f:
            data = f.read()
            if 'sb2.append("Unknown BooleanField: ");' in data:
                return filename


def get_abtests_class_name_smali(path):
    for filename in glob.iglob(os.path.join(path, "**", "*.smali"), recursive=True):
        with open(filename, "r", encoding="utf8") as f:
            data = f.read()
            if 'const-string v0, "Unknown BooleanField: "' in data:
                return filename


def get_boolean_test_method(path):
    with open(path, "r") as f:
        class_data = f.read()
    boolean_method = BOOLEAN_TEST_METHOD_NAME_REGEX.findall(class_data)
    assert (
        len(boolean_method) == 1
    ), f"Didn't find a single boolean method at: {class_data}"
    assert (
        len(boolean_method) >= 1
    ), f"Found more then one boolean method at: {class_data}"
    return boolean_method[0]


def get_boolean_test_method_smali(path):
    with open(path, "r") as f:
        class_data = f.read()
    boolean_method = BOOLEAN_TEST_METHOD_NAME_REGEX_SMALI.findall(class_data)
    assert (
        len(boolean_method) == 1
    ), f"Didn't find a single boolean method at: {class_data}"
    assert (
        len(boolean_method) >= 1
    ), f"Found more then one boolean method at: {class_data}"
    return boolean_method[0]


def get_function_body(class_data, method_name):
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


def get_function_body_smali(class_data):
    return BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI.findall(class_data)[0]


def replace_return_values_smali(method_body):
    new_method_body = method_body
    arr = [6, 7]
    counter = 0
    for match in RETURN_RE_SMALI.finditer(method_body):
        register_name = match.group().strip().split(" ")[1]
        new_method_body = new_method_body.replace(
            match.group(),
            f"""
            const v7, 0x936
            if-eq p2, v7, :cond_{arr[counter]}
            const {register_name}, 1
            :cond_{arr[counter]}
            {match.group().strip()}
            """,
        )
        counter += 1
    return new_method_body


def compile_smali(path):
    apktool_base_path = pathlib.Path(__file__).parent / "bin" / "apktool"
    apk_tool = apktool_base_path / "apktool_2.7.0.jar"
    command = [
        "java",
        "-jar",
        apk_tool,
        "build",
        "--use-aapt2",
        path,
        "--output",
        "WhatsAppPatched.apk",
    ]
    subprocess.check_call(command, timeout=20 * 60)


def sign_apk(path):
    command = [
        "java",
        "-jar",
        "uber-apk-signer-1.2.1.jar",
        "--apks",
        path,
    ]
    subprocess.check_call(command, timeout=20 * 60)


extract_apk(
    "./WhatsApp.apk",
    r"C:\Users\alonp\PycharmProjects\whatsappBeta\extracted",
)
class_path = get_abtests_class_name_smali("./extracted")
termcolor.cprint(f"[+] The ab testing class has been found: {class_path}", "green")
boolean_method_name = get_boolean_test_method_smali(class_path)
termcolor.cprint(f"[+] The boolean method name is: {boolean_method_name}", "green")
with open(class_path, "r") as f:
    class_code = f.read()
function_body = get_function_body_smali(class_code)
termcolor.cprint("[+] Extracted function body.", "green")
new_function_body = replace_return_values_smali(function_body)
termcolor.cprint("[+] Function body has been modified.", "green")
new_class_code = class_code.replace(function_body, new_function_body)
termcolor.cprint("[+] Class code has been modified.", "green")
with open(class_path, "w") as f:
    f.write(new_class_code)
termcolor.cprint("[+] Class code has been written.", "green")
compile_smali("./extracted")
termcolor.cprint("[+] Smali has been compiled.", "green")
sign_apk("WhatsAppPatched.apk")
termcolor.cprint("[+] Apk has been signed.", "green")
os.remove("WhatsAppPatched.apk")
