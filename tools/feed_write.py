#!/usr/bin/env python3
"""Write a signed, encrypted alarm feed envelope for the kokok app.

Uses Google Tink (vetted library) on both ends with identical key templates:
- hybrid encryption: ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM
- signature: ECDSA_P256 (SHA-256, IEEE P1363 encoding)

Envelope (JSON):
  {"v":1, "kid": "<verify-keyset primary key id>", "enc": "<base64 hybrid ciphertext>",
   "sig": "<base64 signature over the utf-8 bytes of the enc string>"}
"""
import argparse
import base64
import json
import sys

import tink
from tink import cleartext_keyset_handle, hybrid, signature

CONTEXT_INFO = b'kokok-feed-v1'


def read_keyset(path):
    with open(path, 'r') as f:
        return cleartext_keyset_handle.read(tink.JsonKeysetReader(f.read()))


def write_keyset(handle, path):
    with open(path, 'w') as f:
        cleartext_keyset_handle.write(tink.JsonKeysetWriter(f), handle)


def app_key_template():
    from tink.proto import aes_gcm_pb2, common_pb2, ecies_aead_hkdf_pb2, tink_pb2
    dem = tink_pb2.KeyTemplate(
        type_url='type.googleapis.com/google.crypto.tink.AesGcmKey',
        value=aes_gcm_pb2.AesGcmKeyFormat(key_size=16).SerializeToString(),
        output_prefix_type=tink_pb2.TINK)
    fmt = ecies_aead_hkdf_pb2.EciesAeadHkdfKeyFormat(
        params=ecies_aead_hkdf_pb2.EciesAeadHkdfParams(
            kem_params=ecies_aead_hkdf_pb2.EciesHkdfKemParams(
                curve_type=common_pb2.NIST_P256,
                hkdf_hash_type=common_pb2.SHA256,
                hkdf_salt=b''),
            dem_params=ecies_aead_hkdf_pb2.EciesAeadDemParams(aead_dem=dem),
            ec_point_format=common_pb2.COMPRESSED))
    return tink_pb2.KeyTemplate(
        type_url='type.googleapis.com/google.crypto.tink.EciesAeadHkdfPrivateKey',
        value=fmt.SerializeToString(),
        output_prefix_type=tink_pb2.TINK)


def cmd_gen_sign(args):
    handle = tink.new_keyset_handle(signature.signature_key_templates.ECDSA_P256)
    write_keyset(handle, args.private)
    write_keyset(handle.public_keyset_handle(), args.public)
    print(f"wrote {args.private} and {args.public}")


def cmd_gen_app(args):
    handle = tink.new_keyset_handle(app_key_template())
    write_keyset(handle, args.private)
    write_keyset(handle.public_keyset_handle(), args.public)
    print(f"wrote {args.private} and {args.public}")


def cmd_encrypt(args):
    with open(args.input, 'rb') as f:
        payload = f.read()
    app_pub = read_keyset(args.to)
    sign_priv = read_keyset(args.sign)
    verify_pub = read_keyset(args.sign_public)
    # Prevent producing an envelope labeled with a different publisher's key id.
    # This challenge is local only and carries no feed plaintext.
    challenge = b'kokok-writer-key-match-v1'
    proof = sign_priv.primitive(signature.PublicKeySign).sign(challenge)
    verify_pub.primitive(signature.PublicKeyVerify).verify(proof, challenge)

    enc = app_pub.primitive(hybrid.HybridEncrypt).encrypt(payload, CONTEXT_INFO)
    enc_b64 = base64.b64encode(enc).decode('ascii')
    sig = sign_priv.primitive(signature.PublicKeySign).sign(enc_b64.encode('utf-8'))

    with open(args.sign_public) as f:
        kid = str(json.load(f)['primaryKeyId'])
    envelope = {"v": 1, "kid": str(kid), "enc": enc_b64,
                "sig": base64.b64encode(sig).decode('ascii')}
    with open(args.out, 'w') as f:
        json.dump(envelope, f, separators=(',', ':'))
    print(f"wrote {args.out} ({len(json.dumps(envelope))} bytes)")


def cmd_decrypt(args):
    with open(args.envelope) as f:
        env = json.load(f)
    app_priv = read_keyset(args.key)
    verify_pub = read_keyset(args.verify)
    verify_pub.primitive(signature.PublicKeyVerify).verify(
        base64.b64decode(env['sig']), env['enc'].encode('utf-8'))
    plain = app_priv.primitive(hybrid.HybridDecrypt).decrypt(
        base64.b64decode(env['enc']), CONTEXT_INFO)
    sys.stdout.buffer.write(plain)
    sys.stdout.buffer.write(b'\n')


def cmd_pair(args):
    from urllib.parse import urlsplit
    u = urlsplit(args.url)
    if u.scheme != 'https' or not u.hostname or u.username is not None or u.password is not None or u.fragment:
        raise ValueError('feed URL must be HTTPS with a host, no user info or fragment')
    with open(args.verify) as f:
        pub = json.load(f)
    if not pub.get('key') or any(k.get('keyData', {}).get('keyMaterialType') != 'ASYMMETRIC_PUBLIC' for k in pub['key']):
        raise ValueError('pairing requires a public verification keyset, never a private key')
    read_keyset(args.verify).primitive(signature.PublicKeyVerify)
    with open(args.out, 'w') as f:
        json.dump({'feedUrl': args.url, 'verifyKeyset': pub}, f, separators=(',', ':'))
    print(f'wrote non-secret pairing configuration to {args.out}')


def main():
    tink.hybrid.register()
    tink.signature.register()
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest='cmd', required=True)

    g = sub.add_parser('gen-sign')
    g.add_argument('--private', required=True)
    g.add_argument('--public', required=True)
    g.set_defaults(f=cmd_gen_sign)

    g = sub.add_parser('gen-app')
    g.add_argument('--private', required=True)
    g.add_argument('--public', required=True)
    g.set_defaults(f=cmd_gen_app)

    e = sub.add_parser('encrypt')
    e.add_argument('--in', dest='input', required=True, help='feed JSON file')
    e.add_argument('--to', required=True, help='app public keyset JSON')
    e.add_argument('--sign', required=True, help='feed signing private keyset JSON')
    e.add_argument('--sign-public', required=True, help='verify public keyset JSON (for kid)')
    e.add_argument('--out', required=True)
    e.set_defaults(f=cmd_encrypt)

    d = sub.add_parser('decrypt')
    d.add_argument('--envelope', required=True)
    d.add_argument('--key', required=True, help='app private keyset JSON')
    d.add_argument('--verify', required=True, help='verify public keyset JSON')
    d.set_defaults(f=cmd_decrypt)

    pair = sub.add_parser('pair')
    pair.add_argument('--url', required=True)
    pair.add_argument('--verify', required=True, help='publisher public verification keyset')
    pair.add_argument('--out', required=True)
    pair.set_defaults(f=cmd_pair)

    args = p.parse_args()
    args.f(args)


if __name__ == '__main__':
    main()
