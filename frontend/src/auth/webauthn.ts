/**
 * Passkeys in the browser: the server sends WebAuthn options as JSON with base64url binary fields; the browser API needs
 * ArrayBuffers, and its responses go back as JSON in the W3C "toJSON" shape. Converted by hand so older browsers without
 * PublicKeyCredential.parseCreationOptionsFromJSON still work.
 */

const toBuffer = (b64url: string): ArrayBuffer => {
  const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(b64url.length / 4) * 4, '=');
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
};

export const toBase64Url = (buf: ArrayBuffer): string => {
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

type Json = Record<string, unknown>;
type Descriptor = { type: 'public-key'; id: string };

export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential && !!navigator.credentials;
}

/** Runs navigator.credentials.create with the server's options; returns the attestation as JSON for the server. */
export async function createPasskey(options: Json): Promise<Json> {
  const user = options.user as { id: string; name: string; displayName: string };
  const publicKey: PublicKeyCredentialCreationOptions = {
    ...(options as unknown as PublicKeyCredentialCreationOptions),
    challenge: toBuffer(options.challenge as string),
    user: { ...user, id: toBuffer(user.id) },
    excludeCredentials: ((options.excludeCredentials as Descriptor[]) ?? []).map((c) => ({ type: c.type, id: toBuffer(c.id) })),
  };
  const cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential | null;
  if (!cred) throw new Error('No passkey was created.');
  const r = cred.response as AuthenticatorAttestationResponse;
  return {
    id: cred.id, rawId: toBase64Url(cred.rawId), type: cred.type, clientExtensionResults: cred.getClientExtensionResults(),
    response: { clientDataJSON: toBase64Url(r.clientDataJSON), attestationObject: toBase64Url(r.attestationObject),
      transports: typeof r.getTransports === 'function' ? r.getTransports() : [] },
  };
}

/** Runs navigator.credentials.get with the server's options; returns the assertion as JSON for the server. */
export async function signWithPasskey(options: Json): Promise<Json> {
  const publicKey: PublicKeyCredentialRequestOptions = {
    ...(options as unknown as PublicKeyCredentialRequestOptions),
    challenge: toBuffer(options.challenge as string),
    allowCredentials: ((options.allowCredentials as Descriptor[]) ?? []).map((c) => ({ type: c.type, id: toBuffer(c.id) })),
  };
  const cred = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential | null;
  if (!cred) throw new Error('No passkey was used.');
  const r = cred.response as AuthenticatorAssertionResponse;
  return {
    id: cred.id, rawId: toBase64Url(cred.rawId), type: cred.type, clientExtensionResults: cred.getClientExtensionResults(),
    response: { clientDataJSON: toBase64Url(r.clientDataJSON), authenticatorData: toBase64Url(r.authenticatorData),
      signature: toBase64Url(r.signature), userHandle: r.userHandle ? toBase64Url(r.userHandle) : null },
  };
}
