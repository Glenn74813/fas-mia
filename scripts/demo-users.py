"""DEVELOPMENT / DEMO ONLY. Prepares the demo database's users through the user-administration API (so every change is
checked and audited like a manual one):

  1. deactivates every active account except the administrator, the demo super administrator, the chart-conversion
     service user and the demo.* users (end-to-end, performance and review runs leave thousands behind);
  2. creates two onboarded demo users for every role (demo.<role>.1 and .2) except DEMO_SUPERADMIN, which user
     administration never grants: password changed, authenticator enrolled.

Credentials of the new users are written to the file given (mode 600, under the gitignored .devdata/), never printed;
add a user's TOTP secret to an authenticator app to sign in. Concessionaire accounts still need linking to a customer
(Receivables -> Portal access), which a second person approves. Re-running skips users that already exist.

Usage: python3 scripts/demo-users.py <api-base-url> <credentials-out-file>
Reads CAS_BOOTSTRAP_ADMIN_USERNAME, CAS_DEV_ADMIN_CURRENT_PASSWORD, CAS_DEV_ADMIN_TOTP_SECRET and
CAS_DEMO_SUPERADMIN_USERNAME from the environment (source .env first). New users get CAS_DEMO_SUPERADMIN_PASSWORD as
their password when it is set, otherwise a random one."""
import base64, datetime, hashlib, hmac, json, os, secrets, struct, sys, time, urllib.error, urllib.parse, urllib.request

BASE = sys.argv[1].rstrip('/') + '/api/v1'


def call(method, path, body=None, token=None):
    for _ in range(20):
        req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(int(e.headers.get('Retry-After') or 10) + 1)
                continue
            raise RuntimeError(f'{method} {path}: {e.code} {e.read()[:300]!r}') from None
    raise RuntimeError(f'{method} {path}: still rate limited')


def totp(secret):
    key = base64.b32decode(secret.upper() + '=' * (-len(secret) % 8))
    h = hmac.new(key, struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
    o = h[-1] & 15
    return '%06d' % ((struct.unpack('>I', h[o:o + 4])[0] & 0x7fffffff) % 1_000_000)


def admin_token():
    step = call('POST', '/auth/login', {'identifier': os.environ['CAS_BOOTSTRAP_ADMIN_USERNAME'],
                                        'password': os.environ['CAS_DEV_ADMIN_CURRENT_PASSWORD']})
    if step.get('status') == 'DEV_MFA_BYPASS':
        return call('POST', '/auth/mfa/dev-bypass', {'mfaToken': step['mfaToken']})['accessToken']
    return call('POST', '/auth/mfa/verify', {'mfaToken': step['mfaToken'], 'code': totp(os.environ['CAS_DEV_ADMIN_TOTP_SECRET'])})['accessToken']


def all_users(token):
    out, page = [], 0
    while True:
        r = call('GET', f'/users?status=ACTIVE&page={page}&size=100', token=token)
        out += r['items']
        if (page + 1) * 100 >= r['totalItems']:
            return out
        page += 1


def main():
    out_file = sys.argv[2]
    token = admin_token()
    keep = {os.environ['CAS_BOOTSTRAP_ADMIN_USERNAME'], os.environ.get('CAS_DEMO_SUPERADMIN_USERNAME', ''), 'system.conversion'}
    users = all_users(token)
    stale = [u for u in users if u['username'] not in keep and not u['username'].startswith('demo.')]
    for i, u in enumerate(stale, 1):
        call('POST', f"/users/{u['id']}/deactivate", {'version': u['version']}, token)
        if i % 100 == 0:
            print(f'deactivated {i} of {len(stale)}', flush=True)
    print(f'Deactivated {len(stale)} test accounts; kept {sorted(k for k in keep if k)} and the demo.* users.')

    existing = {u['username'] for u in all_users(token)}
    roles = sorted(r['code'] for r in call('GET', '/roles', token=token) if r['code'] != 'DEMO_SUPERADMIN')
    expiry = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365)).isoformat()
    lines, created = [], 0
    for role in roles:
        for n in (1, 2):
            username = f'demo.{role.lower()}.{n}'
            if username in existing:
                continue
            kind = {'CONCESSIONAIRE': 'CONCESSIONAIRE', 'EXTERNAL_AUDITOR': 'EXTERNAL_AUDITOR'}.get(role, 'INTERNAL')
            temporary = 'Tmp-' + secrets.token_urlsafe(12) + '-1a!'
            call('POST', '/users', {'username': username, 'email': f'{username}@demo.invalid',
                                    'displayName': f"Demo {role.replace('_', ' ').title()} {n}", 'userType': kind,
                                    'temporaryPassword': temporary, 'roleCodes': [role],
                                    'accessExpiresAt': expiry if kind == 'EXTERNAL_AUDITOR' else None}, token)
            step = call('POST', '/auth/login', {'identifier': username, 'password': temporary})
            secret = call('POST', '/auth/mfa/totp/enroll', {'mfaToken': step['mfaToken']})['secret']
            tokens = call('POST', '/auth/mfa/totp/activate', {'mfaToken': step['mfaToken'], 'code': totp(secret)})
            # Same password as the demo super administrator when it is set, so one password opens every demo account.
            password = os.environ.get('CAS_DEMO_SUPERADMIN_PASSWORD') or 'Demo-' + secrets.token_urlsafe(12) + '-1a!'
            call('POST', '/auth/password/change', {'currentPassword': temporary, 'newPassword': password}, tokens['accessToken'])
            lines.append(f'{username},{role},{password},{secret}')
            created += 1
    if lines:
        new = not os.path.exists(out_file)
        fd = os.open(out_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, 'a') as f:
            if new:
                f.write('username,role,password,totp_secret\n')
            f.write('\n'.join(lines) + '\n')
    print(f'Created {created} demo users ({len(roles)} roles x 2, existing ones skipped); credentials in {out_file}')


if __name__ == '__main__':
    main()
