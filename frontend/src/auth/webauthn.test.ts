import { describe, expect, it } from 'vitest';
import { toBase64Url } from './webauthn';

describe('webauthn encoding', () => {
  it('encodes binary as unpadded base64url, the form WebAuthn JSON uses', () => {
    expect(toBase64Url(new Uint8Array([0xfb, 0xff, 0xbf]).buffer)).toBe('-_-_');
    expect(toBase64Url(new Uint8Array([1, 2]).buffer)).toBe('AQI');
  });
});
