import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import bundle
from test_bundle_cli import ec_keypair, minimal_apk

SECRET = 'S3CRET-upload-token-xyz'


class FakeServer:
    def __init__(self, status, reply):
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                outer.requests.append({'method': 'POST', 'path': self.path, 'headers': dict(self.headers),
                                       'body': body})
                payload = json.dumps(reply).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        self.httpd = HTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.httpd.server_port}'
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def fake_server():
    servers = []

    def make(status, reply):
        server = FakeServer(status, reply)
        servers.append(server)
        return server

    yield make
    for server in servers:
        server.close()


@pytest.fixture
def build_env(tmp_path, monkeypatch):
    key_pem, _ = ec_keypair(tmp_path)
    fake = {'smali_generator': minimal_apk(tmp_path / 'm.apk', min_sdk=33),
            'PatchPaywall': minimal_apk(tmp_path / 'p.apk', min_sdk=30)}
    calls = []

    def fake_build(module_path, work_dir, substitutions):
        calls.append(module_path)
        return fake[Path(module_path).name]

    monkeypatch.setattr(bundle, 'build_gradle_module', fake_build)
    out = tmp_path / 'b.zip'
    argv = ['--paywall', str(tmp_path / 'PatchPaywall'), '--signing-key', str(key_pem), '-o', str(out),
            '--work', str(tmp_path / 'work'), '--upload']
    return argv, out, calls


def test_upload_posts_raw_zip_with_token(build_env, fake_server, monkeypatch, capsys):
    argv, out, _ = build_env
    server = fake_server(200, {'package': 'com.whatsapp', 'patcher_version': '3.0.0', 'revision': 7})
    monkeypatch.setenv('PATCH_SERVER_URL', server.url + '/')
    monkeypatch.setenv('BUNDLE_UPLOAD_SECRET', SECRET)
    assert bundle.main(argv) == 0
    [request] = server.requests
    assert request['method'] == 'POST' and request['path'] == '/api/internal/bundles'
    assert request['body'] == out.read_bytes()
    assert request['headers']['Content-Type'] == 'application/zip'
    assert request['headers']['X-Internal-Token'] == SECRET
    captured = capsys.readouterr()
    assert 'Uploaded com.whatsapp 3.0.0: revision 7' in captured.out
    assert SECRET not in captured.out + captured.err


def test_upload_error_exits_1_with_detail(build_env, fake_server, monkeypatch, capsys):
    argv, _, _ = build_env
    server = fake_server(413, {'detail': 'bundle too large'})
    monkeypatch.setenv('PATCH_SERVER_URL', server.url)
    monkeypatch.setenv('BUNDLE_UPLOAD_SECRET', SECRET)
    assert bundle.main(argv) == 1
    captured = capsys.readouterr()
    assert 'bundle too large' in captured.err and '413' in captured.err
    assert SECRET not in captured.out + captured.err


def test_upload_connection_error_exits_1(build_env, monkeypatch, capsys):
    argv, _, _ = build_env
    monkeypatch.setenv('PATCH_SERVER_URL', 'http://127.0.0.1:1')
    monkeypatch.setenv('BUNDLE_UPLOAD_SECRET', SECRET)
    assert bundle.main(argv) == 1
    captured = capsys.readouterr()
    assert captured.err.strip()
    assert SECRET not in captured.out + captured.err


@pytest.mark.parametrize('missing', ['PATCH_SERVER_URL', 'BUNDLE_UPLOAD_SECRET'])
def test_upload_requires_env(build_env, monkeypatch, capsys, tmp_path, missing):
    argv, out, calls = build_env
    monkeypatch.setenv('PATCH_SERVER_URL', 'http://127.0.0.1:1')
    monkeypatch.setenv('BUNDLE_UPLOAD_SECRET', SECRET)
    monkeypatch.delenv(missing)
    assert bundle.main(argv) == 2
    captured = capsys.readouterr()
    assert missing in captured.err and SECRET not in captured.out + captured.err
    assert calls == [] and not out.exists()


def test_upload_unverifiable_bundle_is_not_uploaded(build_env, fake_server, monkeypatch, capsys, tmp_path):
    argv, _, _ = build_env
    server = fake_server(200, {'package': 'x', 'patcher_version': 'y', 'revision': 1})
    monkeypatch.setenv('PATCH_SERVER_URL', server.url)
    monkeypatch.setenv('BUNDLE_UPLOAD_SECRET', SECRET)

    def reject(path, public_pem):
        raise bundle.BundleError('bad signature')

    monkeypatch.setattr(bundle, 'verify_bundle', reject)
    assert bundle.main(argv) == 1
    assert 'bad signature' in capsys.readouterr().err
    assert server.requests == []


def test_without_upload_env_is_not_needed(build_env, monkeypatch):
    argv, out, _ = build_env
    monkeypatch.delenv('PATCH_SERVER_URL', raising=False)
    monkeypatch.delenv('BUNDLE_UPLOAD_SECRET', raising=False)
    assert bundle.main(argv[:-1]) == 0
    assert out.exists()
