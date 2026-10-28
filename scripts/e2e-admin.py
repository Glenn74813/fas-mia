"""DEVELOPMENT ONLY. Completes the bootstrap administrator's first sign-in on the end-to-end test backend: changes the
temporary password and enrols an authenticator, then writes the result to the file given (gitignored .devdata/), so
Playwright can sign in as that administrator. Usage: python3 scripts/e2e-admin.py <api-base-url> <out-file>"""
import base64, hashlib, hmac, json, os, secrets, struct, sys, time, urllib.request


def call(base, path, body, token=None):
    req = urllib.request.Request(base + '/api/v1' + path, data=json.dumps(body).encode(), method='POST',
                                 headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def totp(secret):
    key = base64.b32decode(secret.upper() + '=' * (-len(secret) % 8))
    h = hmac.new(key, struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
    o = h[-1] & 15
    return '%06d' % ((struct.unpack('>I', h[o:o + 4])[0] & 0x7fffffff) % 1_000_000)


def main():
    base, out = sys.argv[1], sys.argv[2]
    username, temporary = os.environ['CAS_BOOTSTRAP_ADMIN_USERNAME'], os.environ['CAS_BOOTSTRAP_ADMIN_PASSWORD']
    step = call(base, '/auth/login', {'identifier': username, 'password': temporary})
    secret = call(base, '/auth/mfa/totp/enroll', {'mfaToken': step['mfaToken']})['secret']
    tokens = call(base, '/auth/mfa/totp/activate', {'mfaToken': step['mfaToken'], 'code': totp(secret)})
    password = 'E2e-' + secrets.token_urlsafe(18) + '-1a!'
    call(base, '/auth/password/change', {'currentPassword': temporary, 'newPassword': password}, tokens['accessToken'])
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(f'E2E_ADMIN_USERNAME={username}\nE2E_ADMIN_PASSWORD={password}\nE2E_ADMIN_TOTP_SECRET={secret}\n')
    print(f'Onboarded {username} on the end-to-end backend; credentials in {out}')


if __name__ == '__main__':
    main()
