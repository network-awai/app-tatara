// Synchronous hash and signature checks for tatara-core in the page
// (AWAI-3410). tatara.core.canonical/cid needs a synchronous hash, which
// WebCrypto does not offer, so this uses @noble (audited, pure JS).
import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex, utf8ToBytes } from '@noble/hashes/utils.js';
import { ed25519 } from '@noble/curves/ed25519.js';

const DID_PREFIX = 'did:tatara:ed25519:';
// DER SubjectPublicKeyInfo prefix for an Ed25519 key (RFC 8410).
const SPKI = [0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00];

function b64ToBytes(s) {
  if (typeof s !== 'string' || !/^[A-Za-z0-9_\-+/]*=*$/.test(s)) throw new Error('not base64');
  let t = s.replace(/-/g, '+').replace(/_/g, '/');
  while (t.length % 4) t += '=';
  const bin = atob(t);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function sha256Hex(text) { return bytesToHex(sha256(utf8ToBytes(text))); }

export function didPublicKey(did) {
  if (typeof did !== 'string' || !did.startsWith(DID_PREFIX)) return null;
  const spki = b64ToBytes(did.slice(DID_PREFIX.length));
  if (spki.length !== 44 || SPKI.some((b, i) => spki[i] !== b)) return null;
  return spki.slice(12);
}

// Strict RFC 8032 (zip215: false), like the JDK verifier the coordinator uses.
export function verifyDid(did, message, signature) {
  try {
    const pub = didPublicKey(did);
    if (!pub) return false;
    const sig = b64ToBytes(signature);
    if (sig.length !== 64) return false;
    return ed25519.verify(sig, utf8ToBytes(message), pub, { zip215: false });
  } catch (_) { return false; }
}

globalThis.tataraCrypto = { sha256Hex, verifyDid, didPublicKey };
